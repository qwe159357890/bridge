package my.bridge.com.jikong

import timber.log.Timber

/**
 * 极空（JK BMS）保护板协议编解码器。
 *
 * 极空 BMS 通过 BLE 蓝牙**透传**通信，内部封装两套帧协议（反编译官方 APK
 * `com.jktech.bmss` 6.4.04 实证）：
 *
 * 一、0xDD 帧（软件板通用协议，读/写分开）：
 *   - 读：`DD A5 <命令码> <长度> <数据...> <校验和> 77`
 *   - 写：`DD 5A <命令码> <长度> <数据...> <校验和> 77`
 *   - 命令码：0x01 读基本信息、0x02 读单体电压、0x03 读告警状态、
 *             0x04 读设备信息、0x05 读均衡信息、0x06 读温度；
 *             0xE1 控制（MOS/均衡）、0xE2 写参数等。
 *   - 校验：帧头到校验前一字节的和，取低 8 位（求和校验）。
 *
 * 二、NW 帧（监控平台/上位机协议 V2.0，帧头 0x4E 0x57 = "NW"）：
 *   - 结构：`4E 57 <LENGTH(2B)> <终端号(4B)> <命令字(1B)> <帧来源(1B)>
 *            <传输类型(1B)> <标识码(1B)+数据...> <记录号(4B)> <68> <校验和(4B)>`
 *   - 数据标识码：0x79 总压、0x7A 电流、0x7B 剩余容量/SOC、0x7C~ 单体电压、
 *                 0x85~ 温度、0x8E~ 保护状态、0xA0~ 参数等。
 *
 * 由于无真机，本实现**同时支持两种帧**的编解码，BLE 收到的裸字节先尝试 NW 帧解析，
 * 再尝试 0xDD 帧解析；发送按调用方指定的协议族构造。真机联调时以实际抓包为准微调。
 */
object JikongProtocol {

    // ---- 帧头常量 ----
    const val DD_READ_HEAD = 0xDD
    const val DD_READ_CMD = 0xA5
    const val DD_WRITE_CMD = 0x5A
    const val DD_TAIL = 0x77

    const val NW_HEAD_0 = 0x4E
    const val NW_HEAD_1 = 0x57
    const val NW_TAIL = 0x68

    // ---- 0xDD 命令码 ----
    const val DD_CMD_BASIC = 0x01      // 读基本信息（总压/电流/剩余容量等）
    const val DD_CMD_CELL_VOLTAGE = 0x02 // 读单体电压
    const val DD_CMD_ALARM = 0x03      // 读告警/保护状态
    const val DD_CMD_DEVICE_INFO = 0x04 // 读设备信息（串数/型号/版本）
    const val DD_CMD_BALANCE = 0x05    // 读均衡信息
    const val DD_CMD_TEMP = 0x06       // 读温度
    const val DD_CMD_CONTROL = 0xE1    // 控制（MOS 开关/均衡开关）
    const val DD_CMD_WRITE_PARAM = 0xE2 // 写参数

    /** 读帧：`DD A5 <cmd> <len> <data> <sum> 77`。 */
    fun buildDdRead(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size
        val out = ByteArray(3 + 1 + len + 1 + 1) // 头(1)+A5(1)+cmd(1)+len(1)+data+sum(1)+77(1)
        out[0] = DD_READ_HEAD.toByte()
        out[1] = DD_READ_CMD.toByte()
        out[2] = cmd.toByte()
        out[3] = len.toByte()
        System.arraycopy(data, 0, out, 4, len)
        out[4 + len] = checksumDd(out, 0, 4 + len)
        out[5 + len] = DD_TAIL.toByte()
        return out
    }

    /** 写帧：`DD 5A <cmd> <len> <data> <sum> 77`。 */
    fun buildDdWrite(cmd: Int, data: ByteArray): ByteArray {
        val len = data.size
        val out = ByteArray(3 + 1 + len + 1 + 1)
        out[0] = DD_READ_HEAD.toByte()
        out[1] = DD_WRITE_CMD.toByte()
        out[2] = cmd.toByte()
        out[3] = len.toByte()
        System.arraycopy(data, 0, out, 4, len)
        out[4 + len] = checksumDd(out, 0, 4 + len)
        out[5 + len] = DD_TAIL.toByte()
        return out
    }

    /** 求和校验（帧头到校验前一字节，低 8 位）。 */
    private fun checksumDd(frame: ByteArray, from: Int, toExclusive: Int): Byte {
        var sum = 0
        for (i in from until toExclusive) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }

    /**
     * 解析 BLE 收到的原始字节，尝试识别并解码为 [JikongFrame]。
     *
     * 顺序：先 NW 帧（有明确帧头），再 0xDD 帧。返回 null 表示无法识别
     * （分片粘包/半包时调用方需先做流式缓冲重组）。
     */
    fun decode(raw: ByteArray): JikongFrame? {
        if (raw.size < 5) return null
        val h = raw[0].toInt() and 0xFF
        return when {
            h == NW_HEAD_0 && raw.size >= 6 && (raw[1].toInt() and 0xFF) == NW_HEAD_1 ->
                decodeNw(raw)
            h == DD_READ_HEAD && (raw[1].toInt() and 0xFF) in setOf(DD_READ_CMD, DD_WRITE_CMD) ->
                decodeDd(raw)
            else -> null
        }
    }

    // ---- 0xDD 帧解析 ----
    private fun decodeDd(raw: ByteArray): JikongFrame? {
        if (raw.size < 6) return null
        val isWrite = (raw[1].toInt() and 0xFF) == DD_WRITE_CMD
        val cmd = raw[2].toInt() and 0xFF
        val len = raw[3].toInt() and 0xFF
        if (raw.size < 5 + len + 1) return null
        val data = raw.copyOfRange(4, 4 + len)
        val tail = raw[5 + len].toInt() and 0xFF
        // 尾字节不强制校验 0x77（部分固件尾不同），仅做长度校验
        return JikongFrame(
            protocol = if (isWrite) "0xDD-W" else "0xDD",
            command = cmd,
            data = data,
            raw = raw,
            tailOk = tail == DD_TAIL,
        )
    }

    // ---- NW 帧解析 ----
    private fun decodeNw(raw: ByteArray): JikongFrame? {
        if (raw.size < 12) return null
        val length = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        // length 语义因固件而异，这里做宽松解析：取可用数据区
        val cmd = raw[8].toInt() and 0xFF
        val markCode = if (raw.size >= 11) raw[10].toInt() and 0xFF else 0
        val data = if (raw.size > 11) raw.copyOfRange(11, raw.size) else ByteArray(0)
        val tailIdx = raw.indexOfLast { (it.toInt() and 0xFF) == NW_TAIL }
        val payload = if (tailIdx > 11) raw.copyOfRange(11, tailIdx) else data
        return JikongFrame(
            protocol = "NW",
            command = cmd,
            markCode = markCode,
            length = length,
            data = payload,
            raw = raw,
            tailOk = tailIdx > 0,
        )
    }
}

/** 解析后的一帧协议数据。 */
data class JikongFrame(
    val protocol: String,        // "0xDD" / "0xDD-W" / "NW"
    val command: Int,            // 命令字（0xDD）或 NW 命令字
    val markCode: Int = 0,       // NW 数据标识码
    val length: Int = 0,         // NW 长度字段
    val data: ByteArray,         // 数据区（未解码原始字节）
    val raw: ByteArray,          // 完整原始帧
    val tailOk: Boolean = true,
)
