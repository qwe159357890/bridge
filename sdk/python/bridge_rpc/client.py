"""
Bridge RPC Python 客户端（thin client）。

让 Python 脚本像调用本地对象一样驱动手机自动化：
    from bridge_rpc import Bridge

    phone = Bridge("192.168.31.109:15161", token="xxxx")  # 局域网 IP 连入
    phone = Bridge("86.74.128.88:15161", token="xxxx")    # VNT 虚拟网 IP 连入
    phone = Bridge("127.0.0.1:15161", token="xxxx")       # adb forward 后本机连入
    phone.dump()                  # 导出控件树
    phone.find("登录")             # 查找控件
    phone.click("登录")            # 点击
    phone.set_text("输入框", "hello")
    phone.swipe(100, 500, 100, 200)

底层走 HTTP/REST（requests），与 Bridge App 内嵌 RPC 服务通讯。
"""

from __future__ import annotations

import base64
from typing import Any, Dict, List, Optional

import requests

from .selector import Selector, to_selector


class RpcError(Exception):
    """RPC 调用失败（命令执行失败或网络错误）。"""

    def __init__(self, message: str, error_code: Optional[str] = None, retryable: bool = False):
        super().__init__(message)
        self.message = message
        self.error_code = error_code
        self.retryable = retryable


class Bridge:
    """Bridge 手机 RPC 客户端。"""

    def __init__(self, host: str = "127.0.0.1:15161", token: str = "", timeout: float = 30.0):
        """
        :param host: 手机 RPC 地址（host:port）。手机端监听 0.0.0.0，默认端口 15161，可从任一可达地址连入：
                     本机 127.0.0.1（需 adb forward）、局域网 IP（如 192.168.31.109）、
                     VNT 虚拟网 IP（如 86.74.128.88）。
        :param token: rpc_start 返回的鉴权 token。
        :param timeout: HTTP 超时（秒）。
        """
        if not host.startswith("http"):
            host = f"http://{host}"
        self.base = host.rstrip("/")
        self.token = token
        self.timeout = timeout
        self._session = requests.Session()

    # ------------------------------------------------------------------
    # 内部：HTTP 封装
    # ------------------------------------------------------------------

    def _auth(self, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        p = dict(params or {})
        if self.token:
            p["token"] = self.token
        return p

    def _get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        r = self._session.get(
            f"{self.base}{path}", params=self._auth(params), timeout=self.timeout
        )
        return self._handle(r)

    def _post(self, path: str, body: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        r = self._session.post(
            f"{self.base}{path}", json=body or {}, params=self._auth(), timeout=self.timeout
        )
        return self._handle(r)

    def _handle(self, r: requests.Response) -> Dict[str, Any]:
        if r.status_code == 401:
            raise RpcError("鉴权失败：token 无效", "UNAUTHORIZED", retryable=False)
        if r.status_code != 200:
            raise RpcError(f"HTTP {r.status_code}: {r.text[:200]}", "HTTP_ERROR", retryable=True)
        data = r.json()
        if not data.get("success", False):
            raise RpcError(
                data.get("message", "命令执行失败"),
                data.get("error_code"),
                data.get("retryable", False),
            )
        return data

    # ------------------------------------------------------------------
    # 控件树 / 查找
    # ------------------------------------------------------------------

    def dump(self, max_nodes: int = 500, max_depth: int = 20) -> List[Dict[str, Any]]:
        """导出当前窗口控件树（JSON 列表，含 depth 层级）。"""
        data = self._get("/dump", {"max_nodes": max_nodes, "max_depth": max_depth})
        return self._parse_nodes(data.get("data", "[]"))

    def find(self, selector: Any, max_results: int = 20, timeout_ms: int = 0) -> List[Dict[str, Any]]:
        """按选择器查找控件，返回匹配节点列表。

        :param selector: 字符串（如 "text('登录')"）或 Selector 对象。
        :param timeout_ms: 等待控件出现的超时（毫秒），0=不等待。
        """
        data = self._post(
            "/find",
            {"selector": to_selector(selector), "max_results": max_results, "timeout_ms": timeout_ms},
        )
        return self._parse_nodes(data.get("data", "[]"))

    def wait_for(self, selector: Any, timeout_ms: int = 10000, max_results: int = 20) -> List[Dict[str, Any]]:
        """等待控件出现，超时抛 RpcError。"""
        nodes = self.find(selector, max_results=max_results, timeout_ms=timeout_ms)
        if not nodes:
            raise RpcError(f"等待控件超时：{selector}", "TIMEOUT", retryable=True)
        return nodes

    # ------------------------------------------------------------------
    # 动作
    # ------------------------------------------------------------------

    def click(self, selector: Any = None, x: Optional[int] = None, y: Optional[int] = None, index: int = 0) -> Dict[str, Any]:
        """点击。优先按选择器，其次按坐标。"""
        body: Dict[str, Any] = {"index": index}
        if selector is not None:
            body["selector"] = to_selector(selector)
        if x is not None and y is not None:
            body["x"] = x
            body["y"] = y
        return self._post("/click", body)

    def set_text(self, selector: Any, text: str, index: int = 0) -> Dict[str, Any]:
        """向输入框写入文本（自动 ACTION_SET_TEXT → IME 兜底）。"""
        return self._post("/set_text", {"selector": to_selector(selector), "text": text, "index": index})

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> Dict[str, Any]:
        """滑动（起点→终点）。"""
        return self._post("/swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms})

    def gesture(self, action: str = "swipe", x1: int = 0, y1: int = 0,
                x2: Optional[int] = None, y2: Optional[int] = None, duration_ms: int = 300) -> Dict[str, Any]:
        """通用手势：swipe / click / long_click。"""
        body: Dict[str, Any] = {"action": action, "x1": x1, "y1": y1, "duration_ms": duration_ms}
        if x2 is not None and y2 is not None:
            body["x2"] = x2
            body["y2"] = y2
        return self._post("/gesture", body)

    def long_click(self, x: int, y: int, duration_ms: int = 800) -> Dict[str, Any]:
        """坐标长按。"""
        return self.gesture("long_click", x, y, duration_ms=duration_ms)

    def back(self) -> Dict[str, Any]:
        """返回键。"""
        return self._post("/back")

    def home(self) -> Dict[str, Any]:
        """主页键。"""
        return self._post("/home")

    # ------------------------------------------------------------------
    # 通用 exec（透传任意白名单内命令）
    # ------------------------------------------------------------------

    def exec(self, command: str, **params: Any) -> Dict[str, Any]:
        """调用任意白名单内的自动化命令（通用入口）。

        :param command: 命令名（如 "ui_scroll_to"、"ui_launch"、"ui_find_color"）。
        :param params: 命令参数（键值对）。
        """
        body: Dict[str, Any] = {"command": command}
        body.update(params)
        return self._post("/exec", body)

    # ------------------------------------------------------------------
    # 元素动作增强
    # ------------------------------------------------------------------

    def scroll_to(self, selector: Any, direction: str = "down", max_swipes: int = 20, nth: int = 0) -> Dict[str, Any]:
        """滚动直到目标控件可见。"""
        return self.exec("ui_scroll_to", selector=to_selector(selector),
                         direction=direction, max_swipes=max_swipes, nth=nth)

    def wait_idle(self, ms: int = 500, timeout_ms: int = 10000) -> Dict[str, Any]:
        """等待画面静止。"""
        return self.exec("ui_wait_idle", ms=ms, timeout_ms=timeout_ms)

    def wait_gone(self, selector: Any, timeout_ms: int = 10000) -> Dict[str, Any]:
        """等待控件消失。"""
        return self.exec("ui_wait_gone", selector=to_selector(selector), timeout_ms=timeout_ms)

    def clear(self, selector: Any, nth: int = 0) -> Dict[str, Any]:
        """清空输入框。"""
        return self.exec("ui_clear", selector=to_selector(selector), nth=nth)

    def sendkey(self, key: str = "", text: str = "") -> Dict[str, Any]:
        """发送按键（键名/键码）或文字。"""
        body: Dict[str, Any] = {}
        if key:
            body["key"] = key
        if text:
            body["text"] = text
        return self.exec("ui_sendkey", **body)

    def exists(self, selector: Any) -> bool:
        """控件是否立即存在。"""
        return self.exec("ui_exists", selector=to_selector(selector)).get("message", "false") == "true"

    def get_text(self, selector: Any, nth: int = 0) -> str:
        """读取控件文本。"""
        return self.exec("ui_get_text", selector=to_selector(selector), nth=nth).get("message", "")

    def checked(self, selector: Any) -> bool:
        return self.exec("ui_checked", selector=to_selector(selector)).get("message", "false") == "true"

    def enabled(self, selector: Any) -> bool:
        return self.exec("ui_enabled", selector=to_selector(selector)).get("message", "false") == "true"

    def selected(self, selector: Any) -> bool:
        return self.exec("ui_selected", selector=to_selector(selector)).get("message", "false") == "true"

    def count(self, selector: Any) -> int:
        """匹配控件数量。"""
        try:
            return int(self.exec("ui_count", selector=to_selector(selector)).get("message", "0"))
        except (ValueError, TypeError):
            return 0

    def which(self, candidates: List[Any], timeout_ms: int = 10000) -> int:
        """多个候选中哪个先出现，返回序号或 -1。"""
        import json
        sel = [to_selector(c) for c in candidates]
        r = self.exec("ui_which", candidates=json.dumps(sel), timeout_ms=timeout_ms)
        try:
            return int(r.get("message", "-1"))
        except (ValueError, TypeError):
            return -1

    def in_app(self, package: str) -> bool:
        """某包是否前台。"""
        return self.exec("ui_in_app", package=package).get("message", "false") == "true"

    def keyboard_shown(self) -> bool:
        return self.exec("ui_keyboard_shown").get("message", "false") == "true"

    def last_toast(self, max_age: int = 30) -> str:
        return self.exec("ui_last_toast", max_age=max_age).get("message", "")

    def color(self, x: int, y: int) -> str:
        """坐标像素色 #RRGGBB。"""
        return self.exec("ui_color", x=x, y=y).get("message", "")

    def recents(self) -> Dict[str, Any]:
        return self.exec("ui_recents")

    def open_notifications(self) -> Dict[str, Any]:
        return self.exec("ui_open_notifications")

    def quick_settings(self) -> Dict[str, Any]:
        return self.exec("ui_quick_settings")

    # ------------------------------------------------------------------
    # 设备 / 应用
    # ------------------------------------------------------------------

    def launch(self, package: str, activity: str = "") -> Dict[str, Any]:
        """启动应用。"""
        body: Dict[str, Any] = {"package": package}
        if activity:
            body["activity"] = activity
        return self.exec("ui_launch", **body)

    def open_url(self, url: str) -> Dict[str, Any]:
        return self.exec("ui_open_url", url=url)

    def intent(self, action: str) -> Dict[str, Any]:
        return self.exec("ui_intent", action=action)

    def apps(self, system: bool = False) -> List[Dict[str, Any]]:
        """已装应用列表。"""
        raw = self.exec("ui_apps", system=system).get("message", "[]")
        return self._parse_nodes(raw)

    def installed(self, package: str) -> str:
        """应用版本名。"""
        return self.exec("ui_installed", package=package).get("message", "")

    def screen_on(self) -> bool:
        return self.exec("ui_screen_on").get("message", "false") == "true"

    def locked(self) -> bool:
        return self.exec("ui_locked").get("message", "false") == "true"

    def wake(self) -> Dict[str, Any]:
        return self.exec("ui_wake")

    def orientation(self) -> str:
        return self.exec("ui_orientation").get("message", "")

    def info(self) -> Dict[str, Any]:
        """设备信息汇总。"""
        import json
        raw = self.exec("ui_info").get("message", "{}")
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def launch_app(self, name: str) -> Dict[str, Any]:
        return self.exec("ui_launch_app", name=name)

    def package_name(self, name: str) -> str:
        return self.exec("ui_package_name", name=name).get("message", "")

    def app_name(self, package: str) -> str:
        return self.exec("ui_app_name", package=package).get("message", "")

    def open_app_setting(self, package: str) -> Dict[str, Any]:
        return self.exec("ui_open_app_setting", package=package)

    def set_brightness(self, value: int) -> Dict[str, Any]:
        return self.exec("ui_set_brightness", value=value)

    def get_brightness(self) -> int:
        try:
            return int(self.exec("ui_get_brightness").get("message", "-1"))
        except (ValueError, TypeError):
            return -1

    def get_mem(self) -> Dict[str, Any]:
        import json
        raw = self.exec("ui_get_mem").get("message", "{}")
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def is_charging(self) -> bool:
        return self.exec("ui_is_charging").get("message", "false") == "true"

    # ------------------------------------------------------------------
    # 找色 / 传感器
    # ------------------------------------------------------------------

    def find_color(self, color: str, threshold: int = 16, algorithm: str = "difference") -> Optional[Dict[str, int]]:
        """找色，返回 {"x":..,"y":..} 或 None。"""
        import json
        r = self.exec("ui_find_color", color=color, threshold=threshold, algorithm=algorithm)
        if not r.get("success"):
            return None
        try:
            return json.loads(r.get("message", "{}"))
        except Exception:
            return None

    def find_multi_colors(self, first_color: str, points: List[int], threshold: int = 16,
                          algorithm: str = "difference") -> Optional[Dict[str, int]]:
        import json
        r = self.exec("ui_find_multi_colors", first_color=first_color, points=json.dumps(points),
                      threshold=threshold, algorithm=algorithm)
        if not r.get("success"):
            return None
        try:
            return json.loads(r.get("message", "{}"))
        except Exception:
            return None

    def sensors(self, name: str) -> Dict[str, Any]:
        """读取传感器。"""
        import json
        raw = self.exec("ui_sensors", name=name).get("message", "{}")
        try:
            return json.loads(raw)
        except Exception:
            return {}

    # ------------------------------------------------------------------
    # 文件 / 媒体
    # ------------------------------------------------------------------

    def file_read(self, path: str) -> str:
        return self.exec("ui_file_read", path=path).get("message", "")

    def file_write(self, path: str, content: str) -> Dict[str, Any]:
        return self.exec("ui_file_write", path=path, content=content)

    def file_append(self, path: str, content: str) -> Dict[str, Any]:
        return self.exec("ui_file_append", path=path, content=content)

    def file_exists(self, path: str) -> bool:
        return self.exec("ui_file_exists", path=path).get("message", "false") == "true"

    def file_list(self, path: str = ".") -> List[Dict[str, Any]]:
        return self._parse_nodes(self.exec("ui_file_list", path=path).get("message", "[]"))

    def file_copy(self, src: str, dst: str) -> Dict[str, Any]:
        return self.exec("ui_file_copy", src=src, dst=dst)

    def file_move(self, src: str, dst: str) -> Dict[str, Any]:
        return self.exec("ui_file_move", src=src, dst=dst)

    def file_remove(self, path: str) -> Dict[str, Any]:
        return self.exec("ui_file_remove", path=path)

    def media_seek(self, position: int) -> Dict[str, Any]:
        return self.exec("ui_media_seek", position=position)

    def media_volume(self, volume: int) -> Dict[str, Any]:
        return self.exec("ui_media_volume", volume=volume)

    def media_position(self) -> int:
        try:
            return int(self.exec("ui_media_position").get("message", "-1"))
        except (ValueError, TypeError):
            return -1

    def media_duration(self) -> int:
        try:
            return int(self.exec("ui_media_duration").get("message", "-1"))
        except (ValueError, TypeError):
            return -1

    def media_state(self) -> str:
        return self.exec("ui_media_state").get("message", "")

    # ------------------------------------------------------------------
    # 通知 / 剪贴板
    # ------------------------------------------------------------------

    def notifications(self, package: str = "") -> List[Dict[str, Any]]:
        """当前通知列表。"""
        body: Dict[str, Any] = {}
        if package:
            body["package"] = package
        return self._parse_nodes(self.exec("ui_notifications", **body).get("message", "[]"))

    def has_notification(self, by: str = "text", value: str = "") -> bool:
        return self.exec("ui_has_notification", by=by, value=value).get("message", "false") == "true"

    def notification_reply(self, key: str, text: str) -> Dict[str, Any]:
        return self.exec("ui_notification_reply", key=key, text=text)

    def notification_click(self, key: str) -> Dict[str, Any]:
        return self.exec("ui_notification_click", key=key)

    def notification_dismiss(self, key: str) -> Dict[str, Any]:
        return self.exec("ui_notification_dismiss", key=key)

    def clipboard(self, text: str = "") -> str:
        """读/写剪贴板（text 有值则写，否则读）。"""
        body: Dict[str, Any] = {}
        if text:
            body["text"] = text
        return self.exec("ui_clipboard", **body).get("message", "")

    # ------------------------------------------------------------------
    # 截图 / 状态
    # ------------------------------------------------------------------

    def screenshot(self, save_to: Optional[str] = None) -> bytes:
        """截屏，返回 JPEG 字节。save_to 非空则同时落盘。"""
        r = self._session.get(f"{self.base}/screenshot", params=self._auth(), timeout=self.timeout)
        if r.status_code != 200:
            raise RpcError(f"截屏失败 HTTP {r.status_code}", "SCREENSHOT_FAILED", retryable=True)
        if save_to:
            with open(save_to, "wb") as f:
                f.write(r.content)
        return r.content

    def screenshot_base64(self) -> str:
        """截屏并返回 base64（便于嵌入或传输）。"""
        return base64.b64encode(self.screenshot()).decode("ascii")

    def a11y_ready(self) -> bool:
        """无障碍服务是否就绪。"""
        data = self._get("/status")
        raw = data.get("data", "{}")
        import json
        try:
            return json.loads(raw).get("accessibility", False)
        except Exception:
            return False

    # ------------------------------------------------------------------
    # 解析
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_nodes(raw: str) -> List[Dict[str, Any]]:
        import json
        if not raw:
            return []
        # data 字段可能带「匹配 N 个控件：」前缀（ui_find 回执），剥掉取 JSON 数组
        idx = raw.find("[")
        if idx < 0:
            return []
        end = raw.rfind("]")
        if end <= idx:
            return []
        try:
            arr = json.loads(raw[idx : end + 1])
            return arr if isinstance(arr, list) else []
        except Exception:
            return []


__all__ = ["Bridge", "Selector", "RpcError", "to_selector"]
