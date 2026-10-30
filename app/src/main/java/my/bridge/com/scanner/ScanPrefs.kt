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

    // 进入快递扫码页时的入口（v0.1.95）：ENTRY_SCAN = 直接扫码页，ENTRY_LIST = 会话列表。
    // 兼容迁移：无 entry_mode 记录时沿用旧 auto_enter_today 开关（true→scan / false→list）。
    const val KEY_ENTRY_MODE = "scan_entry_mode"
    const val ENTRY_SCAN = "scan"
    const val ENTRY_LIST = "list"

    // 同码冷却窗口（v0.1.95）：同一单号处理过之后 N 毫秒内再次识别 → 静默忽略，
    // 先于去重策略执行、对所有策略生效（工业扫描枪 same-symbol timeout 惯例）。
    const val KEY_COOLDOWN_MS = "scan_cooldown_ms"
    const val DEFAULT_COOLDOWN_MS = 2500L

    // 旧开关（v0.1.94「打开自动进入今日会话」），仅作 entry_mode 迁移兜底，不再直接使用
    const val KEY_AUTO_ENTER_TODAY = "scan_auto_enter_today"

    // 去重策略（DedupStrategy.name）
    const val KEY_DEDUP = "scan_dedup"

    // 码制白名单（StringSet，值为 ScanFormats.ALL 子集）
    const val KEY_FORMATS = "scan_formats"

    const val KEY_SOUND = "scan_sound"
    const val KEY_VIBRATION = "scan_vibration"

    // 上传到 PC（v0.2.02，扩展 Barcode to PC 协议）：开关 + 目标地址「IP:端口」
    const val KEY_UPLOAD_ENABLED = "scan_upload_enabled"
    const val KEY_UPLOAD_HOST = "scan_upload_host"
    const val DEFAULT_UPLOAD_PORT = 57891

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 进入扫码页时的入口：ENTRY_SCAN（直接扫码）/ ENTRY_LIST（会话列表）。 */
    fun entryMode(context: Context): String {
        val saved = sp(context).getString(KEY_ENTRY_MODE, null)
        if (saved != null) return saved
        return if (sp(context).getBoolean(KEY_AUTO_ENTER_TODAY, true)) ENTRY_SCAN else ENTRY_LIST
    }

    fun setEntryMode(context: Context, value: String) {
        sp(context).edit().putString(KEY_ENTRY_MODE, value).apply()
    }

    /** 同码冷却窗口毫秒数。 */
    fun cooldownMs(context: Context): Long =
        sp(context).getInt(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS.toInt()).toLong()

    fun setCooldownMs(context: Context, value: Long) {
        sp(context).edit().putInt(KEY_COOLDOWN_MS, value.toInt()).apply()
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

    /** 上传到 PC 总开关（v0.2.02）。 */
    fun uploadEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_UPLOAD_ENABLED, false)

    fun setUploadEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_UPLOAD_ENABLED, value).apply()
    }

    /** 上传目标地址，原始字符串（用户输入，形如 192.168.31.5:57891 或纯 IP）。 */
    fun uploadHost(context: Context): String = sp(context).getString(KEY_UPLOAD_HOST, "") ?: ""

    fun setUploadHost(context: Context, value: String) {
        sp(context).edit().putString(KEY_UPLOAD_HOST, value.trim()).apply()
    }

    /** 解析为 ws:// URL；无效输入返回 null。容忍「纯 IP」「IP:端口」「ws://…」三种写法。 */
    fun uploadUrl(context: Context): String? {
        val raw = uploadHost(context).trim()
        if (raw.isEmpty()) return null
        val withScheme = when {
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            raw.contains(":") -> "ws://$raw"
            else -> "ws://$raw:$DEFAULT_UPLOAD_PORT"
        }
        return runCatching { java.net.URI(withScheme) }
            .getOrNull()
            ?.takeIf { it.host != null && it.port != -1 }
            ?.toString()
    }
}
