#!/usr/bin/env python3
"""v0.2.16 发布：销售页表格空白定位（加全链路高度诊断日志）。

（与 push_v0210.py 同构：git hash-object vs 远端 tree sha，差异才上传；
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
TAG = "v0.2.16"
MESSAGE = (
    "fix: 销售页表格空白定位——加全链路高度诊断日志（v0.2.16）\n"
    "\n"
    "- v0.2.15 日志确认：数据体 Box 高度=0（w=1080 h=0px），行虽 compose 但被\n"
    "  压成 0 高不可见；uiautomator 确认表头(Y717-789)到分页(Y2148)之间 1359px\n"
    "  数据区零节点\n"
    "- 本轮在 SalesPage 顶层 Box、主 Column、SalesTable 父 Box、SalesTable Column\n"
    "  四处加 onGloballyPositioned 日志，一次性打印整条高度链，锁定塌缩环节"
)

# 候选文件（hash 一致自动跳过）
CANDIDATES = [
    "app/build.gradle.kts",
    "app/src/main/java/my/bridge/com/ui/MainScreen.kt",
    "app/src/main/java/my/bridge/com/ui/SalesPages.kt",
    "app/src/main/java/my/bridge/com/sales/SalesModels.kt",
    "app/src/main/java/my/bridge/com/sales/SalesPrefs.kt",
    "app/src/main/java/my/bridge/com/sales/SalesApiClient.kt",
    "app/src/main/java/my/bridge/com/sales/SalesWsClient.kt",
    "app/src/main/java/my/bridge/com/sales/SalesState.kt",
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
