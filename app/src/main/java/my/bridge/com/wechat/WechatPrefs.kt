package my.bridge.com.wechat

import android.content.Context
import android.content.SharedPreferences

/**
 * 微信（中心服务器）本地配置。
 *
 * 照 `SalesPrefs` 的写法：单 SharedPreferences，进程内可读可写。
 * 注意：**没有「后台接收」开关**——实时性是硬需求，连接常开不可关；
 * 可关的只有提醒方式（通知 / 提示音 / 静音）。
 */
object WechatPrefs {

    private const val SP_NAME = "wechat_center"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_CURRENT_WXID = "current_wxid"
    private const val KEY_SESSION_MODE = "session_mode"        // friend | subscription
    private const val KEY_CONTACTS_FILTER = "contacts_filter"  // all|has_session|person|group|subscription
    private const val KEY_NOTIFY = "notify_enabled"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_MUTE_ALL = "mute_all"
    private const val KEY_CLIENT_ID = "ws_client_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_UNREAD = "last_unread_total"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun normalizeServerUrl(raw: String): String = raw.trim().trimEnd('/')

    fun isValidServerUrl(raw: String): Boolean =
        normalizeServerUrl(raw).matches(Regex("^https?://.+"))

    fun serverUrl(context: Context): String =
        normalizeServerUrl(sp(context).getString(KEY_SERVER_URL, "") ?: "")

    fun setServerUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_SERVER_URL, normalizeServerUrl(url)).apply()
    }

    fun apiKey(context: Context): String = sp(context).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(context: Context, key: String) {
        sp(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    /** 是否已配置（地址合法 + 有 Key）。 */
    fun configured(context: Context): Boolean =
        isValidServerUrl(serverUrl(context)) && apiKey(context).isNotBlank()

    /** 当前账号（发送上下文，参考前端 currentWxid）。 */
    fun currentWxid(context: Context): String = sp(context).getString(KEY_CURRENT_WXID, "") ?: ""

    fun setCurrentWxid(context: Context, wxid: String) {
        sp(context).edit().putString(KEY_CURRENT_WXID, wxid).apply()
    }

    fun sessionMode(context: Context): String =
        sp(context).getString(KEY_SESSION_MODE, "friend") ?: "friend"

    fun setSessionMode(context: Context, mode: String) {
        sp(context).edit().putString(KEY_SESSION_MODE, mode).apply()
    }

    fun contactsFilter(context: Context): String =
        sp(context).getString(KEY_CONTACTS_FILTER, "all") ?: "all"

    fun setContactsFilter(context: Context, filter: String) {
        sp(context).edit().putString(KEY_CONTACTS_FILTER, filter).apply()
    }

    fun notifyEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_NOTIFY, true)

    fun setNotifyEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_NOTIFY, enabled).apply()
    }

    fun soundEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    /** 全局静音：只关提示音，不关通知、更不关连接。 */
    fun muteAll(context: Context): Boolean = sp(context).getBoolean(KEY_MUTE_ALL, false)

    fun setMuteAll(context: Context, muted: Boolean) {
        sp(context).edit().putBoolean(KEY_MUTE_ALL, muted).apply()
    }

    /** 本端 client_id：由服务端 connected 事件下发，用于广播去自扰（from_client == 自己时忽略）。 */
    fun localClientId(context: Context): String = sp(context).getString(KEY_CLIENT_ID, "") ?: ""

    fun setLocalClientId(context: Context, id: String) {
        sp(context).edit().putString(KEY_CLIENT_ID, id).apply()
    }

    /** 设备标识（register 的 device_id）：首次生成后固定。 */
    fun deviceId(context: Context): String {
        val old = sp(context).getString(KEY_DEVICE_ID, "") ?: ""
        if (old.isNotBlank()) return old
        val created = "bridge-android-" + java.util.UUID.randomUUID().toString().take(8)
        sp(context).edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun lastUnreadTotal(context: Context): Int = sp(context).getInt(KEY_LAST_UNREAD, 0)

    fun setLastUnreadTotal(context: Context, total: Int) {
        sp(context).edit().putInt(KEY_LAST_UNREAD, total).apply()
    }
}
