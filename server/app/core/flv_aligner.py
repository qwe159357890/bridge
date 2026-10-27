"""FLV 直播流对齐器（中途加入/重建订阅者的无缝接入，2026-10-27）。

背景：旧的 StreamHub 512KB 盲字节缓存有两个致命缺陷——
1. 截断发生在任意字节边界（可能切在 tag 中间）；
2. 中途加入者拿到「头缓存（ts 0~N 秒）+ 当前位置的实时流」，中间出现
   **字节断层**，demuxer 把断层后的数据当连续流解析 → Invalid PrevTagSize
   → 画面冻结；重建自愈再拿同样的坏缓存 → 冻结循环（实测「卡住→刷新
   恢复→再卡住」）。

本对齐器把流解析为结构化视图：
- **prefix**：FLV header + onMetaData + AVC/AAC sequence header（解码器
  初始化所需全部，通常 <1KB），流开始后不变；
- **tail**：自最近一个关键帧 tag 起的完整字节（tag 对齐、无断层）。

订阅者接入时拿到 prefix + tail + 后续实时流：字节连续、tag 对齐、
且从关键帧开始（解码器可直接出画面）。tail 上限 8MB（客户端 GOP 2s ×
观测极端码率 ~22Mbps ≈ 5.5MB），超限则失效、等下一个关键帧重新对齐。

只处理 FLV（kind=video 服务端转封装产物 / kind=screen 客户端原生）；
kind=audio（ADTS）帧自同步，无需对齐。
"""

from __future__ import annotations

# tail 上限：GOP 2s × 观测极端码率 22Mbps ≈ 5.5MB，留余量
_MAX_TAIL = 8 * 1024 * 1024
# prefix 上限（异常流保护）：正常 <1KB
_MAX_PREFIX = 64 * 1024


class FlvAligner:
    """单路 FLV 流（一次 screen_live / 流式录像会话）的对齐状态。"""

    def __init__(self) -> None:
        self._prefix = bytearray()  # FLV header + metadata + seq headers
        self._prefix_done = False
        self._tail = bytearray()  # 自最近关键帧 tag 起的字节（tag 对齐）
        self._tail_valid = False  # tail 是否可作接入点（未超限/已见关键帧）
        self._scanned = 0  # _tail 中已完成 tag 定界扫描的字节数
        self._scan = bytearray()  # prefix 阶段的未定界输入余量

    # ------------------------------------------------------------------
    # 输入
    # ------------------------------------------------------------------

    def feed(self, chunk: bytes) -> None:
        """喂入一段实时流字节（块边界任意，内部按 tag 定界）。"""
        if not chunk:
            return
        if self._prefix_done:
            self._feed_media(chunk)
            return

        self._scan.extend(chunk)
        scan = self._scan
        n = len(scan)

        # FLV header（13 字节，必然位于流最前）
        if len(self._prefix) == 0:
            if n < 13:
                return  # 留在 _scan 等下一个 chunk
            self._prefix.extend(scan[:13])
            del scan[:13]
            n = len(scan)

        pos = 0
        while True:
            head = self._tag_at(scan, pos)
            if head is None:
                break  # 剩半个 tag，留待下个 chunk
            tag_type, body_start, body_end, total = head
            media = False
            if tag_type in (0x09, 0x08) and body_end - body_start >= 2:
                pkt = scan[body_start + 1]
                if (tag_type == 0x09 and pkt == 1) or (tag_type == 0x08 and pkt == 1):
                    media = True  # 第一个媒体 tag（NALU / AAC raw）
            if media:
                # prefix 完成；自本 tag 起进入媒体区（正常即关键帧起）
                self._prefix_done = True
                self._tail.extend(scan[pos:])
                self._scanned = 0
                self._tail_valid = False  # 由 _feed_media 扫到关键帧再置位
                self._feed_media(b"")  # 立即扫描刚并入的字节
                del scan[:len(scan)]  # 清空（已全部并入 tail）
                return
            # header tag（script / AVC/AAC seq header）：并入 prefix
            self._prefix.extend(scan[pos:total])
            pos = total
            if len(self._prefix) > _MAX_PREFIX:
                # 异常流：放弃 prefix 收集（本流无法中途接入，仅保实时）
                self._prefix_done = True
                self._tail_valid = False
                del scan[:pos]
                return
        del scan[:pos]

    def _feed_media(self, chunk: bytes) -> None:
        """媒体区扫描：找关键帧 tag 起点，维护自关键帧起的 tail。"""
        buf = self._tail
        buf.extend(chunk)
        n = len(buf)
        # 从上次扫描位置回退少许（tag 头可能跨 chunk 边界）
        pos = max(0, self._scanned - 15)
        while pos + 11 <= n:
            size = int.from_bytes(buf[pos + 1 : pos + 4], "big")
            end = pos + 11 + size
            if end + 4 > n:
                break  # 半个 tag，等下个 chunk
            if buf[pos] == 0x09 and size >= 2 and buf[pos + 11] >> 4 == 1 and buf[pos + 12] == 1:
                # 关键帧 tag（frameType=1 且 AVCPacketType=1）：tail 对齐到这
                if pos > 0:
                    del buf[:pos]
                    n -= pos
                    pos = 0
                self._tail_valid = True
            pos = end + 4
        self._scanned = min(pos, n)
        if len(buf) > _MAX_TAIL:
            # 本 GOP 超限：放弃对齐，等下一关键帧重新对齐
            buf.clear()
            self._tail_valid = False
            self._scanned = 0

    @staticmethod
    def _tag_at(data: bytearray | bytes, pos: int) -> tuple[int, int, int, int] | None:
        """解析 pos 处的 tag。返回 (type, body_start, body_end, total_end)；不完整返回 None。"""
        if pos + 11 > len(data):
            return None
        size = int.from_bytes(data[pos + 1 : pos + 4], "big")
        body_start = pos + 11
        body_end = body_start + size
        total = body_end + 4  # 含 PrevTagSize
        if total > len(data):
            return None
        return data[pos], body_start, body_end, total

    # ------------------------------------------------------------------
    # 输出
    # ------------------------------------------------------------------

    def join_bytes(self) -> bytes | None:
        """新订阅者的接入字节：prefix + 关键帧 tail。未就绪返回 None。

        就绪条件：prefix 完整且 tail 已对齐到关键帧（tag 对齐、无断层）。
        """
        if not self._prefix_done or not self._tail_valid or not self._tail:
            return None
        return bytes(self._prefix) + bytes(self._tail)
