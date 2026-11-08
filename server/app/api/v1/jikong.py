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
    """极空控制命令请求。action 决定命令名与参数。

    v0.2.102：新增 speak / set_theme / set_speak（管理后台↔手机同步类，params 透传）。
    """

    action: str = Field(..., description="mos / balance / write_param / speak / set_theme / set_speak")
    charge: bool | None = Field(None, description="action=mos 时：是否含充电 MOS")
    discharge: bool | None = Field(None, description="action=mos 时：是否含放电 MOS")
    on: bool | None = Field(None, description="action=mos/balance 时：开/关")
    param_id: int | None = Field(None, description="action=write_param 时：参数 id")
    value: int | None = Field(None, description="action=write_param 时：参数值")
    # v0.2.102：同步类命令参数透传（speak 无参；set_theme: mode；set_speak:
    # muted/speak_enabled/speak_interval_min/speak_low_soc/speak_warn_repeat_min）
    params: dict | None = Field(None, description="action=speak/set_theme/set_speak 时：透传参数")


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
    if body.action == "mos":
        params: dict = {
            "charge": body.charge,
            "discharge": body.discharge,
            "on": body.on,
        }
    elif body.action == "balance":
        params = {"on": body.on}
    elif body.action == "write_param":
        params = {"param_id": body.param_id, "value": body.value}
    elif body.action in ("speak", "set_theme", "set_speak"):
        # v0.2.102：同步类命令参数原样透传（客户端执行器自行校验字段与取值范围）
        params = body.params or {}
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"未知 action: {body.action}",
        )

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


# ----------------------------------------------------------------------
# 里程台账查询（管理侧只读，v0.2.86，后台「极空页 1:1 复刻」统计 Tab 用）
# ----------------------------------------------------------------------


def _compute_segments(records_asc: list[dict]) -> list[dict | None]:
    """按设备端同款公式算段电耗（ records 须为 record_ts 升序、不含墓碑）。

    段 i（i>=1）：Δ容量 × 平均电压 ÷ Δ里程 → Wh/km；
    Δ容量<=0（换电池/BMS 重置）或 Δ里程<=0 → None（无效段，不进曲线）。
    返回与输入等长的列表；第 0 项恒为 None（基准）。
    """
    segs: list[dict | None] = [None]
    for i in range(1, len(records_asc)):
        prev, cur = records_asc[i - 1], records_asc[i]
        d_odo = round(cur["odo_km"] - prev["odo_km"], 3)
        d_ah = round(cur["cycle_ah"] - prev["cycle_ah"], 3)
        if d_odo <= 0 or d_ah <= 0:
            segs.append(None)
            continue
        v0 = prev["volt"] if prev["volt"] else cur["volt"]
        v1 = cur["volt"] if cur["volt"] else prev["volt"]
        if not v0 or not v1:
            segs.append(None)
            continue
        avg_v = (v0 + v1) / 2.0
        segs.append({
            "d_odo_km": d_odo,
            "d_ah": d_ah,
            "avg_volt": round(avg_v, 2),
            "kwh": round(d_ah * avg_v / 1000.0, 3),
            "wh_km": round(d_ah * avg_v / d_odo, 1),
        })
    return segs


@router.get("/{device_id}/odometer", dependencies=[Depends(require_admin)])
def get_jikong_odometer(device_id: str, request: Request, limit: int = 500) -> dict:
    """某设备里程台账（管理侧只读）：记录 + 段电耗 + 汇总（与手机统计页同口径）。"""
    store = request.app.state.odometer_store
    try:
        rows = store.list(device_id, limit=min(max(limit, 1), 2000))
    except Exception as e:  # MySQL 不可用统一 503
        logger.error("里程台账查询失败：device=%s %s", device_id, e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="里程台账存储暂不可用",
        ) from e
    live = [r for r in rows if not r["deleted"]]
    live.sort(key=lambda r: r["ts_ms"])  # 升序算段（基准在前）
    segs = _compute_segments(live)
    records = []
    for rec, seg in zip(live, segs):
        records.append({**rec, "seg": seg})
    records.reverse()  # 倒序输出（最新在前，与手机台账一致）
    # 汇总（基准→最新）
    if len(live) >= 2:
        total_km = round(live[-1]["odo_km"] - live[0]["odo_km"], 1)
        valid = [s for s in segs if s]
        total_kwh = round(sum(s["kwh"] for s in valid), 2)
        avg_wh_km = round(total_kwh * 1000.0 / total_km, 1) if total_km > 0 else None
    else:
        total_km, total_kwh, avg_wh_km = 0.0, 0.0, None
    return {
        "device_id": device_id,
        "records": records,
        "summary": {
            "total_km": total_km,
            "total_kwh": total_kwh,
            "avg_wh_km": avg_wh_km,
            "count": len(live),
        },
    }
