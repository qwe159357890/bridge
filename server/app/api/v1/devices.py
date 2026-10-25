"""设备相关 REST 接口：设备注册、设备列表、设备统计。

这些端点是给「业务侧/管理侧」调用的 HTTP 接口，与 WebSocket 长连接互补。
依赖注入：单例 registry / manager 来自 ``request.app.state``。

多客户端对应关系（关键）：
- ``device_id`` 是全局唯一键，所有「精确到设备」的操作（推送/命令/日志/踢下线）都以它定位。
- 昵称 ``device_name`` 仅作展示名，便于管理侧在设备列表中「认出」设备再下发命令，不参与唯一性判断。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request, status

from app.api.v1.admin import require_admin
from app.core.connection_manager import ConnectionManager
from app.core.device_registry import DeviceRegistry
from app.schemas.ws import (
    DeviceListItem,
    DeviceRegisterRequest,
    DeviceRegisterResponse,
)

router = APIRouter(prefix="/devices", tags=["devices"])


def _get_registry(request: Request) -> DeviceRegistry:
    return request.app.state.device_registry


def _get_manager(request: Request) -> ConnectionManager:
    return request.app.state.connection_manager


@router.post("/register", response_model=DeviceRegisterResponse, status_code=status.HTTP_201_CREATED)
async def register_device(body: DeviceRegisterRequest, request: Request) -> DeviceRegisterResponse:
    """注册设备，返回 device_id + token（客户端保存 token 用于后续连接鉴权）。"""
    registry = _get_registry(request)
    device = registry.register(body.device_id, device_name=body.device_name)
    return DeviceRegisterResponse(device_id=device.device_id, token=device.token)


@router.get("", response_model=list[DeviceListItem], dependencies=[Depends(require_admin)])
async def list_devices(request: Request) -> list[DeviceListItem]:
    """列出所有已注册设备（含昵称 + 在线状态），供管理侧挑设备下发命令/推送。"""
    registry = _get_registry(request)
    manager = _get_manager(request)
    result = []
    for device in registry.list_devices():
        result.append(
            DeviceListItem(
                device_id=device.device_id,
                device_name=device.device_name,
                online=manager.get(device.device_id) is not None,
                created_at=device.created_at,
            )
        )
    return result


@router.get("/{device_id}/music-state", dependencies=[Depends(require_admin)])
async def get_music_state(device_id: str, request: Request) -> dict:
    """查询设备最新音乐播放状态（管理侧，供命令下发页展示与按钮禁用）。

    返回 {device_id, state, source, updated_at}；state ∈ playing/paused/idle/unknown。
    unknown = 设备从未上报或当前离线（断连时 ws 层已清除记录）。
    """
    store = request.app.state.music_state_store
    record = store.get(device_id)
    return {"device_id": device_id, **record}


@router.get("/count", dependencies=[Depends(require_admin)])
async def device_count(request: Request) -> dict:
    """已注册设备总数。"""
    registry = _get_registry(request)
    return {"registered_devices": registry.count()}
