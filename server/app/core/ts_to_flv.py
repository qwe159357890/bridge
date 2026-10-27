"""MPEG-2 TS → FLV 转封装器（流式录像实时通道专用，2026-10-27）。

为什么需要（根因见 .workbuddy/memory/2026-10-27.md v0.1.55 节）：
Android MediaMuxer 的 MPEG_2_TS 产物里 PCR/时间戳基准是系统 boottime，
mpegts.js 解析后把 MSE buffered 末端算成 ~1.29 亿秒（实验复现：解码 5 帧
后完全停滞，buffered 不再增长）——「只出首帧、声音正常」的直接根因。
FLV 无 PCR、时间戳就是 tag 毫秒值，本模块把 TS 拆解后**重新按归一化
时间戳**打 FLV，播放端（mpegts.js type:'flv'）验证解码完全正常。

设计：
- 流式状态机：feed() 可被任意大小的字节块调用（分片边界不必对齐 188 包，
  跨块残字节自动缓冲）；返回本次新增的 FLV 字节。
- 只取视频（H.264）与音频（AAC ADTS）两条 PES 流；一个视频 PES = 一个
  访问单元（Android MediaMuxer 行为），AU 内 NALU 转 AVCC 长度前缀。
- 时间戳：全部用 PES 的 PTS/DTS（90kHz → ms），以首个视频 DTS 为 0 基准；
  B 帧写入 FLV 的 composition time offset = pts - dts。
- 首 AU 通常含 SPS/PPS → 组装 AVCDecoderConfigurationRecord（裸 NALU，
  v0.1.53 同款坑已规避）与 AAC 的 AudioSpecificConfig（由 ADTS 头构造）。
"""

from __future__ import annotations

import logging
import struct

logger = logging.getLogger(__name__)

_START_CODE = b"\x00\x00\x01"


class TsToFlv:
    """增量 TS → FLV 转封装器（每个设备流一个实例，is_end 后丢弃）。"""

    def __init__(self) -> None:
        self._buf = b""  # 未消费的 TS 字节（不足 188 的残尾）
        self._video_pid: int | None = None
        self._audio_pid: int | None = None
        self._pes_buf: dict[int, bytearray] = {}  # pid -> 未完 PES 负载
        self._base_dts: int | None = None  # 90kHz 首视频 DTS（时间归一基准）
        self._prev_dts_ms = -1  # 上一帧单调化后的 dts ms
        self._video_asc_done = False
        self._audio_asc: bytes | None = None
        self._out = bytearray()  # FLV 产物累积

    # ---------------- 对外接口 ----------------

    def feed(self, ts_bytes: bytes) -> bytes:
        """喂一段 TS 字节（任意边界），返回本次产生的 FLV 字节。"""
        self._out.clear()
        self._buf += ts_bytes
        pos = 0
        n = len(self._buf)
        while pos + 188 <= n:
            pkt = self._buf[pos : pos + 188]
            if pkt[0] != 0x47:
                # 同步丢失：滑动找下一个 0x47（容忍分片边界脏字节）
                nxt = self._buf.find(0x47, pos + 1)
                pos = nxt if nxt >= 0 else n
                continue
            self._handle_packet(pkt)
            pos += 188
        self._buf = self._buf[pos:]
        return bytes(self._out)

    @property
    def format_name(self) -> str:
        return "flv"

    # ---------------- TS 层 ----------------

    def _handle_packet(self, pkt: bytes) -> None:
        pid = ((pkt[1] & 0x1F) << 8) | pkt[2]
        pusi = bool(pkt[1] & 0x40)
        afc = (pkt[3] >> 4) & 0x3
        payload_off = 4
        if afc & 0x2:  # 有 adaptation field
            af_len = pkt[4]
            payload_off = 5 + af_len
        if not (afc & 0x1):  # 无 payload
            return
        payload = pkt[payload_off:]
        if not payload:
            return

        if pid == 0:  # PAT
            self._parse_pat(payload)
        elif pid == getattr(self, "_pmt_pid", None):
            self._handle_pmt(payload)
        elif pid == self._video_pid or pid == self._audio_pid:
            self._handle_pes(pid, pusi, payload)

    def _parse_pat(self, payload: bytes) -> None:
        """PAT → PMT PID（取第一个 program）。"""
        try:
            p = 1 + payload[0]  # pointer_field
            # table header: tid, sec_len(12b)…
            sec_len = ((payload[p + 1] & 0x0F) << 8) | payload[p + 2]
            end = p + 3 + sec_len - 4  # 去 CRC
            q = p + 8  # 跳过 table 头
            while q + 4 <= end:
                prog = (payload[q] << 8) | payload[q + 1]
                pmt_pid = ((payload[q + 2] & 0x1F) << 8) | payload[q + 3]
                if prog != 0:
                    self._pmt_pid = pmt_pid
                    break
                q += 4
        except Exception:
            logger.debug("PAT 解析失败（忽略）", exc_info=True)

    _pmt_pid: int | None = None

    def _handle_pmt(self, payload: bytes) -> None:
        """PMT → 视频/音频 PID（stream_type: 0x1B=H264, 0x0F=AAC）。"""
        try:
            p = 1 + payload[0]
            sec_len = ((payload[p + 1] & 0x0F) << 8) | payload[p + 2]
            end = p + 3 + sec_len - 4
            q = p + 12  # 跳过 table 头 + program_info
            while q + 5 <= end:
                stype = payload[q]
                epid = ((payload[q + 1] & 0x1F) << 8) | payload[q + 2]
                es_len = ((payload[q + 3] & 0x0F) << 8) | payload[q + 4]
                if stype == 0x1B and self._video_pid is None:
                    self._video_pid = epid
                elif stype == 0x0F and self._audio_pid is None:
                    self._audio_pid = epid
                q += 5 + es_len
        except Exception:
            logger.debug("PMT 解析失败（忽略）", exc_info=True)

    # ---------------- PES 层 ----------------

    def _handle_pes(self, pid: int, pusi: bool, payload: bytes) -> None:
        if pusi:
            # 新 PES 开始：先把上一条收尾（正常按 unit_start 切分）
            pending = self._pes_buf.pop(pid, None)
            if pending:
                self._emit_pes(pid, bytes(pending))
            self._pes_buf[pid] = bytearray(payload)
        elif pid in self._pes_buf:
            self._pes_buf[pid].extend(payload)

    def _emit_pes(self, pid: int, data: bytes) -> None:
        try:
            if data[0:3] != b"\x00\x00\x01":
                return
            stream_id = data[3]
            pes_len = (data[4] << 8) | data[5]
            body = data[6:]
            if pes_len > 0:
                body = body[: pes_len]  # 截到声明长度（去尾部 padding）
            hdr_len = body[2]
            flags = (body[0] << 8) | body[1]
            pts = dts = None
            if flags & 0x8000:  # PTS_DTS_flags 在 flags2 高 2 位
                if flags & 0x4000:
                    # '11' = PTS+DTS 都有：MPEG 规范**先 DTS 后 PTS**（此前写反导致
                    # tag 时间戳全用 pts 序、B 帧 comp offset 全错，mpegts.js 的
                    # MSE 时间轴被算成天文数字 → 解码停滞）
                    dts = _parse_pts(body[3:8])
                    pts = _parse_pts(body[8:13])
                else:
                    pts = _parse_pts(body[3:8])
                    dts = pts
            payload = body[3 + hdr_len :]
            if not payload:
                return
            if pid == self._video_pid and (pts is not None):
                self._emit_video(payload, pts, dts if dts is not None else pts)
            elif pid == self._audio_pid and pts is not None:
                self._emit_audio(payload, pts)
        except Exception:
            logger.debug("PES 解析失败（忽略一包）", exc_info=True)

    # ---------------- 视频轨 ----------------

    def _emit_video(self, annexb: bytes, pts: int, dts: int) -> None:
        if self._base_dts is None:
            self._base_dts = dts
            self._prev_dts_ms = -1
        dts_ms = max(0, (dts - self._base_dts) * 1000 // 90000)
        pts_ms = max(0, (pts - self._base_dts) * 1000 // 90000)
        # dts 单调化（ffmpeg -c copy 同款 fixup）：Android MediaMuxer TS 的 dts
        # 有病态跳跃/非单调（实测 IDR 后 818ms 跳变），如实转写会让 mpegts.js
        # 把 MSE 时间轴算成天文数字（buffered ~1.1 亿秒）→ 解码几帧后停滞。
        # 强制 dms 严格递增，B 帧的显示关系由 composition offset 表达。
        if dts_ms <= self._prev_dts_ms:
            dts_ms = self._prev_dts_ms + 1
        self._prev_dts_ms = dts_ms

        nalus = _split_nalus(annexb)
        types = [t for t, _ in nalus]
        if 7 in types or 8 in types:  # 带 SPS/PPS 的 AU
            if not self._video_asc_done:
                sps = next((raw for t, raw in nalus if t == 7), None)
                pps = next((raw for t, raw in nalus if t == 8), None)
                if sps:
                    self._write_flv_header_and_meta(sps, pps)
                    self._video_asc_done = True
        if not self._video_asc_done:
            return  # 还没拿到 SPS，丢弃（首个 AU 之前的 AU 不应存在）

        key = 5 in types or (7 in types)  # IDR 或带 SPS 的 AU
        body = bytearray()
        body.append((0x10 if key else 0x20) | 0x07)
        body.append(1)  # AVCPacketType=1 (NALU)
        comp = pts_ms - dts_ms
        body += struct.pack(">i", comp)[1:]  # 3 字节有符号 composition offset
        n_out = 0
        for t, raw in nalus:
            if t in (7, 8):  # SPS/PPS 已进 sequence header，帧内不重复
                continue
            if t == 9 and len(nalus) > 1:  # AUD 无解码意义
                continue
            body += struct.pack(">I", len(raw))
            body += raw
            n_out += 1
        if n_out == 0:
            # 纯 SPS/PPS 的 AU（首个 AU 常见）：过滤后无帧数据，写空 tag 会把
            # mpegts.js 的 MSE 时间戳搞坏（实测 buffered 变 1.1 亿秒）——跳过
            return
        _write_tag(self._out, 0x09, bytes(body), dts_ms)

    def _write_flv_header_and_meta(self, sps: bytes, pps: bytes | None) -> None:
        """首 AU：FLV 头 + onMetaData + AVC sequence header（+音频 ASC 占位）。"""
        out = self._out
        out += b"FLV\x01\x05\x00\x00\x00\x09" + b"\x00\x00\x00\x00"
        # onMetaData
        meta = bytearray()
        meta += b"\x02" + _amf_str("onMetaData") + b"\x08" + struct.pack(">I", 4)
        meta += _amf_num("duration", 0.0)
        meta += _amf_num("videocodecid", 7.0)
        meta += _amf_num("audiocodecid", 10.0)
        meta += _amf_num("framerate", 30.0)
        meta += b"\x00\x00\x09"
        _write_tag(out, 0x12, bytes(meta), 0)
        # AVC sequence header（裸 NALU，v0.1.53 同款规避）
        rec = bytearray()
        rec += b"\x01"
        rec += sps[1:4]
        rec.append(0xFC | 0x03)
        rec.append(0xE0 | 0x01)
        rec += struct.pack(">H", len(sps)) + sps
        if pps:
            rec.append(0x01)
            rec += struct.pack(">H", len(pps)) + pps
        else:
            rec.append(0x00)
        vtag = bytearray(b"\x17\x00\x00\x00\x00")
        vtag += rec
        _write_tag(out, 0x09, bytes(vtag), 0)
        # 音频 sequence header（ASC 已知时立即写，否则等 ADTS 首帧）
        if self._audio_asc:
            atag = bytearray(b"\xAF\x00") + self._audio_asc
            _write_tag(out, 0x08, bytes(atag), 0)

    # ---------------- 音频轨 ----------------

    def _emit_audio(self, payload: bytes, pts: int) -> None:
        """TS 里的 AAC 带 ADTS 头 → 剥头 → FLV audio tag（raw）。"""
        off = 0
        while off + 7 <= len(payload):
            if payload[off] != 0xFF or (payload[off + 1] & 0xF0) != 0xF0:
                off += 1
                continue
            frame_len = ((payload[off + 3] & 0x03) << 11) | (payload[off + 4] << 3) | (payload[off + 5] >> 5)
            if frame_len < 7 or off + frame_len > len(payload):
                return
            if self._audio_asc is None:
                self._audio_asc = _asc_from_adts(payload[off : off + 7])
                if self._video_asc_done:
                    atag = bytearray(b"\xAF\x00") + self._audio_asc
                    _write_tag(self._out, 0x08, bytes(atag), 0)
            if self._video_asc_done:
                ts_ms = 0 if self._base_dts is None else max(0, (pts - self._base_dts) * 1000 // 90000)
                raw = payload[off + 7 : off + frame_len]
                _write_tag(self._out, 0x08, b"\xAF\x01" + raw, ts_ms)
            off += frame_len


# ---------------- 工具函数 ----------------


def _parse_pts(b: bytes) -> int:
    """PES 的 5 字节 PTS/DTS 字段 → 90kHz 整数。"""
    return ((b[0] >> 1) & 0x07) << 30 | b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | (b[4] >> 1)


def _split_nalus(annexb: bytes) -> list[tuple[int, bytes]]:
    """AnnexB → [(nal_type, 裸数据)]（3/4 字节起始码都兼容）。"""
    out: list[tuple[int, bytes]] = []
    starts: list[int] = []
    i = 0
    n = len(annexb)
    while i + 2 < n:
        if annexb[i] == 0 and annexb[i + 1] == 0:
            if annexb[i + 2] == 1:
                starts.append((i, 3))
                i += 3
                continue
            if i + 3 < n and annexb[i + 2] == 0 and annexb[i + 3] == 1:
                starts.append((i, 4))
                i += 4
                continue
        i += 1
    for k, (pos, sc_len) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else n
        raw = annexb[pos + sc_len : end]
        if raw:
            out.append((raw[0] & 0x1F, raw))
    return out


def _write_tag(out: bytearray, tag_type: int, body: bytes, ts_ms: int) -> None:
    size = len(body)
    ts = max(0, ts_ms)
    out.append(tag_type)
    out += bytes(((size >> 16) & 0xFF, (size >> 8) & 0xFF, size & 0xFF))
    out += bytes(((ts >> 16) & 0xFF, (ts >> 8) & 0xFF, ts & 0xFF, (ts >> 24) & 0xFF))
    out += b"\x00\x00\x00"  # stream id
    out += body
    prev = size + 11
    out += bytes(((prev >> 24) & 0xFF, (prev >> 16) & 0xFF, (prev >> 8) & 0xFF, prev & 0xFF))


def _amf_str(s: str) -> bytes:
    b = s.encode("ascii")
    return struct.pack(">H", len(b)) + b


def _amf_num(name: str, v: float) -> bytes:
    """ECMA array entry：key（AMF string）+ value（number marker + 8 字节）。"""
    return _amf_str(name) + b"\x00" + struct.pack(">d", v)


def _asc_from_adts(adts: bytes) -> bytes:
    """从 ADTS 头构造 2 字节 AudioSpecificConfig。"""
    profile = ((adts[2] & 0xC0) >> 6) + 1  # 2bit → +1（ASC 里 profile 是 MPEG-4 值）
    freq = (adts[2] & 0x3C) >> 2
    chan = ((adts[2] & 0x01) << 2) | ((adts[3] & 0xC0) >> 6)
    return bytes(((profile << 3) | (freq >> 1), ((freq & 1) << 7) | (chan << 3)))
