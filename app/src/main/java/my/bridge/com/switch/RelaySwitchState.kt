package my.bridge.com.switch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 智能控制 UI 状态快照，**不可变**。
 *
 * 与参考项目 relay-control/src/main.ts 的全局量一一对应：
 * - [connected] ← setConnected(connected: Boolean) 的 WS 连接态（两态：已连接/未连接）
 * - [devices] / [deviceOrder] ← cachedDevices / cachedDeviceOrder
 * - [statusCache] ← deviceStatusCache（deviceId -> 引脚号字符串 -> 电平值，0=开 1=关）
 */
data class SwitchUiState(
    val connected: Boolean = false,
    val devices: List<RelaySwitchState.Device> = emptyList(),
    val deviceOrder: List<String> = emptyList(),
    val statusCache: Map<String, Map<String, Int>> = emptyMap(),
)

/**
 * 智能控制全局状态中心（严格照 main.ts 的三个全局缓存 + setConnected）。
 *
 * ★ 设计要点：
 * 1. **单一不可变快照**：任何变更都 copy 出全新对象（含内部 List/Map），
 *    Compose 结构相等比较即可可靠检测变化并重组。
 * 2. **无中间态**：没有「执行中」「轮询」「离线宽限」等自研概念——
 *    显示状态完全由 connected + device.online + statusCache 三个量推导，
 *    与参考项目 renderPaneCard/updateUI 的分支逻辑严格一致。
 * 3. 状态写入发生在 OkHttp IO 线程，Compose snapshot 系统保证线程安全通知 UI。
 */
object RelaySwitchState {

    /** 设备信息（id / 名称 / 是否在线），对应参考的 DeviceInfo。 */
    data class Device(
        val id: String,
        val name: String,
        val online: Boolean,
    )

    /** Toast 通知回调（对应参考的 showToast），由 UI 层注册。 */
    fun interface ToastListener {
        fun onToast(message: String)
    }

    private var _state by mutableStateOf(SwitchUiState())

    /** UI 唯一观察点：读取该快照即自动订阅重组。 */
    val state: SwitchUiState get() = _state

    @Volatile
    private var toastListener: ToastListener? = null

    fun setToastListener(listener: ToastListener?) {
        toastListener = listener
    }

    /** 统一写入口：产出全新快照对象。 */
    private fun mutate(block: (SwitchUiState) -> SwitchUiState) {
        _state = block(_state)
    }

    // ------------------------------------------------------------------
    // 读取辅助（纯函数，供 UI 计算显示态）
    // ------------------------------------------------------------------

    /** 取某设备某引脚的电平值：0=开，1=关，null=尚未收到真实状态。 */
    fun pioValue(status: Map<String, Int>?, pio: Int): Int? = status?.get(pio.toString())

    /** 设备排序（照 compareDevices：在线优先 → 服务端 device_order → 中文名）。 */
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

    /** 设备显示名（照 getDeviceName：列表里找不到时回退为设备 ID）。 */
    fun deviceName(devices: List<Device>, deviceId: String): String =
        devices.find { it.id == deviceId }?.name ?: deviceId

    // ------------------------------------------------------------------
    // 写入（均由客户端在 IO 线程调用，逐条对应 handleServerMessage 分支）
    // ------------------------------------------------------------------

    /** WS 连接态（照 setConnected）。 */
    fun setConnected(connected: Boolean) {
        mutate { if (it.connected == connected) it else it.copy(connected = connected) }
    }

    /** 更新设备列表（devices_list 消息 / REST 拉取结果）。 */
    fun updateDevices(newDevices: List<Device>, order: List<String>) {
        mutate {
            it.copy(devices = newDevices.toList(), deviceOrder = order.toList())
        }
    }

    /**
     * 设备上线（照 device_online 分支）：
     * 已存在则标记在线并更新名称；未知设备则 push 到列表尾部。
     */
    fun updateDeviceOnline(id: String, name: String?) {
        mutate { s ->
            val exists = s.devices.any { it.id == id }
            val devices = if (exists) {
                s.devices.map { if (it.id == id) it.copy(online = true, name = name ?: it.name) else it }
            } else {
                s.devices + Device(id = id, name = name ?: id, online = true)
            }
            s.copy(devices = devices)
        }
    }

    /**
     * 设备离线（照 device_offline 分支）：
     * 标记离线，并**立即删除其状态缓存**（delete deviceStatusCache[id]），
     * 卡片随即显示「等待连接」。
     */
    fun updateDeviceOffline(id: String) {
        mutate { s ->
            s.copy(
                devices = s.devices.map { if (it.id == id) it.copy(online = false) else it },
                statusCache = s.statusCache - id,
            )
        }
    }

    /** 更新某设备的继电器状态缓存（照 status 分支：deviceStatusCache[id] = data）。 */
    fun updateStatus(deviceId: String, status: Map<String, Int>) {
        mutate { s ->
            s.copy(statusCache = s.statusCache + (deviceId to status.toMap()))
        }
    }

    /** 弹出 Toast（照 showToast）。 */
    fun toast(message: String) {
        toastListener?.onToast(message)
    }
}
