"""
示例 1：快速上手 —— 连接、导树、找控件、点击、输入、滑动、截屏。

前置：
1. 手机后台下发 rpc_start，拿到端口与 token（填到 _common.py）
2. 连入方式任选其一（详见 docs/guides/automation.md §三）：
   - 局域网：HOST = "192.168.31.109:15161"
   - VNT 组网：HOST = "86.74.128.88:15161"
   - adb 转发：先 `adb forward tcp:15161 tcp:15161`，再 HOST = "127.0.0.1:15161"
3. 手机开启 Bridge 辅助服务（无障碍）+ Bridge 输入辅助（IME，仅输入密码框需要）
"""

from _common import make_phone
from bridge_rpc import RpcError, Selector


def main():
    phone = make_phone()

    # 0. 无障碍就绪检查
    if not phone.a11y_ready():
        print("⚠️ 无障碍服务未开启，请在手机「设置→无障碍→Bridge 辅助服务」开启后重试")
        return

    # 1. 导出控件树，观察界面结构
    print("=== 当前界面控件树（前 20 个节点）===")
    tree = phone.dump(max_nodes=100)
    for n in tree[:20]:
        depth = n.get("depth", -1)
        cls = (n.get("className") or "").split(".")[-1]
        text = n.get("text") or n.get("desc") or ""
        cid = n.get("id") or ""
        print("  " * depth + f"{cls} text={text!r} id={cid}")

    # 2. 查找并点击
    print("\n=== 查找「确定」按钮 ===")
    try:
        nodes = phone.wait_for(Selector.text_contains("确定").clickable(), timeout_ms=8000)
        print(f"找到 {len(nodes)} 个，点击第一个")
        phone.click(Selector.text_contains("确定").clickable())
    except RpcError as e:
        print(f"未找到：{e.message}")

    # 3. 输入文本
    print("\n=== 输入文本 ===")
    phone.set_text(Selector.class_name("EditText"), "自动化测试文本")

    # 4. 滑动
    print("\n=== 向上滑动 ===")
    phone.swipe(540, 1600, 540, 600, duration_ms=400)

    # 5. 截屏
    print("\n=== 截屏 ===")
    phone.screenshot(save_to="automation_result.jpg")
    print("已保存截图到 automation_result.jpg")

    print("\n=== 完成 ===")


if __name__ == "__main__":
    main()
