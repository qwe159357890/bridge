"""极空 BMS REST 接口：管理侧查询设备极空状态 + 下发远程控制命令 + 里程台账同步。

- ``GET  /api/jikong/{device_id}``          查询设备最新极空 BMS 状态快照
- ``POST /api/jikong/{device_id}/control``  下发控制命令（MOS/均衡/写参数）
- ``POST /api/jikong/odometer/sync``        设备端里程台账批量同步（设备头鉴权，
  MySQL 幂等 upsert，v0.2.85；删除走 deleted=1 墓碑）

控制命令本质是向设备下发一条 jikong_* 命令（经既有的命令下发链路），
由客户端 CommandModule 执行器转成 BLE 写帧发给保护板。
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.api.v1.admin import require_admin
from app.core.odometer_store import OdometerRecord
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


# ----------------------------------------------------------------------
# 里程台账同步（设备端上报，v0.2.85）
# ----------------------------------------------------------------------


class OdometerRecordIn(BaseModel):
    """一条台账记录（deleted=True 时为删除墓碑，仅 record_id 有业务意义）。"""

    record_id: str = Field(min_length=1, max_length=64, description="设备端唯一 id（录制后不变）")
    ts: int = Field(default=0, ge=0, description="录入时刻 epoch 毫秒")
    odo_km: float = Field(default=0, ge=0, description="三轮车里程表读数 km")
    cycle_ah: float = Field(default=0, ge=0, description="录入时保护板循环容量 Ah")
    volt: float | None = Field(None, description="录入时总压 V")
    deleted: bool = Field(default=False, description="True=删除墓碑")


class OdometerSyncRequest(BaseModel):
    """批量同步请求体（含墓碑；服务端按 (device_id, record_id) 幂等 upsert）。"""

    records: list[OdometerRecordIn] = Field(default_factory=list, max_length=200)


class OdometerSyncResponse(BaseModel):
    received: int
    inserted: int
    updated: int


def _check_device_auth(request: Request, device_id: str | None, token: str | None) -> None:
    """设备鉴权（auth.enabled 时生效，与 /api/locations 同一套注册表校验）。"""
    settings = request.app.state.settings
    if not settings.auth.enabled:
        return
    registry = request.app.state.device_registry
    if not device_id or not token or not registry.authenticate(device_id, token):
        logger.warning("里程台账同步鉴权失败：device=%s", device_id or "unknown")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="设备鉴权失败")


@router.post("/odometer/sync", response_model=OdometerSyncResponse)
def sync_odometer(
    body: OdometerSyncRequest,
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> OdometerSyncResponse:
    """设备端里程台账批量同步（本地为准：服务端/MySQL 故障时设备端保留待同步标记重试）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    store = request.app.state.odometer_store
    records = [
        OdometerRecord(
            record_id=r.record_id,
            ts_ms=r.ts,
            odo_km=r.odo_km,
            cycle_ah=r.cycle_ah,
            volt=r.volt,
            deleted=r.deleted,
        )
        for r in body.records
    ]
    try:
        result = store.sync_batch(x_device_id or "", records)
    except Exception as e:  # MySQL 连接/执行异常统一 503，设备端保留待同步标记
        logger.error("里程台账存储不可用：%s", e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="里程台账存储暂不可用，请稍后重试",
        ) from e
    return OdometerSyncResponse(**result)
