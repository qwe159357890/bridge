package my.bridge.com.relay

import android.content.Context
import android.content.SharedPreferences

/**
 * 音频中继的配置持久化（借鉴 Aurelay AppPrefs，收敛为本模块专用）。
 *
 * 后端零改动硬约束：中转服务器复用 Aurelay 中心服务器，WebSocket 路径
 * /api/aurelay/relay，握手发 JSON hello（device_id/token/role）。
 *
 * 配置优先级：**用户已保存的值优先，默认值仅作兜底**。中转服务器地址、上报令牌、
 * 地址上报接口地址、DNS 兜底 IP 均可在设置页编辑；设备唯一标识首次生成 UUID 后
 * 持久化（与 Aurelay AddressReporter 一致，保证 PC 端按 device_id 配对一致）。
 */
object RelayPrefs {

    private const val SP_NAME = "relay_config"

    // 中转服务器地址（形如 039039.xyz:15151，可被用户编辑）
    const val KEY_RELAY_SERVER = "relay_server"

    // 中转服务器 DNS 解析失败时的兜底 IP（可被用户编辑）
    const val KEY_RELAY_FALLBACK_IP = "relay_fallback_ip"

    // 中转的 WebSocket 路径（与 center-server 的 aurelay.relay_path 一致）
    const val RELAY_PATH = "/api/aurelay/relay"

    // 中转上报令牌（与 Aurelay HTTP API 同一把，服务端校验；可被用户编辑）
    const val KEY_REPORT_TOKEN = "relay_report_token"

    // 地址上报接口地址（供 PC 端查询本机 device_id，配对前提；可被用户编辑）
    const val KEY_REPORT_URL = "relay_report_url"

    // 设备唯一标识（首次生成 UUID 并持久化，与 Aurelay AddressReporter 一致）
    const val KEY_DEVICE_ID = "relay_device_id"

    // 自动重连开关（默认开；关闭需二次确认）
    const val KEY_AUTO_RECONNECT = "relay_auto_reconnect"

    // 中继播放音量（0.0 ~ 1.0，AudioTrack.setVolume 软增益，不改系统音量）
    const val KEY_VOLUME = "relay_volume"

    // 默认中转服务器地址
    const val DEFAULT_RELAY_SERVER = "039039.xyz:15151"

    // 默认 DNS 兜底 IP（服务器实际公网 IPv4）
    const val DEFAULT_RELAY_FALLBACK_IP = "122.6.190.161"

    // 默认上报令牌（与 Aurelay 中心服务器 aurelay.token 一致）
    const val DEFAULT_REPORT_TOKEN = "qwe951753"

    // 默认地址上报接口地址（与 Aurelay AddressReporter 的 DEFAULT_REPORT_URL 一致）
    const val DEFAULT_REPORT_URL = "http://039039.xyz:15151/api/aurelay/report"

    // 默认音量（80%）
    const val DEFAULT_VOLUME = 0.8f

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String): String =
        sp(context).getString(key, default) ?: default

    fun setString(context: Context, key: String, value: String) {
        sp(context).edit().putString(key, value).apply()
    }

    fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        sp(context).getBoolean(key, default)

    fun setBoolean(context: Context, key: String, value: Boolean) {
        sp(context).edit().putBoolean(key, value).apply()
    }

    fun getFloat(context: Context, key: String, default: Float): Float =
        sp(context).getFloat(key, default)

    fun setFloat(context: Context, key: String, value: Float) {
        sp(context).edit().putFloat(key, value).apply()
    }

    /** 中转服务器地址（去掉 ws:// / wss:// 前缀后的 host:port）。 */
    fun relayServer(context: Context): String =
        getString(context, KEY_RELAY_SERVER, DEFAULT_RELAY_SERVER)

    /** DNS 兜底 IP（中转服务器域名解析失败时使用）。 */
    fun relayFallbackIp(context: Context): String =
        getString(context, KEY_RELAY_FALLBACK_IP, DEFAULT_RELAY_FALLBACK_IP)

    /** 上报令牌。 */
    fun reportToken(context: Context): String =
        getString(context, KEY_REPORT_TOKEN, DEFAULT_REPORT_TOKEN)

    /** 地址上报接口地址。 */
    fun reportUrl(context: Context): String =
        getString(context, KEY_REPORT_URL, DEFAULT_REPORT_URL)

    /** 自动重连开关。 */
    fun autoReconnect(context: Context): Boolean =
        getBoolean(context, KEY_AUTO_RECONNECT, true)

    /** 中继播放音量（0.0~1.0）。 */
    fun volume(context: Context): Float =
        getFloat(context, KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)

    /**
     * 设备唯一标识（首次生成 UUID 并持久化，与 Aurelay AddressReporter 一致）。
     *
     * 与 Bridge 全局 device_id（ANDROID_ID）**不同**：中转配对要求「手机上报的
     * device_id == PC 端查询到的 device_id」，而 PC 端从中心服务器查询的设备列表
     * 来自 Aurelay 上报链路（UUID）。为保证 Bridge 与 Aurelay 原版可无缝共存、
     * 配对一致，这里沿用 UUID 方案，不复用 ANDROID_ID。
     */
    fun deviceId(context: Context): String {
        val appContext = context.applicationContext
        val existing = getString(appContext, KEY_DEVICE_ID, "")
        if (existing.isNotBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        setString(appContext, KEY_DEVICE_ID, generated)
        return generated
    }
}
