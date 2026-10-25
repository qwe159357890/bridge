"""设备音乐播放状态存储（内存实现）。

客户端在播放状态变化时（播/暂停/恢复/停止/自然播完/出错/重连）通过 WS 上行
``message`` 消息上报 music_state，服务端在此按 device_id 存最新状态，
供管理侧查询（GET /api/devices/{id}/music-state）与实时推送（admin 事件总线）。

状态语义：
- ``playing``  播放中（source=曲目）
- ``paused``   暂停中（source=曲目）
- ``idle``     空闲（客户端播放器无实例；进程被杀后客户端重启补报 idle）
- ``unknown``  未知（设备从未上报 / 已断连——断连时 ws.py 会清除该设备的记录）

注意：进程内内存存储，服务端重启后全部归零（客户端重连时会补报当前状态）。
"""

from __future__ import annotations

import threading
import time
from typing import Any

# 客户端允许上报的合法状态（其余忽略，防脏数据）
VALID_STATES = {"playing", "paused", "idle"}


class MusicStateStore:
    """device_id -> 最新音乐播放状态（线程安全，单进程内存）。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # {device_id: {"state": str, "source": str|None, "updated_at": float}}
        self._states: dict[str, dict[str, Any]] = {}

    def set(self, device_id: str, state: str, source: str | None = None) -> bool:
        """记录设备音乐状态。非法状态返回 False（调用方忽略即可）。"""
        if state not in VALID_STATES:
            return False
        with self._lock:
            self._states[device_id] = {
                "state": state,
                "source": source or None,
                "updated_at": time.time(),
            }
        return True

    def clear(self, device_id: str) -> None:
        """清除设备记录（断连时调用，之后 get 返回 unknown 语义）。"""
        with self._lock:
            self._states.pop(device_id, None)

    def get(self, device_id: str) -> dict[str, Any]:
        """查询设备状态；无记录返回 unknown。"""
        with self._lock:
            record = self._states.get(device_id)
        if record is None:
            return {"state": "unknown", "source": None, "updated_at": None}
        return dict(record)
