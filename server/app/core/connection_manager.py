"""连接管理器：维护 device_id -> 连接 的映射，保证唯一连接 + 顶旧连接。

核心业务规则（docs/server/design.md 第七节）：
   同一个 device_id，服务端全局只保留一条活跃连接；新连接顶掉旧连接。

并发安全：用 asyncio.Lock 保护「顶旧 + 登记」操作，保证原子性。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable, Optional

from app.models.connection import Connection

logger = logging.getLogger(__name__)

# 旧连接被顶掉时的关闭回调签名：async def on_replaced(connection: Connection, reason: str) -> None
CloseHandler = Callable[[Connection, str], Awaitable[None]]


class ConnectionManager:
    """管理全局连接表，维护唯一连接约束。"""

    def __init__(self, max_connections: int = 0) -> None:
        self._lock = asyncio.Lock()
        # device_id -> Connection（仅活跃连接在此登记）
        self._by_device: dict[str, Connection] = {}
        self._max_connections = max_connections
        # 旧连接被顶掉时的回调（由端点层注入，用于发 close 帧后关闭底层 socket）
        self._on_replace: Optional[CloseHandler] = None

    def set_replace_handler(self, handler: CloseHandler) -> None:
        """注入「旧连接被顶掉」的回调。"""
        self._on_replace = handler

    @property
    def active_count(self) -> int:
        return len(self._by_device)

    async def register(self, device_id: str, conn: Connection) -> Optional[Connection]:
        """登记一条新连接。若该 device_id 已有活跃连接，返回被顶掉的旧连接。

        返回旧连接（若有），由调用方负责关闭（发 close 通知 + 关底层 socket）。
        保证原子性：并发握手同一 device_id 时，最终只保留后登记的连接。
        """
        async with self._lock:
            # 连接数上限检查（0 表示不限）
            if self._max_connections > 0 and len(self._by_device) >= self._max_connections:
                raise ConnectionLimitError(self._max_connections)

            replaced: Optional[Connection] = None
            old = self._by_device.get(device_id)
            if old is not None and old.is_active:
                # 顶旧：从映射移除旧连接，标记被顶掉
                del self._by_device[device_id]
                old.replaced_at = __import__("time").time()
                old.close(reason="replaced")
                replaced = old

            conn.activate(device_id)
            self._by_device[device_id] = conn
            logger.info("device %s 已登记，当前活跃连接数=%d", device_id, self.active_count)
            return replaced

    async def unregister(self, device_id: str, conn: Connection) -> None:
        """移除连接。仅当映射中该 device_id 指向的正是 conn 时才移除（避免误删新连接）。"""
        async with self._lock:
            current = self._by_device.get(device_id)
            if current is conn:
                del self._by_device[device_id]
                conn.close(reason="closed")
                logger.info("device %s 已移除，剩余活跃连接数=%d", device_id, self.active_count)

    def get(self, device_id: str) -> Optional[Connection]:
        """查询某设备的活跃连接（非并发安全，仅用于只读场景）。"""
        return self._by_device.get(device_id)

    def list_connections(self) -> list[dict]:
        """列出所有活跃连接的基本信息（用于管理接口/监控）。"""
        result = []
        for device_id, conn in self._by_device.items():
            result.append({
                "device_id": device_id,
                "state": conn.state.value,
                "connected_at": conn.connected_at,
                "last_active_at": conn.last_active_at,
                "idle_seconds": round(conn.idle_seconds, 2),
            })
        return result

    async def send_to_device(self, device_id: str, raw_json: str) -> bool:
        """向指定设备的活跃连接发送消息。返回是否发送成功。"""
        conn = self._by_device.get(device_id)
        if conn is None or not conn.is_active:
            return False
        try:
            await conn.send(raw_json)
            return True
        except Exception:
            logger.exception("向 device %s 推送消息失败", device_id)
            return False

    async def broadcast(self, raw_json: str) -> int:
        """向所有活跃连接广播消息，返回成功发送的连接数。"""
        success = 0
        for device_id, conn in list(self._by_device.items()):
            if conn.is_active:
                try:
                    await conn.send(raw_json)
                    success += 1
                except Exception:
                    logger.exception("向 device %s 广播失败", device_id)
        return success

    async def notify_replaced(self, old: Connection, reason: str = "replaced") -> None:
        """通知旧连接它已被顶掉（调用注入的回调，发 close 帧 + 关闭 socket）。"""
        if self._on_replace is not None:
            try:
                await self._on_replace(old, reason)
            except Exception:
                logger.exception("顶旧连接回调执行失败（忽略）")


class ConnectionLimitError(Exception):
    """连接数达到上限。"""

    def __init__(self, limit: int) -> None:
        super().__init__(f"连接数已达上限 {limit}")
        self.limit = limit
