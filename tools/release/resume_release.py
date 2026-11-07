# -*- coding: utf-8 -*-
"""发版接续脚本（一次性工具）：

release.py 在轮询 /actions/runs（~390KB 大响应）时网络不稳会抛
http.client.IncompleteRead 崩掉。tag 已推送、CI 已触发，本脚本复用
release.py 的内部函数（wait_workflow / download_apk / copy_to_updates_dir），
仅给 api_request 套 5 次重试，接完「等待打包 -> 下载 APK -> 拷贝 updates」。

用法：python tools/release/resume_release.py v0.2.99
"""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release as R  # noqa: E402


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else None
    if not tag:
        print("[错误] 用法: python resume_release.py v0.2.99")
        sys.exit(1)

    # 给 api_request 套重试：IncompleteRead/连接中断等瞬时网络故障
    _orig = R.api_request

    def api_retry(env, path, token=None, *a, **k):
        last = None
        for i in range(5):
            try:
                return _orig(env, path, token, *a, **k)
            except Exception as e:  # noqa: BLE001
                last = e
                print(f"  [重试 {i + 1}/5] api_request {path} 失败: {type(e).__name__}: {e}")
                time.sleep(3)
        raise last

    R.api_request = api_retry

    env = R.load_env()
    repo = f"{env['GITHUB_USER']}/{env['GITHUB_REPO']}"
    tag_s = tag.lstrip("v")
    print(f"[接续] 等待 tag {tag} 的 workflow 完成（per_page=10 缩响应防截断）...")
    time.sleep(5)
    run_id = None
    for i in range(80):  # 最多约 20 分钟
        try:
            status, data = R.api_request(
                env, f"/repos/{repo}/actions/runs?event=push&per_page=10", env["GITHUB_TOKEN"])
        except Exception as e:  # noqa: BLE001
            print(f"  [跳过] 轮询异常（继续等）: {type(e).__name__}: {e}")
            data = {}
        if status == 200:
            matched = [r for r in data.get("workflow_runs", []) if r.get("head_branch") == tag]
            if matched:
                run = max(matched, key=lambda r: r.get("created_at", ""))
                print(f"  workflow: {run['name']} status={run['status']} "
                      f"conclusion={run.get('conclusion')}")
                if run["status"] == "completed":
                    if run.get("conclusion") == "success":
                        run_id = run["id"]
                    break
        else:
            print(f"  [提示] 轮询 status={status}（继续等）")
        time.sleep(15)
    if not run_id:
        print("[失败] workflow 未成功（未完成/失败/超时），检查 GitHub Actions 日志")
        sys.exit(2)
    apk = R.download_apk(env, tag, run_id)
    if apk:
        R.copy_to_updates_dir(tag, apk)
    print(f"[完成] APK: {apk}")


if __name__ == "__main__":
    main()
