#!/usr/bin/env python3
"""
Bridge 图标生成脚本：从源图（assets/icons/）生成各分辨率 mipmap 与 Play Store 图标。

用法（需 Pillow）：
    python tools/icon/generate_icon.py

产物：
    app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
    app/store/ic_launcher-playstore.png

说明：源图为非正方形时，采用「等比缩放 + 透明画布居中」策略，避免拉伸变形。
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "assets", "icons", "沟通记录.png")
APP_RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE = os.path.join(ROOT, "app", "store")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def fit_square(im: Image.Image, size: int) -> Image.Image:
    """等比缩放到画布 88% 区域，保持比例居中放置，避免变形。"""
    inner = int(size * 0.88)
    ratio = min(inner / im.width, inner / im.height)
    w, h = int(im.width * ratio), int(im.height * ratio)
    resized = im.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(resized, ((size - w) // 2, (size - h) // 2), resized)
    return canvas


def main() -> None:
    img = Image.open(SRC).convert("RGBA")
    print(f"源图: {SRC} 尺寸 {img.size}")

    for density, size in DENSITIES.items():
        out_dir = os.path.join(APP_RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        fit_square(img, size).save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
        print(f"  mipmap-{density}/ic_launcher.png {size}x{size}")

    os.makedirs(STORE, exist_ok=True)
    fit_square(img, 512).save(os.path.join(STORE, "ic_launcher-playstore.png"), "PNG")
    print("  Play Store 512 完成")


if __name__ == "__main__":
    main()
