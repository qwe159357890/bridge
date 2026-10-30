package my.bridge.com.switch

import android.content.Context
import android.content.SharedPreferences

/**
 * 智能控制（ESP32 继电器）的配置持久化。
 *
 * 配置优先级：用户已保存的值优先，默认值仅作兜底（与 RelayPrefs 一致）。
 * 服务器地址 / API Key / 当前设备 / 继电器端口 / 窗格模式均可持久化。
 */
object RelaySwitchPrefs {

    private const val SP_NAME = "relay_switch_config"

    // 中继服务器地址（形如 127.0.0.1:15152，可被用户编辑）
    const val KEY_SERVER = "switch_server"

    // API Key（服务端 devices.yaml 的 auth.api_key；留空则连接不带 token）
    const val KEY_API_KEY = "switch_api_key"

    // 当前选中的设备 ID
    const val KEY_DEVICE_ID = "switch_device_id"

    // 窗格模式（多设备卡片墙）开关
    const val KEY_PANE_MODE = "switch_pane_mode"

    // 指定设备的继电器端口（按设备存储，key = "switch_pio_<deviceId>"）
    private fun pioKey(deviceId: String): String = "switch_pio_$deviceId"

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

    fun getInt(context: Context, key: String, default: Int): Int =
        sp(context).getInt(key, default)

    fun setInt(context: Context, key: String, value: Int) {
        sp(context).edit().putInt(key, value).apply()
    }

    /** 中继服务器地址（host:port）。 */
    fun server(context: Context): String =
        getString(context, KEY_SERVER, RelaySwitchProtocol.DEFAULT_SERVER)

    /** API Key（留空则不启用认证）。 */
    fun apiKey(context: Context): String =
        getString(context, KEY_API_KEY, RelaySwitchProtocol.DEFAULT_API_KEY)

    /** 当前设备 ID。 */
    fun deviceId(context: Context): String =
        getString(context, KEY_DEVICE_ID, RelaySwitchProtocol.DEFAULT_DEVICE_ID)

    /** 窗格模式开关。 */
    fun paneMode(context: Context): Boolean =
        getBoolean(context, KEY_PANE_MODE, false)

    /** 指定设备的继电器端口（未单独配置时用默认值）。 */
    fun pioForDevice(context: Context, deviceId: String): Int =
        getInt(context, pioKey(deviceId), RelaySwitchProtocol.DEFAULT_PIO)

    fun setPioForDevice(context: Context, deviceId: String, pio: Int) {
        setInt(context, pioKey(deviceId), pio.coerceIn(1, 9))
    }
}
