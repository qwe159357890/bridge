"""
Bridge RPC 视觉能力（跑在 PC 端，手机端只提供截屏字节流）。

对应 Droidline 的 find_image / tap_image / ocr / ocr_find / ocr_tap，
但实现放在 Python SDK 端，避免在手机上引入 OpenCV/OCR 重量依赖。

依赖（可选安装）：
    pip install opencv-python numpy          # 找图
    pip install pytesseract                   # OCR（需系统装 tesseract）
    或 pip install paddleocr                 # 高精度中文 OCR

用法：
    from bridge_rpc import Bridge
    from bridge_rpc.vision import Vision

    phone = Bridge("192.168.31.109:15161", token="xxxx")
    vis = Vision(phone)

    pt = vis.find_image("template.png")       # 在手机屏幕上找模板图 → (x, y) 或 None
    vis.tap_image("template.png")            # 找到并点击
    text = vis.ocr()                          # OCR 整屏
    pt = vis.ocr_find("登录")                 # OCR 后按文字找位置
    vis.ocr_tap("登录")                       # OCR 找到并点击
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

import io


class Vision:
    """基于截屏字节流的视觉能力（opencv 找图 + OCR）。"""

    def __init__(self, bridge: "Any", confidence: float = 0.8):
        self.bridge = bridge
        self.confidence = confidence

    # ------------------------------------------------------------------
    # 图像工具
    # ------------------------------------------------------------------

    def _screen_bgr(self):
        """截屏并解码为 OpenCV BGR 图像。"""
        import cv2
        import numpy as np
        jpg = self.bridge.screenshot()
        arr = np.frombuffer(jpg, dtype=np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            raise RuntimeError("截屏解码失败")
        return img

    def _template_bgr(self, template_path: str):
        import cv2
        img = cv2.imread(template_path, cv2.IMREAD_COLOR)
        if img is None:
            raise FileNotFoundError(f"模板图读取失败：{template_path}")
        return img

    # ------------------------------------------------------------------
    # 找图
    # ------------------------------------------------------------------

    def find_image(self, template_path: str, confidence: Optional[float] = None) -> Optional[Tuple[int, int]]:
        """在手机屏幕上找模板图，返回中心点 (x, y) 或 None。

        :param template_path: 模板图路径（PNG/JPG）。
        :param confidence: 匹配置信度（0~1），默认取构造时的 confidence。
        """
        import cv2
        screen = self._screen_bgr()
        template = self._template_bgr(template_path)
        th, tw = template.shape[:2]
        result = cv2.matchTemplate(screen, template, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, max_loc = cv2.minMaxLoc(result)
        conf = confidence if confidence is not None else self.confidence
        if max_val < conf:
            return None
        return (max_loc[0] + tw // 2, max_loc[1] + th // 2)

    def find_all_images(self, template_path: str, confidence: Optional[float] = None,
                        max_results: int = 50) -> List[Tuple[int, int]]:
        """找所有匹配模板的位置（非极大值抑制前）。"""
        import cv2
        screen = self._screen_bgr()
        template = self._template_bgr(template_path)
        th, tw = template.shape[:2]
        result = cv2.matchTemplate(screen, template, cv2.TM_CCOEFF_NORMED)
        conf = confidence if confidence is not None else self.confidence
        ys, xs = (result >= conf).nonzero()
        return [(int(x) + tw // 2, int(y) + th // 2) for x, y in zip(xs, ys)][:max_results]

    def tap_image(self, template_path: str, confidence: Optional[float] = None) -> bool:
        """找到模板图并点击中心，返回是否成功。"""
        pt = self.find_image(template_path, confidence)
        if pt is None:
            return False
        self.bridge.click(x=pt[0], y=pt[1])
        return True

    # ------------------------------------------------------------------
    # OCR
    # ------------------------------------------------------------------

    def _ocr_engine(self, engine: str):
        """按 engine 名返回 OCR 函数（text = func(img_bgr) -> list[(text, x, y, w, h)]）。"""
        if engine == "tesseract":
            try:
                import pytesseract
                import cv2

                def _run(img):
                    data = pytesseract.image_to_data(img, output_type=pytesseract.Output.DICT)
                    out = []
                    n = len(data["text"])
                    for i in range(n):
                        t = (data["text"][i] or "").strip()
                        if not t:
                            continue
                        x, y = data["left"][i], data["top"][i]
                        w, h = data["width"][i], data["height"][i]
                        out.append((t, int(x), int(y), int(w), int(h)))
                    return out
                return _run
            except ImportError:
                raise RuntimeError("OCR 需要安装 pytesseract（pip install pytesseract）并装 tesseract")
        elif engine == "paddle":
            try:
                from paddleocr import PaddleOCR
                ocr = PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)

                def _run(img):
                    import numpy as np
                    rgb = img[:, :, ::-1]
                    result = ocr.ocr(rgb, cls=True)
                    out = []
                    for line in (result[0] if result else []):
                        box = line[0]
                        text = line[1][0]
                        xs = [p[0] for p in box]
                        ys = [p[1] for p in box]
                        x, y = int(min(xs)), int(min(ys))
                        w, h = int(max(xs) - min(xs)), int(max(ys) - min(ys))
                        out.append((text, x, y, w, h))
                    return out
                return _run
            except ImportError:
                raise RuntimeError("OCR 需要安装 paddleocr（pip install paddleocr）")
        else:
            raise ValueError(f"未知 OCR 引擎：{engine}（可选 tesseract / paddle）")

    def ocr(self, engine: str = "tesseract") -> List[Dict[str, Any]]:
        """OCR 整屏，返回 [{"text","x","y","w","h"}, ...]。"""
        img = self._screen_bgr()
        run = self._ocr_engine(engine)
        out = run(img)
        return [{"text": t, "x": x, "y": y, "w": w, "h": h} for t, x, y, w, h in out]

    def ocr_find(self, keyword: str, engine: str = "tesseract", contains: bool = True) -> Optional[Dict[str, Any]]:
        """OCR 后按文字找位置，返回首个匹配或 None。"""
        for item in self.ocr(engine):
            text = item["text"]
            if (contains and keyword in text) or (not contains and text == keyword):
                return item
        return None

    def ocr_tap(self, keyword: str, engine: str = "tesseract", contains: bool = True) -> bool:
        """OCR 找到文字并点击中心，返回是否成功。"""
        item = self.ocr_find(keyword, engine, contains)
        if item is None:
            return False
        cx = item["x"] + item["w"] // 2
        cy = item["y"] + item["h"] // 2
        self.bridge.click(x=cx, y=cy)
        return True


__all__ = ["Vision"]
