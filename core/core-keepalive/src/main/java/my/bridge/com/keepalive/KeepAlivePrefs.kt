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
    // 在最近任务列表中隐藏（防上滑/一键清理误杀，借鉴 SmsForwarder enableExcludeFromRecents）
    const val KEY_KEEP_ALIVE_HIDE_RECENTS = "keep_alive_hide_recents"

    // 「后台资源占用」的 8 类细分资源开关（受总开关 KEY_KEEP_ALIVE_RESOURCE 约束）
    const val KEY_RES_WAKELOCK = "keep_alive_res_wakelock"
    const val KEY_RES_WIFILOCK = "keep_alive_res_wifilock"
    const val KEY_RES_NETWORK = "keep_alive_res_network"
    const val KEY_RES_LOCATION = "keep_alive_res_location"
    const val KEY_RES_SENSOR = "keep_alive_res_sensor"
    const val KEY_RES_BLUETOOTH = "keep_alive_res_bluetooth"
    const val KEY_RES_CAMERA = "keep_alive_res_camera"
    const val KEY_RES_AUDIO = "keep_alive_res_audio"

    /** 默认配置：一像素默认关闭（避免系统悬浮窗提示），其余核心手段默认开启 */
    private val defaults = mapOf(
        KEY_KEEP_ALIVE_ALWAYS to true,
        KEY_KEEP_ALIVE_ONE_PIXEL to false,
        KEY_KEEP_ALIVE_SILENT_PLAY to true,
        KEY_KEEP_ALIVE_FALLBACK to true,
        KEY_KEEP_ALIVE_RESOURCE to true,
        KEY_KEEP_ALIVE_BOOT to true,
        // 最近任务隐藏默认开启：Bridge 是受控端，防误杀优先于用户从最近任务快速切换
        KEY_KEEP_ALIVE_HIDE_RECENTS to true,
        // 8 类细分资源默认全开
        KEY_RES_WAKELOCK to true,
        KEY_RES_WIFILOCK to true,
        KEY_RES_NETWORK to true,
        KEY_RES_LOCATION to true,
        KEY_RES_SENSOR to true,
        KEY_RES_BLUETOOTH to true,
        KEY_RES_CAMERA to true,
        KEY_RES_AUDIO to true,
    )

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun getBoolean(context: Context, key: String): Boolean =
        sp(context).getBoolean(key, defaults[key] ?: false)

    fun setBoolean(context: Context, key: String, value: Boolean) {
        sp(context).edit().putBoolean(key, value).apply()
    }
}
