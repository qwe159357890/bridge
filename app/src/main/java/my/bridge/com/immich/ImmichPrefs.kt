package my.bridge.com.immich

import android.content.Context
import java.util.UUID

/**
 * Immich 相册功能配置持久化（接 API 阶段，2026-11-16）。
 *
 * 存储服务端访问配置与自动备份记账，供 ImmichApiClient / ImmichUploadWorker 使用。
 * 采用 SharedPreferences（与 MonitorPrefs / AudioRelayPrefs 同范式）。
 *
 * 字段：
 * - 服务地址（完整 URL 带协议，默认本机服务端 http://039039.xyz:15148）
 * - 账号 / 密码（POST /auth/login 换 accessToken 后持久化）
 * - accessToken（登录会话令牌，请求头 x-immich-user-token）
 * - 自动备份开关（默认关，能耗纪律见调研报告 §5.4）
 * - 仅 Wi-Fi 上传（默认开，UNMETERED 约束）
 * - 勾选备份相册（bucket_display_name 集合；空 = 全部相册）
 * - 备份记账：上次同步点（date_added 水位）+ 已同步集合（id|size|date_added）
 * - 设备 ID（deviceId 上传字段，首启随机生成后固定）
 */
object ImmichPrefs {

    private const val PREFS = "immich_prefs"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_AUTO_BACKUP = "auto_backup"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_SELECTED_BUCKETS = "selected_buckets"
    private const val KEY_LAST_SYNC = "last_sync_date_added"
    private const val KEY_SYNCED_ITEMS = "synced_items"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SCRUBBER_COLLAPSED = "scrubber_collapsed"
    private const val KEY_SCRUBBER_SIDE = "scrubber_side"
    private const val KEY_LEGACY_NOTIF_CLEANED = "legacy_notif_2001_cleaned"

    /** 默认服务地址：本机已部署验证的 Immich 服务端（VNT 组网可直达）。 */
    const val DEFAULT_BASE_URL = "http://039039.xyz:15148"

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ============ 服务端访问 ============

    /** 服务端完整地址（带协议，去末尾斜杠）。 */
    fun baseUrl(context: Context): String =
        sp(context).getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL

    fun saveBaseUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    /** 登录账号（邮箱）。 */
    fun username(context: Context): String =
        sp(context).getString(KEY_USERNAME, "") ?: ""

    fun saveUsername(context: Context, value: String) {
        sp(context).edit().putString(KEY_USERNAME, value.trim()).apply()
    }

    /** 登录密码（登录失败重登时使用）。 */
    fun password(context: Context): String =
        sp(context).getString(KEY_PASSWORD, "") ?: ""

    fun savePassword(context: Context, value: String) {
        sp(context).edit().putString(KEY_PASSWORD, value).apply()
    }

    /** 会话令牌（请求头 x-immich-user-token；空 = 未登录）。 */
    fun token(context: Context): String =
        sp(context).getString(KEY_TOKEN, "") ?: ""

    fun saveToken(context: Context, value: String) {
        sp(context).edit().putString(KEY_TOKEN, value).apply()
    }

    /** 服务端 userId（登录响应返回，上传时作 deviceId 之外的归属标识）。 */
    fun userId(context: Context): String =
        sp(context).getString(KEY_USER_ID, "") ?: ""

    fun saveUserId(context: Context, value: String) {
        sp(context).edit().putString(KEY_USER_ID, value).apply()
    }

    /** 服务端用户名（顶栏头像首字母用）。 */
    fun serverUserName(context: Context): String =
        sp(context).getString(KEY_USER_NAME, "") ?: ""

    fun saveServerUserName(context: Context, value: String) {
        sp(context).edit().putString(KEY_USER_NAME, value).apply()
    }

    /** 是否已配置账号密码（登录链路可用）。 */
    fun isConfigured(context: Context): Boolean =
        username(context).isNotEmpty() && password(context).isNotEmpty()

    // ============ 自动备份 ============

    /** 自动备份开关（默认关；能耗纪律：关闭时 Observer 不注册、Worker 直接退出）。 */
    fun autoBackup(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_BACKUP, false)

    fun saveAutoBackup(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    /** 仅 Wi-Fi 上传（默认开；映射 WorkManager UNMETERED 网络约束）。 */
    fun wifiOnly(context: Context): Boolean =
        sp(context).getBoolean(KEY_WIFI_ONLY, true)

    fun saveWifiOnly(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    /** 勾选备份的本地相册（bucket_display_name 集合；空 = 全部相册）。 */
    fun selectedBuckets(context: Context): Set<String> =
        sp(context).getStringSet(KEY_SELECTED_BUCKETS, emptySet()) ?: emptySet()

    fun saveSelectedBuckets(context: Context, buckets: Set<String>) {
        sp(context).edit().putStringSet(KEY_SELECTED_BUCKETS, buckets).apply()
    }

    // ============ 备份记账 ============

    /** 增量扫描水位（date_added > 水位 才入候选；纯 DB 读，避免全量扫描）。 */
    fun lastSyncDateAdded(context: Context): Long =
        sp(context).getLong(KEY_LAST_SYNC, 0L)

    fun saveLastSyncDateAdded(context: Context, value: Long) {
        sp(context).edit().putLong(KEY_LAST_SYNC, value).apply()
    }

    /** 已同步集合（元素格式 "mediaStoreId|size|dateAdded"，用于对账去重）。 */
    fun syncedItems(context: Context): Set<String> =
        sp(context).getStringSet(KEY_SYNCED_ITEMS, emptySet()) ?: emptySet()

    fun saveSyncedItems(context: Context, items: Set<String>) {
        sp(context).edit().putStringSet(KEY_SYNCED_ITEMS, items).apply()
    }

    /** 追加一条已同步记账（id|size|dateAdded）。 */
    fun addSyncedItem(context: Context, item: String) {
        val cur = syncedItems(context).toMutableSet()
        cur.add(item)
        saveSyncedItems(context, cur)
    }

    /** 清空备份记账（切换账号 / 手动重新同步时调用）。 */
    fun clearSyncState(context: Context) {
        sp(context).edit().remove(KEY_LAST_SYNC).remove(KEY_SYNCED_ITEMS).apply()
    }

    // ============ 设备标识 ============

    /** 设备 ID（上传 multipart 的 deviceId 字段；首启随机 UUID 后固定）。 */
    fun deviceId(context: Context): String {
        val stored = sp(context).getString(KEY_DEVICE_ID, null)
        if (!stored.isNullOrBlank()) return stored
        val fresh = "bridge-${UUID.randomUUID()}"
        sp(context).edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    /**
     * 月份浮窗是否处于收缩态（箭头贴右侧边缘；用户拍板：记忆收缩状态）。
     *
     * :param context: 任意 Context
     * :return: true = 收缩
     */
    fun scrubberCollapsed(context: Context): Boolean = sp(context).getBoolean(KEY_SCRUBBER_COLLAPSED, false)

    /**
     * 保存月份浮窗收缩状态（跨会话记忆）。
     *
     * :param context: 任意 Context
     * :param value: true = 收缩
     */
    fun saveScrubberCollapsed(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SCRUBBER_COLLAPSED, value).apply()
    }

    /**
     * 月份浮窗停靠侧（用户拍板：设置页可配置左/右）。
     *
     * :param context: 任意 Context
     * :return: true = 右缘（默认），false = 左缘
     */
    fun scrubberSide(context: Context): Boolean = sp(context).getBoolean(KEY_SCRUBBER_SIDE, true)

    /**
     * 保存月份浮窗停靠侧。
     *
     * :param context: 任意 Context
     * :param value: true = 右缘，false = 左缘
     */
    fun saveScrubberSide(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SCRUBBER_SIDE, value).apply()
    }

    /**
     * 历史残留通知迁移清理是否已执行（v0.2.177 一次性清理误用 ID 2001 的备份通知）。
     *
     * :param context: 任意 Context
     * :return: true = 已清理过，不再触碰 2001
     */
    fun legacyNotificationCleaned(context: Context): Boolean =
        sp(context).getBoolean(KEY_LEGACY_NOTIF_CLEANED, false)

    /**
     * 标记历史残留通知迁移清理已执行。
     *
     * :param context: 任意 Context
     */
    fun saveLegacyNotificationCleaned(context: Context) {
        sp(context).edit().putBoolean(KEY_LEGACY_NOTIF_CLEANED, true).apply()
    }
}
