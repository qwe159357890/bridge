package my.bridge.com.scanner

import android.content.Context
import android.content.SharedPreferences

/**
 * 快递扫码配置持久化（照 RelaySwitchPrefs 模式：SharedPreferences 直存）。
 *
 * v1 纯本地闭环暂不含服务器地址（ScanUploader 下一版接入时再启用）。
 */
object ScanPrefs {

    private const val SP_NAME = "scan_config"

    // 打开页面自动进入今日会话（关 = 停在会话选择页手动进入）
    const val KEY_AUTO_ENTER_TODAY = "scan_auto_enter_today"

    // 去重策略（DedupStrategy.name）
    const val KEY_DEDUP = "scan_dedup"

    // 码制白名单（StringSet，值为 ScanFormats.ALL 子集）
    const val KEY_FORMATS = "scan_formats"

    const val KEY_SOUND = "scan_sound"
    const val KEY_VIBRATION = "scan_vibration"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun autoEnterToday(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_ENTER_TODAY, true)

    fun setAutoEnterToday(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_ENTER_TODAY, value).apply()
    }

    fun dedup(context: Context): DedupStrategy = runCatching {
        DedupStrategy.valueOf(sp(context).getString(KEY_DEDUP, DedupStrategy.ADJACENT.name)!!)
    }.getOrDefault(DedupStrategy.ADJACENT)

    fun setDedup(context: Context, value: DedupStrategy) {
        sp(context).edit().putString(KEY_DEDUP, value.name).apply()
    }

    /** 码制白名单（空集兜底为全选，避免用户清空后永远扫不出）。 */
    fun formats(context: Context): Set<String> {
        val saved = sp(context).getStringSet(KEY_FORMATS, null)
        return if (saved.isNullOrEmpty()) ScanFormats.ALL.toSet() else saved
    }

    fun setFormats(context: Context, value: Set<String>) {
        sp(context).edit().putStringSet(KEY_FORMATS, value).apply()
    }

    fun sound(context: Context): Boolean = sp(context).getBoolean(KEY_SOUND, true)

    fun setSound(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SOUND, value).apply()
    }

    fun vibration(context: Context): Boolean = sp(context).getBoolean(KEY_VIBRATION, true)

    fun setVibration(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_VIBRATION, value).apply()
    }
}
