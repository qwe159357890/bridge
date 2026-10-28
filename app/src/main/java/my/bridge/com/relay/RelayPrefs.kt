package my.bridge.com.relay

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * 音频中继的配置持久化（借鉴 Aurelay AppPrefs，收敛为本模块专用）。
 *
 * 后端零改动硬约束：中转服务器复用 Aurelay 中心服务器（039039.xyz:15151），
 * WebSocket 路径 /api/aurelay/relay，握手发 JSON hello（device_id/token/role）。
 * 因此 device_id 沿用 Bridge 的 ANDROID_ID（与 Aurelay 的 UUID 不同，但均为
 * 服务器侧唯一标识即可；服务器仅按 device_id 配对，不校验生成方式）。
 */
object RelayPrefs {

    private const val SP_NAME = "relay_config"

    // 中转服务器地址（形如 039039.xyz:15151，可被用户编辑）
    const val KEY_RELAY_SERVER = "relay_server"

    // 中转服务器 DNS 解析失败时的兜底 IP（服务器实际公网 IPv4）
    const val RELAY_FALLBACK_IP = "122.6.190.161"

    // 中转的 WebSocket 路径（与 center-server 的 aurelay.relay_path 一致）
    const val RELAY_PATH = "/api/aurelay/relay"

    // 中转上报令牌（与 Aurelay HTTP API 同一把，服务端校验）
    const val KEY_REPORT_TOKEN = "relay_report_token"

    // 自动重连开关（默认开；关闭需二次确认）
    const val KEY_AUTO_RECONNECT = "relay_auto_reconnect"

    // 中继播放音量（0.0 ~ 1.0，AudioTrack.setVolume 软增益，不改系统音量）
    const val KEY_VOLUME = "relay_volume"

    // 默认中转服务器地址
    const val DEFAULT_RELAY_SERVER = "039039.xyz:15151"

    // 默认上报令牌
    const val DEFAULT_REPORT_TOKEN = "qwe951753"

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

    /** 上报令牌。 */
    fun reportToken(context: Context): String =
        getString(context, KEY_REPORT_TOKEN, DEFAULT_REPORT_TOKEN)

    /** 自动重连开关。 */
    fun autoReconnect(context: Context): Boolean =
        getBoolean(context, KEY_AUTO_RECONNECT, true)

    /** 中继播放音量（0.0~1.0）。 */
    fun volume(context: Context): Float =
        getFloat(context, KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)

    /**
     * 设备唯一标识（与 Bridge 全局 device_id 一致，ANDROID_ID）。
     * 复用 [my.bridge.com.core.storage.DeviceStore] 的同一算法，保证 Bridge
     * 后端连接与中继配对使用同一 device_id，方便管理后台对应。
     */
    fun deviceId(context: Context): String {
        val appContext = context.applicationContext
        val androidId = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        if (!androidId.isNullOrBlank()) return androidId
        return "fallback-${android.os.Build.MANUFACTURER}-${android.os.Build.MODEL}-${android.os.Build.DEVICE}"
    }
}
