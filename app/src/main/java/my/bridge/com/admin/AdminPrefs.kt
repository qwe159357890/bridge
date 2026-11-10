package my.bridge.com.admin

import android.content.Context
import android.content.SharedPreferences

/**
 * 「管理后台」内嵌页配置持久化（v0.2.125，见 docs/admin/client-embed-admin.md）。
 *
 * 语义：客户端内嵌 WebView 打开管理后台（admin），URL 可配置，默认值见
 * [DEFAULT_ADMIN_URL]（生产 nginx 地址）。用户可在管理页点地址栏「改地址」覆盖。
 * admin 本身走相对路径（VITE_API_BASE 空），由托管它的地址自动指向服务端，
 * 故只需存「admin 的访问地址」，无需存服务端 API 地址。
 *
 * 环境取值对照（权威表 docs/admin/environment-matrix.md）：
 *   生产（nginx 托管 dist）：https://appclient.039039.xyz:15443   ← 默认值
 *   测试（本机 npm run dev）：http://192.168.31.226:5173（Vite dev server）
 */
object AdminPrefs {

    private const val SP_NAME = "admin_embed"

    /**
     * admin 访问地址默认值（★ 必须带协议，[isValid] 校验 ^https?://）。
     *
     * 生产：https://appclient.039039.xyz:15443（nginx 的 HTTPS 端口）
     * 测试：本机 npm run dev 时改为 http://192.168.31.226:5173
     * 清空已保存值（[setUrl] 传空串）即回落到本默认值。
     */
    const val DEFAULT_ADMIN_URL = "https://appclient.039039.xyz:15443"

    /** admin 访问地址（如 http://192.168.31.226:5173 或 nginx 部署的地址）。 */
    private const val KEY_URL = "admin_url"

    /** 横屏锁定开关（v0.2.127）：true = 锁定横屏，false = 跟随系统自动旋转。 */
    private const val KEY_LANDSCAPE = "admin_landscape"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /** 是否为合法 admin URL（http/https 开头）。 */
    fun isValid(raw: String): Boolean =
        normalize(raw).matches(Regex("^https?://.+"))

    /** 读取 admin URL；未配置或已清空时回落到 [DEFAULT_ADMIN_URL]。 */
    fun url(context: Context): String =
        normalize(sp(context).getString(KEY_URL, DEFAULT_ADMIN_URL) ?: DEFAULT_ADMIN_URL)

    fun setUrl(context: Context, url: String) {
        val normalized = normalize(url)
        if (normalized.isBlank()) {
            sp(context).edit().remove(KEY_URL).apply()
        } else {
            sp(context).edit().putString(KEY_URL, normalized).apply()
        }
    }

    /** 读取横屏锁定开关。 */
    fun isLandscape(context: Context): Boolean =
        sp(context).getBoolean(KEY_LANDSCAPE, false)

    /** 写入横屏锁定开关。 */
    fun setLandscape(context: Context, landscape: Boolean) {
        sp(context).edit().putBoolean(KEY_LANDSCAPE, landscape).apply()
    }
}
