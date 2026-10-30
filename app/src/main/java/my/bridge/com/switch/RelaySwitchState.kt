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
    /** 等待设备确认的命令：key = deviceId:pio。只用于按钮确认/重试，不参与真实开关状态推导。 */
    val pendingCommands: Map<String, RelaySwitchState.PendingCommand> = emptyMap(),
)

/**
 * 智能控制全局状态中心（严格照 main.ts 的三个全局缓存 + setConnected）。
 *
 * ★ 设计要点：
 * 1. **单一不可变快照**：任何变更都 copy 出全新对象（含内部 List/Map），
 *    Compose 结构相等比较即可可靠检测变化并重组。
 * 2. **真实状态无中间态**：开关徽章仍由 connected + device.online + statusCache 推导；
 *    pendingCommands 只负责按钮防重复点击、超时查询与定向重试，不伪造开关状态。
 * 3. 状态写入发生在 OkHttp IO 线程，Compose snapshot 系统保证线程安全通知 UI。
 */
object RelaySwitchState {

    /** 设备信息（id / 名称 / 是否在线），对应参考的 DeviceInfo。 */
    data class Device(
        val id: String,
        val name: String,
        val online: Boolean,
    )

    /**
     * 一条等待确认的 set 命令。
     * awaitingRetry=false：已发送，按钮禁用并等待真实 status；
     * awaitingRetry=true：首次查询后仍未确认，按钮变为「重试开启/关闭」。
     */
    data class PendingCommand(
        val commandId: Long,
        val deviceId: String,
        val pio: Int,
        val targetValue: Int,
        val awaitingRetry: Boolean = false,
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

    /** 命令确认粒度：同一设备的不同端口互不影响。 */
    fun commandKey(deviceId: String, pio: Int): String = "$deviceId:$pio"

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
                pendingCommands = s.pendingCommands.filterValues { it.deviceId != id },
            )
        }
    }

    /** 更新某设备的继电器状态缓存（照 status 分支：deviceStatusCache[id] = data）。 */
    fun updateStatus(deviceId: String, status: Map<String, Int>) {
        mutate { s ->
            s.copy(statusCache = s.statusCache + (deviceId to status.toMap()))
        }
    }

    /** 发送成功后登记命令；同端口的新命令会覆盖旧命令并使旧超时回调失效。 */
    @Synchronized
    fun beginCommand(command: PendingCommand) {
        mutate { s ->
            s.copy(
                pendingCommands = s.pendingCommands +
                    (commandKey(command.deviceId, command.pio) to command),
            )
        }
    }

    /** 发送失败时撤销刚登记的命令；若已有新命令则不影响。 */
    @Synchronized
    fun discardCommand(commandId: Long) {
        mutate { s ->
            val key = s.pendingCommands.entries.firstOrNull { it.value.commandId == commandId }?.key
                ?: return@mutate s
            s.copy(pendingCommands = s.pendingCommands - key)
        }
    }

    /** 首次确认超时任务是否仍对应当前命令。 */
    @Synchronized
    fun isCommandWaiting(commandId: Long): Boolean =
        _state.pendingCommands.values.any { it.commandId == commandId && !it.awaitingRetry }

    /**
     * 查询兜底也超时后，把命令切换为「待重试」。
     * 返回 false 表示期间已收到状态或命令已被新命令替代，不应再提示超时。
     */
    @Synchronized
    fun markAwaitingRetry(commandId: Long): Boolean {
        val entry = _state.pendingCommands.entries.firstOrNull {
            it.value.commandId == commandId && !it.value.awaitingRetry
        } ?: return false
        mutate { s ->
            s.copy(
                pendingCommands = s.pendingCommands +
                    (entry.key to entry.value.copy(awaitingRetry = true)),
            )
        }
        return true
    }

    /** 收到包含对应端口的状态广播后，确认并清除等待命令。 */
    @Synchronized
    fun confirmStatus(deviceId: String, reportedPins: Set<String>) {
        mutate { s ->
            val confirmedKeys = s.pendingCommands.filterValues {
                it.deviceId == deviceId && it.pio.toString() in reportedPins
            }.keys
            if (confirmedKeys.isEmpty()) s else s.copy(pendingCommands = s.pendingCommands - confirmedKeys)
        }
    }

    /** 页面离开/连接断开时清理全部等待命令，旧定时回调随后自然失效。 */
    @Synchronized
    fun clearAllCommands() {
        if (_state.pendingCommands.isEmpty()) return
        mutate { it.copy(pendingCommands = emptyMap()) }
    }

    /** 弹出 Toast（照 showToast）。 */
    fun toast(message: String) {
        toastListener?.onToast(message)
    }
}
