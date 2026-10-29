"""日志上传/读取 REST 接口。

借鉴 aurelay 的「诊断日志上传/读取」语义：
- 客户端把日志文本 POST 到服务端，供排查问题；
- 管理侧可读取/列出/清除设备的日志。

依赖注入：单例 log_store 来自 ``request.app.state``。
"""

from __future__ import annotations

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
    """接收客户端上传的日志。"""
    store = _get_store(request)
    store.add(
        device_id=body.device_id,
        content=body.content,
        device_name=body.device_name or "",
        app_version=body.app_version or "",
    )
    return LogUploadResponse(code=0, message="上传成功", device_id=body.device_id)


@router.get("/{device_id}", response_model=LogListResponse, dependencies=[Depends(require_admin)])
async def get_device_logs(
    device_id: str,
    request: Request,
    offset: int = 0,
    limit: int = 20,
) -> LogListResponse:
    """读取某设备的日志（分页，由旧到新）。

    ``limit=0`` 表示不分页、返回全部（兼容旧调用方）；默认 20 条/页。
    """
    store = _get_store(request)
    offset = max(0, offset)
    limit = max(0, limit)
    total = store.count(device_id)
    entries = store.list(device_id, offset=offset, limit=limit)
    logs = [
        LogEntryResponse(
            device_id=e.device_id,
            device_name=e.device_name,
            app_version=e.app_version,
            content=e.content,
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
    """列出有日志的设备摘要（含昵称 + 日志条数 + 最新时间 + 在线状态）。"""
    store = _get_store(request)
    summaries = store.device_summaries()

    # 合并在线状态
    manager = request.app.state.connection_manager
    for item in summaries:
        item["online"] = manager.get(item["device_id"]) is not None

    return {
        "devices": summaries,
        "total_logs": store.count(),
    }


@router.delete("/{device_id}", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def clear_device_logs(device_id: str, request: Request) -> dict:
    """清除某设备的全部日志。"""
    store = _get_store(request)
    removed = store.clear(device_id)
    if removed == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备无日志")
    return {"status": "cleared", "device_id": device_id, "removed": removed}
