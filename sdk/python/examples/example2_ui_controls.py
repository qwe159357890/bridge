"""
示例 2：控件操作进阶 —— 关系查询 + 滚动 + 等待 + 读文本。

演示列表自动化最常见的套路：
  1. 用关系查询定位「某一行里的按钮」；
  2. scroll_to 滚动列表直到目标出现；
  3. wait_for / wait_idle 处理页面跳转与动画；
  4. 读文本、计数、多分支判断。

前置：手机开启 Bridge 辅助服务；先跑 example1 确认连接正常。
"""
from _common import make_phone
from bridge_rpc import Bridge, Selector, RpcError


def main():
    phone = make_phone()
    if not phone.a11y_ready():
        print("⚠️  请先在手机「设置 → 无障碍」开启 Bridge 辅助服务")
        return

    # 1. 关系查询：找「价格 ¥99 的那一行的购买按钮」
    print("=== 关系查询：找 ¥99 的购买按钮 ===")
    nodes = phone.find({"text": "¥99", "has": {"textContains": "购买"}})
    print(f"命中 {len(nodes)} 个，第一个：", nodes[0].get("text") if nodes else "无")

    # 2. 滚动直到目标可见
    print("\n=== 滚动列表找「确认收货」 ===")
    try:
        phone.scroll_to("text('确认收货')", direction="down", max_swipes=15)
        phone.click("text('确认收货')")
        print("已滚动到并点击")
    except RpcError as e:
        print(f"没找到：{e.message}")

    # 3. 等页面静止（动画结束）再继续
    phone.wait_idle(ms=500)

    # 4. 多分支判断：等「成功」或「失败」哪个先出现
    print("\n=== 多分支判断 ===")
    idx = phone.which([Selector.text_contains("成功"), Selector.text_contains("失败")])
    if idx == 0:
        print("操作成功")
    elif idx == 1:
        print("操作失败")
    else:
        print("超时，未知结果")

    # 5. 读文本 / 计数
    print("\n=== 读文本 / 计数 ===")
    title = phone.get_text("className('TextView')")
    print("标题文字：", title)
    print("按钮数量：", phone.count("className('Button')"))


if __name__ == "__main__":
    main()
