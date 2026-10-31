package my.bridge.com.sales

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 销售页（SalesRecordRemote）配置持久化。
 *
 * 语义照参考项目 web/src/utils/storage.ts + LoginPage/AfterSalesPage：
 * token/user_info/current_product_type/server_url/ws_client_id/账号历史/列配置
 * 都在客户端本地保存；Bridge 只保留 SharedPreferences 单写，不引入 plus.storage。
 */
object SalesPrefs {

    private const val SP_NAME = "sales_record_remote"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_USER = "user_info"
    private const val KEY_CLIENT_ID = "ws_client_id"
    private const val KEY_PRODUCT_TYPE = "current_product_type"
    private const val KEY_ACCOUNTS = "sales_accounts"
    private const val KEY_PAGE_SIZE = "page_size"
    private const val KEY_VIRTUAL_SCROLL = "virtual_scroll"
    private const val KEY_TOOLBAR_COLLAPSED = "toolbar_collapsed"
    private const val KEY_COLUMN_MOBILE = "column_config_mobile"
    private const val KEY_COLUMN_READING = "column_config_reading"
    private const val KEY_SCROLL_POS = "table_scroll_pos"

    data class SavedAccount(
        val username: String,
        val password: String,
        val lastUsed: Long,
    )

    enum class ColumnMode { MOBILE, READING }

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private fun normalizeServerUrl(raw: String): String = raw.trim().trimEnd('/')

    fun isValidServerUrl(raw: String): Boolean =
        normalizeServerUrl(raw).matches(Regex("^https?://.+"))

    fun serverUrl(context: Context): String =
        normalizeServerUrl(sp(context).getString(KEY_SERVER_URL, "") ?: "")

    fun setServerUrl(context: Context, url: String) {
        val normalized = normalizeServerUrl(url)
        if (normalized.isBlank()) {
            sp(context).edit().remove(KEY_SERVER_URL).apply()
        } else {
            sp(context).edit().putString(KEY_SERVER_URL, normalized).apply()
        }
    }

    fun token(context: Context): String = sp(context).getString(KEY_TOKEN, "") ?: ""

    fun saveLogin(context: Context, token: String, user: SalesUser) {
        sp(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, userToJson(user).toString())
            .apply()
    }

    fun user(context: Context): SalesUser? {
        val raw = sp(context).getString(KEY_USER, null) ?: return null
        return runCatching { userFromJson(JSONObject(raw)) }.getOrNull()
    }

    /** 退出/被踢/token 失效时照参考项目清登录态与当前产品类型；服务器地址和账号历史保留。 */
    fun clearLogin(context: Context) {
        sp(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .remove(KEY_PRODUCT_TYPE)
            .apply()
    }

    fun clientId(context: Context): String {
        val old = sp(context).getString(KEY_CLIENT_ID, "") ?: ""
        if (old.isNotBlank()) return old
        val created = "android-" + UUID.randomUUID().toString()
        sp(context).edit().putString(KEY_CLIENT_ID, created).apply()
        return created
    }

    fun setClientId(context: Context, id: String) {
        if (id.isNotBlank()) sp(context).edit().putString(KEY_CLIENT_ID, id).apply()
    }

    fun productType(context: Context): SalesProductType =
        SalesProductType.fromKey(sp(context).getString(KEY_PRODUCT_TYPE, SalesProductType.SPONGE.key) ?: SalesProductType.SPONGE.key)

    fun setProductType(context: Context, type: SalesProductType) {
        sp(context).edit().putString(KEY_PRODUCT_TYPE, type.key).apply()
    }

    fun pageSize(context: Context): Int = sp(context).getInt(KEY_PAGE_SIZE, 100)

    fun setPageSize(context: Context, size: Int) {
        sp(context).edit().putInt(KEY_PAGE_SIZE, size).apply()
    }

    fun virtualScroll(context: Context): Boolean = sp(context).getBoolean(KEY_VIRTUAL_SCROLL, false)

    fun setVirtualScroll(context: Context, enabled: Boolean) {
        sp(context).edit()
            .putBoolean(KEY_VIRTUAL_SCROLL, enabled)
            .putInt(KEY_PAGE_SIZE, if (enabled) 9999 else 100)
            .apply()
    }

    fun toolbarCollapsed(context: Context): Boolean = sp(context).getBoolean(KEY_TOOLBAR_COLLAPSED, false)

    fun setToolbarCollapsed(context: Context, collapsed: Boolean) {
        sp(context).edit().putBoolean(KEY_TOOLBAR_COLLAPSED, collapsed).apply()
    }

    /** 列配置：返回 null=无自定义配置（使用移动端/阅读默认集），否则为 prop->visible。 */
    fun columnConfig(context: Context, mode: ColumnMode): Map<String, Boolean>? {
        val key = if (mode == ColumnMode.READING) KEY_COLUMN_READING else KEY_COLUMN_MOBILE
        val raw = sp(context).getString(key, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optBoolean(it, true) }
        }.getOrNull()
    }

    fun saveColumnConfig(context: Context, mode: ColumnMode, config: Map<String, Boolean>) {
        val key = if (mode == ColumnMode.READING) KEY_COLUMN_READING else KEY_COLUMN_MOBILE
        val obj = JSONObject()
        config.forEach { (prop, visible) -> obj.put(prop, visible) }
        sp(context).edit().putString(key, obj.toString()).apply()
    }

    fun clearColumnConfig(context: Context, mode: ColumnMode) {
        val key = if (mode == ColumnMode.READING) KEY_COLUMN_READING else KEY_COLUMN_MOBILE
        sp(context).edit().remove(key).apply()
    }

    fun tableScrollX(context: Context, type: SalesProductType): Int {
        val raw = sp(context).getString(KEY_SCROLL_POS, null) ?: return 0
        return runCatching { JSONObject(raw).optInt(type.key, 0) }.getOrDefault(0)
    }

    fun saveTableScrollX(context: Context, type: SalesProductType, x: Int) {
        val raw = sp(context).getString(KEY_SCROLL_POS, null)
        val obj = runCatching { raw?.let { JSONObject(it) } ?: JSONObject() }.getOrDefault(JSONObject())
        obj.put(type.key, x)
        sp(context).edit().putString(KEY_SCROLL_POS, obj.toString()).apply()
    }

    fun accounts(context: Context): List<SavedAccount> {
        val raw = sp(context).getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val username = obj.optString("username")
                if (username.isBlank()) return@mapNotNull null
                SavedAccount(
                    username = username,
                    password = obj.optString("password"),
                    lastUsed = obj.optLong("lastUsed"),
                )
            }.sortedByDescending { it.lastUsed }
        }.getOrDefault(emptyList())
    }

    /** 登录成功后保存账号历史；remember=false 时照参考项目把该账号密码清空。 */
    fun saveAccount(context: Context, username: String, password: String, remember: Boolean) {
        val list = accounts(context).toMutableList()
        val idx = list.indexOfFirst { it.username == username }
        val item = SavedAccount(username, if (remember) password else "", System.currentTimeMillis())
        if (idx >= 0) list[idx] = item else list += item
        saveAccounts(context, list.sortedByDescending { it.lastUsed })
    }

    fun removeAccount(context: Context, username: String) {
        saveAccounts(context, accounts(context).filter { it.username != username })
    }

    fun accountPassword(context: Context, username: String): String =
        accounts(context).firstOrNull { it.username == username }?.password ?: ""

    private fun saveAccounts(context: Context, list: List<SavedAccount>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("username", it.username)
                put("password", it.password)
                put("lastUsed", it.lastUsed)
            })
        }
        sp(context).edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun userToJson(user: SalesUser): JSONObject = JSONObject().apply {
        put("username", user.username)
        put("display_name", user.display_name)
        put("role", user.role)
        put("editable_columns", JSONArray(user.editable_columns ?: emptyList<String>()))
        put("allowed_product_types", JSONArray(user.allowed_product_types ?: emptyList<String>()))
        put("visible_columns", JSONArray(user.visible_columns ?: emptyList<String>()))
    }

    private fun userFromJson(obj: JSONObject): SalesUser = SalesUser(
        username = obj.optString("username"),
        display_name = obj.optString("display_name"),
        role = obj.optString("role", "operator"),
        editable_columns = obj.optJSONArray("editable_columns")?.toStringList(),
        allowed_product_types = obj.optJSONArray("allowed_product_types")?.toStringList(),
        visible_columns = obj.optJSONArray("visible_columns")?.toStringList(),
    )

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
