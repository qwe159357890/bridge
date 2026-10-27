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
from app.core.ts_to_flv import TsToFlv
from app.models.connection import Connection
from app.schemas.ws import CloseData, Envelope, HelloData

logger = logging.getLogger(__name__)

router = APIRouter()


async def _handle_music_state_message(app, device_id: str, data: dict) -> None:
    """处理客户端上报的音乐播放状态（WS ``message`` 通道，event=music_state）。

    存储最新状态并广播 ``music_state`` 事件给管理后台；非法状态忽略。
    独立成函数便于单测（绕开 TestClient 的 WS 会话兼容性问题）。
    """
    store = app.state.music_state_store
    state = data.get("state")
    source = data.get("source")
    if store.set(device_id, str(state) if state else "", source):
        logger.debug("device %s 音乐状态上报：%s %s", device_id, state, source)
        try:
            bus = app.state.admin_event_bus
            await bus.publish("music_state", {
                "device_id": device_id,
                "state": state,
                "source": source,
            })
        except Exception:
            logger.exception("发布音乐状态事件失败（忽略）")
    else:
        logger.debug("device %s 非法音乐状态：%s", device_id, state)


async def _handle_dialog_click(app, device_id: str, data: dict) -> None:
    """处理 show_dialog 的用户点击回传（WS ``message`` 通道，event=dialog_click）。

    向命令记录追加交互备注（后台命令历史「回执信息」列可见），并经 SSE
    推送 ``command_interaction`` 事件实时通知管理侧。
    """
    command_id = data.get("command_id")
    if not command_id:
        return
    try:
        note = "用户已点击弹窗确认"
        record = app.state.command_store.append_note(str(command_id), note)
        if record is None:
            return
        bus = app.state.admin_event_bus
        await bus.publish("command_interaction", {
            "command_id": command_id,
            "device_id": device_id,
            "note": note,
        })
        logger.info("device %s 弹窗点击回传：command=%s", device_id, command_id)
    except Exception:
        logger.exception("处理弹窗点击回传失败（忽略）")


async def _handle_command_progress(app, device_id: str, data: dict) -> None:
    """处理命令执行进度上报（WS ``message`` 通道，event=command_progress）。

    客户端把耗时命令拆两阶段上报：phase=executing（录制类按时长百分比，
    由 MediaCaptureService 上报）/ phase=uploading（按上传字节百分比，由
    CommandModule 上报）。只经 SSE 实时转发给管理侧，**不落库**——
    命令历史只保留最终回执。
    """
    command_id = data.get("command_id")
    phase = data.get("phase")
    if not command_id or phase not in ("executing", "uploading"):
        return
    try:
        bus = app.state.admin_event_bus
        await bus.publish("command_progress", {
            "command_id": str(command_id),
            "device_id": device_id,
            "phase": phase,
            "percent": data.get("percent"),
            "sent_bytes": data.get("sent_bytes"),
            "total_bytes": data.get("total_bytes"),
        })
    except Exception:
        logger.exception("转发命令进度事件失败（忽略）")


async def _handle_media_stream(app, device_id: str, data: dict) -> None:
    """处理客户端流式录像/录音分片（WS ``message`` 通道，event=media_stream）。

    数据分片（data 非空）→ 解 base64 后经 StreamHub 以二进制帧投递给
    管理后台专用流 WS（/api/admin/stream/{device_id}）的播放器；
    起始帧（带 info）/ 结束帧（is_end=true）额外广播 ``stream_status``
    事件给管理后台（轻量元数据，供命令页自动开/关播放卡片）。
    """
    kind = data.get("kind")
    if kind not in ("video", "audio", "screen"):
        return
    hub = app.state.stream_hub
    payload_b64 = data.get("data") or ""
    is_end = bool(data.get("is_end"))
    info = data.get("info")

    # 流式录像（kind=video）的设备产物是 MPEG-2 TS，Android MediaMuxer 的 TS
    # 时间戳有病态缺陷（PCR/boottime、dts 非单调）——mpegts.js 播放会「只出
    # 首帧后停滞」（2026-10-27 实测+浏览器复现根因）。服务端统一转封装 FLV
    # （时间戳归一化）再投递，播放端 type:'flv'。转换器按 (device, kind) 保持
    # 流式状态（跨分片的 PES 重组），流结束清理。
    # 注意：只有首帧带 info，后续分片 info=None——格式判定基于 (device,kind)
    # 池（首帧登记，之后按 key 命中），不能要求每片都带 info。
    converters: dict = app.state.ts_to_flv_converters
    conv_key = (device_id, kind)
    fmt = (info or {}).get("format", "")
    converter: TsToFlv | None = None
    if kind == "video" and not is_end:
        if fmt == "mpeg2ts":
            converter = converters.setdefault(conv_key, TsToFlv())
        elif fmt == "flv":
            converters.pop(conv_key, None)  # 设备原生 FLV，清残留转换器
        elif conv_key in converters:
            converter = converters[conv_key]

    payload_out = payload_b64
    if converter is not None and payload_b64:
        try:
            import base64 as _b64

            raw = _b64.b64decode(payload_b64)
            flv = converter.feed(raw)
            payload_out = _b64.b64encode(flv).decode() if flv else ""
        except Exception:
            logger.exception("TS→FLV 转封装失败（本片丢弃）")
            payload_out = ""
    try:
        delivered = await hub.push_b64(device_id, payload_out) if payload_out else 0
    except Exception:
        logger.exception("流分片转发失败（忽略）")
        delivered = 0
    # 观测日志：起始帧/结束帧/每 20 个数据帧记一条，便于定位「无实时画面」是
    # 设备没推分片还是后台没订阅（chunk_index=0 且 data 为空 = 起始帧）
    chunk_idx = data.get("chunk_index", -1)
    if info is not None:
        logger.info(
            "device %s 流开始 kind=%s 订阅者=%d 信息=%s", device_id, kind, delivered, info,
        )
    elif is_end:
        logger.info(
            "device %s 流结束 kind=%s 总字节=%s", device_id, kind, data.get("total_bytes"),
        )
        # 流结束：清流头缓存（下一个流重新累积，避免旧流头污染新流）
        hub.clear_head(device_id)
        app.state.ts_to_flv_converters.pop((device_id, kind), None)
    elif isinstance(chunk_idx, int) and chunk_idx % 20 == 1:
        logger.info(
            "device %s 流分片 kind=%s idx=%s 本帧字节=%s 订阅者=%d",
            device_id, kind, chunk_idx, data.get("bytes"), delivered,
        )

    if info or is_end:
        try:
            bus = app.state.admin_event_bus
            await bus.publish("stream_status", {
                "device_id": device_id,
                "kind": kind,
                "command_id": data.get("command_id"),
                "is_end": is_end,
                "total_bytes": data.get("total_bytes"),
                "info": info,
            })
        except Exception:
            logger.exception("发布流状态事件失败（忽略）")


async def _clear_music_state(app, device_id: str) -> None:
    """断连时清除设备音乐状态（此后查询返回 unknown），并通知管理侧。"""
    try:
        app.state.music_state_store.clear(device_id)
        bus = app.state.admin_event_bus
        await bus.publish("music_state", {
            "device_id": device_id,
            "state": "unknown",
            "source": None,
        })
    except Exception:
        logger.exception("清除音乐状态失败（忽略）")


async def _ws_endpoint(ws: WebSocket, manager, settings) -> None:
    """连接处理主流程（提取为独立协程，便于注入 manager 进行测试）。"""
    await ws.accept()
    conn = Connection()
    conn._ws = ws  # 供顶旧回调真正关闭底层 socket
    # 记录客户端 IP：adb 助手据此把「后台选中的设备」与「adb 已连接的手机」匹配
    conn.ip = ws.client.host if ws.client else None

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
    # 2.3 记录客户端本机全部 IP：adb 匹配「任一命中」+ 后台展示交叉确认
    if hello.ips:
        conn.ips = [ip for ip in hello.ips if ip][:16]

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

    # 3.2 握手后下发该设备的上报配置（客户端离线期间错过的 config_update 在此兜底）
    try:
        cfg = ws.app.state.report_config_store.get(device_id)
        await manager.send_to_device(
            device_id,
            Envelope.make("message", {"event": "config_update", **cfg}).to_json(),
        )
    except Exception:
        logger.exception("握手后下发上报配置失败（忽略）")

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
                # 业务消息：音乐播放状态 / 弹窗点击回传 / 命令进度上报
                data = envelope.data or {}
                if data.get("event") == "music_state":
                    await _handle_music_state_message(ws.app, device_id, data)
                elif data.get("event") == "dialog_click":
                    await _handle_dialog_click(ws.app, device_id, data)
                elif data.get("event") == "command_progress":
                    await _handle_command_progress(ws.app, device_id, data)
                elif data.get("event") == "media_stream":
                    await _handle_media_stream(ws.app, device_id, data)
                else:
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
                        "command": data.get("command", ""),
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
            # 5.1.1 设备断连：若有流式采集在推流，通知管理侧结束（播放器收尾）
            try:
                await ws.app.state.admin_event_bus.publish("stream_status", {
                    "device_id": device_id,
                    "kind": "video",
                    "command_id": None,
                    "is_end": True,
                    "total_bytes": None,
                    "info": {"reason": "device_offline"},
                })
            except Exception:
                logger.exception("发布设备下线流结束事件失败（忽略）")
            # 5.2 音乐状态随断连失效：清除记录（查询端点此后返回 unknown）并通知管理侧
            await _clear_music_state(ws.app, device_id)
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
