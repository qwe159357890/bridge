"""API 根路由聚合器。

统一挂载所有路由：
- WebSocket 路由（``app/api/ws.py``）
- REST 接口 v1（``app/api/v1/router.py``，前缀 ``/api``）

``app/main.py`` 只需 ``app.include_router(api_router)`` 一行即可挂载全部路由，
新增子路由时在对应包内登记即可，无需改动 main.py。
"""

from __future__ import annotations

from fastapi import APIRouter

from app.api import ws
from app.api.v1.router import router as v1_router

api_router = APIRouter()

# WebSocket 长连接路由（无版本前缀）
api_router.include_router(ws.router)

# REST 接口 v1（/api/*）
api_router.include_router(v1_router)

__all__ = ["api_router"]
