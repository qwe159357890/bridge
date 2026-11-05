"""设备极空 BMS 状态存储（内存实现）。

客户端在连接极空保护板并读取到状态后，经 WS 上行 ``message`` 消息
（event=jikong_state）上报；服务端在此按 device_id 存最新状态快照，
供管理侧查询（REST）与实时推送（admin 事件总线）。

设备断连时清除记录（ws.py 在 finally 里调用 clear），避免管理后台看到陈旧数据。

注意：进程内内存存储，服务端重启后全部归零（客户端下次状态变化会补报）。
"""

from __future__ import annotations

import threading
import time
from typing import Any


class JikongStore:
    """device_id -> 最新极空 BMS 状态快照（线程安全，单进程内存）。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # {device_id: {"status": {...}, "updated_at": float}}
        self._states: dict[str, dict[str, Any]] = {}

    def set(self, device_id: str, status: dict[str, Any]) -> bool:
        """记录设备极空状态快照。status 为空字典视为无效，返回 False。"""
        if not isinstance(status, dict) or not status:
            return False
        with self._lock:
            self._states[device_id] = {
                "status": status,
                "updated_at": time.time(),
            }
        return True

    def clear(self, device_id: str) -> None:
        """清除设备记录（断连时调用）。"""
        with self._lock:
            self._states.pop(device_id, None)

    def get(self, device_id: str) -> dict[str, Any]:
        """查询设备状态；无记录返回空状态。"""
        with self._lock:
            record = self._states.get(device_id)
        if record is None:
            return {"status": None, "updated_at": None}
        return dict(record)
