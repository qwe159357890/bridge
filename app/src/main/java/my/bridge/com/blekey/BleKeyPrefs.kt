package my.bridge.com.blekey

import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import java.util.Calendar

/**
 * 蓝牙钥匙配置存储（SharedPreferences，照 JikongPrefs 范式）。
 *
 * 设计文档：docs/research/ble-key-broadcast.md §10、§12。
 * 注意：`number` 初始为空（null）——不能让两台新手机默认都变同一把钥匙（§4.3）。
 */
object BleKeyPrefs {

    private const val SP_NAME = "blekey_config"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_NUMBER = "number"
    private const val KEY_PREFIX = "uuid_prefix"
    private const val KEY_TX_POWER = "tx_power"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"
    private const val KEY_QUIET_NOTICE_ON = "quiet_notice_on"
    private const val KEY_QUIET_NOTICE_DATE = "quiet_notice_date"

    private const val NO_NUMBER = -1

    /** 静默时段默认值（用户可改，支持跨午夜）。 */
    const val DEFAULT_QUIET_START = "21:00"
    const val DEFAULT_QUIET_END = "05:00"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    // ---- 总开关 ----

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    // ---- 编号（00~99，未设置返回 null） ----

    fun number(ctx: Context): Int? =
        sp(ctx).getInt(KEY_NUMBER, NO_NUMBER).takeIf { it in BleKeyProtocol.NUMBER_MIN..BleKeyProtocol.NUMBER_MAX }

    fun setNumber(ctx: Context, number: Int) {
        sp(ctx).edit().putInt(KEY_NUMBER, number.coerceIn(BleKeyProtocol.NUMBER_MIN, BleKeyProtocol.NUMBER_MAX)).apply()
    }

    fun clearNumber(ctx: Context) {
        sp(ctx).edit().remove(KEY_NUMBER).apply()
    }

    // ---- 前缀（30 字符，高级可改） ----

    fun prefix(ctx: Context): String =
        sp(ctx).getString(KEY_PREFIX, null)?.takeIf { BleKeyProtocol.normalizePrefix(it) == it }
            ?: BleKeyProtocol.DEFAULT_UUID_PREFIX

    fun setPrefix(ctx: Context, prefix: String) {
        val clean = prefix.replace("-", "").trim().lowercase()
        sp(ctx).edit().putString(KEY_PREFIX, clean).apply()
    }

    // ---- 发射功率（存 AdvertiseSettings.ADVERTISE_TX_POWER_* 常量本身） ----

    fun txPower(ctx: Context): Int =
        sp(ctx).getInt(KEY_TX_POWER, AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)

    fun setTxPower(ctx: Context, level: Int) {
        sp(ctx).edit().putInt(KEY_TX_POWER, level).apply()
    }

    fun txPowerText(level: Int): String = when (level) {
        AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> "最低（约 -21dBm）"
        AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> "低（约 -15dBm）"
        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> "中（约 -7dBm，默认）"
        AdvertiseSettings.ADVERTISE_TX_POWER_HIGH -> "高（约 +1dBm）"
        else -> "未知"
    }

    // ---- 静默时段（只作用于后台慢速档；前台/极空已连接时不受限） ----

    fun quietEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_QUIET_ENABLED, true)

    fun setQuietEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_QUIET_ENABLED, on).apply()
    }

    fun quietStart(ctx: Context): String = sp(ctx).getString(KEY_QUIET_START, null) ?: DEFAULT_QUIET_START

    fun quietEnd(ctx: Context): String = sp(ctx).getString(KEY_QUIET_END, null) ?: DEFAULT_QUIET_END

    /** 保存起止时刻；格式非法则忽略（保持原值）。 */
    fun setQuietWindow(ctx: Context, start: String, end: String) {
        if (parseHm(start) == null || parseHm(end) == null) return
        if (start == end) return   // 跨度 0 视为整天静默，拒绝
        sp(ctx).edit().putString(KEY_QUIET_START, start).putString(KEY_QUIET_END, end).apply()
    }

    /** 当前是否落在静默时段内（支持跨午夜，如 21:00–05:00）。 */
    fun inQuietWindow(ctx: Context, now: Calendar = Calendar.getInstance()): Boolean {
        if (!quietEnabled(ctx)) return false
        val start = parseHm(quietStart(ctx)) ?: return false
        val end = parseHm(quietEnd(ctx)) ?: return false
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start <= end) cur in start until end else (cur >= start || cur < end)
    }

    /** "HH:MM" → 当日分钟数；非法返回 null。 */
    fun parseHm(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 静默时段在下一次生效前还剩多少分钟（不在静默期内才有意义；用于提前提醒）。 */
    fun minutesUntilQuietStart(ctx: Context, now: Calendar = Calendar.getInstance()): Int? {
        if (!quietEnabled(ctx) || inQuietWindow(ctx, now)) return null
        val start = parseHm(quietStart(ctx)) ?: return null
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val diff = if (start >= cur) start - cur else 24 * 60 - cur + start
        return diff
    }

    /**
     * 静默生效前是否发一次性提醒（默认开）。
     *
     * 砍掉「运动安全阀」之后，这条提醒是**唯一**让用户知道「今晚后台不再广播」的手段——
     * 否则夜里到车边不通电会被当成故障。
     */
    fun quietNoticeOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_QUIET_NOTICE_ON, true)

    fun setQuietNoticeOn(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_QUIET_NOTICE_ON, on).apply()
    }

    /** 已提醒过的日期（`yyyy-MM-dd`），用于每天最多提醒一次。 */
    fun quietNoticeDate(ctx: Context): String = sp(ctx).getString(KEY_QUIET_NOTICE_DATE, "") ?: ""

    fun setQuietNoticeDate(ctx: Context, date: String) {
        sp(ctx).edit().putString(KEY_QUIET_NOTICE_DATE, date).apply()
    }

    // ---- 派生 ----

    /** 完整 32 字符 UUID；编号未设置返回 null（此时不允许开播）。 */
    fun fullUuidOrNull(ctx: Context): String? {
        val n = number(ctx) ?: return null
        return BleKeyProtocol.fullUuid(prefix(ctx), n)
    }

    /** 可读标识：`钥匙 03`（未设置编号时 `未设编号`）。 */
    fun label(ctx: Context): String = number(ctx)?.let { "钥匙 %02d".format(it) } ?: "未设编号"
}
