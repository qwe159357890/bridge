package my.bridge.com.ui

import android.content.Context

/**
 * 主页顶部页面排序持久化（v0.2.08；v0.2.30 扩至 5 页新增「组网」；
 * v0.2.55 扩至 6 页新增「极空」）。
 *
 * 设计：页面 id 恒定（0=短信 1=音频中继 2=智能控制 3=快递扫码 4=组网 5=极空，
 * 与 MainScreen 的 when 分支一一对应，永不增删改语义）；可配置的只有「显示顺序」
 * tabOrder——tabOrder[位置] = 页面 id。默认 [0,1,2,3,4,5]，新页固定排在末尾。
 *
 * 安全边界（保证不破坏现有功能）：
 * - 读取时严格校验（长度=页面数、元素∈合法 id、无重复），任何异常回退默认顺序；
 * - ★ 版本迁移：旧版本存的页数少于当前页数时，保留原顺序、末尾追加新页 id 并回写，
 *   避免丢失老用户的自定义排序与改名；
 * - 只存顺序，不碰任何页面组件/业务逻辑。
 */
object TabOrderPrefs {

    /** 页面 id → 标题（单一来源，HomePage 与设置页共用）。 */
    val PAGE_TITLES = listOf("短信", "音频中继", "智能控制", "快递扫码", "组网", "极空")

    /** 页面 id → 图标（设置页排序编辑器用）。 */
    val PAGE_ICONS = listOf("💬", "🔊", "🔌", "📦", "🕸️", "🔋")

    private const val KEY = "home_tab_order"
    private const val DEFAULT = "0,1,2,3,4,5"

    fun load(context: Context): List<Int> {
        val sp = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return PAGE_TITLES.indices.toList()
        // ★ 版本迁移：旧值页数少于当前页数（如 4 页/5 页）时，保留用户自定义顺序、
        //   末尾追加缺失的新页 id 并回写，避免每次冷启动都重复迁移。
        val legacy = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (legacy.isNotEmpty() && legacy.size < PAGE_TITLES.size &&
            legacy.distinct().size == legacy.size &&
            legacy.all { it in PAGE_TITLES.indices }
        ) {
            val existing = legacy.toSet()
            val missing = PAGE_TITLES.indices.filter { it !in existing }
            val migrated = legacy + missing
            sp.edit().putString(KEY, migrated.joinToString(",")).apply()
            return migrated
        }
        return parse(raw)
    }

    fun save(context: Context, order: List<Int>) {
        if (!isValid(order)) return
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, order.joinToString(",")).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, DEFAULT).apply()
    }

    private fun isValid(order: List<Int>): Boolean =
        order.size == PAGE_TITLES.size && order.toSet() == PAGE_TITLES.indices.toSet()

    private fun parse(raw: String): List<Int> = runCatching {
        val order = raw.split(",").map { it.trim().toInt() }
        if (isValid(order)) order else PAGE_TITLES.indices.toList()
    }.getOrDefault(PAGE_TITLES.indices.toList())

    // ---- 自定义名称（v0.2.08）：按页面 id 序存 JSON 数组，空串=用默认名 ----

    private const val KEY_NAMES = "home_tab_names"
    private const val NAME_MAX = 8

    /** 自定义名称上限（超长截断，Tab 条不换行且空间有限）。 */
    const val NAME_MAX_LEN = NAME_MAX

    /** 读取自定义名称：List[页面id]，异常/缺省回 ["","","",""]（全用默认名）。 */
    fun loadNames(context: Context): List<String> = runCatching {
        val raw = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .getString(KEY_NAMES, null) ?: return@runCatching PAGE_TITLES.map { "" }
        val arr = org.json.JSONArray(raw)
        List(PAGE_TITLES.size) { i ->
            if (i < arr.length()) arr.optString(i).take(NAME_MAX) else ""
        }
    }.getOrDefault(PAGE_TITLES.map { "" })

    /** 保存自定义名称（空串=恢复该页默认名）。 */
    fun saveNames(context: Context, names: List<String>) {
        if (names.size != PAGE_TITLES.size) return
        val arr = org.json.JSONArray()
        names.forEach { arr.put(it.trim().take(NAME_MAX)) }
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY_NAMES, arr.toString()).apply()
    }

    /** 显示名 = 自定义名（非空）否则默认名。 */
    fun displayName(names: List<String>, pageId: Int): String =
        names.getOrNull(pageId)?.takeIf { it.isNotBlank() } ?: PAGE_TITLES[pageId]
}
