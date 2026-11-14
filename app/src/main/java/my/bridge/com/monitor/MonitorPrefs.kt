package my.bridge.com.monitor

import android.content.Context

/**
 * 监控功能配置持久化（v0.2.140）。
 *
 * 存储 NVR hub（8200 统一控制台）访问配置，供 NvrApiClient 使用。
 * 采用 SharedPreferences（与项目其它轻量配置一致，见 AudioRelayPrefs/ReportPrefs 范式）。
 *
 * 字段：
 * - hub 地址（完整 URL 带协议，默认 http://86.74.128.68:8200，VNT 组网内网地址）
 * - 访问令牌（HUB_API_TOKEN，可为空）
 * - 默认节点（空 = 全部节点聚合视图）
 * - 快照自动轮询开关 + 间隔秒数
 */
object MonitorPrefs {

    private const val PREFS = "monitor_prefs"

    private const val KEY_HUB_URL = "hub_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEFAULT_NODE = "default_node"
    private const val KEY_AUTO_REFRESH = "auto_refresh"
    private const val KEY_REFRESH_SECONDS = "refresh_seconds"
    private const val KEY_LIVE_COLUMNS = "live_columns"
    private const val KEY_CLOUD_PATH = "cloud_path"

    /** 默认 hub 地址：VNT 组网内 NVR 统一控制台（盒子2 wxy2）。 */
    const val DEFAULT_HUB_URL = "http://86.74.128.68:8200"
    const val DEFAULT_REFRESH_SECONDS = 10

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** hub 完整地址（带协议，去末尾斜杠）。 */
    fun hubUrl(context: Context): String =
        sp(context).getString(KEY_HUB_URL, null) ?: DEFAULT_HUB_URL

    fun saveHubUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_HUB_URL, url.trim().trimEnd('/')).apply()
    }

    /** 访问令牌（HUB_API_TOKEN，可为空）。 */
    fun token(context: Context): String =
        sp(context).getString(KEY_TOKEN, "") ?: ""

    fun saveToken(context: Context, token: String) {
        sp(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /** 默认节点 ID（空 = 全部节点聚合视图）。 */
    fun defaultNode(context: Context): String =
        sp(context).getString(KEY_DEFAULT_NODE, "") ?: ""

    fun saveDefaultNode(context: Context, nodeId: String) {
        sp(context).edit().putString(KEY_DEFAULT_NODE, nodeId).apply()
    }

    /** 快照自动轮询开关（默认关，省流）。 */
    fun autoRefresh(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_REFRESH, false)

    fun saveAutoRefresh(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_REFRESH, enabled).apply()
    }

    /** 自动轮询间隔秒数（默认 10）。 */
    fun refreshSeconds(context: Context): Int =
        sp(context).getInt(KEY_REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS)

    fun saveRefreshSeconds(context: Context, seconds: Int) {
        sp(context).edit().putInt(KEY_REFRESH_SECONDS, seconds.coerceIn(5, 300)).apply()
    }

    /** 实时预览布局列数（1/2/3，默认 2）。 */
    fun liveColumns(context: Context): Int =
        sp(context).getInt(KEY_LIVE_COLUMNS, 2)

    fun saveLiveColumns(context: Context, columns: Int) {
        sp(context).edit().putInt(KEY_LIVE_COLUMNS, columns.coerceIn(1, 3)).apply()
    }

    /**
     * 云端目录浏览路径记忆：存「节点/摄像头/类别」前缀（不含日期层），
     * 用 / 分隔；空 = 无记忆。下次进入云端自动拼当天日期定位。
     * nodeKey：按节点视图分别记忆（空串 = 全部节点视图），避免跨节点路径串层。
     */
    fun cloudPath(context: Context, nodeKey: String): String =
        sp(context).getString("$KEY_CLOUD_PATH|$nodeKey", "") ?: ""

    fun saveCloudPath(context: Context, nodeKey: String, path: String) {
        sp(context).edit().putString("$KEY_CLOUD_PATH|$nodeKey", path.trim('/')).apply()
    }
}
