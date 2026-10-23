package my.bridge.com.core.network.ws

/**
 * 运行时服务地址持有器。
 *
 * 服务地址可配置 + 持久化：启动时由 app 层从 DataStore 加载写入本对象，
 * WebSocket 客户端与 Retrofit 在构建请求时读取本对象的最新值。
 *
 * 用户修改地址后更新本对象并触发重连（见设置页 ConnectionViewModel）。
 *
 * 线程安全：字段用 @Volatile，读写为原子操作。
 */
object ServerAddress {

    @Volatile
    private var host: String = WsConfig.HOST_DEFAULT

    @Volatile
    private var port: String = WsConfig.PORT_DEFAULT

    /** 设置 host + port（trim 后非空才生效）。 */
    fun set(host: String, port: String) {
        val h = host.trim()
        val p = port.trim()
        if (h.isNotEmpty()) this.host = h
        if (p.isNotEmpty()) this.port = p
    }

    /** 当前 host。 */
    fun host(): String = host

    /** 当前 port。 */
    fun port(): String = port

    /** 当前 WebSocket 地址（ws://host:port/ws）。 */
    fun wsUrl(): String = "ws://$host:$port/ws"

    /** 当前 REST 基础地址（http://host:port）。 */
    fun restBaseUrl(): String = "http://$host:$port"

    /**
     * 校验服务地址是否合法。
     *
     * 返回 null 表示合法；否则返回错误提示（中文，供 UI 直接展示）。
     *
     * host 规则：非空；不区分大小写，允许域名/IPv4/IPv6 字面量；不允许协议前缀
     * （http://、ws://）、路径、斜杠、冒号、空白等（客户端会自行拼 ``ws://host:port``）。
     * port 规则：纯数字，范围 1~65535。
     */
    fun validate(host: String, port: String): String? {
        val h = host.trim()
        val p = port.trim()

        if (h.isEmpty()) return "服务器地址不能为空"
        if (p.isEmpty()) return "端口不能为空"

        // host 不允许包含协议前缀、路径分隔、空白、冒号（IPv6 用方括号另行处理）
        if (h.contains("://") || h.contains('/') || h.contains('\\') || h.contains(' ')) {
            return "地址格式不正确（不要包含 http:// 或路径）"
        }
        if (h.contains(':')) {
            // 允许 IPv6 字面量（用方括号包裹，如 [::1]）
            if (!(h.startsWith("[") && h.endsWith("]"))) {
                return "地址格式不正确（IPv6 请用 [::1] 形式）"
            }
        }
        // 非法字符粗筛
        if (h.any { it.isWhitespace() }) {
            return "地址不能包含空白字符"
        }

        // port 必须是纯数字且范围合法
        val portInt = p.toIntOrNull() ?: return "端口必须是数字"
        if (portInt !in 1..65535) {
            return "端口范围应为 1~65535"
        }

        return null
    }
}
