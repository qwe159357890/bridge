package my.bridge.com.switch

/**
 * 智能控制（ESP32 继电器）UI 状态模型。
 *
 * 设备信息 + 各设备继电器状态（引脚编号 → 电平值）+ 连接态。
 * 与参考项目 relay-control/src/main.ts 的 cachedDevices / deviceStatusCache 对应。
 */
object RelaySwitchState {

    /** 设备信息（id / 名称 / 是否在线）。 */
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

    /** 设备在线状态变化的回调。 */
    fun interface DeviceListener {
        fun onChange()
    }

    /** 连接状态变化的回调。 */
    fun interface ConnectionListener {
        fun onChange(state: Connection)
    }

    /** 收到服务端 error 消息的回调。 */
    fun interface ErrorListener {
        fun onError(message: String)
    }

    /** 设备列表（按服务端下发顺序，客户端排序在页面层做）。 */
    @Volatile
    var devices: List<Device> = emptyList()

    /** 设备排序顺序（服务端 device_order，参考 devices.yaml）。 */
    @Volatile
    var deviceOrder: List<String> = emptyList()

    /** 设备状态缓存：deviceId -> (pin 编号字符串 -> 电平值)。 */
    @Volatile
    var deviceStatus: Map<String, Map<String, Int>> = emptyMap()

    @Volatile
    var connection: Connection = Connection.DISCONNECTED

    @Volatile
    private var deviceListener: DeviceListener? = null

    @Volatile
    private var connectionListener: ConnectionListener? = null

    @Volatile
    private var errorListener: ErrorListener? = null

    fun setDeviceListener(listener: DeviceListener?) {
        deviceListener = listener
    }

    fun setConnectionListener(listener: ConnectionListener?) {
        connectionListener = listener
    }

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    /** 更新设备列表并通知 UI。 */
    fun updateDevices(newDevices: List<Device>, order: List<String>) {
        devices = newDevices
        deviceOrder = order
        deviceListener?.onChange()
    }

    /** 更新单个设备的在线状态并通知 UI。 */
    fun updateDeviceOnline(id: String, name: String?) {
        devices = devices.map {
            if (it.id == id) it.copy(online = true, name = name ?: it.name) else it
        }
        deviceListener?.onChange()
    }

    /** 更新单个设备的离线状态并清除其状态缓存，通知 UI。 */
    fun updateDeviceOffline(id: String) {
        devices = devices.map {
            if (it.id == id) it.copy(online = false) else it
        }
        deviceStatus = deviceStatus - id
        deviceListener?.onChange()
    }

    /** 更新某设备的继电器状态并通知 UI。 */
    fun updateStatus(deviceId: String, status: Map<String, Int>) {
        deviceStatus = deviceStatus + (deviceId to status)
        deviceListener?.onChange()
    }

    /** 更新连接状态并通知 UI。 */
    fun updateConnection(state: Connection) {
        connection = state
        connectionListener?.onChange(state)
    }

    /** 通知服务端 error 消息。 */
    fun notifyError(message: String) {
        errorListener?.onError(message)
    }

    /** 取指定设备的继电器端口状态（pin 未上报时为 null）。 */
    fun pioValue(deviceId: String, pio: Int): Int? =
        deviceStatus[deviceId]?.get(pio.toString())
}
