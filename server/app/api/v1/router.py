"""API v1 版本根路由。

统一 ``/api`` 前缀，聚合 v1 下各子功能路由（devices / push / connections / logs / commands / music）。
未来新增 v2 时，可平行创建 ``app/api/v2/`` 包，并在 ``app/api/router.py`` 挂载。
"""

from __future__ import annotations

from fastapi import APIRouter

from app.api.v1 import admin, commands, connections, devices, logs, music, push

router = APIRouter(prefix="/api")

router.include_router(admin.router)
router.include_router(devices.router)
router.include_router(push.router)
router.include_router(connections.router)
router.include_router(logs.router)
router.include_router(commands.router)
router.include_router(music.router)

__all__ = ["router"]
