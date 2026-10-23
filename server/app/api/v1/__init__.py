"""API v1 子路由包。

将 REST 接口按功能拆分为多个子模块，各模块定义自己的 ``router``，
在此统一聚合。WebSocket 路由不在此包内（见 ``app/api/ws.py``）。

子功能路由模块：
- ``devices.py``      设备注册、设备统计
- ``push.py``         消息推送
- ``connections.py``  连接管理（查询/踢下线）
- ``logs.py``         日志上传/读取/清除
"""

from . import connections, devices, logs, push

__all__ = ["connections", "devices", "logs", "push"]
