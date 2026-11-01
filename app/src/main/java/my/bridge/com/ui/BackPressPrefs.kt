package my.bridge.com.ui

import android.content.Context

/**
 * 返回键「二次确认」窗口持久化（v0.2.26）。
 *
 * 改前：两处窗口都是**硬编码 2000ms**——
 * - `ui/MainScreen.kt`：主界面「再按一次返回退出 Bridge」；
 * - `ui/WechatPages.kt` `BACK_CONFIRM_WINDOW_MS`：微信页「再按一次返回主页」（销售页同款后沿用）。
 *
 * 现在两项都可自定义（设置 → 通用 → 返回键），**默认仍是 2000ms**，与历史行为完全一致。
 *
 * 安全边界：读取时钳制到 [MIN_MS, MAX_MS]，异常/缺省回 [DEFAULT_WINDOW_MS]，
 * 只影响提示窗口时长，不触碰任何页面切换与生命周期逻辑。
 */
object BackPressPrefs {

    /** 默认窗口（毫秒）——与改前硬编码值一致。 */
    const val DEFAULT_WINDOW_MS = 2000L

    private const val MIN_MS = 1000L
    private const val MAX_MS = 5000L

    private const val SP_NAME = "ui_prefs"
    private const val KEY_EXIT_MS = "back_exit_window_ms"
    private const val KEY_HOME_MS = "back_home_window_ms"

    /** 主界面「再按一次返回退出 Bridge」的窗口（毫秒）。 */
    fun exitWindowMs(context: Context): Long =
        read(context, KEY_EXIT_MS)

    fun setExitWindowMs(context: Context, ms: Long) = write(context, KEY_EXIT_MS, ms)

    /** 微信页/销售页「再按一次返回主页」的窗口（毫秒）。 */
    fun homeWindowMs(context: Context): Long =
        read(context, KEY_HOME_MS)

    fun setHomeWindowMs(context: Context, ms: Long) = write(context, KEY_HOME_MS, ms)

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): Long =
        runCatching { sp(context).getLong(key, DEFAULT_WINDOW_MS) }
            .getOrDefault(DEFAULT_WINDOW_MS)
            .coerceIn(MIN_MS, MAX_MS)

    private fun write(context: Context, key: String, ms: Long) {
        sp(context).edit().putLong(key, ms.coerceIn(MIN_MS, MAX_MS)).apply()
    }
}
