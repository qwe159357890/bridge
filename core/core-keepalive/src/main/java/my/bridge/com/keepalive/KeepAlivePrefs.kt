package my.bridge.com.keepalive

import android.content.Context
import android.content.SharedPreferences

/**
 * 保活模块的配置开关管理。
 *
 * 借鉴 Aurelay（分层开关）+ SmsForwarder（电池白名单引导）的思路，
 * 把每层保活手段做成独立开关，默认给出推荐值。
 */
object KeepAlivePrefs {

    private const val SP_NAME = "keep_alive_config"

    const val KEY_KEEP_ALIVE_ALWAYS = "keep_alive_always"
    const val KEY_KEEP_ALIVE_ONE_PIXEL = "keep_alive_one_pixel"
    const val KEY_KEEP_ALIVE_SILENT_PLAY = "keep_alive_silent_play"
    const val KEY_KEEP_ALIVE_FALLBACK = "keep_alive_fallback"
    const val KEY_KEEP_ALIVE_RESOURCE = "keep_alive_resource"
    const val KEY_KEEP_ALIVE_BOOT = "keep_alive_boot"

    /** 默认配置：一像素默认关闭（避免系统悬浮窗提示），其余核心手段默认开启 */
    private val defaults = mapOf(
        KEY_KEEP_ALIVE_ALWAYS to true,
        KEY_KEEP_ALIVE_ONE_PIXEL to false,
        KEY_KEEP_ALIVE_SILENT_PLAY to true,
        KEY_KEEP_ALIVE_FALLBACK to true,
        KEY_KEEP_ALIVE_RESOURCE to true,
        KEY_KEEP_ALIVE_BOOT to true,
    )

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun getBoolean(context: Context, key: String): Boolean =
        sp(context).getBoolean(key, defaults[key] ?: false)

    fun setBoolean(context: Context, key: String, value: Boolean) {
        sp(context).edit().putBoolean(key, value).apply()
    }
}
