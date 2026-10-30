package my.bridge.com.switch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 智能控制（ESP32 继电器）UI 状态快照，**不可变**。
 *
 * 与参考项目 relay-control/src/main.ts 的全局量一一对应：
 * - [devices] / [deviceOrder] ← cachedDevices / cachedDeviceOrder
 * - [deviceStatus] ← deviceStatusCache（deviceId -> 引脚号字符串 -> 电平值）
 * - [pendingDevices] = 本项目扩展的「执行中」提示态集合
 * - [connection] ← setConnected 的 WebSocket 连接态
 */
data class SwitchUiState(
    val devices: List<RelaySwitchState.Device> = emptyList(),
    val deviceOrder: List<String> = emptyList(),
    val deviceStatus: Map<String, Map<String, Int>> = emptyMap(),
    val pendingDevices: Set<String> = emptySet(),
    val connection: RelaySwitchState.Connection = RelaySwitchState.Connection.DISCONNECTED,
)

/**
 * 智能控制全局状态中心。
 *
 * ★ 设计要点（v0.1.91 重写，解决「状态必须手动切页才刷新」的顽疾）：
 *
 * 采用**单一不可变快照**而非多个可变字段：任何变更都 `copy` 出全新的 data class，
 * 且其中的 List/Map/Set 也一并生成新实例。Compose 通过结构相等比较即可可靠地检测到
 * 「有变化」并触发重组，彻底避免了过去「某个字段变了但 UI 认为对象引用没变」的漏刷问题。
 *
 * 另外，状态写入发生在 OkHttp 的 IO 线程，Compose 的 snapshot 系统保证线程安全地通知 UI，
 * 因此无需再用 Handler 手动切主线程（过去 `mainHandler.post` 的写法也是漏刷诱因之一）。
 */
object RelaySwitchState {

    /** 设备信息（id / 名称 / 是否在线），对应 DeviceInfo。 */
    data class Device(
        val id: String,
        val name: String,
        val online: Boolean,
    )

    /** 连接状态。 */
    enum class Connection {
        DISCONNECTED, // 未连接
        CONNECTING,   // 连接中
        CONNECTED,    // 已连接
    }

    /** 收到服务端 error 消息的回调（UI 层弹 Toast）。 */
    fun interface ErrorListener {
        fun onError(message: String)
    }

    private var _state by mutableStateOf(SwitchUiState())

    /** UI 唯一观察点：读取该快照即可随状态变化自动重组。 */
    val state: SwitchUiState get() = _state

    @Volatile
    private var errorListener: ErrorListener? = null

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    /** 统一写入口：产出全新快照对象。 */
    private fun mutate(block: (SwitchUiState) -> SwitchUiState) {
        _state = block(_state)
    }

    // ------------------------------------------------------------------
    // 读取辅助（纯函数，便于 UI 传入 snapshot 计算，不隐式依赖单例）
    // ------------------------------------------------------------------

    /** 取某设备某引脚的电平值：0=开，1=关，null=尚未收到真实状态。 */
    fun pioValue(status: Map<String, Int>?, pio: Int): Int? = status?.get(pio.toString())

    /** 设备排序（照 compareDevices：在线优先 → 服务端顺序 → 中文名）。 */
    fun sortedDevices(devices: List<Device>, order: List<String>): List<Device> {
        if (devices.size <= 1) return devices
        return devices.sortedWith(
            compareBy<Device> { if (it.online) 0 else 1 }
                .thenBy {
                    val index = order.indexOf(it.id)
                    if (index >= 0) index else Int.MAX_VALUE
                }
                .thenBy { it.name }
        )
    }

    // ------------------------------------------------------------------
    // 写入（均由客户端在 IO 线程调用）
    // ------------------------------------------------------------------

    fun updateConnection(connection: Connection) {
        mutate { if (it.connection == connection) it else it.copy(connection = connection) }
    }

    /** 更新设备列表（ devices_list 消息 / REST 拉取结果）。 */
    fun updateDevices(newDevices: List<Device>, order: List<String>) {
        mutate {
            it.copy(devices = newDevices.toList(), deviceOrder = order.toList())
        }
    }

    /** 设备上线：已存在则更新，未知设备则追加（照 handleServerMessage 的 device_online 分支）。 */
    fun updateDeviceOnline(id: String, name: String?) {
        mutate { s ->
            val exists = s.devices.any { it.id == id }
            val devices = if (exists) {
                s.devices.map { if (it.id == id) it.copy(online = true, name = name ?: it.name) else it }
            } else {
                // 新设备：照参考工程 push 到列表尾部
                s.devices + Device(id = id, name = name ?: id, online = true)
            }
            s.copy(devices = devices)
        }
    }

    /** 设备离线：标记离线并清除其状态缓存（照 device_offline 分支）。 */
    fun updateDeviceOffline(id: String) {
        mutate { s ->
            val devices = s.devices.map { if (it.id == id) it.copy(online = false) else it }
            s.copy(
                devices = devices,
                deviceStatus = s.deviceStatus - id,
                pendingDevices = s.pendingDevices - id,
            )
        }
    }

    /** 更新某设备的继电器状态；收到真实状态即代表 set 完成，同时退出「执行中」态。 */
    fun updateStatus(deviceId: String, status: Map<String, Int>) {
        mutate { s ->
            s.copy(
                deviceStatus = s.deviceStatus + (deviceId to status.toMap()),
                pendingDevices = s.pendingDevices - deviceId,
            )
        }
    }

    /** 标记设备进入「执行中」态。 */
    fun markPending(deviceId: String) {
        mutate { s ->
            if (deviceId in s.pendingDevices) s else s.copy(pendingDevices = s.pendingDevices + deviceId)
        }
    }

    /** 清除设备的「执行中」态。 */
    fun clearPending(deviceId: String) {
        mutate { s ->
            if (deviceId !in s.pendingDevices) s else s.copy(pendingDevices = s.pendingDevices - deviceId)
        }
    }

    /** 通知服务端 error 消息。 */
    fun notifyError(message: String) {
        errorListener?.onError(message)
    }
}
