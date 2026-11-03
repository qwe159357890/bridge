"""
示例 6：视觉能力 —— 找图 + OCR（跑在电脑端）。

依赖（可选）：
  找图：pip install opencv-python numpy
  OCR： pip install pytesseract （需系统装 tesseract）
        或 pip install paddleocr（高精度中文，推荐）

适用场景：界面没有可读控件（游戏、图片、验证码），只能靠「看屏幕」来定位。
"""
from _common import make_phone
from bridge_rpc.vision import Vision


def main():
    phone = make_phone()
    vis = Vision(phone, confidence=0.85)

    # 1. 找图：屏幕里找 template.png（先截屏裁一块存成模板）
    print("=== 找图 ===")
    pt = vis.find_image("template.png")
    if pt:
        print(f"找到模板在 ({pt[0]}, {pt[1]})")
        vis.tap_image("template.png")   # 找到并点击
        print("已点击")
    else:
        print("没找到模板（先截屏裁一块存成 template.png）")

    # 2. OCR 整屏：把所有文字和坐标打出来
    print("\n=== OCR 整屏 ===")
    for item in vis.ocr(engine="paddle"):
        print(f"  ({item['x']},{item['y']}) {item['text']}")

    # 3. OCR 找文字并点击
    print("\n=== OCR 找「登录」并点击 ===")
    ok = vis.ocr_tap("登录", engine="paddle")
    print("点击结果：", ok)


if __name__ == "__main__":
    main()
