"""领域模型：连接（Connection）与其状态机。

每个 WebSocket 连接在服务端经历：CONNECTING -> ACTIVE -> CLOSED。
详见 docs/server/design.md 第八节「连接状态机」。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Awaitable, Callable, Optional

# 发送消息回调签名：async def send(raw_json: str) -> None
SendHandler = Callable[[str], Awaitable[None]]


class ConnectionState(str, Enum):
    """连接状态。"""

    CONNECTING = "CONNECTING"  # 握手进行中，尚未拿到 device_id
    ACTIVE = "ACTIVE"          # 活跃，正常收发消息 + 心跳
    CLOSED = "CLOSED"          # 已关闭（主动关闭/读超时/被顶旧/客户端断开）


@dataclass
class Connection:
    """一条 WebSocket 连接的领域模型。

    注意：此模型只承载状态与元信息，**不直接持有** WebSocket 对象。
    WebSocket 的收发通过注入的 ``send`` 回调完成，避免领域模型与框架耦合，
    便于单元测试（测试时注入假回调）。
    """

    device_id: Optional[str] = None
    state: ConnectionState = ConnectionState.CONNECTING
    connected_at: float = field(default_factory=time.time)
    # 最后一次收到该连接任意消息的时间戳（用于服务端读超时判死）
    last_active_at: float = field(default_factory=time.time)
    # 被新连接顶掉的时间（用于诊断）
    replaced_at: Optional[float] = None
    # 客户端进程运行时长（秒），握手 hello 时上报，用于展示「APP 已运行 XX」
    uptime_seconds: Optional[float] = None
    # 发送回调（由端点层注入，用于服务端主动推送/顶旧通知）
    send_handler: Optional[SendHandler] = field(default=None, repr=False)

    def set_send_handler(self, handler: SendHandler) -> None:
        """注入发送回调。"""
        self.send_handler = handler

    async def send(self, raw_json: str) -> None:
        """向该连接发送消息（供服务端主动推送使用）。"""
        if self.send_handler is None:
            raise RuntimeError("send_handler 未注入")
        await self.send_handler(raw_json)

    def touch(self) -> None:
        """更新最后活跃时间（收到任意消息时调用）。"""
        self.last_active_at = time.time()

    def activate(self, device_id: str) -> None:
        """握手完成，登记设备标识，进入活跃状态。"""
        self.device_id = device_id
        self.state = ConnectionState.ACTIVE
        self.touch()

    def close(self, reason: str = "closed") -> None:
        """标记为已关闭。"""
        if self.state != ConnectionState.CLOSED:
            self.state = ConnectionState.CLOSED

    @property
    def is_active(self) -> bool:
        return self.state == ConnectionState.ACTIVE

    @property
    def idle_seconds(self) -> float:
        """距离最后一次活跃的秒数。"""
        return time.time() - self.last_active_at
