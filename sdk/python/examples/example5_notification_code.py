"""
示例 5：验证码自动化 —— 通知监听 + 读验证码 + 回填。

这是自动化模块最有价值的场景之一：
  1. 触发发验证码（点「获取验证码」按钮）；
  2. 监听短信/App 通知，读到验证码；
  3. 回填到输入框。

前置：手机需授权「通知使用权」给 Bridge（系统设置 → 通知使用权）。
"""
from _common import make_phone
from bridge_rpc import Bridge, Selector, RpcError
import re
import time


def main():
    phone = make_phone()
    if not phone.a11y_ready():
        print("⚠️  请先开启 Bridge 辅助服务")
        return

    # 1. 点「获取验证码」
    print("=== 点击获取验证码 ===")
    try:
        phone.click(Selector.text_contains("获取验证码").clickable())
    except RpcError as e:
        print(f"没找到按钮：{e.message}")
        return

    # 2. 轮询通知，等验证码出现
    print("=== 等待验证码通知 ===")
    code = None
    deadline = time.time() + 30  # 最多等 30 秒
    while time.time() < deadline:
        notifs = phone.notifications()
        for n in notifs:
            text = (n.get("text") or "") + (n.get("title") or "")
            m = re.search(r"\b(\d{4,6})\b", text)  # 4~6 位数字
            if m:
                code = m.group(1)
                break
        if code:
            break
        time.sleep(1)

    if not code:
        print("30 秒内没读到验证码")
        return
    print(f"读到验证码：{code}")

    # 3. 回填到输入框
    print("=== 回填验证码 ===")
    phone.set_text(Selector.class_name("EditText"), code)

    # 4. （可选）清除这条通知
    for n in phone.notifications():
        if code in (n.get("text") or ""):
            phone.notification_dismiss(n.get("key", ""))
            break

    print("完成")


if __name__ == "__main__":
    main()
