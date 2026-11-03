"""
示例 3：设备 / 应用操作 —— 启动、调亮度、读状态。

演示设备域的常用操作：启动应用、查设备信息、控制亮度、读内存/充电状态。
"""
from _common import make_phone


def main():
    phone = make_phone()

    # 1. 启动应用（按包名 / 按应用名）
    print("=== 启动应用 ===")
    phone.launch("com.tencent.mm")      # 按包名
    # phone.launch_app("微信")            # 或按应用名

    # 2. 设备信息汇总
    print("\n=== 设备信息 ===")
    info = phone.info()
    for k, v in info.items():
        print(f"  {k}: {v}")

    # 3. 屏幕状态
    print("\n=== 屏幕 / 电量 ===")
    print("屏幕亮：", phone.screen_on())
    print("锁屏：", phone.locked())
    print("横竖屏：", phone.orientation())
    print("充电中：", phone.is_charging())

    # 4. 内存
    mem = phone.get_mem()
    print("\n=== 内存 ===")
    print(mem)

    # 5. 调亮度（0-255）
    print("\n=== 亮度 ===")
    before = phone.get_brightness()
    phone.set_brightness(200)
    print(f"亮度 {before} -> {phone.get_brightness()}")

    # 6. 已装应用里找微信
    print("\n=== 已装应用（前 5 个） ===")
    for app in phone.apps()[:5]:
        print(f"  {app.get('label') or app.get('packageName')}")


if __name__ == "__main__":
    main()
