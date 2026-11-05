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
}
