package my.bridge.com.ui

import android.content.Context

/**
 * 「切到后台立即结束进程」开关持久化（v0.2.27）。
 *
 * 打开后：APP 一旦切到后台（所有 Activity 都已 stop），立刻结束整个进程——
 * 见 `my.bridge.com.AppExit.kill`。
 *
 * 默认 **false**：这与 Bridge 的保活定位相反（保活就是要在后台常驻收消息），
 * 误开的代价远大于收益，必须用户显式打开。
 *
 * 存储沿用 `ui_prefs`（与 [BackPressPrefs] 同一份 SharedPreferences），
 * 读取失败一律回默认 false（宁可不明杀进程）。
 */
object AppExitPrefs {

    private const val SP_NAME = "ui_prefs"
    private const val KEY_KILL_ON_BACKGROUND = "kill_on_background"

    /** 是否在切到后台时立即结束进程（默认 false）。 */
    fun killOnBackground(context: Context): Boolean =
        runCatching {
            context.applicationContext
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KILL_ON_BACKGROUND, false)
        }.getOrDefault(false)

    fun setKillOnBackground(context: Context, on: Boolean) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_KILL_ON_BACKGROUND, on)
                .apply()
        }
    }
}
