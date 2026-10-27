"""命令下发 REST 接口：服务端主动向指定设备下发命令，并管理命令回执。

给「管理侧」调用的 HTTP 接口：
- ``POST /api/commands`` 挑一台设备下发一条命令（如 upload_log）；
- ``GET /api/commands/{command_id}`` 查询某条命令的当前状态（含回执结果）；
- ``GET /api/commands`` 列出全部命令记录。

多客户端对应关系（关键）：
- 命令精确下发到**一个** ``device_id``（不在本接口做广播，广播用 push）。
- 每条命令带唯一 ``command_id``，客户端回执原样带回，管理侧据此关联。
- 回执由 [CommandStore] 持久化（限制最大条数），避免仅记日志而丢失。
"""

from __future__ import annotations

import logging
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.api.v1.admin import require_admin
from app.core.command_catalog import ALLOWED_COMMANDS, catalog_payload
from app.core.command_store import CommandStore
from app.core.connection_manager import ConnectionManager
from app.schemas.ws import (
    CommandData,
    CommandRequest,
    CommandResponse,
    Envelope,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/commands", tags=["commands"])

# 采集类命令（互斥组）：同一设备同一时刻只允许一条在途——摄像头/麦克风/投屏
# 都是独占硬件，并发下发必然一条成功一条失败（v0.1.46 录像裸 ISE 的诱因之一）。
CAPTURE_COMMANDS = {"screenshot", "record_screen", "take_photo", "record_video", "record_audio"}

# 互斥守卫窗口（秒）：pending 记录超过该时长视为僵尸（客户端崩溃回执丢失），
# 不再拦截——否则一次回执丢失会锁死设备 10 分钟内的所有采集命令。
PENDING_GUARD_WINDOW_SEC = 15 * 60


def _get_manager(request: Request) -> ConnectionManager:
    return request.app.state.connection_manager


def _get_store(request: Request) -> CommandStore:
    return request.app.state.command_store


@router.get("/meta", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def command_meta() -> dict:
    """返回命令目录（类别 + 命令 + 参数 schema），供管理后台动态渲染命令选择器。

    这是「可下发命令」的单一事实来源，管理后台据此构建分类树 + 卡片墙，
    无需前端写死命令清单。
    """
    return catalog_payload()


@router.post("", response_model=CommandResponse, status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def send_command(body: CommandRequest, request: Request) -> CommandResponse:
    """向指定设备下发一条命令。

    - 命令名必须在白名单内（ALLOWED_COMMANDS），否则 400。
    - 设备必须在线（有活跃连接），否则 404。
    - 下发成功后返回 command_id，供管理侧后续关联客户端回执。
    """
    if body.command not in ALLOWED_COMMANDS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"不支持的命令：{body.command}（支持：{', '.join(sorted(ALLOWED_COMMANDS))}）",
        )

    # 采集类互斥守卫：同设备已有采集命令在途（15 分钟窗口内的 pending）时拒绝，
    # 避免独占硬件（摄像头/麦克风/投屏）并发冲突；勾选「强制下发」可越过。
    if body.command in CAPTURE_COMMANDS and not body.force:
        store = _get_store(request)
        now = time.time()
        active = next(
            (
                r
                for r in store.list()  # 由新到旧，第一条命中即最新在途
                if r.device_id == body.device_id
                and r.status == "pending"
                and r.command in CAPTURE_COMMANDS
                and now - r.created_at <= PENDING_GUARD_WINDOW_SEC
            ),
            None,
        )
        if active is not None:
            elapsed = int(now - active.created_at)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"该设备正在执行采集命令「{active.command}」（已等待 {elapsed} 秒，未回执）。"
                    f"摄像头/麦克风/投屏为独占资源，并发下发会失败；"
                    f"请等回执后再下发，若确认设备无响应可勾选「强制下发」"
                ),
            )

    manager = _get_manager(request)
    conn = manager.get(body.device_id)
    if conn is None or not conn.is_active:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="设备不在线，无法下发命令",
        )

    command_id = uuid.uuid4().hex
    command_data = CommandData(
        command=body.command,
        command_id=command_id,
        params=body.params,
    )
    envelope = Envelope.make("command", command_data.model_dump())
    delivered = await manager.send_to_device(body.device_id, envelope.to_json())

    if not delivered:
        # send_to_device 返回 False 说明发送失败（连接刚好断开）
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="命令下发失败（设备连接已断开）",
        )

    # 登记命令（pending），等待客户端回执更新
    _get_store(request).create(command_id, body.device_id, body.command, body.params)

    logger.info("向 device %s 下发命令 %s（command_id=%s）", body.device_id, body.command, command_id)
    return CommandResponse(
        command_id=command_id,
        device_id=body.device_id,
        command=body.command,
        delivered=True,
    )


@router.get("", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def list_commands(request: Request) -> list[dict]:
    """列出全部命令记录（由旧到新）。"""
    return [r.to_dict() for r in _get_store(request).list()]


@router.delete("", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def clear_commands(
    request: Request,
    device_id: str | None = Query(default=None, min_length=1),
) -> dict:
    """清空命令历史（管理后台「清空历史」按钮，带二次确认）。

    :param device_id: 只清该设备的记录；缺省清空全部（存储上限 200 条，防堆积）。
    """
    deleted = _get_store(request).clear(device_id)
    return {"deleted": deleted, "device_id": device_id}


@router.get("/{command_id}", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def get_command(command_id: str, request: Request) -> dict:
    """查询某条命令的当前状态（含回执结果）。"""
    record = _get_store(request).get(command_id)
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="命令不存在",
        )
    return record.to_dict()
