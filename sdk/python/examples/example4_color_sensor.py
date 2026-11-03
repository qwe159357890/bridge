"""
示例 4：找色 / 传感器 —— 游戏自动化入门。

演示纯像素级定位（不依赖控件树），适合游戏、无节点界面：
  - find_color：全屏找某个颜色点；
  - find_multi_colors：多点找色（更精确，游戏道具定位核心）；
  - color：读某坐标像素色；
  - sensors：读传感器（重力、陀螺仪等）。
"""
from _common import make_phone


def main():
    phone = make_phone()

    # 1. 找色：在屏幕上找红色像素
    print("=== 找色 ===")
    pt = phone.find_color("#FF0000", threshold=16)
    if pt:
        print(f"找到红色在 ({pt['x']}, {pt['y']})")
        # 找到后直接点击
        phone.click(x=pt["x"], y=pt["y"])
    else:
        print("没找到纯红像素")

    # 2. 多点找色：先找基准色，再按相对偏移验证其他点（更精确）
    # points 格式：[x偏移, y偏移, 期望色, ...] 依次排列
    print("\n=== 多点找色 ===")
    pt = phone.find_multi_colors("#FF0000", points=[0, 0, 0xFF0000, 10, 0, 0xFF0000])
    print(f"结果：{pt}")

    # 3. 读某坐标像素色（判断某个按钮当前是红还是绿）
    print("\n=== 读像素色 ===")
    c = phone.color(500, 300)
    print(f"(500,300) 的颜色是 {c}")

    # 4. 传感器
    print("\n=== 传感器 ===")
    for name in ["accelerometer", "gravity", "light", "proximity"]:
        v = phone.sensors(name)
        print(f"  {name}: {v}")


if __name__ == "__main__":
    main()
