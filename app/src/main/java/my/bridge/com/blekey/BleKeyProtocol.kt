package my.bridge.com.blekey

/**
 * iBeacon 组包与 UUID 拼装（纯逻辑，无 Android 依赖，可单测）。
 *
 * 对端硬约定（espAutoUnlock/src/ble_scanner.parse_ibeacon_uuid）：
 * ```
 * <len> FF 4C 00 02 15 <UUID 16B> <Major 2B> <Minor 2B> <TxPower 1B>
 *               └ 公司ID 0x004C 小端  └ 02=类型, 15=后续 21 字节
 * ```
 * - `addManufacturerData(0x004C, payload)` 的 payload **从 `02 15` 开始，不含公司 ID**（Android 自动补 0x004C）。
 * - 对端按**完整 32 字符小写 hex** 做字符串相等比较，所以这里必须拼出完整 UUID，不能只发编号。
 *
 * 字节预算（legacy 广播包上限 31）：Flags 3 + 厂商数据 27 = 30，只剩 1 字节。
 * 调用方必须同时 `setIncludeDeviceName(false)` + `setIncludeTxPowerLevel(false)`，否则必然 DATA_TOO_LARGE。
 *
 * 设计文档：docs/research/ble-key-broadcast.md §5、§6。
 */
object BleKeyProtocol {

    /** Apple Inc.，iBeacon 规范强制。 */
    const val COMPANY_ID_APPLE = 0x004C

    /** iBeacon 固定头：0x02 = 类型（信标），0x15 = 后续 21 字节。 */
    const val IBEACON_TYPE = 0x02
    const val IBEACON_LEN = 0x15

    /** 厂商数据 payload 长度：02 15 + UUID16 + Major2 + Minor2 + Tx1 = 23。 */
    const val PAYLOAD_LEN = 23

    /** 1 米处参考 RSSI，对端不使用（`parse_ibeacon_uuid` 只取 UUID），保持 iBeacon 惯例值。 */
    const val DEFAULT_MEASURED_POWER = -59

    /** Major 固定；Minor 写编号（编号在广播里出现两次，串口日志更好认）。 */
    const val DEFAULT_MAJOR = 1

    /**
     * 默认 UUID 前缀（30 字符）= "bridge" 的 ASCII hex（62 72 69 64 67 65）+ 18 个 0。
     * 完整 UUID = 前缀 + 2 位编号（共 32 字符）。可在高级设置改（改 = 换钥匙，对端须重新登记）。
     */
    const val DEFAULT_UUID_PREFIX = "627269646765000000000000000000"

    /** 前缀长度（完整 UUID 32 - 编号 2）。 */
    const val PREFIX_LEN = 30

    const val NUMBER_MIN = 0
    const val NUMBER_MAX = 99

    /**
     * 拼装完整 UUID：前缀（30）+ 编号补两位（2）= 32 字符小写 hex。
     *
     * @param prefix 30 字符 hex（允许带连字符/大写，内部归一化），非法时回退默认前缀
     * @param number 0~99，超界自动钳制
     */
    fun fullUuid(prefix: String, number: Int): String {
        val p = normalizePrefix(prefix)
        val n = number.coerceIn(NUMBER_MIN, NUMBER_MAX)
        return p + n.toString().padStart(2, '0')
    }

    /** 前缀归一化：去连字符、转小写；长度不足 30 时用默认前缀（避免拼出非法 UUID）。 */
    fun normalizePrefix(prefix: String): String {
        val clean = prefix.replace("-", "").trim().lowercase()
        return when {
            clean.length == PREFIX_LEN && clean.all { isHex(it) } -> clean
            else -> DEFAULT_UUID_PREFIX
        }
    }

    /**
     * 32 字符 hex → 16 字节。
     * @throws IllegalArgumentException 长度或字符非法（调用方已在上一层校验，此处仍防御）
     */
    fun uuidHexToBytes(hex: String): ByteArray {
        val clean = hex.replace("-", "").trim().lowercase()
        require(clean.length == 32) { "UUID 必须是 32 个 hex 字符，实际 ${clean.length}" }
        require(clean.all { isHex(it) }) { "UUID 含非 hex 字符" }
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /**
     * 组 iBeacon 厂商数据 payload（**不含公司 ID**，交给 `addManufacturerData` 自动补 0x004C）。
     *
     * 返回 23 字节：`02 15 <UUID16> <Major2> <Minor2> <TxPower1>`
     */
    fun buildIbeaconPayload(
        uuidHex: String,
        major: Int = DEFAULT_MAJOR,
        minor: Int = DEFAULT_MAJOR,
        measuredPower: Int = DEFAULT_MEASURED_POWER,
    ): ByteArray {
        val uuid = uuidHexToBytes(uuidHex)
        return ByteArray(PAYLOAD_LEN).apply {
            this[0] = IBEACON_TYPE.toByte()
            this[1] = IBEACON_LEN.toByte()
            System.arraycopy(uuid, 0, this, 2, 16)
            this[18] = (major shr 8).toByte()
            this[19] = (major and 0xFF).toByte()
            this[20] = (minor shr 8).toByte()
            this[21] = (minor and 0xFF).toByte()
            this[22] = measuredPower.toByte()
        }
    }

    /** 带连字符的标准 UUID 形式（8-4-4-4-12），仅供页面肉眼与 nRF Connect 比对。 */
    fun toDashed(uuidHex: String): String {
        val h = uuidHex.replace("-", "").lowercase()
        if (h.length != 32) return uuidHex
        return buildString(36) {
            append(h, 0, 8).append('-')
            append(h, 8, 12).append('-')
            append(h, 12, 16).append('-')
            append(h, 16, 20).append('-')
            append(h, 20, 32)
        }
    }

    /** 调试用：payload → 小写 hex（带空格分组，便于与对端串口日志比对）。 */
    fun payloadHex(payload: ByteArray): String =
        payload.joinToString(" ") { "%02x".format(it) }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'
}
