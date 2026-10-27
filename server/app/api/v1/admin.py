"""管理后台鉴权接口：管理员登录，以及供其他管理接口复用的鉴权依赖。

给「管理后台」调用的 HTTP 接口：
- ``POST /api/admin/login`` 管理员账号密码登录，返回 admin token。
- ``WS  /api/admin/ws`` 管理侧实时事件通道（WebSocket），用于命令回执等实时推送，
  同时支持管理侧主动下发指令（双向交互）。
- 其余管理接口通过 ``require_admin`` 依赖校验 ``Authorization: Bearer <token>``。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Request, WebSocket, WebSocketDisconnect, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.admin_auth import AdminAuth
from app.schemas.admin import AdminLoginRequest, AdminLoginResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/admin", tags=["admin"])

# HTTP Bearer 认证头解析器（不自动报错，由 require_admin 统一处理）
_bearer = HTTPBearer(auto_error=False)


def _get_admin_auth(request: Request) -> AdminAuth:
    return request.app.state.admin_auth


def require_admin(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> None:
    """校验管理接口的 admin token。校验失败抛 401。

    用法：在需要保护的管理接口上添加 ``dependencies=[Depends(require_admin)]``。
    """
    admin_auth: AdminAuth = request.app.state.admin_auth
    token = credentials.credentials if credentials else None
    if not admin_auth.verify(token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="未授权：请先登录管理后台",
            headers={"WWW-Authenticate": "Bearer"},
        )


@router.post("/login", response_model=AdminLoginResponse)
async def admin_login(body: AdminLoginRequest, request: Request) -> AdminLoginResponse:
    """管理员登录，返回 admin token。"""
    admin_auth: AdminAuth = request.app.state.admin_auth
    token = admin_auth.login(body.username, body.password)
    if token is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="账号或密码错误",
        )
    return AdminLoginResponse(
        token=token,
        token_type="bearer",
        expires_in=request.app.state.settings.admin.token_ttl,
    )


@router.websocket("/ws")
async def admin_ws(ws: WebSocket) -> None:
    """管理侧实时事件通道（WebSocket）。

    鉴权：在握手前校验 token。WebSocket 原生支持自定义请求头，故优先从
    ``Authorization: Bearer <token>`` 读取；同时兼容 ``?token=`` query 参数
    （二者满足其一即可），方便浏览器端 WebSocket 无法自定义 header 时降级。

    能力：
    - 服务端 → 管理端：实时推送 ``command_result`` 等事件（JSON 文本帧）。
    - 管理端 → 服务端：未来扩展双向交互（接收指令）。
    """
    admin_auth: AdminAuth = ws.app.state.admin_auth

    # 鉴权：Authorization header 或 ?token= query 参数
    header_token: str | None = None
    auth_header = ws.headers.get("authorization", "")
    if auth_header.lower().startswith("bearer "):
        header_token = auth_header[7:].strip()
    query_token = ws.query_params.get("token")

    if not (admin_auth.verify(query_token) or admin_auth.verify(header_token)):
        # 未握手时直接 close，浏览器端表现为连接被拒绝
        await ws.close(code=4401, reason="unauthorized")
        return

    # 鉴权通过后再 accept 握手，此后才可收发消息
    await ws.accept()

    bus = ws.app.state.admin_event_bus
    await bus.connect(ws)
    try:
        while True:
            # 目前仅监听管理端消息（预留双向交互）；即使无消息也保持连接。
            # 收到消息仅记录，未来在此接入指令处理。
            try:
                raw = await ws.receive_text()
                logger.debug("管理端消息：%s", raw)
            except WebSocketDisconnect:
                break
    finally:
        await bus.disconnect(ws)


@router.websocket("/stream/{device_id}")
async def admin_stream_ws(ws: WebSocket, device_id: str) -> None:
    """管理侧媒体流专用通道（流式录像/录音实时播放，二进制帧）。

    数据源：客户端流式分片经 StreamHub 投递（服务端已解 base64，
    这里转发的是裸 MPEG-2 TS（视频）/ AAC ADTS（音频）二进制帧）。

    消费方：
    - 视频：mpegts.js 以 ``url: ws://…/api/admin/stream/{id}`` 当直播流直接播放；
    - 音频：管理后台 Web Audio 逐段解码实时出声。

    鉴权与 /admin/ws 一致（Authorization header 或 ?token= query）。
    无流时本连接静默保持（客户端只在流式采集期间推分片）。
    """
    admin_auth: AdminAuth = ws.app.state.admin_auth

    header_token: str | None = None
    auth_header = ws.headers.get("authorization", "")
    if auth_header.lower().startswith("bearer "):
        header_token = auth_header[7:].strip()
    query_token = ws.query_params.get("token")

    if not (admin_auth.verify(query_token) or admin_auth.verify(header_token)):
        await ws.close(code=4401, reason="unauthorized")
        return

    await ws.accept()
    hub = ws.app.state.stream_hub
    # kind 查询参数：video/screen（FLV）→ 取对应流的对齐器，中途加入/
    # 重建的订阅者从「流头 + 最近关键帧」无缝接入（2026-10-27 根治
    # Invalid PrevTagSize 冻结循环）；audio（ADTS 帧自同步）无需对齐。
    kind = ws.query_params.get("kind") or ""
    aligner = ws.app.state.flv_aligners.get((device_id, kind)) if kind else None
    queue = await hub.subscribe(
        device_id,
        join_provider=(aligner.join_bytes if aligner is not None else None),
    )
    joined = "关键帧接入" if aligner is not None else "直连"
    logger.info("管理侧订阅设备 %s 媒体流（kind=%s，%s）", device_id, kind or "?", joined)
    try:
        while True:
            frame = await queue.get()
            await ws.send_bytes(frame)
    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("管理侧流通道异常（device=%s）", device_id)
    finally:
        await hub.unsubscribe(device_id, queue)
        logger.info("管理侧取消订阅设备 %s 媒体流", device_id)


