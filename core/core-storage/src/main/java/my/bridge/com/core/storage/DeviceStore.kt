package my.bridge.com.core.storage

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deviceDataStore by preferencesDataStore(name = "bridge_device")

/**
 * 设备标识与昵称持久化。
 *
 * device_id 用系统 **ANDROID_ID**（`Settings.Secure.ANDROID_ID`）：
 * - 同设备重装 APK 不变（区别于自生成 UUID），无需任何权限；
 * - 官方推荐的稳定设备标识（恢复出厂设置会变，可接受）。
 *
 * 与后端唯一连接约定一致（docs/server/design.md 第七节）：
 * 同一 device_id 全局仅一条活跃连接。
 *
 * 昵称（device_name）由用户可自定义，缺省用 device_id；随握手/注册上报，
 * 服务端仅作展示名，不参与唯一性判断。
 */
class DeviceStore(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TOKEN = stringPreferencesKey("token")
        // v0.2.130 起：完整 URL 单值（必须带协议）
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        // 老版本（v0.2.130 之前）分 host/port 两个 key；保留仅供迁移读取，不再写入
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = stringPreferencesKey("server_port")
    }

    private val appContext = context.applicationContext

    /**
     * 取设备唯一 ID（ANDROID_ID）。
     * 极端情况 ANDROID_ID 为空（罕见），回退为固定前缀 + 硬件信息，仍保证稳定。
     */
    fun deviceId(): String {
        val androidId = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        if (!androidId.isNullOrBlank()) return androidId
        // 回退：用 Build 信息拼一个稳定标识（同一设备恒定）
        return "fallback-${android.os.Build.MANUFACTURER}-${android.os.Build.MODEL}-${android.os.Build.DEVICE}"
    }

    /** 取已持久化的昵称；未设置返回 null（此时展示用 device_id）。 */
    suspend fun deviceName(): String? =
        appContext.deviceDataStore.data.map { it[Keys.DEVICE_NAME] }.first()

    /** 保存昵称（空串表示清除，回退 device_id）。 */
    suspend fun saveDeviceName(name: String) {
        appContext.deviceDataStore.edit { it[Keys.DEVICE_NAME] = name }
    }

    /** 取已持久化的 token；未注册返回 null。 */
    suspend fun token(): String? =
        appContext.deviceDataStore.data.map { it[Keys.TOKEN] }.first()

    /** 保存 token。 */
    suspend fun saveToken(token: String) {
        appContext.deviceDataStore.edit { it[Keys.TOKEN] = token }
    }

    // ===== 服务地址（可配，持久化） =====

    /**
     * 取已保存的服务地址（完整 URL，必须带协议）；未设置返回 null（此时用 WsConfig 默认）。
     *
     * ★ 老数据迁移（v0.2.130）：老版本把地址拆成 `server_host` + `server_port` 两个 key，
     * 这里自动补 `http://` 前缀拼回完整 URL，保证升级后仍连原服务器、
     * 且 `ConnectionViewModel` 的 `_isFirstRun = saved == null` 不会误判为首次安装。
     */
    suspend fun serverAddress(): String? =
        appContext.deviceDataStore.data.map { prefs ->
            prefs[Keys.SERVER_BASE_URL]
                ?: prefs[Keys.SERVER_HOST]?.let { host ->
                    val port = prefs[Keys.SERVER_PORT] ?: "15160"
                    // 一次性收敛（v0.2.130）：过渡期曾用 appclient.039039.xyz:15080 作生产地址
                    // （依赖 nginx 301 跳 https）。升级后直接改为 15443 直连，不再走重定向。
                    if (host == "appclient.039039.xyz" && port == "15080") {
                        "https://appclient.039039.xyz:15443"
                    } else {
                        "http://$host:$port"
                    }
                }
        }.first()

    /** 保存完整服务地址；老 key 保留不清（回滚到旧版本仍可读取）。 */
    suspend fun saveServerAddress(baseUrl: String) {
        appContext.deviceDataStore.edit { it[Keys.SERVER_BASE_URL] = baseUrl }
    }
}
