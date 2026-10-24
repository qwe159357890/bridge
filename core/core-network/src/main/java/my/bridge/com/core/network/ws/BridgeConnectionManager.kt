package my.bridge.com.core.network.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bridge 连接编排器。
 *
 * 职责：串联「加载服务地址 → 初始化设备标识 → 启动 WebSocket」。
 * 设备注册已下沉到 [BridgeWebSocketClient] 的 ``onBeforeHello`` 钩子（每次连接成功时
 * 幂等注册），保证服务端重启后注册表清空时，重连也能自动补注册。
 *
 * 设计要点：
 * - device_id 用 ANDROID_ID（[deviceIdProvider] 同步返回，启动时缓存）；
 * - 注册/重连失败不崩，交给 WS 客户端重连退避。
 */
class BridgeConnectionManager(
    private val webSocketClient: BridgeWebSocketClient,
    private val deviceIdProvider: () -> String,
    private val loadServerAddress: suspend () -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false

    /** 启动：加载服务地址 → 初始化设备标识 → 拉起 WS 连接。幂等。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                // 先加载持久化的服务地址（若有），再连接
                loadServerAddress()
                // 触发设备标识初始化（确保 ANDROID_ID 已缓存）
                deviceIdProvider()
                webSocketClient.start()
            } catch (e: Exception) {
                Timber.e(e, "连接编排初始化失败")
            }
        }
    }

    /** 停止：关闭 WS 连接。 */
    fun stop() {
        started = false
        webSocketClient.stop()
    }
}
