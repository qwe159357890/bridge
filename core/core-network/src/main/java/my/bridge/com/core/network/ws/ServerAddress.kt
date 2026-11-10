package my.bridge.com.core.network.ws

import java.net.URI

/**
 * 运行时服务地址持有器（v0.2.130 起：完整 URL 单值，**必须带协议**）。
 *
 * 存储形态 `scheme://host[:port]`：
 * - 生产（nginx 部署）：`https://appclient.039039.xyz:15443`
 * - 测试（本机 `npm run dev` / 后端直连）：`http://039039.xyz:15160` 或 `http://192.168.31.226:15160`
 *
 * 环境对照权威表：`docs/admin/environment-matrix.md`。
 *
 * [wsUrl] 按 scheme 自动推导 `ws` / `wss`，[restBaseUrl] 直接作为 Retrofit baseUrl，
 * 因此设备端既能走明文测试环境，也能直连生产 HTTPS（不再依赖 nginx 的 301 跳转）。
 * 启动时由 app 层从 DataStore 加载写入本对象（含老 host/port 数据迁移），
 * 用户修改地址后更新本对象并触发重连（见 ConnectionViewModel）。
 *
 * 线程安全：字段用 @Volatile，读写为原子操作。
 */
object ServerAddress {

    @Volatile
    private var baseUrl: String = WsConfig.BASE_URL_DEFAULT

    /** 归一化：去首尾空白、去末尾斜杠。 */
    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /** 设置完整服务地址（调用方需先用 [validate] 校验）。 */
    fun set(url: String) {
        val u = normalize(url)
        if (u.isNotEmpty()) this.baseUrl = u
    }

    /** 当前完整服务地址。 */
    fun baseUrl(): String = baseUrl

    /** 当前 WebSocket 地址：https→wss、http→ws，末尾拼 /ws。 */
    fun wsUrl(): String {
        val b = normalize(baseUrl)
        return when {
            b.startsWith("https://", ignoreCase = true) -> "wss://" + b.substring(8)
            b.startsWith("http://", ignoreCase = true) -> "ws://" + b.substring(7)
            else -> "ws://$b" // validate 保证不会走到
        } + "/ws"
    }

    /** 当前 REST 基础地址（Retrofit baseUrl 要求以 / 结尾）。 */
    fun restBaseUrl(): String = normalize(baseUrl) + "/"

    /**
     * 校验完整服务地址：必须 `http://` 或 `https://` 开头，只含协议 + 主机 + 端口，
     * 不含路径、查询参数、片段或用户名密码。返回 null 表示合法，否则为可直接展示的错误文案。
     */
    fun validate(raw: String): String? {
        val u = normalize(raw)
        if (u.isEmpty()) return "服务器地址不能为空"
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            return "地址必须以 http:// 或 https:// 开头（如 https://appclient.039039.xyz:15443）"
        }
        val uri = runCatching { URI(u) }.getOrNull() ?: return "地址格式不正确"
        if (uri.host.isNullOrBlank()) return "缺少主机名"
        if (uri.userInfo != null) return "地址不能包含用户名/密码"
        val path = uri.path.orEmpty()
        if (path.isNotBlank() && path != "/") return "地址不能包含路径（只填协议+域名+端口）"
        if (uri.query != null || uri.fragment != null) return "地址不能包含 ? 参数或 # 片段"
        if (uri.port == 0 || uri.port > 65535) return "端口范围应为 1~65535"
        return null
    }
}
