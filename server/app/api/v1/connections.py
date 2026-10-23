"""连接管理 REST 接口：查询活跃连接、强制断开指定设备。

给「管理侧」调用的 HTTP 接口，用于在线连接监控与运维。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.api.v1.admin import require_admin
from app.core.connection_manager import ConnectionManager

router = APIRouter(tags=["connections"])


def _get_manager(request: Request) -> ConnectionManager:
    return request.app.state.connection_manager


@router.get("/connections", dependencies=[Depends(require_admin)])
async def list_connections(request: Request) -> dict:
    """列出当前所有活跃连接。"""
    manager = _get_manager(request)
    return {
        "active_connections": manager.active_count,
        "connections": manager.list_connections(),
    }


@router.post("/devices/{device_id}/disconnect", status_code=status.HTTP_202_ACCEPTED, dependencies=[Depends(require_admin)])
async def disconnect_device(device_id: str, request: Request) -> dict:
    """强制断开指定设备的活跃连接（服务端主动踢下线）。"""
    manager = _get_manager(request)
    conn = manager.get(device_id)
    if conn is None or not conn.is_active:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备无活跃连接")

    # 发 close 通知后关闭（复用顶旧回调逻辑）
    await manager.notify_replaced(conn, "server_kick")
    await manager.unregister(device_id, conn)
    return {"status": "disconnected", "device_id": device_id}
