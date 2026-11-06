package my.bridge.com.jikong

import android.content.Context
import android.content.SharedPreferences

/**
 * 极空页面显示偏好持久化。
 *
 * 背景主题三态：
 *  - THEME_AUTO（默认）：跟随当前时间——白天（6:00~18:00）浅色、夜晚深色；
 *  - THEME_LIGHT：强制浅色；
 *  - THEME_DARK：强制深色。
 *
 * 优先级：用户手动指定（LIGHT/DARK）高于自动（AUTO）。AUTO 才跟随时间。
 */
object JikongPrefs {

    private const val SP_NAME = "jikong_config"

    const val KEY_THEME = "jikong_theme"

    /** 时速仪表盘开关（v0.2.83）：开=首页显示时速/续航卡片并启动 GPS；关=完全零成本。 */
    const val KEY_DASH_ENABLED = "jikong_dash_enabled"

    /** 累计统计（SpeedDash 持久化）：总里程 km / 总耗电 Wh / 历史平均电耗 Wh/km（暖启动种子）。 */
    private const val KEY_TOTAL_KM = "jikong_total_km"
    private const val KEY_TOTAL_WH = "jikong_total_wh"
    private const val KEY_HIST_AVG = "jikong_hist_avg_whkm"

    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 背景主题模式（THEME_AUTO/LIGHT/DARK）。默认自动。 */
    fun theme(context: Context): Int =
        sp(context).getInt(KEY_THEME, THEME_AUTO)

    fun setTheme(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_THEME, mode.coerceIn(THEME_AUTO, THEME_DARK)).apply()
    }

    /** 时速仪表盘开关。默认关（尊重省电，用户显式打开才启用 GPS）。 */
    fun dashEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_DASH_ENABLED, false)

    fun setDashEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DASH_ENABLED, enabled).apply()
    }

    fun totalKm(context: Context): Float = sp(context).getFloat(KEY_TOTAL_KM, 0f)
    fun setTotalKm(context: Context, km: Float) {
        sp(context).edit().putFloat(KEY_TOTAL_KM, km.coerceAtLeast(0f)).apply()
    }

    fun totalWh(context: Context): Float = sp(context).getFloat(KEY_TOTAL_WH, 0f)
    fun setTotalWh(context: Context, wh: Float) {
        sp(context).edit().putFloat(KEY_TOTAL_WH, wh.coerceAtLeast(0f)).apply()
    }

    /** 历史平均电耗（Wh/km）。<0 = 还没有可用历史。 */
    fun histAvgWhKm(context: Context): Float = sp(context).getFloat(KEY_HIST_AVG, -1f)
    fun setHistAvgWhKm(context: Context, whkm: Float) {
        sp(context).edit().putFloat(KEY_HIST_AVG, whkm).apply()
    }
}
