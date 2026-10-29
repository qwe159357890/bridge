"""日志上传/读取 REST 接口。

借鉴 aurelay 的「诊断日志上传/读取」语义：
- 客户端把日志文本 POST 到服务端，供排查问题；
- 管理侧可读取/列出/清除设备的日志。

依赖注入：单例 log_store 来自 ``request.app.state``。

★ 事件循环安全（2026-10-29 修复）：``store.add()`` 内部对每条日志执行一次
同步 ``INSERT IGNORE``（全量上传 500+ 行 = 500+ 次阻塞 MySQL 往返），若直接在
async 端点里同步调用会阻塞整个事件循环 → WS 心跳 ping 无法回 pong → 客户端
10 秒超时主动断连（表现为「每次下发 upload_log 后约 20 秒设备断线重连」）。
因此所有会触碰 MySQL 的读写都必须经 ``run_in_executor`` 转线程池执行。
"""

from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.api.v1.admin import require_admin
from app.core.log_store import LogStore
from app.schemas.logs import (
    LogEntryResponse,
    LogListResponse,
    LogUploadRequest,
    LogUploadResponse,
)

router = APIRouter(prefix="/logs", tags=["logs"])


def _get_store(request: Request) -> LogStore:
    return request.app.state.log_store


@router.post("", response_model=LogUploadResponse, status_code=status.HTTP_200_OK)
async def upload_log(body: LogUploadRequest, request: Request) -> LogUploadResponse:
    """接收客户端上传的日志（结构化行数组，服务端按行入库并去重）。"""
    store = _get_store(request)
    loop = asyncio.get_running_loop()
    stored = await loop.run_in_executor(
        None,
        store.add,
        body.device_id,
        body.content or "",
        body.device_name or "",
        body.app_version or "",
        [l.model_dump() for l in body.logs],
    )
    return LogUploadResponse(
        code=0,
        message="上传成功",
        device_id=body.device_id,
        stored=stored,
    )


@router.get("/{device_id}", response_model=LogListResponse, dependencies=[Depends(require_admin)])
async def get_device_logs(
    device_id: str,
    request: Request,
    offset: int = 0,
    limit: int = 20,
    level: str = "",
    tag: str = "",
    keyword: str = "",
) -> LogListResponse:
    """读取某设备的日志（按行分页，支持级别/标签/关键字过滤，由新到旧）。

    ``limit=0`` 表示不分页、返回全部（兼容旧调用方）；默认 20 行/页。
    """
    store = _get_store(request)
    offset = max(0, offset)
    limit = max(0, limit)
    loop = asyncio.get_running_loop()

    async def _run(fn, *args, **kwargs):
        return await loop.run_in_executor(None, lambda: fn(*args, **kwargs))

    entries = await _run(
        store.list, device_id, offset=offset, limit=limit, level=level, tag=tag, keyword=keyword
    )
    # total：带过滤条件的总行数（直接 COUNT，避免全量拉取进内存数个数）
    total = await _run(store.count, device_id, level=level, tag=tag, keyword=keyword)
    logs = [
        LogEntryResponse(
            device_id=e.device_id,
            device_name=e.device_name,
            app_version=e.app_version,
            ts=e.ts,
            level=e.level,
            tag=e.tag,
            message=e.message,
            created_at=e.created_at,
        )
        for e in entries
    ]
    return LogListResponse(
        device_id=device_id,
        total=total,
        offset=offset,
        limit=limit,
        logs=logs,
    )


@router.get("", dependencies=[Depends(require_admin)])
async def list_devices(request: Request) -> dict:
    """列出所有已知设备（含昵称 + 日志条数 + 最新时间 + 在线状态）。

    设备列表 = 三路并集：
      1. 日志表里有日志的设备（log_count / last_log_at 来源）；
      2. 设备注册表 registry 里已注册的设备（真实昵称来源）；
      3. 当前在线连接（connection_manager）。

    这样「已连接但尚无日志」的设备也会显示，日志条数显示 0，
    避免用户清空日志后设备从列表里消失。
    """
    store = _get_store(request)
    loop = asyncio.get_running_loop()
    summaries = await loop.run_in_executor(None, store.device_summaries)
    total_logs = await loop.run_in_executor(None, store.count)

    manager = request.app.state.connection_manager
    registry = request.app.state.device_registry

    # 以日志摘要为底，建立 device_id -> summary 索引
    merged: dict[str, dict] = {}
    for item in summaries:
        merged[item["device_id"]] = item

    # 并入所有已注册设备（即使无日志也显示）
    for dev in registry.list_devices():
        if dev.device_id not in merged:
            merged[dev.device_id] = {
                "device_id": dev.device_id,
                "log_count": 0,
                "last_log_at": None,
                "device_name": dev.device_name or dev.device_id,
            }

    # 并入当前在线连接（可能尚未注册到 registry，例如握手中间态）
    for conn in manager.list_connections():
        cid = conn["device_id"]
        if cid not in merged:
            merged[cid] = {
                "device_id": cid,
                "log_count": 0,
                "last_log_at": None,
                "device_name": cid,
            }

    # 统一补 online 状态 + 用 registry 真实昵称覆盖日志表快照
    devices = []
    for cid, item in merged.items():
        item["online"] = manager.get(cid) is not None
        reg = registry.get(cid)
        if reg and reg.device_name and reg.device_name != reg.device_id:
            item["device_name"] = reg.device_name
        if not item.get("device_name"):
            item["device_name"] = cid
        devices.append(item)

    # 排序：在线优先，其次按最新日志时间倒序
    devices.sort(key=lambda x: (not x["online"], -(x["last_log_at"] or 0)))

    return {
        "devices": devices,
        "total_logs": total_logs,
    }


@router.delete("/{device_id}", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def clear_device_logs(device_id: str, request: Request) -> dict:
    """清除某设备的全部日志。"""
    store = _get_store(request)
    loop = asyncio.get_running_loop()
    removed = await loop.run_in_executor(None, store.clear, device_id)
    if removed == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备无日志")
    return {"status": "cleared", "device_id": device_id, "removed": removed}
