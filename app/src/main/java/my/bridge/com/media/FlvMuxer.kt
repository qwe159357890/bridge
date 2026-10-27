package my.bridge.com.media

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 极简 FLV 复用器（H.264 only，供投屏直播用）。
 *
 * 为什么是 FLV 而不是 MPEG-2 TS（见 docs/architecture/live-screen-research.md §三）：
 * MediaRecorder 录像走 TS 是因为 MediaRecorder 直接产出 TS 文件；而直播是
 * MediaCodec 裸 H.264 AnnexB 流手工打包——FLV 对 AVC 的打包（AVCC 长度前缀 +
 * sequence header tag）远比 TS 的 PAT/PMT/PES 简单（约百行 vs 三五百行），
 * 且管理后台 mpegts.js 原生支持 type:'flv' 的 WS 直播源。
 *
 * 流结构（写入顺序）：
 * ① 9 字节文件头（video only）+ 4 字节 PreviousTagSize0；
 * ② onMetaData 脚本 tag（width/height/framerate/videocodecid）；
 * ③ AVC sequence header video tag（AVCDecoderConfigurationRecord，由 csd-0/1 组装）；
 * ④ 逐帧 video tag：1 字节 FrameType|CodecID + 1 字节 AVCPacketType(1) +
 *    3 字节 composition offset(0) + AVCC 数据。
 *
 * 时间戳：FLV 为毫秒（24 位 + 8 位扩展），调用方传入相对首帧的 PTS。
 * 音轨：屏幕直播不需要声音，本实现只支持单视频轨。
 */
internal class FlvMuxer(private val out: OutputStream) {

    /** FLV 文件头 + PreviousTagSize0（video only，无音频轨）。 */
    fun writeHeader() {
        // 'F' 'L' 'V' | version 1 | flags 0x01(video) | DataOffset=9 | PreviousTagSize0=0
        out.write(byteArrayOf(0x46, 0x4C, 0x56, 1, 1, 0, 0, 0, 9, 0, 0, 0, 0))
    }

    /**
     * 写 onMetaData + AVC sequence header（首帧视频前必须就位，解码器初始化依赖）。
     * @param csd0 编码器输出格式的 csd-0（AnnexB 的 SPS，带起始码）
     * @param csd1 csd-1（AnnexB 的 PPS），可能为 null（极少数编码器合并进 csd-0）
     */
    fun writeMetadata(width: Int, height: Int, fps: Int, csd0: ByteArray, csd1: ByteArray?) {
        writeScriptTag(width, height, fps)
        writeVideoTag(avcSequenceHeader(csd0, csd1), 0, keyFrame = true, avcPacketType = 0)
    }

    /** 写一帧视频（MediaCodec 输出的 AnnexB 裸流，内部转 AVCC 长度前缀）。 */
    fun writeVideoFrame(annexB: ByteArray, ptsMs: Int, keyFrame: Boolean) {
        writeVideoTag(avccFromAnnexB(annexB), ptsMs, keyFrame, avcPacketType = 1)
    }

    // ------------------------------------------------------------------
    // 内部：tag 组装
    // ------------------------------------------------------------------

    /** VideoTagBody：FrameType|CodecID + AVCPacketType + composition offset(0) + data。 */
    private fun writeVideoTag(data: ByteArray, ptsMs: Int, keyFrame: Boolean, avcPacketType: Int) {
        val body = ByteArray(5 + data.size)
        body[0] = (((if (keyFrame) 0x10 else 0x20) or 0x07)).toByte()
        body[1] = avcPacketType.toByte()
        // body[2..4] = composition time offset = 0（默认 0 延迟编码配置无 B 帧）
        System.arraycopy(data, 0, body, 5, data.size)
        writeTag(0x09, body, ptsMs)
    }

    /** onMetaData 脚本 tag（AMF0：字符串 + ECMA 数组）。 */
    private fun writeScriptTag(width: Int, height: Int, fps: Int) {
        val d = ByteArrayOutputStream(96)
        d.write(0x02) // AMF0 string marker
        writeAmfString(d, "onMetaData")
        d.write(0x08) // ECMA array marker
        writeU32(d, 4) // entry count
        amfNumber(d, "duration", 0.0)
        amfNumber(d, "width", width.toDouble())
        amfNumber(d, "height", height.toDouble())
        amfNumber(d, "framerate", fps.toDouble())
        d.write(0); d.write(0); d.write(9) // object end marker
        writeTag(0x12, d.toByteArray(), 0)
    }

    /** AVCDecoderConfigurationRecord（FLV 的 AVC sequence header）。 */
    private fun avcSequenceHeader(sps: ByteArray, pps: ByteArray?): ByteArray {
        val d = ByteArrayOutputStream(32 + sps.size + (pps?.size ?: 0))
        d.write(1) // configurationVersion
        d.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        d.write(sps[2].toInt() and 0xFF) // profile_compatibility
        d.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        d.write(0xFC or 0x03) // lengthSizeMinusOne=3（4 字节 NALU 长度前缀）
        d.write(0xE0 or 0x01) // numOfSequenceParameterSets=1
        writeU16(d, sps.size)
        d.write(sps, 0, sps.size)
        if (pps != null && pps.isNotEmpty()) {
            d.write(1) // numOfPictureParameterSets=1
            writeU16(d, pps.size)
            d.write(pps, 0, pps.size)
        } else {
            d.write(0)
        }
        return d.toByteArray()
    }

    /**
     * AnnexB → AVCC：起始码分隔的 NALU 流转为「4 字节长度前缀」逐 NALU。
     * H.264 的 emulation prevention 保证 NALU 负载内不会出现 00 00 01 起始码
     * 序列，按起始码切分不会误切。
     */
    private fun avccFromAnnexB(annexB: ByteArray): ByteArray {
        val n = annexB.size
        // 1) 收集全部 NALU 数据起点（起始码之后）
        val starts = ArrayList<Int>(8)
        var i = 0
        while (i + 2 < n) {
            if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte()) {
                if (annexB[i + 2] == 1.toByte()) {
                    starts.add(i + 3)
                    i += 3
                    continue
                }
                if (i + 3 < n && annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte()) {
                    starts.add(i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return annexB // 无起始码：按已封装数据原样透传（防御）
        // 2) 逐 NALU 写「长度前缀 + 负载」；负载终点 = 下一个起始码起点（扣除起始码字节）
        val d = ByteArrayOutputStream(n + starts.size * 4)
        for (k in starts.indices) {
            val from = starts[k]
            val to = if (k + 1 < starts.size) starts[k + 1] - startCodeLen(annexB, starts[k + 1]) else n
            val len = to - from
            if (len > 0) {
                writeU32(d, len)
                d.write(annexB, from, len)
            }
        }
        return d.toByteArray()
    }

    /** start 位置前的起始码长度（4 字节 00 00 00 01 或 3 字节 00 00 01）。 */
    private fun startCodeLen(b: ByteArray, start: Int): Int =
        if (start >= 4 && b[start - 4] == 0.toByte() && b[start - 3] == 0.toByte() &&
            b[start - 2] == 0.toByte() && b[start - 1] == 1.toByte()
        ) 4 else 3

    /** 写一个完整 FLV tag（11 字节头 + data + 4 字节 PreviousTagSize）。 */
    private fun writeTag(type: Int, data: ByteArray, tsMs: Int) {
        val size = data.size
        out.write(type)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
        // 时间戳 24 位 + 8 位扩展（24 位上限约 4.6 小时，直播场景足够）
        val ts = tsMs.coerceAtLeast(0)
        out.write((ts shr 16) and 0xFF)
        out.write((ts shr 8) and 0xFF)
        out.write(ts and 0xFF)
        out.write(ts shr 24)
        out.write(0); out.write(0); out.write(0) // StreamID 恒 0
        out.write(data)
        val prev = size + 11
        out.write((prev shr 24) and 0xFF)
        out.write((prev shr 16) and 0xFF)
        out.write((prev shr 8) and 0xFF)
        out.write(prev and 0xFF)
    }

    private fun writeAmfString(d: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        d.write((bytes.size shr 8) and 0xFF)
        d.write(bytes.size and 0xFF)
        d.write(bytes, 0, bytes.size)
    }

    private fun writeU16(d: ByteArrayOutputStream, v: Int) {
        d.write((v shr 8) and 0xFF)
        d.write(v and 0xFF)
    }

    private fun writeU32(d: ByteArrayOutputStream, v: Int) {
        d.write((v shr 24) and 0xFF)
        d.write((v shr 16) and 0xFF)
        d.write((v shr 8) and 0xFF)
        d.write(v and 0xFF)
    }

    private fun amfNumber(d: ByteArrayOutputStream, name: String, value: Double) {
        writeAmfString(d, name)
        d.write(0x00) // AMF0 number marker
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 7 downTo 0) d.write(((bits shr (8 * i)) and 0xFF).toInt())
    }
}
