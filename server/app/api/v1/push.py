"""消息推送 REST 接口：服务端主动向设备下发消息。

给业务侧调用的 HTTP 接口，将消息推送到指定设备的活跃 WebSocket 连接。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.api.v1.admin import require_admin
from app.core.connection_manager import ConnectionManager
from app.schemas.ws import Envelope, PushRequest, PushResponse

router = APIRouter(tags=["push"])


def _get_manager(request: Request) -> ConnectionManager:
    return request.app.state.connection_manager


@router.post("/push", response_model=PushResponse, dependencies=[Depends(require_admin)])
async def push(body: PushRequest, request: Request) -> PushResponse:
    """向一个或多个设备推送消息（服务端主动下发）。

    推送给指定 device 的活跃 WebSocket 连接，消息类型为 ``message``。
    """
    manager = _get_manager(request)
    delivered = 0
    failed: list[str] = []

    for device_id in body.device_ids:
        envelope = Envelope.make("message", data=body.payload)
        ok = await manager.send_to_device(device_id, envelope.to_json())
        if ok:
            delivered += 1
        else:
            failed.append(device_id)

    return PushResponse(total=len(body.device_ids), delivered=delivered, failed=failed)
