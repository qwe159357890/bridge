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
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status

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
