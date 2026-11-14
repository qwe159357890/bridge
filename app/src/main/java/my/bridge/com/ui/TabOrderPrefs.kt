package my.bridge.com.ui

import android.content.Context

/**
 * 主页顶部页面排序持久化（v0.2.08；v0.2.30 扩至 5 页新增「组网」；
 * v0.2.55 扩至 6 页新增「极空」；v0.2.145 「组网」移到底部工具栏，主页回 5 页；
 * v0.2.147 「组网」回归主页 + 新增「监控」页，主页扩至 7 页）。
 *
 * 设计：页面 id 恒定（0=短信 1=音频中继 2=智能控制 3=快递扫码 4=组网 5=监控 6=极空 7=管理，
 * 与 MainScreen 的 when 分支一一对应，永不增删改语义）；可配置的只有「显示顺序」
 * tabOrder——tabOrder[位置] = 页面 id。默认 [0,1,2,3,4,5,6,7]，新页固定排在末尾。
 *
 * 注：历史 id 变迁——
 * - v0.2.145 前：id=4 组网、id=5 极空（6 页）；
 * - v0.2.145：组网移底部工具栏，极空 id 5→4（5 页）；
 * - v0.2.147：组网回归主页（id=4）+ 新增监控（id=5），极空 4→6（7 页）；
 * - v0.2.15x：「管理」从底部工具栏移入主页（id=7，末尾），主页扩至 8 页。
 *
 * 安全边界（保证不破坏现有功能）：
 * - 读取时严格校验（长度=页面数、元素∈合法 id、无重复），任何异常回退默认顺序；
 * - ★ 版本迁移：旧版本存的页集与当前不一致时（含极空 4→6 重排、新增组网/监控），
 *   自动重排极空 id、末尾追加缺失页 id 并回写，保留用户自定义顺序；
 * - 只存顺序，不碰任何页面组件/业务逻辑。
 */
object TabOrderPrefs {

    /** 页面 id → 标题（单一来源，HomePage 与设置页共用）。 */
    val PAGE_TITLES = listOf("短信", "音频中继", "智能控制", "快递扫码", "组网", "监控", "极空", "管理")

    /** 页面 id → 图标（设置页排序编辑器用）。 */
    val PAGE_ICONS = listOf("💬", "🔊", "🔌", "📦", "🕸️", "🎥", "🔋", "🛠️")

    private const val KEY = "home_tab_order"
    private const val DEFAULT = "0,1,2,3,4,5,6,7"

    fun load(context: Context): List<Int> {
        val sp = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return PAGE_TITLES.indices.toList()
        // ★ 版本迁移：旧值页数 < 当前页数时，末尾追加缺失的页 id 并回写，保留用户自定义顺序。
        //   当前形态（8 页）：旧 7 页（v0.2.147 的 0..6，无「管理」）→ 末尾补 id=7。
        //   更早的极空重排（旧 id=4/5 时代）已被上一版迁移消化，这里不再单独处理。
        val legacy = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (legacy.isNotEmpty() && legacy.size < PAGE_TITLES.size) {
            // 保留旧值中仍是合法 id 的页（按原顺序），末尾追加缺失 id（保证新页「管理」排最后）。
            val existing = legacy.filter { it in PAGE_TITLES.indices }
            val missing = PAGE_TITLES.indices.filter { it !in existing }
            val full = (existing + missing).distinct()
            sp.edit().putString(KEY, full.joinToString(",")).apply()
            return full
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
        val sp = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_NAMES, null) ?: return@runCatching PAGE_TITLES.map { "" }
        val arr = org.json.JSONArray(raw)
        // ★ 版本迁移：旧值页数 < 当前 8 页时，保留 0..6 原名，新增的「管理」(index=7) 用空名（默认名），并回写。
        if (arr.length() < PAGE_TITLES.size) {
            val migrated = mutableListOf<String>()
            for (i in 0 until PAGE_TITLES.size) {
                migrated.add(
                    if (i < arr.length()) arr.optString(i).take(NAME_MAX) else ""
                )
            }
            sp.edit().putString(KEY_NAMES, org.json.JSONArray(migrated.toList()).toString()).apply()
            return@runCatching migrated
        }
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
