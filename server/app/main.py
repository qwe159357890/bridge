"""FastAPI 应用入口。

- 创建应用工厂，挂载 WebSocket 路由与 REST 管理路由。
- 初始化单例连接管理器、设备注册表、配置，注入 ``app.state``。
- 注入「旧连接被顶掉/服务端踢下线」回调（发 close 通知后关闭底层 socket）。
"""

from __future__ import annotations

import logging
import time

from fastapi import FastAPI, WebSocket

from app.api.router import api_router
from app.config import get_settings
from app.core.admin_auth import AdminAuth
from app.core.admin_event_bus import AdminEventBus
from app.core.command_store import CommandStore
from app.core.connection_manager import ConnectionManager
from app.core.device_registry import DeviceRegistry
from app.core.log_store import LogStore
from app.models.connection import Connection
from app.schemas.ws import CloseData, Envelope

_STARTED_AT = time.time()


def _build_replace_handler():
    """构建「旧连接被顶掉 / 服务端踢下线」回调。

    通过 ``Connection`` 注入的 ``send`` 回调发送 close 通知，再关闭底层 socket。
    """
    async def _on_replace(conn: Connection, reason: str) -> None:
        try:
            close_data = CloseData(reason=reason, message="新连接已接管")
            await conn.send(Envelope.make("close", close_data.model_dump()).to_json())
        except Exception:
            pass  # 旧连接可能已不可写，忽略
        try:
            # 底层 socket 关闭：通过发送回调无法真正关闭 socket，这里用注入的底层引用
            ws: WebSocket | None = getattr(conn, "_ws", None)
            if ws is not None:
                await ws.close(code=4000, reason=reason)
        except Exception:
            pass

    return _on_replace


def create_app() -> FastAPI:
    """应用工厂：创建并配置 FastAPI 实例。"""
    settings = get_settings()

    logging.basicConfig(
        level=getattr(logging, settings.logging.level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    app = FastAPI(title="Bridge Server", version="0.1.0")

    manager = ConnectionManager(max_connections=settings.connection.max_connections)
    manager.set_replace_handler(_build_replace_handler())

    registry = DeviceRegistry(token_ttl=settings.auth.token_ttl)

    log_store = LogStore()

    command_store = CommandStore()

    admin_auth = AdminAuth(
        username=settings.admin.username,
        password=settings.admin.password,
        token_ttl=settings.admin.token_ttl,
    )

    admin_event_bus = AdminEventBus()

    # 注入 app.state，供端点使用
    app.state.settings = settings
    app.state.connection_manager = manager
    app.state.device_registry = registry
    app.state.log_store = log_store
    app.state.command_store = command_store
    app.state.admin_auth = admin_auth
    app.state.admin_event_bus = admin_event_bus

    # 挂载全部路由（WebSocket + REST v1），由 api_router 统一聚合
    app.include_router(api_router)

    @app.get("/health")
    async def health() -> dict:
        """健康检查 + 运行统计。"""
        return {
            "status": "ok",
            "active_connections": manager.active_count,
            "registered_devices": registry.count(),
            "uptime_seconds": round(time.time() - _STARTED_AT, 2),
        }

    return app


app = create_app()
