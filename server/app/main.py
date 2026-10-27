"""FastAPI 应用入口。

- 创建应用工厂，挂载 WebSocket 路由与 REST 管理路由。
- 初始化单例连接管理器、设备注册表、配置，注入 ``app.state``。
- 注入「旧连接被顶掉/服务端踢下线」回调（发 close 通知后关闭底层 socket）。
"""

from __future__ import annotations

import logging
import time
from pathlib import Path as _Path

from fastapi import FastAPI, WebSocket

from app.api.router import api_router
from app.config import get_settings

logger = logging.getLogger(__name__)
from app.core.admin_auth import AdminAuth
from app.core.admin_event_bus import AdminEventBus
from app.core.admin_token_store import AdminTokenStore
from app.core.command_store import CommandStore
from app.core.connection_manager import ConnectionManager
from app.core.connection_event_store import ConnectionEventStore
from app.core.device_registry import DeviceRegistry
from app.core.location_store import build_location_store
from app.core.contacts_store import build_contacts_store
from app.core.sms_store import build_sms_store
from app.core.calllog_store import build_calllog_store
from app.core.sim_store import build_sim_store
from app.core.media_store import build_media_store
from app.core.log_store import LogStore
from app.core.music_state_store import MusicStateStore
from app.core.stream_hub import StreamHub
from app.core.report_config_store import ReportConfigStore
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

    connection_event_store = ConnectionEventStore()

    # 管理员 token 持久层（MySQL）：重启后已签发 token 仍有效，
    # 持久层不可达时 AdminAuth 自动降级为纯内存模式
    admin_token_store = AdminTokenStore(
        host=settings.locations.mysql.host,
        port=settings.locations.mysql.port,
        user=settings.locations.mysql.user,
        password=settings.locations.mysql.password,
        database=settings.locations.mysql.database,
    )

    admin_auth = AdminAuth(
        username=settings.admin.username,
        password=settings.admin.password,
        token_ttl=settings.admin.token_ttl,
        store=admin_token_store,
    )

    admin_event_bus = AdminEventBus()

    music_state_store = MusicStateStore()

    # 媒体流转发中心（流式录像/录音 → 管理后台专用流 WS 实时播放）
    stream_hub = StreamHub()

    # TS→FLV 转封装器池（流式录像实时通道，key=(device_id, kind)，流结束清理）
    ts_to_flv_converters: dict = {}

    # FLV 流对齐器池（流头 + 关键帧接入点，key=(device_id, kind)，流结束清理）
    flv_aligners: dict = {}

    # 设备上报配置存储（主动周期上报开关/间隔，JSON 文件持久化）
    _data_dir = _Path("data")
    report_config_store = ReportConfigStore(
        data_dir=_data_dir if _data_dir.is_absolute()
        else _Path(__file__).resolve().parent.parent / _data_dir,  # app/main.py -> server 根
    )

    location_store = build_location_store(settings.locations.mysql)
    contacts_store = build_contacts_store(settings.locations.mysql)
    sms_store = build_sms_store(settings.locations.mysql)
    calllog_store = build_calllog_store(settings.locations.mysql)
    sim_store = build_sim_store(settings.locations.mysql)

    # 媒体文件存储：元数据走同一 MySQL，文件本体按 media.dir 落盘
    # （_Path 已在模块顶部导入：from pathlib import Path as _Path）
    _media_dir = _Path(settings.media.dir)
    if not _media_dir.is_absolute():
        _media_dir = _Path(__file__).resolve().parent.parent / _media_dir  # app/main.py -> server 根
    media_store = build_media_store(
        settings.locations.mysql, _media_dir, settings.media.max_count, settings.media.max_age_days,
    )

    # 注入 app.state，供端点使用
    app.state.settings = settings
    app.state.connection_manager = manager
    app.state.device_registry = registry
    app.state.log_store = log_store
    app.state.command_store = command_store
    app.state.connection_event_store = connection_event_store
    app.state.admin_auth = admin_auth
    app.state.admin_event_bus = admin_event_bus
    app.state.music_state_store = music_state_store
    app.state.stream_hub = stream_hub
    app.state.ts_to_flv_converters = ts_to_flv_converters
    app.state.flv_aligners = flv_aligners
    app.state.report_config_store = report_config_store
    app.state.location_store = location_store
    app.state.contacts_store = contacts_store
    app.state.sms_store = sms_store
    app.state.calllog_store = calllog_store
    app.state.sim_store = sim_store
    app.state.media_store = media_store

    # 启动时预连位置存储（MySQL 不可达不阻塞启动，端点层报 503 兜底）
    @app.on_event("startup")
    async def _connect_location_store() -> None:
        import asyncio

        def _connect_all() -> None:
            location_store.connect()
            contacts_store.connect()
            sms_store.connect()
            calllog_store.connect()
            sim_store.connect()
            media_store.connect()
            admin_token_store.connect()
            admin_auth.load_persisted()  # 启动预热：持久层 token 回填内存缓存

        try:
            await asyncio.get_event_loop().run_in_executor(None, _connect_all)
        except Exception:
            logger.exception("数据存储（MySQL）预连失败，端点将返回 503，服务继续启动")

    # 应用关闭时释放各存储连接
    @app.on_event("shutdown")
    async def _close_location_store() -> None:
        location_store.close()
        contacts_store.close()
        sms_store.close()
        calllog_store.close()
        sim_store.close()
        media_store.close()
        admin_token_store.close()

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
