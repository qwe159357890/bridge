package my.bridge.com.immich

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 时间线三级精细分组（用户拍板 D7，2026-11-16；区别于原版单选 日/月/无）：
 * - 今天 → 分组头「今天」；
 * - 最近一周逐天（昨天起共 7 天）→ 头文案「M月D日 星期X」；
 * - 更早一律按月 → 头文案「yyyy年M月」。
 *
 * 全部资产网格（主时间线 / 相册详情 / 分类结果 / 人物页）共用本套分组。
 * 拖动条（scrubber）仍按月聚合：今天与一周内各天归属当月。
 */
object ImmichTimeline {

    private const val NTAG = "相册"

    /** 星期文案表（星期一 ~ 星期日，dayOfWeek 1=周一）。 */
    private val weekNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 分组段：标签 + 计数文案 + 月份标签（拖动条定位用）+ 资产列表。 */
    data class ImmichGroup(
        val label: String,
        val countLabel: String,
        val monthLabel: String,
        val assets: List<ImmichAsset>,
    )

    /**
     * 三级精细分组：把按 localDateTime 降序的资产列表切成分组段。
     *
     * :param assets: 资产列表（服务端 order=desc 已按拍摄时间降序返回）
     * :param today: 「今天」基准日（默认当天，测试可注入）
     * :return: 分组段列表（时间降序排列）
     */
    fun group(assets: List<ImmichAsset>, today: LocalDate = LocalDate.now()): List<ImmichGroup> {
        val buckets = LinkedHashMap<String, MutableList<ImmichAsset>>()
        val labels = LinkedHashMap<String, String>()
        for (asset in assets) {
            val date = parseDate(asset.localDateTime)
            val (key, label) = when {
                // 一周内逐天（0=今天，1~6=昨天起共 7 天）
                date != null && !date.isBefore(today.minusDays(6)) -> {
                    val label = when (date) {
                        today -> "今天"
                        else -> "${date.monthValue}月${date.dayOfMonth}日 ${weekNames[date.dayOfWeek.value - 1]}"
                    }
                    date.toString() to label
                }
                // 更早按月（date 为 null（无拍摄时间）也归「未标注时间」组，文案按月样式）
                date != null -> "${date.year}年${date.monthValue}月" to "${date.year}年${date.monthValue}月"
                else -> "unknown" to "未标注时间"
            }
            buckets.getOrPut(key) { mutableListOf() }.add(asset)
            labels.getOrPut(key) { label }
        }
        val groups = buckets.map { (key, list) ->
            val label = labels[key] ?: key
            ImmichGroup(
                label = label,
                countLabel = "${list.size} 个",
                monthLabel = monthLabelOfKey(key, list),
                assets = list,
            )
        }
        return groups
    }

    /**
     * 分组段所属月份标签（拖动条高亮/跳转定位用）。
     * 「今天」与一周内各天取真实日期的「M月」；按月组取自身标签的「M月」段。
     *
     * :param key: 分组键（ISO 日期或 yyyy年M月）
     * :param assets: 该组资产（key 无法解析时从首资产兜底）
     * :return: 月份标签（如「11月」）
     */
    private fun monthLabelOfKey(key: String, assets: List<ImmichAsset>): String {
        // 逐天组：key 为 ISO 日期（2026-11-16）
        Regex("""\d{4}-(\d{2})-\d{2}""").find(key)?.let { return "${it.groupValues[1].toInt()}月" }
        // 月度组：key 为 yyyy年M月
        Regex("""(\d+)年(\d+)月""").find(key)?.let { return "${it.groupValues[2].toInt()}月" }
        // 兜底：从首资产日期取月份
        assets.firstOrNull()?.let { parseDate(it.localDateTime)?.let { d -> return "${d.monthValue}月" } }
        return "未知"
    }

    /**
     * 解析资产拍摄时间字符串为本地日期（墙钟口径：localDateTime 是拍摄地本地时间，
     * 服务端以 Z 后缀序列化，直接取分量、不做时区换算）。
     *
     * :param raw: ISO 时间串（如 2026-11-12T23:42:55.000Z）
     * :return: 本地日期；解析失败返回 null
     */
    fun parseDate(raw: String): LocalDate? = try {
        OffsetDateTime.parse(raw).toLocalDate()
    } catch (e: Exception) {
        null
    }

    /**
     * 解析拍摄时间为 LocalDateTime（查看器顶栏时分展示用）。
     *
     * :param raw: ISO 时间串
     * :return: 本地时间；解析失败返回 null
     */
    fun parseDateTime(raw: String): java.time.LocalDateTime? = try {
        OffsetDateTime.parse(raw).toLocalDateTime()
    } catch (e: Exception) {
        null
    }

    /**
     * 格式化资产拍摄时间为「yyyy年M月d日 HH:mm」（查看器顶栏 / EXIF 面板标题）。
     *
     * :param asset: 资产
     * :return: 格式化文案；无时间返回「时间未知」
     */
    fun dateText(asset: ImmichAsset): String {
        val dt = parseDateTime(asset.localDateTime) ?: return "时间未知"
        val df = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
        return dt.format(df)
    }

    /**
     * 格式化资产拍摄时间为「yyyy年M月d日 HH:mm:ss」（EXIF 面板副行）。
     *
     * :param asset: 资产
     * :return: 格式化文案
     */
    fun dateTextFull(asset: ImmichAsset): String {
        val dt = parseDateTime(asset.localDateTime) ?: return "时间未知"
        val df = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss")
        return dt.format(df)
    }

    /**
     * 分组头全局索引表（LazyVerticalGrid 跳转定位用）：每组 = 1 头 + tiles 块，
     * 返回每组第一个条目（头）在网格中的全局序号。
     *
     * :param groups: 分组段列表
     * :return: 各组头全局序号列表
     */
    fun headerIndices(groups: List<ImmichGroup>): List<Int> =
        groups.runningFold(0) { acc, g -> acc + 1 + g.assets.size }.dropLast(1)
}
