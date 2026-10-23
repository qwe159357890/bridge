package my.bridge.com.core.network.ws

/**
 * WebSocket 客户端连接配置。
 *
 * **心跳/重连参数客户端与服务端都硬编码，不提供任何配置入口。**
 * 原因（用户确认）：心跳参数必须客户端 ≤ 服务端读超时，否则服务端必判客户端离线；
 * 若两端都可自由改，极易配置不一致导致误判。参数由协议文档约定，双方写死。
 *
 * 与后端 config.yaml 硬编码值一致（docs/server/design.md）：
 *   heartbeat.interval=20s、client_pong_timeout=10s、reconnect.base=1s/max=60s/jitter=0.2。
 *
 * **服务地址可配置**：默认值见下方常量（模拟器 10.0.2.2），运行时地址由
 * [ServerAddress] 持有（启动时从 DataStore 加载，用户可在设置页修改并触发重连）。
 */
object WsConfig {

    /** WebSocket 服务地址默认值（10.0.2.2 为 Android 模拟器回环；真机改为服务器地址）。 */
    const val WS_URL_DEFAULT = "ws://10.0.2.2:15160/ws"

    /** REST 基础地址默认值（设备注册 / 日志上传）。 */
    const val REST_BASE_URL_DEFAULT = "http://10.0.2.2:15160"

    /** 默认 host（不含协议/端口）。 */
    const val HOST_DEFAULT = "10.0.2.2"

    /** 默认端口。 */
    const val PORT_DEFAULT = "15160"

    /** 心跳发送周期（毫秒）。与服务端 heartbeat.interval=20s 硬编码一致。 */
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /** 发送 ping 后等待 pong 的超时（毫秒）。与服务端 client_pong_timeout=10s 一致。 */
    const val PONG_TIMEOUT_MS = 10_000L

    /** 重连基础退避（毫秒）。 */
    const val RECONNECT_BASE_MS = 1_000L

    /** 重连最大退避（毫秒，封顶）。 */
    const val RECONNECT_MAX_MS = 60_000L

    /** 重连随机抖动比例（±20%）。 */
    const val RECONNECT_JITTER = 0.2
}
