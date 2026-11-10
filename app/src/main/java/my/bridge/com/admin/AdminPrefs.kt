package my.bridge.com.admin

import android.content.Context
import android.content.SharedPreferences

/**
 * 「管理后台」内嵌页配置持久化（v0.2.125，见 docs/admin/client-embed-admin.md）。
 *
 * 语义：客户端内嵌 WebView 打开管理后台（admin），URL 可配置（默认空，首次进入
 * 提示填写）。admin 本身走相对路径（VITE_API_BASE 空），由托管它的地址自动指向服务端，
 * 故只需存「admin 的访问地址」，无需存服务端 API 地址。
 */
object AdminPrefs {

    private const val SP_NAME = "admin_embed"

    /** admin 访问地址（如 http://192.168.31.226:5173 或 nginx 部署的地址）。 */
    private const val KEY_URL = "admin_url"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /** 是否为合法 admin URL（http/https 开头）。 */
    fun isValid(raw: String): Boolean =
        normalize(raw).matches(Regex("^https?://.+"))

    /** 读取 admin URL；未配置返回空串。 */
    fun url(context: Context): String =
        normalize(sp(context).getString(KEY_URL, "") ?: "")

    fun setUrl(context: Context, url: String) {
        val normalized = normalize(url)
        if (normalized.isBlank()) {
            sp(context).edit().remove(KEY_URL).apply()
        } else {
            sp(context).edit().putString(KEY_URL, normalized).apply()
        }
    }
}
