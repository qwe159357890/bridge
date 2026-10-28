"""git push 失败时的 Git Data API 备用通道（github-api-automation）。

流程：读 tools/release/github.env token → 远端 main 为 parent →
逐变更文件建 blob（git cat-file 取仓库原始内容）→ base_tree 建 tree →
建 commit → 更新 refs/heads/main → 创建轻量 tag 触发 Actions。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

REPO = "qwe159357890/bridge"
API = f"https://api.github.com/repos/{REPO}"

# token 从 github.env 解析（GITHUB_TOKEN=xxx 形式）
token = ""
for line in open("github.env", encoding="utf-8"):
    line = line.strip()
    if line.startswith("GITHUB_TOKEN=") and not line.startswith("#"):
        token = line.split("=", 1)[1].strip().strip('"').strip("'")
assert token, "github.env 中未找到 GITHUB_TOKEN"

# 可命令行覆盖：api_push.py <tag> <message>
TAG = sys.argv[1] if len(sys.argv) > 1 else "v0.1.53"
MESSAGE = sys.argv[2] if len(sys.argv) > 2 else (
    "fix: 投屏直播FLV sequence header剥起始码(mpegts FormatError根因)"
    "+首帧不再丢弃+TS播放追帧自愈 (v0.1.53)")

# 差异基线：默认 HEAD~1（单 commit）；本地领先远端多笔 commit 时用
# PUSH_BASE 环境变量指定（如远端 main 对应的本地 commit）。
# 带 --name-status 区分增/改/删/改名的路径：D 与改名的旧路径需要从远端树中
# 删除（Trees API base_tree 模式下 sha=None 即删除），此前不支持会 cat-file 报错。
BASE = os.environ.get("PUSH_BASE", "HEAD~1")
_raw = subprocess.run(["git", "diff", "--name-status", BASE, "HEAD"],
                      capture_output=True, text=True).stdout.splitlines()
FILES: list[str] = []
DELETED: list[str] = []
for _line in _raw:
    parts = _line.split("\t")
    if len(parts) < 2:
        continue
    status, path = parts[0][0], parts[-1]
    if path == ".gitignore":
        continue
    if status == "D":
        DELETED.append(path)
    elif status == "R":
        # 改名：新路径上传，旧路径删除
        FILES.append(path)
        DELETED.append(parts[1])
    else:
        FILES.append(path)


def api(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        f"{API}{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"token {token}", "Content-Type": "application/json",
                 "User-Agent": "bridge-release"},
        method=method,
    )
    with urllib.request.urlopen(req) as r:
        return json.load(r) if r.status != 204 else {}


def cat_blob(path: str) -> bytes:
    return subprocess.run(["git", "cat-file", "blob", f"HEAD:{path}"],
                          check=True, capture_output=True).stdout


def main() -> int:
    base_commit = api("GET", "/git/refs/heads/main")["object"]["sha"]
    base_tree = api("GET", f"/git/commits/{base_commit}")["tree"]["sha"]
    print(f"[1] 远端 main={base_commit[:10]} base_tree={base_tree[:10]}")

    entries = []
    for f in FILES:
        b64 = base64.b64encode(cat_blob(f)).decode()
        sha = api("POST", "/git/blobs", {"content": b64, "encoding": "base64"})["sha"]
        entries.append({"path": f, "mode": "100644", "type": "blob", "sha": sha})
        print(f"[2] blob {f} -> {sha[:10]}")

    # 删除：base_tree 模式下 sha=None 即从远端树移除该路径
    for f in DELETED:
        entries.append({"path": f, "mode": "100644", "type": "blob", "sha": None})
        print(f"[2] delete {f}")

    tree = api("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})["sha"]
    print(f"[3] tree={tree[:10]}")

    commit = api("POST", "/git/commits",
                 {"message": MESSAGE, "tree": tree, "parents": [base_commit]})["sha"]
    print(f"[4] commit={commit[:10]}")

    api("PATCH", f"/git/refs/heads/main", {"sha": commit, "force": False})
    print("[5] main 已更新")

    if TAG and TAG != "-":
        api("POST", "/git/refs", {"ref": f"refs/tags/{TAG}", "sha": commit})
        print(f"[6] tag {TAG} 已创建（触发 Actions）")
    else:
        print("[6] 跳过打 tag（纯服务端/admin 改动，不触发 Actions/APK 构建）")
    return 0


sys.exit(main())
