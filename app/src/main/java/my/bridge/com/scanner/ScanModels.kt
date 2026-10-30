package my.bridge.com.scanner

/**
 * 快递扫码数据模型（纯本地闭环 v1，照调研文档 §5.5「会话按自然日管理」）。
 *
 * 命名区分：本包 scanner/ = 快递扫码，switch/ = ESP32 继电器，relay/ = 音频中继。
 */

/** 一条扫码记录。 */
data class ScanRecord(
    val id: Long,
    val value: String,
    /** 码制名（CODE_128 / QR_CODE / MANUAL…），与 ScanPrefs 支持的码制名一致。 */
    val format: String,
    val timestamp: Long,
    /** true = 手动输入（非相机识别）。 */
    val manual: Boolean = false,
)

/**
 * 一个扫描会话。
 *
 * v0.1.95 起会话支持自由增删改（用户拍板放开「每天一个」限制）：
 * - [id] = 一等键（UUID），旧数据迁移时用 [date] 兜底；
 * - [date] = 创建日期（yyyy-MM-dd），用于分组展示与「今日会话」判定；
 * - [name] = 可编辑名称（创建时默认「10月30日」这类日期文本）。
 */
data class ScanSession(
    /** 会话唯一键。v0.1.94 旧档缺失时由 ScanStore 迁移为 date。 */
    val id: String? = null,
    /** 创建日期 yyyy-MM-dd。 */
    val date: String,
    val name: String,
    val createdAt: Long,
    val records: List<ScanRecord>,
)

/** 会话一等键（id 缺失时退回 date，兼容旧档反序列化中间态）。 */
val ScanSession.key: String get() = id ?: date

/** 去重策略（§6.6 五种；ADJACENT 为默认）。 */
enum class DedupStrategy {
    /** 相邻去重：只忽略与上一条相同的值。 */
    ADJACENT,
    /** 询问：弹窗 接受/丢弃。 */
    ASK,
    /** 总是接受。 */
    ALWAYS_ACCEPT,
    /** 会话内全去重：今日会话出现过即忽略。 */
    SESSION_ALL,
    /** 全局去重：所有历史会话出现过即忽略（Bridge 自研）。 */
    GLOBAL_ALL;

    val label: String
        get() = when (this) {
            ADJACENT -> "相邻去重（推荐）"
            ASK -> "询问我"
            ALWAYS_ACCEPT -> "总是接受"
            SESSION_ALL -> "会话内全去重"
            GLOBAL_ALL -> "全局去重"
        }
}

/** 支持的码制白名单（ML Kit 格式名），默认全选。 */
object ScanFormats {
    const val CODE_128 = "CODE_128"
    const val CODE_39 = "CODE_39"
    const val ITF = "ITF"
    const val QR_CODE = "QR_CODE"
    const val DATA_MATRIX = "DATA_MATRIX"
    const val PDF417 = "PDF417"

    val ALL: List<String> = listOf(CODE_128, CODE_39, ITF, QR_CODE, DATA_MATRIX, PDF417)

    /** UI 显示名。 */
    fun display(format: String): String = when (format) {
        MANUAL -> "手动"
        else -> format.replace('_', '-')
    }

    const val MANUAL = "MANUAL"
}
