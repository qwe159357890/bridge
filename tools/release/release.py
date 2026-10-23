#!/usr/bin/env python3
"""
Bridge 一键发布脚本：自动建仓库 → push 代码 → 触发 GitHub Actions 打包 → 下载 APK 到本地 releases/。

用法：
    python tools/release/release.py [tag]

    tag  可选，不传则自动生成 v0.1.0 形式；传了则用指定 tag（如 v0.1.1）

依赖：
    - 项目根目录存在 github.env（从 github.env.example 复制并填写真实值）
    - 系统已设置代理（http_proxy/https_proxy），或可直连 GitHub

流程（你只需提供 token，其余全自动）：
    1. 读取 github.env 获取 GITHUB_USER / GITHUB_REPO / GITHUB_TOKEN
    2. 若仓库不存在，用 GitHub API 自动创建（无需手动建）
    3. 配置 git remote 并 push 代码到 main
    4. 打 tag 并 push（触发 GitHub Actions）
    5. 轮询等待 workflow 完成
    6. 从 GitHub Release / Artifact 下载 APK 到 releases/
"""

import os
import sys
import json
import time
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
ENV_FILE = ROOT / "github.env"
RELEASE_DIR = ROOT / "releases"

GITHUB_API = "https://api.github.com"

# 内置 gh-proxy 加速代理列表（按优先级顺序，公开仓库时生效）。
# 私有仓库这些代理会 404，脚本会自动回退到 token 直连 API。
# 代理前缀 + 原始 GitHub URL 即完整加速地址。
BUILTIN_PROXIES = [
    "https://v4.gh-proxy.org",
    "https://v6.gh-proxy.org",
    "https://gh-proxy.org",
    "https://cdn.gh-proxy.org",
    "https://axisnow.gh-proxy.org",
]


def load_env() -> dict:
    """从 github.env 读取配置。"""
    if not ENV_FILE.exists():
        print(f"[错误] 未找到 {ENV_FILE}")
        print(f"       请先复制 github.env.example 为 github.env 并填写真实值。")
        sys.exit(1)
    env = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    for key in ("GITHUB_USER", "GITHUB_REPO", "GITHUB_TOKEN"):
        if not env.get(key):
            print(f"[错误] github.env 缺少 {key}")
            sys.exit(1)
    return env


def ensure_repo(env):
    """确保 GitHub 仓库存在，不存在则用 API 自动创建。"""
    repo = f"{env['GITHUB_USER']}/{env['GITHUB_REPO']}"
    print(f"\n[0] 检查仓库 {repo} 是否存在 ...")
    status, data = api_request(env, f"/repos/{repo}", env["GITHUB_TOKEN"])
    if status == 200:
        print(f"  已存在，跳过创建。")
        return
    if status == 404:
        print(f"  不存在，自动创建（private={env.get('GITHUB_PRIVATE', 'true')}）...")
        body = json.dumps({
            "name": env["GITHUB_REPO"],
            "private": env.get("GITHUB_PRIVATE", "true").lower() == "true",
            "auto_init": False,
        }).encode()
        url = f"{GITHUB_API}/user/repos"
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("Authorization", f"Bearer {env['GITHUB_TOKEN']}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                print(f"  [成功] 仓库 {repo} 已创建")
        except urllib.error.HTTPError as e:
            print(f"  [失败] 创建仓库失败: {e.code} {e.read().decode()}")
            sys.exit(1)
        return
    print(f"  [失败] 查询仓库失败: {data.get('message')}")
    sys.exit(1)


def run(cmd: list, cwd=ROOT, check=True):
    print(f"  $ {' '.join(cmd)}")
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0 and check:
        print(r.stderr)
        sys.exit(r.returncode)
    return r


def api_request(env, path, token=None):
    """调用 GitHub API，返回 (status, json)。"""
    url = f"{GITHUB_API}{path}"
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def wait_workflow(env, tag):
    """轮询等待 tag 触发的 workflow 完成。"""
    repo = f"{env['GITHUB_USER']}/{env['GITHUB_REPO']}"
    print(f"\n[3] 等待 GitHub Actions 打包完成（tag: {tag}）...")
    # 等几秒让 workflow 先被创建
    time.sleep(8)
    for _ in range(120):  # 最多等 20 分钟
        status, data = api_request(env, f"/repos/{repo}/actions/runs", env["GITHUB_TOKEN"])
        if status != 200:
            print(f"  [错误] 查询 workflow 失败: {data.get('message')}")
            return None
        for run in data.get("workflow_runs", []):
            if run.get("head_branch") == tag or run.get("head_branch", "").startswith("v"):
                status2 = run["status"]
                conclusion = run.get("conclusion")
                print(f"  workflow: {run['name']} status={status2} conclusion={conclusion}")
                if status2 == "completed":
                    if conclusion == "success":
                        print("  [完成] 打包成功")
                        return run["id"]
                    else:
                        print(f"  [失败] 打包结论为 {conclusion}")
                        return None
        time.sleep(15)
    print("  [超时] 等待打包超时")
    return None


def download_apk(env, tag, run_id):
    """下载 APK 到 releases/。优先 Release 资产，其次 Artifact。"""
    repo = f"{env['GITHUB_USER']}/{env['GITHUB_REPO']}"
    RELEASE_DIR.mkdir(exist_ok=True)

    # 方式1：GitHub Release 资产
    status, release = api_request(env, f"/repos/{repo}/releases/tags/{tag}", env["GITHUB_TOKEN"])
    if status == 200:
        assets = release.get("assets", [])
        for a in assets:
            if a["name"].endswith(".apk"):
                print(f"\n[4] 从 Release 下载 {a['name']} ...")
                # 公开仓库：走 gh-proxy 代理加速；私有仓库：回退 token 直连 API
                raw_url = a["browser_download_url"]
                if _download_via_proxies(raw_url, RELEASE_DIR / a["name"], env):
                    return
                # 代理都失败，回退 token 直连 API
                _download(a["url"], RELEASE_DIR / a["name"], env, is_api=True)
                return

    # 方式2：workflow artifact
    print("\n[4] Release 无 APK，改从 Artifact 下载 ...")
    status, artifacts = api_request(env, f"/repos/{repo}/actions/artifacts", env["GITHUB_TOKEN"])
    if status == 200:
        for art in artifacts.get("artifacts", []):
            if art["name"].startswith("bridge") and ".apk" in art["name"].lower() or ".apk" in art["name"].lower():
                # 下载 artifact 是 zip，需用 API
                url = f"/repos/{repo}/actions/artifacts/{art['id']}/zip"
                print(f"  下载 artifact {art['name']} (zip)")
                _download_artifact(env, url, RELEASE_DIR / f"{art['name']}.zip")
                return
    print("  [警告] 未找到可下载的 APK")


def _download_via_proxies(raw_url, dest, env):
    """按内置代理优先级逐个尝试下载公开资源；全部失败返回 False。"""
    for proxy in BUILTIN_PROXIES:
        proxy_url = f"{proxy}/{raw_url}"
        print(f"  尝试代理 {proxy} ...")
        if _download_simple(proxy_url, dest):
            return True
    print("  [提示] 所有代理失败，回退直连")
    return False


def _download_simple(url, dest):
    """简单下载（不带 token，用于代理/公开资源）。成功返回 True。"""
    try:
        req = urllib.request.Request(url)
        req.add_header("User-Agent", "Bridge-Release")
        with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as f:
            data = resp.read()
            if len(data) > 1024:  # 大于 1KB 才算有效（排除 404 的 9 字节"Not Found"）
                f.write(data)
                print(f"  [成功] 通过代理下载完成，保存到 {dest}（{len(data)} 字节）")
                return True
        print(f"  [失败] 代理返回无效内容（{len(data)} 字节）")
        return False
    except Exception as e:
        print(f"  [失败] 代理 {url} 错误: {e}")
        return False


def _download(url, dest, env, is_api=False):
    """下载文件。is_api=True 时 url 是 api.github.com 的 asset URL，需加 Accept 头才能拿到二进制。"""
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {env['GITHUB_TOKEN']}")
    if is_api:
        req.add_header("Accept", "application/octet-stream")
    try:
        with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as f:
            f.write(resp.read())
        print(f"  [成功] 已保存到 {dest}")
    except Exception as e:
        print(f"  [失败] 下载失败: {e}")


def _download_artifact(env, path, dest):
    url = f"{GITHUB_API}{path}"
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {env['GITHUB_TOKEN']}")
    try:
        with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as f:
            f.write(resp.read())
        print(f"  [成功] 已保存到 {dest}（解压后是 APK）")
    except Exception as e:
        print(f"  [失败] 下载失败: {e}")


def main():
    env = load_env()
    tag = sys.argv[1] if len(sys.argv) > 1 else None

    repo = f"{env['GITHUB_USER']}/{env['GITHUB_REPO']}"
    remote_url = f"https://github.com/{repo}.git"

    # 0. 确保仓库存在（不存在则自动创建）
    ensure_repo(env)

    # 1. 配置 remote（若未配置）
    print(f"[1] 配置 git remote ...")
    r = run(["git", "remote", "get-url", "origin"], check=False)
    if r.returncode != 0:
        run(["git", "remote", "add", "origin", remote_url])
    else:
        if r.stdout.strip() != remote_url:
            run(["git", "remote", "set-url", "origin", remote_url])
    print(f"  remote -> {remote_url}")

    # 2. 提交并 push
    print(f"\n[2] 提交改动并 push ...")
    r = run(["git", "status", "--porcelain"], check=False)
    if r.stdout.strip():
        run(["git", "add", "-A"])
        run(["git", "commit", "-m", "chore: auto release"])
    # 用 token 鉴权 push（避免交互式输入密码）
    auth_url = remote_url.replace("https://", f"https://{env['GITHUB_TOKEN']}@")
    run(["git", "push", auth_url, "main"])

    # 3. 打 tag 触发打包
    if not tag:
        tag = "v0.1.0"
    print(f"\n[3] 打 tag {tag} 并 push（触发打包）...")
    r = run(["git", "tag", "-f", tag], check=False)
    run(["git", "push", auth_url, tag, "-f"])

    # 4. 等待并下载
    run_id = wait_workflow(env, tag)
    if run_id:
        download_apk(env, tag, run_id)

    print("\n全部完成。APK 已下载到 releases/ 目录。")


if __name__ == "__main__":
    main()
