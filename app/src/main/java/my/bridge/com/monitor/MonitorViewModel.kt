package my.bridge.com.monitor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog

private const val NTAG = "监控"

/**
 * 监控功能视图模型（v0.2.140）。
 *
 * 承载节点清单、当前节点选择、摄像头清单等跨二级 Tab 共享状态。
 * 网络调用统一切 IO 线程，UI 通过 StateFlow 订阅。
 */
class MonitorViewModel : ViewModel() {

    private val _nodes = MutableStateFlow<List<NvrNode>>(emptyList())
    val nodes: StateFlow<List<NvrNode>> = _nodes

    /** 当前选中节点 ID（空 = 全部节点聚合视图）。 */
    private val _selectedNode = MutableStateFlow("")
    val selectedNode: StateFlow<String> = _selectedNode

    private val _nodesLoading = MutableStateFlow(false)
    val nodesLoading: StateFlow<Boolean> = _nodesLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun selectNode(nodeId: String) {
        DiagLog.i(NTAG, "切换节点 → ${nodeId.ifBlank { "全部节点" }}")
        _selectedNode.value = nodeId
    }

    /** 拉取节点清单。 */
    fun loadNodes(context: Context) {
        viewModelScope.launch {
            _nodesLoading.value = true
            _error.value = null
            try {
                val api = NvrApiClient(context)
                val list = withContext(Dispatchers.IO) { api.fetchNodes() }
                _nodes.value = list
            } catch (e: Exception) {
                _error.value = e.message ?: "无法连接监控服务"
                DiagLog.e(NTAG, "节点清单加载失败：${_error.value}", e)
            } finally {
                _nodesLoading.value = false
            }
        }
    }
}
