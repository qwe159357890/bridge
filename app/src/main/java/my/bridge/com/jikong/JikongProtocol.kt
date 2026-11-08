package my.bridge.com.jikong

import timber.log.Timber

/**
 * 极空（JK BMS）保护板协议编解码器 —— JK02 BLE 格式。
 *
 * 真机实测（v0.2.57 抓包）确认极空 BMS 蓝牙用的是 **JK02 二进制帧协议**，
 * 而非此前假设的 0xDD/NW 帧。协议来源（开源项目逆向实证，BHP1000/JK-BMS-BLE-ESP32、
 * syssi/esphome-jk-bms、peff74/Arduino-jk-bms）：
 *
 * BLE 服务：service 0xFFE0，characteristic 0xFFE1（读写同一 UUID）。
 *
 * 命令帧（手机 → BMS，固定 20 字节）：
 *   AA 55 90 EB | <命令码 1B> | <长度 1B> | <值 4B LE> | <零填充 9B> | <CRC 1B>
 *   - 命令码：0x97=读设备信息、0x96=读单体数据（触发数据流）、0x05=写密码(123456=0x0001E240)、
 *             0x1D=充电 MOS、0x1E=放电 MOS
 *   - CRC = 前 19 字节累加和（低 8 位）
 *
 * 响应帧（BMS → 手机，约 300 字节，经 MTU 分包）：
 *   55 AA EB 90 | <帧类型 1B> | <数据...> | <CRC>
 *   - 帧类型：0x01=保护/设置参数、0x02=实时数据（连续流）、0x03=设备信息
 *
 * 连接后必须发 0x96（读单体）触发数据流；BMS 才会回 0x01+0x03+0x02 爆发，随后持续 0x02 流。
 */
object JikongProtocol {

    // ---- 帧头 ----
    private const val CMD_H0 = 0xAA
    private const val CMD_H1 = 0x55
    private const val CMD_H2 = 0x90
    private const val CMD_H3 = 0xEB

    private const val RESP_H0 = 0x55
    private const val RESP_H1 = 0xAA
    private const val RESP_H2 = 0xEB
    private const val RESP_H3 = 0x90

    // ---- 命令码 ----
    const val CMD_PASSWORD = 0x05   // 写密码（解锁设置/MOS 写）
    const val CMD_CELL_INFO = 0x96  // 读单体数据（触发数据流）
    const val CMD_DEVICE_INFO = 0x97 // 读设备信息
    const val CMD_CHARGE_MOS = 0x1D // 充电 MOS 开关
    const val CMD_DISCHARGE_MOS = 0x1E // 放电 MOS 开关

    // ---- 响应帧类型 ----
    const val FRAME_SETTINGS = 0x01
    const val FRAME_DATA = 0x02
    const val FRAME_DEVICE_INFO = 0x03

    /** 命令帧固定长度（20 字节）。 */
    const val CMD_LEN = 20

    /** 密码 123456 = 0x0001E240。 */
    const val PASSWORD_VALUE = 0x0001E240L

    /** 所有响应帧（0x01/0x02/0x03）的固定长度：300 字节，含 CRC（@299）。 */
    const val RESPONSE_FRAME_LEN = 300

    /**
     * 构造命令帧（20 字节）。
     * @param cmd 命令码
     * @param value 值（4 字节，仅密码/MOS 等命令用；读命令传 0）
     */
    fun buildCommand(cmd: Int, value: Long = 0): ByteArray {
        val f = ByteArray(CMD_LEN)
        f[0] = CMD_H0.toByte()
        f[1] = CMD_H1.toByte()
        f[2] = CMD_H2.toByte()
        f[3] = CMD_H3.toByte()
        f[4] = cmd.toByte()
        f[5] = 0x00 // length（读命令为 0）
        // 值 4B LE
        f[6] = (value and 0xFF).toByte()
        f[7] = ((value shr 8) and 0xFF).toByte()
        f[8] = ((value shr 16) and 0xFF).toByte()
        f[9] = ((value shr 24) and 0xFF).toByte()
        // [10..18] 零填充（默认 0）
        f[19] = crc8(f, 19)
        return f
    }

    /** 读单体数据（触发 BMS 数据流）。 */
    fun buildCellInfo(): ByteArray = buildCommand(CMD_CELL_INFO)

    /** 读设备信息。 */
    fun buildDeviceInfo(): ByteArray = buildCommand(CMD_DEVICE_INFO)

    /** 写密码（123456）。 */
    fun buildPassword(): ByteArray = buildCommand(CMD_PASSWORD, PASSWORD_VALUE)

    /** 求和校验（前 len 字节累加，低 8 位）。 */
    private fun crc8(data: ByteArray, len: Int): Byte {
        var sum = 0
        for (i in 0 until len) sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }

    /**
     * 从字节流中提取一帧响应（流式重组用）。
     *
     * 响应帧头 `55 AA EB 90`。极空 JK02 所有响应帧（0x01/0x02/0x03）均为固定
     * 300 字节（BHP1000 `FRAME_COMPLETE=300`，pippofo 文档「300-320 bytes」，
     * CRC 在第 299 字节）。协议里响应帧无显式长度字段，靠「帧头同步 + 固定 300B」
     * 切分，故必须等满 300 字节才算完整一帧（不能按帧类型用不同长度，否则 0x03
     * 帧切错会破坏流式重组、吞掉后续帧头）。
     *
     * 返回 null 表示「缓冲不足一帧」或「头部非本协议帧」。
     */
    fun extractFrame(buf: ByteArray): ExtractedFrame? {
        if (buf.size < 5) return null
        if (!(buf[0].toInt() and 0xFF == RESP_H0 && buf[1].toInt() and 0xFF == RESP_H1 &&
                buf[2].toInt() and 0xFF == RESP_H2 && buf[3].toInt() and 0xFF == RESP_H3)
        ) {
            return null
        }
        val type = buf[4].toInt() and 0xFF
        // 仅接受已知帧类型；其余（如 AT 心跳、C8 ACK 帧头 AA 55 90 EB）由 handleData 跳过
        if (type != FRAME_SETTINGS && type != FRAME_DATA && type != FRAME_DEVICE_INFO) {
            return null
        }
        // 所有响应帧统一 300 字节（含 CRC @299）
        if (buf.size < RESPONSE_FRAME_LEN) return null // 半包，等更多字节
        val raw = buf.copyOfRange(0, RESPONSE_FRAME_LEN)
        // v0.2.118：校验 CRC（前 299 字节累加和低 8 位 == d[299]）。半死链苏醒瞬间 BMS
        // 可能吐出字节错位/假帧头的坏帧（真机误报 SOC=10 即此类），坏帧仍按 300 字节消费
        // 以维持流式重组节奏不变，但 frame 置空由调用方跳过解析——正常帧路径完全不变。
        if (!verifyCrc(raw)) {
            Timber.w("极空帧 CRC 校验失败（帧类型 0x%02X），丢弃该帧不解析", type)
            return ExtractedFrame(null, RESPONSE_FRAME_LEN)
        }
        val frame = JikongFrame(
            protocol = "JK02",
            command = type,
            data = raw,
            raw = raw,
        )
        return ExtractedFrame(frame, RESPONSE_FRAME_LEN)
    }

    /**
     * 校验响应帧 CRC：d[299] 应等于前 299 字节（d[0..298]）累加和的低 8 位。
     * 与命令帧 crc8 同一算法；已用真机抓包 30+ 帧逐字节实证 100% 匹配。
     */
    fun verifyCrc(raw: ByteArray): Boolean {
        if (raw.size < RESPONSE_FRAME_LEN) return false
        var sum = 0
        for (i in 0 until RESPONSE_FRAME_LEN - 1) {
            sum = (sum + (raw[i].toInt() and 0xFF)) and 0xFF
        }
        return (raw[RESPONSE_FRAME_LEN - 1].toInt() and 0xFF) == sum
    }

    /** 兼容旧 decode 接口（单帧解析，供测试/调试）。 */
    fun decode(raw: ByteArray): JikongFrame? {
        if (raw.size < 5) return null
        if (!(raw[0].toInt() and 0xFF == RESP_H0 && raw[1].toInt() and 0xFF == RESP_H1 &&
                raw[2].toInt() and 0xFF == RESP_H2 && raw[3].toInt() and 0xFF == RESP_H3)
        ) return null
        return JikongFrame("JK02", raw[4].toInt() and 0xFF, data = raw, raw = raw)
    }
}

/** 解析后的一帧协议数据。 */
data class JikongFrame(
    val protocol: String,        // "JK02"
    val command: Int,            // 帧类型（0x01/0x02/0x03）
    val markCode: Int = 0,       // 保留（兼容）
    val length: Int = 0,         // 保留（兼容）
    val data: ByteArray,         // 完整原始帧（含帧头）
    val raw: ByteArray,          // 完整原始帧
    val tailOk: Boolean = true,
)

/** 流式重组提取结果：一帧 + 已消费字节数。frame 为 null 表示 CRC 校验失败的坏帧（已消费、跳过解析）。 */
data class ExtractedFrame(
    val frame: JikongFrame?,
    val consumedLength: Int,
)
