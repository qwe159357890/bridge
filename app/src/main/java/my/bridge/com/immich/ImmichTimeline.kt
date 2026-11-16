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

    /** 分组段：标签 + 计数文案 + 月份标签（拖动条定位用）+ 月份键（yyyy-MM）+ 资产列表。 */
    data class ImmichGroup(
        val label: String,
        val countLabel: String,
        val monthLabel: String,
        val monthKey: String,
        val assets: List<ImmichAsset>,
    )

    /** 拖动条条目：显示文案 + 月份键 + 已加载分组序号（null = 未加载月份，点击触发按月加载）。 */
    data class ImmichScrubItem(
        val label: String,
        val monthKey: String,
        val groupIndex: Int?,
        val count: Int,
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
        val monthKeys = LinkedHashMap<String, String>()
        for (asset in assets) {
            val date = parseDate(asset.localDateTime)
            val (key, label, monthKey) = when {
                // 一周内逐天（0=今天，1~6=昨天起共 7 天）
                date != null && !date.isBefore(today.minusDays(6)) -> {
                    val label = when (date) {
                        today -> "今天"
                        else -> "${date.monthValue}月${date.dayOfMonth}日 ${weekNames[date.dayOfWeek.value - 1]}"
                    }
                    Triple(date.toString(), label, date.toString().take(7))
                }
                // 更早按月（date 为 null（无拍摄时间）也归「未标注时间」组，文案按月样式）
                date != null -> Triple(
                    "${date.year}年${date.monthValue}月",
                    "${date.year}年${date.monthValue}月",
                    "%04d-%02d".format(date.year, date.monthValue),
                )
                else -> Triple("unknown", "未标注时间", "")
            }
            buckets.getOrPut(key) { mutableListOf() }.add(asset)
            labels.getOrPut(key) { label }
            monthKeys.getOrPut(key) { monthKey }
        }
        val groups = buckets.map { (key, list) ->
            val label = labels[key] ?: key
            ImmichGroup(
                label = label,
                countLabel = "${list.size} 个",
                monthLabel = monthLabelOfKey(key, list, today),
                monthKey = monthKeys[key] ?: "",
                assets = list,
            )
        }
        return groups
    }

    /**
     * 分组段所属月份显示标签（拖动条高亮/跳转定位用；跨年月份带年份前缀）。
     *
     * :param key: 分组键（ISO 日期或 yyyy年M月）
     * :param assets: 该组资产（key 无法解析时从首资产兜底）
     * :param today: 当前日期（判断是否跨年）
     * :return: 月份显示标签（如今年「11月」、往年「2025年12月」）
     */
    private fun monthLabelOfKey(key: String, assets: List<ImmichAsset>, today: LocalDate): String {
        // 逐天组：key 为 ISO 日期（2026-11-16）
        Regex("""(\d{4})-(\d{2})-\d{2}""").find(key)?.let {
            return monthDisplayLabel("${it.groupValues[1]}-${it.groupValues[2]}", today)
        }
        // 月度组：key 为 yyyy年M月
        Regex("""(\d+)年(\d+)月""").find(key)?.let {
            return monthDisplayLabel("%04d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt()), today)
        }
        // 兜底：从首资产日期取月份
        assets.firstOrNull()?.let { a ->
            parseDate(a.localDateTime)?.let {
                return monthDisplayLabel("%04d-%02d".format(it.year, it.monthValue), today)
            }
        }
        return "未知"
    }

    /**
     * 月份键转显示标签：今年「M月」，往年「yyyy年M月」。
     *
     * :param monthKey: 月份键（yyyy-MM）
     * :param today: 当前日期
     * :return: 显示标签
     */
    fun monthDisplayLabel(monthKey: String, today: LocalDate = LocalDate.now()): String {
        val y = monthKey.take(4).toIntOrNull() ?: return monthKey
        val m = monthKey.drop(3).toIntOrNull() ?: return monthKey
        return if (y == today.year) "${m}月" else "${y}年${m}月"
    }

    /**
     * 构造拖动条固定全量条目：已加载分组（今天 / 一周内逐天 MM-dd / 已加载月份）+
     * 服务端月份分布中尚未加载的月份（groupIndex = null，点击触发按月加载）。
     *
     * :param groups: 当前已加载的分组段
     * :param bucketList: 服务端全量月份分布（降序）
     * :param today: 当前日期
     * :return: 拖动条条目（时间降序）
     */
    fun scrubItems(
        groups: List<ImmichGroup>,
        bucketList: List<ImmichMonthBucket>,
        today: LocalDate = LocalDate.now(),
    ): List<ImmichScrubItem> {
        val out = mutableListOf<ImmichScrubItem>()
        val seenMonths = HashSet<String>()
        // 已加载分组逐个映射：今天 / 一周内逐天（MM-dd）/ 已加载月份
        groups.forEachIndexed { gi, g ->
            if (g.monthKey.isNotBlank()) seenMonths.add(g.monthKey)
            val dayMatch = Regex("""(\d+)月(\d+)日""").find(g.label)
            val item = when {
                g.label == "今天" -> ImmichScrubItem("今天", g.monthKey, gi, g.assets.size)
                dayMatch != null -> ImmichScrubItem(
                    "${dayMatch.groupValues[1]}-${dayMatch.groupValues[2]}",
                    g.monthKey,
                    gi,
                    g.assets.size,
                )
                else -> ImmichScrubItem(monthDisplayLabel(g.monthKey, today), g.monthKey, gi, g.assets.size)
            }
            out.add(item)
        }
        // 服务端全量分布中尚未加载的月份（点击触发按月加载）
        bucketList.forEach { b ->
            if (b.monthKey !in seenMonths && b.count > 0) {
                out.add(ImmichScrubItem(monthDisplayLabel(b.monthKey, today), b.monthKey, null, b.count))
            }
        }
        return out
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
