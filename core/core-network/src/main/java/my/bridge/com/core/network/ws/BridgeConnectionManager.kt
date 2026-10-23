package my.bridge.com.core.network.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bridge 连接编排器。
 *
 * 职责：串联「设备标识初始化 → 设备注册（拿 token + 昵称）→ 启动 WebSocket」，
 * 与后端「设备鉴权 + 注册 + 唯一连接」约定对应。
 *
 * 设计要点：
 * - device_id 用 ANDROID_ID（[deviceIdProvider] 同步返回，启动时缓存）；
 * - token 可选：先尝试注册获取（幂等），后端 auth.enabled=false 时失败也不阻断连接；
 * - 昵称随注册上报（可选，缺省 device_id）；
 * - 注册失败/网络不可用不崩，交给 WS 客户端重连退避。
 */
class BridgeConnectionManager(
    private val webSocketClient: BridgeWebSocketClient,
    private val deviceIdProvider: () -> String,
    private val tokenProvider: suspend () -> String?,
    private val deviceNameProvider: suspend () -> String?,
    private val register: suspend (deviceId: String, deviceName: String?) -> String,
    private val loadServerAddress: suspend () -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false

    /** 启动：加载服务地址 → 初始化设备标识 → 尝试注册 → 拉起 WS 连接。幂等。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                // 先加载持久化的服务地址（若有），再连接
                loadServerAddress()
                val deviceId = deviceIdProvider()
                val deviceName = deviceNameProvider()
                // 尝试注册拿 token（失败仅记日志，不阻断连接；auth.enabled=false 时允许无 token）
                try {
                    val token = register(deviceId, deviceName)
                    Timber.i("设备注册成功：$deviceId")
                } catch (e: Exception) {
                    Timber.w("设备注册失败（继续无 token 连接）：${e.message}")
                }
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
