package my.bridge.com.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.core.network.ws.ServerAddress
import my.bridge.com.core.network.ws.WsConnectionState
import my.bridge.com.core.network.ws.WsConfig
import my.bridge.com.core.storage.DeviceStore
import timber.log.Timber
import javax.inject.Inject

/**
 * 后端连接相关 UI 状态与操作。
 *
 * 对外暴露：
 * - [connectionState]：连接状态（实时，来自 WS 客户端 StateFlow）
 * - [deviceId]：设备唯一标识（ANDROID_ID）
 * - [deviceName]：设备昵称（可编辑，缺省 device_id）
 * - [serverHost]/[serverPort]：服务地址（可编辑，持久化，改后触发重连）
 * - [uploadResult]：手动上传日志的结果提示
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: BridgeWebSocketClient,
    private val deviceStore: DeviceStore,
    private val apiClient: BridgeApiClient,
) : ViewModel() {

    val connectionState: StateFlow<WsConnectionState> = webSocketClient.state

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _serverHost = MutableStateFlow("")
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    private val _serverPort = MutableStateFlow("")
    val serverPort: StateFlow<String> = _serverPort.asStateFlow()

    private val _serverAddressError = MutableStateFlow<String?>(null)
    val serverAddressError: StateFlow<String?> = _serverAddressError.asStateFlow()

    private val _uploadResult = MutableStateFlow<String?>(null)
    val uploadResult: StateFlow<String?> = _uploadResult.asStateFlow()

    init {
        viewModelScope.launch {
            val id = deviceStore.deviceId()
            _deviceId.value = id
            _deviceName.value = deviceStore.deviceName() ?: id
            // 服务地址：优先读持久化值，否则用默认
            _serverHost.value = deviceStore.serverHost() ?: WsConfig.HOST_DEFAULT
            _serverPort.value = deviceStore.serverPort() ?: WsConfig.PORT_DEFAULT
        }
    }

    /** 保存昵称（空则回退 device_id）。 */
    fun saveDeviceName(name: String) {
        val trimmed = name.trim()
        viewModelScope.launch {
            deviceStore.saveDeviceName(trimmed)
            _deviceName.value = trimmed.ifBlank { _deviceId.value }
            Timber.i("设备昵称已保存：${_deviceName.value}")
        }
    }

    /** 保存服务地址（校验 → 持久化 → 触发重连）。 */
    fun saveServerAddress(host: String, port: String) {
        val h = host.trim()
        val p = port.trim()
        val error = ServerAddress.validate(h, p)
        if (error != null) {
            _serverAddressError.value = error
            return
        }
        _serverAddressError.value = null
        viewModelScope.launch {
            deviceStore.saveServerAddress(h, p)
            ServerAddress.set(h, p)
            _serverHost.value = h
            _serverPort.value = p
            Timber.i("服务地址已保存：$h:$p")
            // 触发重连（用新地址）
            webSocketClient.reconnect()
        }
    }

    /** 清除服务地址校验错误提示。 */
    fun clearServerAddressError() {
        _serverAddressError.value = null
    }

    /** 手动上传诊断日志。 */
    fun uploadLog() {
        viewModelScope.launch {
            _uploadResult.value = "上传中…"
            val result = try {
                val id = deviceStore.deviceId()
                val content = DiagLog.dump(context)
                val name = deviceStore.deviceName() ?: id
                val version = DiagLog.appVersionName(context)
                apiClient.uploadLog(id, content, name, version)
            } catch (e: Exception) {
                Timber.e(e, "手动上传日志失败")
                "上传失败：${e.message}"
            }
            _uploadResult.value = result
        }
    }

    fun clearUploadResult() {
        _uploadResult.value = null
    }
}
