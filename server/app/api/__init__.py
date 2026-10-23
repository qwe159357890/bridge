"""API 路由层包（专门的路由目录）。

本包是后端的「路由层」，按功能拆分为多个路由模块，支持子路由包：

- ``ws.py``            WebSocket 长连接路由（握手/心跳/鉴权/读超时）
- ``v1/``              REST 接口 v1 子路由包（按功能拆分：devices / push / connections）
- ``router.py``        根路由聚合器，统一挂载 WebSocket + 各版本 REST 子路由

各路由模块只做「协议适配」（解析请求、调用核心层、返回响应），
业务逻辑在 ``app/core/`` 与 ``app/services/``，不在路由层写业务。
"""

from . import router, v1, ws

__all__ = ["router", "v1", "ws"]
