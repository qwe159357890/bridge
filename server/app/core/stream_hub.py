"""媒体流转发中心（流式录像/录音/投屏的实时分片通道）。

数据通路（借鉴 autox 客户端「边录边推」+ 管理侧专用 WS 播放）：

    客户端 MediaCaptureService 每 500ms 读产物文件增量 → base64 →
    WS ``message`` 通道（event=media_stream）→ 本 hub → 二进制帧 →
    管理后台专用流 WS（/api/admin/stream/{device_id}）→
    - 视频/投屏：mpegts.js 直接当直播流播放（服务端统一 FLV）；
    - 音频：Web Audio 逐段解码实时出声（AAC ADTS）。

设计要点：
- 按 device_id 维护订阅者队列；订阅者带 ``armed`` 状态：未武装前
  （FLV 对齐器尚未给出关键帧接入点）丢弃实时分片，直到能从合法
  接入点开始投递——中途加入/重建的播放器拿到的流**永远字节连续、
  tag 对齐、从关键帧开始**（2026-10-27 根治 Invalid PrevTagSize 冻结）。
- 接入点由 core/flv_aligner.py 提供（prefix + 关键帧 tail）。
- 实时流宁丢勿积压：队列满丢新帧（TS/ADTS/FLV 关键帧周期自愈）。
"""

from __future__ import annotations

import asyncio
import base64
import logging
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

# 单订阅者队列深度上限：管理侧消费不过来时丢新帧（实时流宁丢勿积压）
_MAX_QUEUE = 512


@dataclass(eq=False)  # eq=False 保留默认 __hash__（订阅者要放进 set）
class _Subscriber:
    queue: asyncio.Queue
    armed: bool = field(default=False)


class StreamHub:
    """设备媒体流 → 管理侧订阅者的内存转发中心（单进程）。"""

    def __init__(self) -> None:
        # device_id -> set[_Subscriber]
        self._subscribers: dict[str, set[_Subscriber]] = {}
        self._lock = asyncio.Lock()

    async def subscribe(
        self,
        device_id: str,
        join_provider=None,
    ) -> asyncio.Queue:
        """登记订阅者，返回其专属队列（二进制帧投递到这里）。

        @param join_provider 无参函数（在同一把锁内调用），返回接入字节
        （如 FlvAligner.join_bytes()）；非 None 时投进队列并武装订阅者。
        """
        sub = _Subscriber(queue=asyncio.Queue(maxsize=_MAX_QUEUE))
        async with self._lock:
            self._subscribers.setdefault(device_id, set()).add(sub)
            if join_provider is not None:
                join = join_provider()
                logger.info(
                    "hub 订阅 %s：接入点=%s", device_id,
                    "无（等关键帧）" if not join else f"{len(join)} 字节",
                )
                if join:
                    sub.queue.put_nowait(join)
                    sub.armed = True
        return sub.queue

    async def unsubscribe(self, device_id: str, queue: asyncio.Queue) -> None:
        """移除订阅者。"""
        async with self._lock:
            subs = self._subscribers.get(device_id)
            if subs is not None:
                for sub in list(subs):
                    if sub.queue is queue:
                        subs.discard(sub)
                if not subs:
                    self._subscribers.pop(device_id, None)

    def subscriber_count(self, device_id: str) -> int:
        """该设备当前订阅者数量（日志观测用）。"""
        return len(self._subscribers.get(device_id, ()))

    async def push(
        self,
        device_id: str,
        chunk: bytes,
        aligner=None,
    ) -> int:
        """投递一段实时流字节给该设备全部订阅者。

        与 subscribe 共用一把锁：对齐器喂入、接入点快照、订阅者登记
        三者串行化，保证任意订阅者的字节流**无断层、无重复**。

        @param aligner FLV 对齐器（kind=video/screen 时传入；audio 传 None）
        @return 投递到的订阅者数量（含本次武装的）
        """
        async with self._lock:
            if aligner is not None and chunk:
                aligner.feed(chunk)
            subs = list(self._subscribers.get(device_id, ()))
            delivered = 0
            for sub in subs:
                data: bytes | None = None
                if not sub.armed:
                    if aligner is None:
                        # 音频等无对齐器流：立即武装，从当前字节开始
                        # （ADTS 帧自同步，无需关键帧对齐）
                        sub.armed = True
                        data = chunk
                    else:
                        join = aligner.join_bytes()
                        if join is None:
                            continue  # 尚未到关键帧接入点，继续丢
                        sub.armed = True
                        data = join  # 接入字节已包含本 chunk 的关键帧起内容
                else:
                    data = chunk
                if not data:
                    continue
                try:
                    sub.queue.put_nowait(data)
                    delivered += 1
                except asyncio.QueueFull:
                    # 队列满：丢弃（实时流语义，恢复靠关键帧周期）
                    logger.debug("device %s 流队列满，丢帧（实时语义）", device_id)
                except Exception:
                    logger.exception("流分片投递失败（忽略）")
            return delivered

    async def push_b64(self, device_id: str, data_b64: str, aligner=None) -> int:
        """base64 入口的便捷包装（空数据跳过）。"""
        try:
            raw = base64.b64decode(data_b64) if data_b64 else b""
        except Exception:
            logger.warning("device %s 流分片 base64 解码失败（丢弃）", device_id)
            return 0
        if not raw:
            return 0
        return await self.push(device_id, raw, aligner)
