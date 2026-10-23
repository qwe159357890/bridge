"""管理侧事件总线：命令回执等事件实时推送给管理后台。

基于 WebSocket 实现实时推送（替代原 SSE）：
- ``connect`` 登记一条管理侧 WebSocket 连接，返回连接对象。
- ``disconnect`` 移除连接。
- ``publish`` 将事件广播给所有已连接的管理后台。

事件格式（JSON 字符串，直接作为 WS 文本帧下发）：
    {"type": "command_result", "data": {...}}

当前仅用于命令回执实时通知；未来可扩展连接状态变更等事件，也便于
管理侧主动向服务端发送指令（双向交互）。
"""

from __future__ import annotations

import asyncio
import json
import logging

logger = logging.getLogger(__name__)


class AdminEventBus:
    """管理侧事件总线（内存实现，单进程内广播）。

    维护一组已鉴权的管理后台 WebSocket 连接，广播事件给所有连接。
    """

    def __init__(self) -> None:
        self._connections: set = set()
        self._lock = asyncio.Lock()

    async def connect(self, ws) -> None:
        """登记一条管理侧 WebSocket 连接。"""
        async with self._lock:
            self._connections.add(ws)

    async def disconnect(self, ws) -> None:
        """移除一条管理侧 WebSocket 连接。"""
        async with self._lock:
            self._connections.discard(ws)

    @property
    def active_count(self) -> int:
        """当前活跃的管理侧连接数（供健康检查等展示）。"""
        return len(self._connections)

    async def publish(self, event_type: str, data: dict) -> None:
        """广播一个事件给所有已连接的管理后台。"""
        payload = json.dumps({"type": event_type, "data": data}, ensure_ascii=False)
        async with self._lock:
            connections = list(self._connections)
        for ws in connections:
            try:
                await ws.send_text(payload)
            except Exception:
                logger.exception("向管理侧连接推送事件失败（忽略）")
