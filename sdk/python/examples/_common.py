"""
共享连接配置：所有示例脚本从这里 import，改一处即可到处跑。

用法：
    from _common import make_phone
    phone = make_phone()

前置：手机后台下发 rpc_start，拿到端口与 token，填到下方 HOST / TOKEN。
"""

# ============ 改成你的实际值 ============
# 连入地址三选一（详见 docs/guides/automation.md §三）：
#   A. 局域网： "192.168.31.109:15161"      （手机和电脑同一 WiFi）
#   B. VNT 组网："86.74.128.88:15161"       （已组虚拟网，可跨网络）
#   C. adb 转发：先 `adb forward tcp:15161 tcp:15161`，再填 "127.0.0.1:15161"
HOST = "127.0.0.1:15161"

# rpc_start 回执里的 token
TOKEN = "替换为你的 token"
# =========================================

from bridge_rpc import Bridge


def make_phone(host: str = HOST, token: str = TOKEN) -> Bridge:
    """创建并返回一个已连接的 Bridge 客户端。"""
    if "替换" in token:
        print("⚠️  请先在 _common.py 里把 TOKEN 改成 rpc_start 回执里的真实 token")
    return Bridge(host, token=token)


if __name__ == "__main__":
    phone = make_phone()
    print("连接成功，无障碍就绪：", phone.a11y_ready())
