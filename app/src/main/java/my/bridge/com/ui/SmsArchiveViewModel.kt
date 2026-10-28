package my.bridge.com.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.api.BridgeApi
import my.bridge.com.core.storage.DeviceStore
import timber.log.Timber
import javax.inject.Inject

/**
 * 短信聚合页 ViewModel（APK 短信页聚合模式，2026-10-28 拍板默认聚合）。
 *
 * 数据源 = 服务端短信存档（所有设备），会话键 = (device_id, address)。
 * 与本机视图（ContentResolver 直读）互不干扰，页内切换。
 * 存档只读：标记已读/删除在管理后台做（服务端能力），APK 端仅浏览。
 */
@HiltViewModel
class SmsArchiveViewModel @Inject constructor(
    private val deviceStore: DeviceStore,
    private val apiClient: BridgeApiClient,
) : ViewModel() {

    /** 聚合会话列表。 */
    private val _threads = MutableStateFlow<List<BridgeApi.SmsThreadItem>?>(null)
    val threads = _threads.asStateFlow()

    /** 拉取失败提示（网络/服务器不可达/存档库不可用）。 */
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** 本机 device_id（列表里标记「本机」）。 */
    private val _selfDeviceId = MutableStateFlow("")
    val selfDeviceId = _selfDeviceId.asStateFlow()

    private var lastKeyword = ""

    /** 拉取聚合会话列表。 */
    fun loadThreads(keyword: String = lastKeyword) {
        lastKeyword = keyword
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = deviceStore.deviceId()
                _selfDeviceId.value = id
                val token = runCatching { deviceStore.token() }.getOrNull()
                val r = apiClient.getSmsThreads(
                    deviceId = id,
                    token = token,
                    keyword = keyword.takeIf { it.isNotBlank() },
                )
                _threads.value = r.items
                _error.value = null
            } catch (e: Exception) {
                Timber.w(e, "拉取聚合会话失败")
                _error.value = "聚合列表拉取失败：${e.message ?: "网络异常"}（请确认服务器可达）"
                if (_threads.value == null) _threads.value = emptyList()
            }
        }
    }

    /** 拉取某会话的消息（倒序分页，返回 null = 失败）。 */
    suspend fun loadMessages(
        threadDeviceId: String,
        address: String,
        beforeMs: Long? = null,
        limit: Int = 100,
    ): BridgeApi.SmsMessagesResponse? = withContext(Dispatchers.IO) {
        try {
            val id = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            apiClient.getSmsMessages(
                deviceId = id,
                token = token,
                threadDeviceId = threadDeviceId,
                address = address,
                beforeMs = beforeMs,
                limit = limit,
            )
        } catch (e: Exception) {
            Timber.w(e, "拉取聚合消息失败")
            _error.value = "消息拉取失败：${e.message ?: "网络异常"}"
            null
        }
    }
}
