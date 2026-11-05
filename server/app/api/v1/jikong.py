"""极空 BMS REST 接口：管理侧查询设备极空状态 + 下发远程控制命令。

- ``GET  /api/jikong/{device_id}``          查询设备最新极空 BMS 状态快照
- ``POST /api/jikong/{device_id}/control``  下发控制命令（MOS/均衡/写参数）

控制命令本质是向设备下发一条 jikong_* 命令（经既有的命令下发链路），
由客户端 CommandModule 执行器转成 BLE 写帧发给保护板。
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.api.v1.admin import require_admin
from app.schemas.ws import CommandData, Envelope

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/jikong", tags=["jikong"])


@router.get("/{device_id}", dependencies=[Depends(require_admin)])
async def get_jikong_state(device_id: str, request: Request) -> dict:
    """查询设备最新极空 BMS 状态快照（管理侧）。"""
    store = request.app.state.jikong_store
    record = store.get(device_id)
    return {"device_id": device_id, **record}


class JikongControlRequest(BaseModel):
    """极空控制命令请求。action 决定命令名与参数。"""

    action: str = Field(..., description="mos / balance / write_param")
    charge: bool | None = Field(None, description="action=mos 时：是否含充电 MOS")
    discharge: bool | None = Field(None, description="action=mos 时：是否含放电 MOS")
    on: bool | None = Field(None, description="action=mos/balance 时：开/关")
    param_id: int | None = Field(None, description="action=write_param 时：参数 id")
    value: int | None = Field(None, description="action=write_param 时：参数值")


@router.post("/{device_id}/control", dependencies=[Depends(require_admin)])
async def jikong_control(device_id: str, body: JikongControlRequest, request: Request) -> dict:
    """向设备下发极空控制命令（经命令链路转发到客户端执行器）。"""
    manager = request.app.state.connection_manager
    conn = manager.get(device_id)
    if conn is None or not conn.is_active:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="设备不在线，无法下发极空控制命令",
        )

    command = f"jikong_{body.action}"
    command_id = uuid.uuid4().hex
    params: dict = {}
    if body.action == "mos":
        params = {
            "charge": body.charge,
            "discharge": body.discharge,
            "on": body.on,
        }
    elif body.action == "balance":
        params = {"on": body.on}
    elif body.action == "write_param":
        params = {"param_id": body.param_id, "value": body.value}

    command_data = CommandData(command=command, command_id=command_id, params=params)
    envelope = Envelope.make("command", command_data.model_dump())
    delivered = await manager.send_to_device(device_id, envelope.to_json())
    if not delivered:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="极空控制命令下发失败（设备连接已断开）",
        )

    request.app.state.command_store.create(command_id, device_id, command, params)
    logger.info("向 device %s 下发极空控制命令 %s（command_id=%s）", device_id, command, command_id)
    return {"command_id": command_id, "device_id": device_id, "command": command, "delivered": True}
