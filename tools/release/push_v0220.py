#!/usr/bin/env python3
"""v0.2.20 发布：销售页退出资源释放省电加固。

（与 push_v0219.py 同构：git hash-object vs 远端 tree sha，差异才上传；
流程：远端 main ref → tree(recursive) → 差异文件建 blob → base_tree 建 tree
→ commit → 更新 main → 轻量 tag → 确认 Actions 触发（无则 dispatch 兜底）。）
"""
import base64
import json
import subprocess
import sys
import time
import urllib.request

REPO = "qwe159357890/bridge"
API = f"https://api.github.com/repos/{REPO}"
TAG = "v0.2.20"
MESSAGE = (
    "perf: 销售页退出资源释放省电加固（v0.2.20）\n"
    "\n"
    "对照智能控制/快递扫码 stop() 彻底释放模式，补齐销售页退出时的资源释放：\n"
    "- SalesApiClient 新增 close()：connectionPool.evictAll + dispatcher.executorService.shutdown，\n"
    "  退出页面即回收空闲 HTTP 连接、停止 OkHttp 线程池\n"
    "- SalesController.exitPage() 调用 api?.close(); api=null，并清理残留的\n"
    "  pageLoading/actionLoading/exporting 状态\n"
    "- ensureInit 懒重建：api==null 时重新 SalesApiClient，再次进入页面自动恢复"
)

# 候选文件（hash 一致自动跳过）
CANDIDATES = [
    "app/build.gradle.kts",
    "app/src/main/java/my/bridge/com/sales/SalesApiClient.kt",
    "app/src/main/java/my/bridge/com/sales/SalesController.kt",
]

token = ""
for line in open("github.env", encoding="utf-8"):
    line = line.strip()
    if line.startswith("GITHUB_TOKEN=") and not line.startswith("#"):
        token = line.split("=", 1)[1].strip().strip('"').strip("'")
assert token, "github.env 中未找到 GITHUB_TOKEN"


def api(method, path, body=None):
    req = urllib.request.Request(
        f"{API}{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"token {token}", "Content-Type": "application/json",
                 "User-Agent": "bridge-release", "Accept": "application/vnd.github+json"},
        method=method,
    )
    with urllib.request.urlopen(req) as r:
        data = r.read()
        return json.loads(data) if data else {}


def disk_blob_sha(path):
    out = subprocess.run(["git", "hash-object", path], check=True,
                         capture_output=True, text=True).stdout.strip()
    return out


def latest_run():
    return api("GET", "/actions/runs?per_page=1").get("workflow_runs", [{}])[0]


def main():
    ref = api("GET", "/git/refs/heads/main")
    base_commit = ref["object"]["sha"]
    base_tree = api("GET", f"/git/commits/{base_commit}")["tree"]["sha"]
    print(f"[1] 远端 main={base_commit[:10]} tree={base_tree[:10]}")

    tree_data = api("GET", f"/git/trees/{base_tree}?recursive=1")
    remote_sha = {t["path"]: t["sha"] for t in tree_data.get("tree", [])
                  if t["type"] == "blob"}

    changed = []
    for f in CANDIDATES:
        local = disk_blob_sha(f)
        remote = remote_sha.get(f)
        if local != remote:
            changed.append(f)
            print(f"  差异: {f} (远端={'缺失' if remote is None else '不同'})")
        else:
            print(f"  一致，跳过: {f}")
    if not changed:
        print("无差异，无需提交")
        return 1

    entries = []
    for f in changed:
        blob = api("POST", "/git/blobs", {
            "content": base64.b64encode(open(f, "rb").read()).decode(),
            "encoding": "base64",
        })
        entries.append({"path": f, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    tree = api("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})
    print(f"[2] 新 tree={tree['sha'][:10]}（含 {len(entries)} 个文件）")

    commit = api("POST", "/git/commits", {
        "message": MESSAGE, "tree": tree["sha"],
        "parents": [base_commit],
    })
    api("PATCH", "/git/refs/heads/main", {"sha": commit["sha"], "force": False})
    print(f"[3] commit={commit['sha'][:10]} 已更新 main")

    before = latest_run()
    api("POST", "/git/refs", {"ref": f"refs/tags/{TAG}", "sha": commit["sha"]})
    print(f"[4] tag {TAG} 已创建，等 8s 确认 Actions 触发 ...")
    time.sleep(8)
    after = latest_run()
    if after.get("head_branch") == TAG or after.get("id") != before.get("id"):
        print(f"[5] Actions 已触发 run={after.get('id')} ({after.get('html_url')})")
    else:
        print("[5] tag 未触发工作流（已知坑），dispatch 兜底 ...")
        api("POST", "/actions/workflows/release.yml/dispatches",
            {"ref": f"refs/tags/{TAG}"})
        print(f"[5] dispatch release.yml ref={TAG} 已发出")

    # [6] 追平本地 git（消除「本地 vs 远端分叉」）——方案 B 自动化。
    # API 通道是基于远端 HEAD 新造提交、不并入本地历史，会导致本地 HEAD 与
    # origin/main 分叉。这里发布成功后立刻 fetch + reset --hard 把本地追平到
    # 远端权威历史，下次 git status 就不会再冒出「假差异/分叉」。
    # 仅当本地有未提交改动时才跳过（保护工作区，避免误清用户未保存内容）。
    sync_local_git()
    return 0


def sync_local_git():
    """发布后把本地 git 追平到远端 origin/main（清代理直连，网络策略②）。

    目标：消除 API 通道导致的本地/远端分叉。步骤：
    1. 若工作区有未提交改动则跳过（不强制清工作区，交给用户决定）；
    2. git fetch origin main（清代理直连，规避沙箱代理间歇 502）；
    3. git reset --hard origin/main（本地 HEAD/index 对齐远端权威历史）。
    """
    import os
    import subprocess

    # 1. 保护工作区：有未提交改动（含未跟踪）就不动，避免误清用户内容
    st = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
    if st.stdout.strip():
        print("[6] 工作区有未提交改动，跳过本地 git 追平（交用户手动处理）。")
        return

    # 2. 清代理直连 fetch（API 通道用的是 urllib，fetch 用 git，需绕过沙箱代理）
    env = dict(os.environ)
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
        env.pop(k, None)
    print("[6] 追平本地 git（fetch origin/main，清代理直连）...")
    fr = subprocess.run(["git", "fetch", "origin", "main"], capture_output=True, text=True, env=env)
    if fr.returncode != 0:
        print(f"[6] fetch 失败（{fr.stderr.strip()[:120]}），跳过追平，可稍后手动执行。")
        return

    # 3. reset --hard 对齐远端权威历史（丢弃本地 release.py 遗留的垃圾提交）
    rr = subprocess.run(["git", "reset", "--hard", "origin/main"], capture_output=True, text=True)
    if rr.returncode == 0:
        print("[6] 本地 git 已追平 origin/main（分叉消除）。")
    else:
        print(f"[6] reset 失败（{rr.stderr.strip()[:120]}），可稍后手动执行。")


if __name__ == "__main__":
    sys.exit(main())
