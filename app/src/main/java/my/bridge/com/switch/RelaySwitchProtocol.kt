package my.bridge.com.switch

/**
 * 智能控制（ESP32 继电器）协议常量。
 *
 * 严格照参考项目 references/server（FastAPI 中继服务器）的 WS 协议移植：
 * 客户端连 /ws/client，发送 subscribe/unsubscribe/get_status/set，接收
 * devices_list/status/device_online/device_offline/error。
 *
 * 继电器电平语义：value=0 低电平（开启）、value=1 高电平（关闭）。
 * 电平反转由服务器按 devices.yaml 的 inverted_pins 统一处理，客户端无需关心。
 */
object RelaySwitchProtocol {

    /** 客户端连接的服务端 WS 路径（参考 server 的 routes/__init__.py：ws 前缀 + /client）。 */
    const val CLIENT_PATH = "/ws/client"

    /** 默认中继服务器地址（host:port，参考 .env 的 VITE_SERVER_URL）。 */
    const val DEFAULT_SERVER = "127.0.0.1:15152"

    /** 默认 API Key（参考 .env 的 VITE_API_KEY；留空表示不启用认证）。 */
    const val DEFAULT_API_KEY = "qwe951753"

    /** 默认设备 ID（参考 .env 的 VITE_DEVICE_ID）。 */
    const val DEFAULT_DEVICE_ID = "esp32_001"

    /** 默认继电器端口（1-9，参考 .env 的 VITE_PIO）。 */
    const val DEFAULT_PIO = 6

    /** 断线重连间隔（毫秒，参考 .env 的 VITE_RECONNECT_INTERVAL）。 */
    const val RECONNECT_INTERVAL_MS = 3_000L

    /** 心跳超时判死阈值（毫秒）：超过该时长未收到任何数据则强制重连。 */
    const val HEARTBEAT_TIMEOUT_MS = 60_000L

    /** set 命令发送后首次延迟拉取状态的时长（毫秒）：等设备执行完 set 再 get_status。 */
    const val STATUS_REFRESH_DELAY_MS = 500L

    /** 状态轮询间隔（毫秒）：首次拉取后每隔该时长再拉一次，兜底设备休眠/未主动上报。 */
    const val STATUS_POLL_INTERVAL_MS = 2_000L

    /** 状态轮询最大次数：覆盖最坏 10 秒休眠 + 余量（500ms + 8*2s ≈ 16.5s）。 */
    const val STATUS_POLL_MAX_COUNT = 8

    /** 执行中状态兜底超时（毫秒）：超过该时长仍未收到状态确认，则退出「执行中」态。 */
    const val PENDING_TIMEOUT_MS = 18_000L

    /** 重连退避档位（毫秒），封顶 30 秒。 */
    val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    // ------------------------------------------------------------------
    // 客户端 → 服务器消息类型
    // ------------------------------------------------------------------
    const val TYPE_SUBSCRIBE = "subscribe"
    const val TYPE_UNSUBSCRIBE = "unsubscribe"
    const val TYPE_GET_STATUS = "get_status"
    const val TYPE_SET = "set"

    // ------------------------------------------------------------------
    // 服务器 → 客户端消息类型
    // ------------------------------------------------------------------
    const val TYPE_DEVICES_LIST = "devices_list"
    const val TYPE_STATUS = "status"
    const val TYPE_DEVICE_ONLINE = "device_online"
    const val TYPE_DEVICE_OFFLINE = "device_offline"
    const val TYPE_ERROR = "error"

    /** 继电器端口 → GPIO 引脚名映射（严格照 relay-control/src/main.ts 的 getPioName）。 */
    fun pioName(pio: Int): String = when (pio) {
        1 -> "GPIO23"
        2 -> "GPIO22"
        3 -> "GPIO21"
        4 -> "GPIO19"
        5 -> "GPIO18"
        6 -> "GPIO5"
        7 -> "GPIO4"
        8 -> "GPIO13"
        9 -> "GPIO2"
        else -> "PIO $pio"
    }
}
