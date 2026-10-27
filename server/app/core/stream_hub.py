"""媒体流转发中心（流式录像/录音的实时分片通道）。

数据通路（借鉴 autox 客户端「边录边推」+ 管理侧专用 WS 播放）：

    客户端 MediaCaptureService 每 500ms 读产物文件增量 → base64 →
    WS ``message`` 通道（event=media_stream）→ 本 hub → 二进制帧 →
    管理后台专用流 WS（/api/admin/stream/{device_id}）→
    - 视频：mpegts.js 直接当直播流播放（MPEG-2 TS 原生支持）；
    - 音频：Web Audio 逐段解码实时出声（AAC ADTS）。

设计要点：
- 按 device_id 维护订阅者队列；无订阅者时分片直接丢弃（**不做缓存回放**，
  流式语义就是实时观看，历史完整文件由命令回执后的媒体上传兜底）。
- base64 解码在服务端完成：管理侧拿到的是裸 TS/ADTS 二进制，
  mpegts.js 的 WebSocket loader 要求二进制帧。
"""

from __future__ import annotations

import asyncio
import base64
import logging

logger = logging.getLogger(__name__)

# 单订阅者队列深度上限：管理侧消费不过来时丢新帧（实时流宁丢勿积压）
_MAX_QUEUE = 512

# 流头缓存上限：每个流开头这么多字节会缓存，新订阅者先补发再接实时
# （2026-10-27 加：TS 的 PAT/PMT/SPS/PPS 与 FLV 的 header/script/seq header 只在
#   文件开头出现一次，播放器中途加入或重建后没有流头就永远无法初始化解码器）
_HEAD_CACHE_LIMIT = 512 * 1024


class StreamHub:
    """设备媒体流 → 管理侧订阅者的内存转发中心（单进程）。"""

    def __init__(self) -> None:
        # device_id -> set[asyncio.Queue[bytes]]
        self._subscribers: dict[str, set[asyncio.Queue]] = {}
        # device_id -> 流开头字节缓存（订阅先补发；流结束清空）
        self._head: dict[str, bytearray] = {}
        self._lock = asyncio.Lock()

    async def subscribe(self, device_id: str) -> asyncio.Queue:
        """登记一个订阅者，返回其专属队列（分片 binary 帧投递到这里）。

        若该设备的流已在进行中，先把缓存的流头投进队列（播放器初始化依赖）。
        """
        q: asyncio.Queue = asyncio.Queue(maxsize=_MAX_QUEUE)
        async with self._lock:
            self._subscribers.setdefault(device_id, set()).add(q)
            head = self._head.get(device_id)
            if head:
                q.put_nowait(bytes(head))
        return q

    def clear_head(self, device_id: str) -> None:
        """流结束：清空该设备的流头缓存（下一个流重新累积）。"""
        self._head.pop(device_id, None)

    async def unsubscribe(self, device_id: str, q: asyncio.Queue) -> None:
        """移除订阅者。"""
        async with self._lock:
            subs = self._subscribers.get(device_id)
            if subs is not None:
                subs.discard(q)
                if not subs:
                    self._subscribers.pop(device_id, None)

    def has_subscribers(self, device_id: str) -> bool:
        """该设备是否有管理侧订阅者（服务端日志用）。"""
        return bool(self._subscribers.get(device_id))

    def subscriber_count(self, device_id: str) -> int:
        """该设备当前订阅者数量（日志观测用；空起始帧也报真实值）。"""
        return len(self._subscribers.get(device_id, ()))

    async def push_b64(self, device_id: str, data_b64: str) -> int:
        """接收客户端分片（base64），解码后投递给该设备全部订阅者。

        @return 投递到的订阅者数量
        """
        try:
            raw = base64.b64decode(data_b64) if data_b64 else b""
        except Exception:
            logger.warning("device %s 流分片 base64 解码失败（丢弃）", device_id)
            return 0
        if not raw:
            return 0
        async with self._lock:
            subs = list(self._subscribers.get(device_id, ()))
            # 累积流头缓存（未满上限时；供新订阅者/播放器重建补发）
            head = self._head.get(device_id)
            if head is None:
                head = bytearray()
                self._head[device_id] = head
            if len(head) < _HEAD_CACHE_LIMIT:
                head.extend(raw[: _HEAD_CACHE_LIMIT - len(head)])
        for q in subs:
            try:
                q.put_nowait(raw)
            except asyncio.QueueFull:
                # 队列满：丢弃整帧（实时流语义，恢复靠后续帧的 TS/ADTS 自同步）
                logger.debug("device %s 流队列满，丢帧（实时语义）", device_id)
            except Exception:
                logger.exception("流分片投递失败（忽略）")
        return len(subs)

    def push_b64_sync(self, device_id: str, data_b64: str) -> None:
        """同步入口（无事件循环上下文的调用方备用）；当前 ws.py 走 async 版。"""
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self.push_b64(device_id, data_b64))
        except RuntimeError:
            pass
