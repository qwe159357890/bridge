"""WebSocket 端点：/ws 路由，承载连接建立、鉴权、心跳响应、读超时回收、消息推送。

职责：
- 握手：客户端首条消息为 ``hello``（携带 device_id + 可选 token），完成握手并登记连接。
- 鉴权：若启用鉴权，校验 token；否则接受任意连接（便于联调）。
- 心跳：客户端发 ``ping``，服务端回 ``pong``。
- 读超时：``asyncio.wait_for`` 超过 server_read_timeout 未收到任何消息则判死回收。
- 顶旧：同一 device_id 的新连接顶掉旧连接（先发 close 通知再关 socket）。
- 推送：业务侧经 REST 接口调用 manager.send_to_device，由注入的 send 回调下发。
"""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from app.config import get_settings
from app.core.connection_manager import ConnectionLimitError
from app.models.connection import Connection
from app.schemas.ws import CloseData, Envelope, HelloData

logger = logging.getLogger(__name__)

router = APIRouter()


async def _ws_endpoint(ws: WebSocket, manager, settings) -> None:
    """连接处理主流程（提取为独立协程，便于注入 manager 进行测试）。"""
    await ws.accept()
    conn = Connection()
    conn._ws = ws  # 供顶旧回调真正关闭底层 socket

    # 注入发送回调：让核心层能经 Connection 主动下发消息（推送/顶旧通知）
    async def _send(raw_json: str) -> None:
        await ws.send_text(raw_json)

    conn.set_send_handler(_send)

    # 1. 握手：等待首条 hello 消息（带超时，防止握手期悬挂占用资源）
    try:
        raw = await asyncio.wait_for(
            ws.receive_text(),
            timeout=settings.heartbeat.server_read_timeout,
        )
    except asyncio.TimeoutError:
        logger.warning("握手超时，关闭连接")
        await ws.close(code=4001, reason="handshake timeout")
        return

    try:
        envelope = Envelope.model_validate_json(raw)
        if envelope.type != "hello" or envelope.data is None:
            raise ValueError("首条消息必须是 hello")
        hello = HelloData.model_validate(envelope.data)
        device_id = hello.device_id
    except (ValidationError, ValueError) as e:
        logger.warning("握手消息非法：%s", e)
        await ws.close(code=4002, reason="invalid hello")
        return

    # 2. 鉴权（可选）
    if settings.auth.enabled:
        registry = ws.app.state.device_registry
        token = hello.token
        if token is None or not registry.authenticate(device_id, token):
            logger.warning("device %s 鉴权失败", device_id)
            await ws.close(code=4004, reason="unauthorized")
            return

    # 2.1 握手时上报昵称（若有）：更新注册表昵称，供管理侧设备列表展示
    if hello.device_name:
        ws.app.state.device_registry.update_name(device_id, hello.device_name)

    # 2.2 记录客户端进程运行时长（供管理后台展示「APP 已运行 XX」）
    conn.uptime_seconds = hello.uptime_seconds

    # 3. 登记连接（顶旧）
    try:
        replaced = await manager.register(device_id, conn)
    except ConnectionLimitError as e:
        logger.warning("连接数超限，拒绝：%s", e)
        await ws.close(code=4003, reason="connection limit reached")
        return

    # 3.1 记录上线事件
    try:
        ws.app.state.connection_event_store.record(device_id, "online", "")
    except Exception:
        logger.exception("记录上线事件失败（忽略）")

    # 4. 若顶掉了旧连接，先通知旧连接（发 close 帧 + 关 socket）
    if replaced is not None:
        await manager.notify_replaced(replaced, "replaced")

    logger.info("device %s 连接建立成功", device_id)

    # 离线原因追踪：主循环里区分各种断开原因，供上下线记录用
    offline_reason = "closed"

    try:
        # 5. 主循环：读消息 + 心跳响应 + 读超时回收
        while True:
            try:
                raw = await asyncio.wait_for(
                    ws.receive_text(),
                    timeout=settings.heartbeat.server_read_timeout,
                )
            except asyncio.TimeoutError:
                logger.info("device %s 读超时（%.0fs 无消息），判定死亡，回收",
                            device_id, conn.idle_seconds)
                offline_reason = "read_timeout"
                break

            conn.touch()
            try:
                envelope = Envelope.model_validate_json(raw)
            except ValidationError:
                logger.debug("device %s 收到非法消息，忽略", device_id)
                continue

            if envelope.type == "ping":
                # 心跳携带保活时长（秒），实时刷新连接的「保活时长」，避免握手快照冻结
                if envelope.data is not None and "uptime_seconds" in envelope.data:
                    try:
                        conn.uptime_seconds = float(envelope.data["uptime_seconds"])
                    except (TypeError, ValueError):
                        pass
                await ws.send_text(Envelope.make("pong").to_json())
            elif envelope.type == "pong":
                # 客户端主导模式下服务端一般不主动 ping，收到 pong 仅刷新活跃时间
                pass
            elif envelope.type == "close":
                logger.info("device %s 主动请求关闭", device_id)
                offline_reason = "closed"
                break
            elif envelope.type == "message":
                # 业务消息：预留，当前仅记录（后续可接入 services 层处理）
                logger.debug("device %s 业务消息：%s", device_id, envelope.data)
            elif envelope.type == "command_result":
                # 客户端命令执行回执：写入命令存储（供管理侧查询），并发布事件实时通知管理侧
                data = envelope.data or {}
                command_id = data.get("command_id")
                success = data.get("success", False)
                message = data.get("message", "")
                store = ws.app.state.command_store
                store.set_result(command_id, bool(success), message or "")
                logger.info("device %s 命令回执：%s", device_id, envelope.data)
                # 实时推送给管理后台（SSE）
                try:
                    bus = ws.app.state.admin_event_bus
                    await bus.publish("command_result", {
                        "command_id": command_id,
                        "device_id": device_id,
                        "success": bool(success),
                        "message": message or "",
                    })
                except Exception:
                    logger.exception("发布命令回执事件失败（忽略）")
            # 其他类型忽略

    except WebSocketDisconnect:
        logger.info("device %s 断开连接", device_id)
        offline_reason = "disconnected"
    except Exception:
        logger.exception("device %s 连接处理异常", device_id)
        offline_reason = "error"
    finally:
        # 5.1 记录下线事件（仅当本连接确实登记过 device_id 时）
        if conn.device_id:
            try:
                ws.app.state.connection_event_store.record(device_id, "offline", offline_reason)
            except Exception:
                logger.exception("记录下线事件失败（忽略）")
        # 6. 清理：从映射表移除（仅当映射仍指向本连接时）
        await manager.unregister(device_id, conn)


@router.websocket("/ws")
async def websocket_endpoint(ws: WebSocket) -> None:
    """WebSocket 端点入口。

    依赖注入：单例 manager / registry / settings 由 ``app.state`` 提供（在 main.py 中初始化）。
    """
    manager = ws.app.state.connection_manager
    settings = ws.app.state.settings
    await _ws_endpoint(ws, manager, settings)
