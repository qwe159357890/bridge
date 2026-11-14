package my.bridge.com.monitor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import my.bridge.com.core.common.DiagLog
import java.util.UUID

private const val NTAG = "监控"

private val LiveBlue = Color(0xFF1A6EE8)
private val LiveGreen = Color(0xFF2E9E5B)
private val LiveGrey = Color(0xFF9AA1AC)

/** 单路快照/全部刷新最大超时（毫秒）：防止某路卡死导致刷新按钮一直禁用。 */
private const val SNAPSHOT_TIMEOUT_MS = 15_000L

/**
 * 实时预览页（v0.2.140）。
 *
 * 快照模式卡片墙：默认静态快照（省流），支持单路刷新（卡片右上角 ⟳）与全部刷新（顶栏按钮）；
 * 可选自动轮询（默认关）。点卡片进入全屏音视频流（有声音），全屏期间心跳续租。
 */
@Composable
fun LiveView(
    nodes: List<NvrNode>,
    selectedNode: String,
    nodesLoading: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cameras by remember { mutableStateOf<List<NvrCamera>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // "node|cameraId" -> Bitmap 快照（聚合模式下按节点隔离，避免不同节点同 cameraId 串图）
    var snapshots by remember { mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap()) }
    var refreshingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshingAll by remember { mutableStateOf(false) }
    var playingCam by remember { mutableStateOf<NvrCamera?>(null) }
    // 布局列数（1/2/3，持久化）
    var columns by remember { mutableStateOf(MonitorPrefs.liveColumns(context)) }

    // 全屏播放器租约 + 心跳
    val liveConn = remember { UUID.randomUUID().toString() }

    // 是否「全部节点」聚合视图
    val isAll = selectedNode.isBlank()

    // 某路摄像头所属节点（聚合模式下取条目自身 node，单节点模式下即 selectedNode）
    fun camNode(cam: NvrCamera): String = if (cam.node.isNotBlank()) cam.node else selectedNode

    // 快照缓存的键（节点隔离）
    fun snapKey(cam: NvrCamera): String = "${camNode(cam)}|${cam.cameraId}"

    // 抓单路快照（带超时：防止某路卡死导致按钮一直禁用）
    suspend fun refreshSnapshot(api: NvrApiClient, cam: NvrCamera) {
        val key = snapKey(cam)
        refreshingIds = refreshingIds + key
        try {
            withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
                val url = api.snapshotUrl(camNode(cam), cam.cameraId)
                val bytes = withContext(Dispatchers.IO) { api.downloadBytes(url) }
                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) {
                    snapshots = snapshots + (key to bmp)
                } else {
                    DiagLog.w(NTAG, "快照解码失败 ${cam.cameraId}（${bytes.size} 字节）")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 切节点/切 Tab 导致协程取消：正常现象，静默
            throw e
        } catch (e: Exception) {
            // 单路失败静默（保持旧快照/空态），但记日志供排查
            DiagLog.w(NTAG, "快照刷新失败 ${cam.cameraId}：${e.message}")
        } finally {
            refreshingIds = refreshingIds - key
        }
    }

    // 并发抓所有路快照（等待全部完成或超时后才结束，期间「刷新全部」按钮禁用）
    suspend fun refreshAllSnapshots(api: NvrApiClient) {
        if (cameras.isEmpty()) return
        refreshingAll = true
        DiagLog.i(NTAG, "刷新全部快照 ${cameras.size} 路")
        try {
            withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
                coroutineScope {
                    cameras.forEach { cam ->
                        launch { refreshSnapshot(api, cam) }
                    }
                }
            }
        } finally {
            refreshingAll = false
        }
    }

    // 拉摄像头清单（v0.2.151 改流式：清单到手立即结束 loading 出网格，快照异步逐路点亮）
    suspend fun loadCameras() {
        loading = true
        error = null
        try {
            val api = NvrApiClient(context)
            DiagLog.i(NTAG, "实时预览加载摄像头 node=${if (isAll) "全部" else selectedNode}")
            cameras = withContext(Dispatchers.IO) {
                if (isAll) api.fetchAllCameras(nodes) else api.fetchCameras(selectedNode)
            }
            // 全部节点 + 结果为空 + 确有在线节点：大概率是 VNT 首连抖动导致聚合拉取失败，
            // 延迟 800ms 重试一次（节点清单刚就绪时隧道尚未稳定）。
            if (isAll && cameras.isEmpty() && nodes.any { it.online }) {
                DiagLog.w(NTAG, "全部节点聚合摄像头为空，延迟重试一次（疑似 VNT 首连抖动）")
                delay(800)
                cameras = withContext(Dispatchers.IO) { api.fetchAllCameras(nodes) }
            }
            // ★ 流式显示：摄像头清单到手立即结束 loading，进入网格渲染；
            //   此时各卡片快照为空（显示占位/刷新圈），随后快照并发抓取、逐路点亮，
            //   不再像旧版那样「等所有路快照都返回（或超时）才一次性显示」。
            loading = false
            scope.launch { refreshAllSnapshots(api) }
        } catch (e: Exception) {
            error = e.message ?: "无法连接监控服务"
            DiagLog.e(NTAG, "实时预览加载摄像头失败：${error}", e)
            loading = false
        }
    }

    // 依赖「在线节点集合」而非整个 nodes 列表：节点清单是异步加载的，首次进入时
    // selectedNode 已是 ""（全部节点）但 nodes 仍为空 → fetchAllCameras 返回空，显示「无在线摄像头」。
    // 把在线节点 ID 集合纳入 key，节点清单就绪后自动重新拉取摄像头清单。
    val onlineNodeKeys = nodes.filter { it.online }.map { it.id }.sorted().joinToString(",")
    LaunchedEffect(selectedNode, onlineNodeKeys) {
        loadCameras()
    }

    // 自动轮询（可选）
    val autoRefresh = MonitorPrefs.autoRefresh(context)
    val refreshSeconds = MonitorPrefs.refreshSeconds(context)
    LaunchedEffect(autoRefresh, refreshSeconds, cameras.size) {
        if (!autoRefresh || cameras.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(refreshSeconds * 1000L)
            val api = NvrApiClient(context)
            cameras.forEach { cam ->
                scope.launch { refreshSnapshot(api, cam) }
            }
        }
    }

    // 全屏播放：心跳续租
    DisposableEffect(playingCam) {
        if (playingCam != null) {
            MonitorUiState.playerOpen = true
            val api = NvrApiClient(context)
            val node = camNode(playingCam!!)
            val job = scope.launch {
                while (true) {
                    delay(2000)
                    val ok = withContext(Dispatchers.IO) { api.heartbeat(node, liveConn) }
                    if (!ok) {
                        DiagLog.w(NTAG, "实时流心跳续租失效，租约可能被服务端回收")
                        break
                    }
                }
            }
            onDispose {
                job.cancel()
                MonitorUiState.playerOpen = false
                DiagLog.i(NTAG, "实时流播放器已关闭，停止心跳续租")
            }
        } else {
            MonitorUiState.playerOpen = false
            onDispose { }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：路数 + 布局切换 + 刷新全部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${cameras.size} 路 · ${if (isAll) "全部节点" else (nodes.find { it.id == selectedNode }?.nick ?: selectedNode)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 布局切换：1/2/3 列
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .clickable {
                        columns = if (columns >= 3) 1 else columns + 1
                        MonitorPrefs.saveLiveColumns(context, columns)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "▦ ${columns}列",
                    fontSize = 12.sp,
                    color = LiveBlue,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .clickable(enabled = !refreshingAll) {
                        scope.launch { refreshAllSnapshots(NvrApiClient(context)) }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (refreshingAll) "刷新中…" else "⟳ 刷新全部",
                    fontSize = 12.sp,
                    color = if (refreshingAll) LiveGrey else LiveBlue,
                )
            }
        }

        when {
            // 节点清单还在加载中（首次进入）：显示 loading，避免误判「无在线摄像头」
            loading || nodesLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.background(LiveBlue, RoundedCornerShape(16.dp)).clickable { scope.launch { loadCameras() } }.padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text("重试", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            cameras.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无在线摄像头", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cameras, key = { snapKey(it) }) { cam ->
                    CameraCard(
                        cam = cam,
                        nodeNick = if (isAll) (nodes.find { it.id == camNode(cam) }?.nick ?: "") else "",
                        snapshot = snapshots[snapKey(cam)],
                        refreshing = snapKey(cam) in refreshingIds,
                        onClick = {
                            DiagLog.i(NTAG, "点开实时音视频流 ${cam.cameraId}（${cam.nick}）")
                            playingCam = cam
                        },
                        onRefresh = { scope.launch { refreshSnapshot(NvrApiClient(context), cam) } },
                    )
                }
            }
        }
    }

    // 全屏播放器
    playingCam?.let { cam ->
        MonitorPlayer(
            title = cam.nick,
            urlProvider = { q ->
                NvrApiClient(context).avstreamUrl(camNode(cam), cam.cameraId, q, liveConn)
            },
            isLive = true,
            subtitle = "实时音视频 · 延迟约 2~3 秒",
            onClose = { playingCam = null },
        )
    }
}

@Composable
private fun CameraCard(
    cam: NvrCamera,
    nodeNick: String,
    snapshot: android.graphics.Bitmap?,
    refreshing: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(Color.Black),
        ) {
            if (snapshot != null) {
                Image(
                    bitmap = snapshot.asImageBitmap(),
                    contentDescription = cam.nick,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text("📷", fontSize = 32.sp)
                    }
                }
            }
            // LIVE 角标
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xCCE5484D), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(LiveGreen, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (nodeNick.isNotBlank()) "$nodeNick · ${cam.nick}" else cam.nick,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            // 单路刷新按钮（名称后面，刷新中禁用 + 明显禁用态）
            if (refreshing) {
                Row(
                    modifier = Modifier
                        .background(LiveGrey, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text("刷新中", fontSize = 11.sp, color = Color.White)
                }
            } else {
                Box(
                    modifier = Modifier
                        .background(LiveBlue, RoundedCornerShape(12.dp))
                        .clickable { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("⟳ 刷新", fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}
