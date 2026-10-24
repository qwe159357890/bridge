"""连接事件存储层：记录设备的上下线历史。

需求来源：管理后台要展示设备的上线下线时间轴（如「1 点上线 → 跑半小时 →
网络断掉线 → 2 点再上线 → 3 点下线」），需要把「上线登记」「下线回收」两个时机
各记一条事件。

存储模型：内存环形列表（保留最近 ``max_events`` 条，超限丢最旧）。
接口与存储解耦，后续可替换为文件/数据库/时序库。

事件字段：
- device_id：设备标识
- event：``online`` / ``offline``
- reason：下线原因（closed=主动断开 / read_timeout=读超时 / replaced=被顶替 /
  server_kick=服务端踢 / reconnect=客户端重连等），上线时通常为空字符串
- ts：时间戳
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ConnectionEvent:
    """一条上下线事件。"""

    device_id: str
    event: str  # "online" | "offline"
    reason: str = ""
    ts: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "event": self.event,
            "reason": self.reason,
            "ts": self.ts,
        }


class ConnectionEventStore:
    """连接事件存储（内存实现，接口可替换）。

    :param max_events: 最多保留的事件条数（0 = 不限）
    """

    def __init__(self, max_events: int = 500) -> None:
        self._events: list[ConnectionEvent] = []
        self._max_events = max_events

    def record(self, device_id: str, event: str, reason: str = "") -> ConnectionEvent:
        """记录一条事件，返回该事件。超限丢弃最旧。"""
        e = ConnectionEvent(device_id=device_id, event=event, reason=reason)
        self._events.append(e)
        if self._max_events > 0 and len(self._events) > self._max_events:
            del self._events[: len(self._events) - self._max_events]
        logger.info("连接事件 %s: device=%s reason=%s", event, device_id, reason or "-")
        return e

    def list(self, device_id: str | None = None, limit: int = 100) -> list[dict]:
        """列出事件（可按设备过滤），按时间倒序（最新在前）。

        :param device_id: 为空返回全部设备的事件
        :param limit: 最多返回条数
        """
        events = self._events
        if device_id is not None:
            events = [e for e in events if e.device_id == device_id]
        return [e.to_dict() for e in reversed(events[-limit:])]

    def count(self) -> int:
        return len(self._events)
