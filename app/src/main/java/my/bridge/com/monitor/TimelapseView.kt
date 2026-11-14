package my.bridge.com.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog

private const val NTAG = "监控"

private val TlBlue = Color(0xFF1A6EE8)

/**
 * 延时摄影页（v0.2.140）。
 *
 * 日期倒序卡片列表 + 点播（首次下载缓存较慢，服务端已做缓存二次秒开）。
 * 支持节点聚合（全部节点）/ 单节点。
 */
@Composable
fun TimelapseView(
    nodes: List<NvrNode>,
    selectedNode: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<NvrTimelapse>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) } // (标题, url)

    suspend fun load() {
        loading = true
        error = null
        try {
            val api = NvrApiClient(context)
            items = withContext(Dispatchers.IO) { api.fetchTimelapse(selectedNode) }
        } catch (e: Exception) {
            error = e.message ?: "无法加载延时摄影"
            DiagLog.e(NTAG, "延时摄影加载失败 node=${selectedNode.ifBlank { "全部" }}：${error}", e)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selectedNode) {
        load()
    }

    fun friendlyDate(name: String): String {
        val digits = name.replace(".mp4", "")
        return if (digits.length == 8) {
            "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
        } else name
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 说明条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                "每 5 分钟从摄像头抓取一帧快照，每天自动合成约 29 秒浓缩视频，云端永久保留，用于回看历史样貌变迁。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.background(TlBlue, RoundedCornerShape(16.dp)).clickable { scope.launch { load() } }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("重试", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无延时摄影", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.node + it.cameraId + it.name }) { tl ->
                    val node = nodes.find { it.id == tl.node }
                    val nodeNick = node?.nick ?: tl.nodeNick
                    // 摄像头昵称：优先用节点状态里的 cam_nicks 映射，回退 cameraId
                    val camNick = node?.camNicks?.get(tl.cameraId) ?: tl.cameraId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .clickable {
                                val api = NvrApiClient(context)
                                DiagLog.i(NTAG, "延时摄影点播 node=${tl.node.ifBlank { "全部" }} ${tl.cameraId} ${tl.name}")
                                playing = Pair(
                                    "${friendlyDate(tl.name)} · 延时摄影",
                                    api.timelapsePlayUrl(tl.node, tl.cameraId, tl.name),
                                )
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 缩略图占位
                        Box(
                            modifier = Modifier
                                .size(width = 96.dp, height = 56.dp)
                                .background(Color(0xFF1D3A5F), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🎬", fontSize = 22.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(friendlyDate(tl.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${nodeNick}·${camNick} · ${"%.1f".format(tl.sizeMb)} MB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(TlBlue, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("▶", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    // 延时视频播放器
    playing?.let { (title, url) ->
        MonitorPlayer(
            title = title,
            urlProvider = { url },
            isLive = false,
            subtitle = "延时摄影 · 首次加载较慢请稍候",
            onClose = { playing = null },
        )
    }
}
