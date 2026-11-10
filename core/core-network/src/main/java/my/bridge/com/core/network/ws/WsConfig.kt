package my.bridge.com.core.network.ws

/**
 * WebSocket 客户端连接配置。
 *
 * **心跳/重连参数客户端与服务端都硬编码，不提供任何配置入口。**
 * 原因（用户确认）：心跳参数必须客户端 ≤ 服务端读超时，否则服务端必判客户端离线；
 * 若两端都可自由改，极易配置不一致导致误判。参数由协议文档约定，双方写死。
 *
 * 与后端 config.yaml 硬编码值一致（docs/server/design.md）：
 *   heartbeat.interval=60s、client_pong_timeout=10s、reconnect.base=1s/max=60s/jitter=0.2。
 *   （v0.2.21 心跳由 20s 放宽到 60s，服务端 server_read_timeout 同步 45s → 150s；
 *    详见 docs/dev/heartbeat-inventory.md §8）
 *
 * **服务地址可配置**：默认值见 [BASE_URL_DEFAULT]（完整 URL，必须带协议），
 * 运行时地址由 [ServerAddress] 持有（启动时从 DataStore 加载，用户可在设置页修改并触发重连）。
 * 环境对照权威表：docs/admin/environment-matrix.md。
 */
object WsConfig {

    /**
     * 服务地址默认值（完整 URL，★ 必须带协议）。
     *
     * - 生产（nginx 部署）：`https://appclient.039039.xyz:15443`  ← 当前默认值
     * - 测试（本机 `npm run dev` / 后端直连）：`http://039039.xyz:15160`
     *   或 `http://192.168.31.226:15160`
     *
     * 设备端直连生产 HTTPS 后，`ServerAddress.wsUrl()` 会推导出 `wss://…:15443/ws`，
     * 不再经过 nginx 15080 的 301 跳转（证书为 Let's Encrypt 受信任证书）。
     */
    const val BASE_URL_DEFAULT = "https://appclient.039039.xyz:15443"

    /** WebSocket 服务地址默认值（由 [BASE_URL_DEFAULT] 按 https→wss 推导）。 */
    const val WS_URL_DEFAULT = "wss://appclient.039039.xyz:15443/ws"

    /** REST 基础地址默认值（设备注册 / 日志上传；Retrofit baseUrl 以 / 结尾）。 */
    const val REST_BASE_URL_DEFAULT = "https://appclient.039039.xyz:15443/"

    /** 心跳发送周期（毫秒）。与服务端 heartbeat.interval=60s 硬编码一致。 */
    const val HEARTBEAT_INTERVAL_MS = 60_000L

    /** 发送 ping 后等待 pong 的超时（毫秒）。与服务端 client_pong_timeout=10s 一致。 */
    const val PONG_TIMEOUT_MS = 10_000L

    /** 重连基础退避（毫秒）。 */
    const val RECONNECT_BASE_MS = 1_000L

    /** 重连最大退避（毫秒，封顶）。 */
    const val RECONNECT_MAX_MS = 60_000L

    /** 重连随机抖动比例（±20%）。 */
    const val RECONNECT_JITTER = 0.2

    /**
     * 距下一个心跳栅格的毫秒数（取值区间 (0, HEARTBEAT_INTERVAL_MS]）。
     *
     * 把心跳对齐到 Unix 毫秒时间的整数倍，使 Bridge 主 WS 与微信 WS 的 ping 落在**同一时刻**，
     * 共用一次无线唤醒（radio tail），而不是各自错相触发。
     * 每次都按当前时间重算，因此能自我纠正累积漂移。
     *
     * 详见 docs/dev/heartbeat-inventory.md §3.3 结论 3。
     */
    fun delayToNextGrid(nowMs: Long = System.currentTimeMillis()): Long =
        HEARTBEAT_INTERVAL_MS - (nowMs % HEARTBEAT_INTERVAL_MS)
}
