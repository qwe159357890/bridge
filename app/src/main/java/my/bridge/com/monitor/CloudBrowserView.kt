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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val CloudBlue = Color(0xFF1A6EE8)

/**
 * 云端录像浏览器（v0.2.140）。
 *
 * 仿网盘层级浏览：NVR备份/<camera_id>/<human|motion>/<YYYYMMDD>/<段名>.mp4。
 * 面包屑路径导航 + 文件夹/文件区分 + 昵称/类别/日期中文映射 + 点播。
 * 支持跨节点浏览（全部节点走 /api/hub/cloud/list 聚合）。
 */
@Composable
fun CloudBrowserView(
    nodes: List<NvrNode>,
    selectedNode: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 路径栈（面包屑），空 = 根目录
    var pathStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<NvrCloudItem>>(emptyList()) }
    var source by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) } // (标题, url)
    // 「全部节点」聚合模式下，hub 会任选一个在线节点代查云端目录；
    // 点播必须路由到这个代查节点（缓存也在那台机器上），否则 remote 定位不到。
    var serveNode by remember { mutableStateOf("") }

    val currentPath = pathStack.joinToString("/")
    // 是否「全部节点」聚合视图
    val isAll = selectedNode.isBlank()

    // 当天日期（YYYYMMDD，8 位，与云端日期目录名一致）
    fun todayYmd(): String {
        val now = java.time.LocalDate.now()
        return String.format("%04d%02d%02d", now.year, now.monthValue, now.dayOfMonth)
    }

    // 当前路径栈（面包屑）：空 = 备份根（NVR备份）
    // 结构：全部节点模式 = NVR备份/<节点ID>/<camera_id>/<human|motion>/<YYYYMMDD>/...；
    //       单节点模式   = NVR备份/<camera_id>/<human|motion>/<YYYYMMDD>/...（顶层即摄像头层）

    // 当前浏览所属节点：全部节点模式下由路径第一段（节点ID）决定；单节点=selectedNode。
    fun nodeIdOf(): String =
        if (isAll) pathStack.firstOrNull() ?: "" else selectedNode

    // 该节点在清单里的对象（取昵称 + 摄像头昵称表用）
    fun nodeOf(): NvrNode? = nodes.find { it.id == nodeIdOf() }

    // 摄像头层所在深度：全部节点模式第 1 层（索引 1），单节点模式第 0 层（索引 0）
    val camDepth = if (isAll) 1 else 0

    // 节点层目录显示名（用节点昵称）
    fun nodeLayerLabel(part: String): String =
        nodeOf()?.nick?.takeIf { it.isNotBlank() } ?: part

    // 摄像头层目录显示名（用摄像头昵称，来自节点的 cam_nicks）
    fun camLayerLabel(part: String): String =
        nodeOf()?.camNicks?.get(part)?.takeIf { it.isNotBlank() } ?: part

    /** 加载目录，成功返回 true；失败返回 false（error 已设置，但日期层目录不存在的回退由调用方处理）。 */
    suspend fun load(path: String): Boolean {
        loading = true
        error = null
        return try {
            val api = NvrApiClient(context)
            val dir = withContext(Dispatchers.IO) { api.fetchCloudList(selectedNode, path, "") }
            items = dir.items
            source = dir.source
            serveNode = dir.node.ifBlank { selectedNode }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（切 Tab/切节点导致 LaunchedEffect 重启）：正常现象，静默 rethrow。
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法浏览云端目录"
            DiagLog.e(NTAG, "云端目录加载失败 node=${selectedNode.ifBlank { "全部" }} path=${path.ifBlank { "/" }}：${error}", e)
            false
        } finally {
            loading = false
        }
    }

    // 进入云端（含切换节点）时：重置路径并按当前节点视图的记忆恢复（前缀 + 今天日期）。
    // 每次 selectedNode 变化都重置并恢复对应记忆，避免跨节点路径串层。
    LaunchedEffect(selectedNode) {
        val prefix = MonitorPrefs.cloudPath(context, selectedNode)
        val segs = if (prefix.isBlank()) emptyList() else prefix.split("/").filter { it.isNotBlank() }
        pathStack = if (segs.isEmpty()) emptyList() else segs + todayYmd()
        if (segs.isNotEmpty()) {
            DiagLog.i(NTAG, "云端路径记忆恢复 node=${selectedNode.ifBlank { "全部" }}：${pathStack.joinToString("/")}")
        }
    }

    // 加载当前路径；若「日期层」目录不存在（记忆恢复的「今天」尚无录像），
    // 回退到类别层再加载一次，避免卡在错误页。回退直接在此 effect 内改 pathStack，
    // 但不会重启本 effect（key 是 selectedNode+currentPath，dropLast 后 currentPath 变化
    // 会重启——这里改为：回退后直接再 load 一次类别层，然后同步记忆，全程不依赖重启）。
    LaunchedEffect(selectedNode, currentPath) {
        var loaded = load(currentPath)
        // 日期层目录不存在时回退到类别层
        if (!loaded && pathStack.size >= camDepth + 3) {
            DiagLog.w(NTAG, "云端日期目录不存在，回退到类别层：$currentPath")
            val fallback = pathStack.dropLast(1)
            pathStack = fallback
            loaded = load(fallback.joinToString("/"))
        }
        // 已进入日期层时记忆前缀（去掉日期层）
        if (loaded && pathStack.size >= camDepth + 3) {
            val prefix = pathStack.dropLast(1).joinToString("/")
            if (prefix != MonitorPrefs.cloudPath(context, selectedNode)) {
                MonitorPrefs.saveCloudPath(context, selectedNode, prefix)
                DiagLog.i(NTAG, "云端路径记忆保存 node=${selectedNode.ifBlank { "全部" }}：$prefix")
            }
        }
    }
    // 云端录像点播：先 locate（path 传相对路径，服务端自动拼 /NVR备份/ 前缀）拿 remote，
    // 再 stream。直接跳 stream 会因 remote 缺 /NVR备份/ 前缀 → Source error。
    fun playCloudVideo(fullPath: String) {
        scope.launch {
            try {
                val api = NvrApiClient(context)
                val loc = withContext(Dispatchers.IO) {
                    api.locateVideo(serveNode, path = fullPath, source = source)
                }
                val url = api.cloudStreamUrl(serveNode, loc.remote, source)
                DiagLog.i(NTAG, "云端录像点播 node=$serveNode remote=${loc.remote}")
                playing = Pair(fullPath.substringAfterLast("/"), url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 切 Tab 导致协程取消（Composable 已离开组合）：正常现象，静默 rethrow
                throw e
            } catch (e: Exception) {
                DiagLog.e(NTAG, "云端录像定位失败 $fullPath：${e.message}", e)
            }
        }
    }

    // 类别层中文映射
    fun catLabel(name: String): String = when (name) {
        "human" -> "人形事件"
        "motion" -> "普通运动"
        else -> name
    }

    // 展示层映射
    fun displayName(name: String, isFolder: Boolean, depth: Int): String {
        if (!isFolder) return name // 文件（段名/视频名）
        // 文件夹层级映射
        return when {
            // 节点层目录（全部节点模式根目录下列出的第一层）：显示节点昵称
            isAll && depth == 0 -> nodeLayerLabel(name)
            depth == camDepth -> camLayerLabel(name) // 摄像头层：显示摄像头昵称
            depth == camDepth + 1 -> catLabel(name)  // 类别层：human/motion 中文
            else -> name
        }
    }

    fun friendlyDate(ymd: String): String {
        if (ymd.length != 8) return ymd
        return "${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6, 8)}"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 面包屑
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                "NVR备份",
                fontSize = 12.sp,
                color = CloudBlue,
                modifier = Modifier.clickable { pathStack = emptyList() },
            )
            pathStack.forEachIndexed { index, seg ->
                Text(" › ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 节点层（全部节点模式 index 0）→ 节点昵称；摄像头层（index == camDepth）→ 摄像头昵称；
                // 类别层（index == camDepth+1）→ 中文；日期层（index == camDepth+2）→ 友好日期
                val label = when (index) {
                    camDepth -> camLayerLabel(seg)
                    camDepth + 1 -> catLabel(seg)
                    camDepth + 2 -> friendlyDate(seg)
                    else -> if (isAll && index == 0) nodeLayerLabel(seg) else seg
                }
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (index == pathStack.lastIndex) MaterialTheme.colorScheme.onSurface else CloudBlue,
                    modifier = Modifier.clickable { pathStack = pathStack.take(index + 1) },
                )
            }
            if (source.isNotBlank()) {
                Spacer(Modifier.weight(1f))
                Text(source, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.background(CloudBlue, RoundedCornerShape(16.dp)).clickable { scope.launch { load(currentPath) } }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("重试", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("目录为空", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.name }) { item ->
                    val depth = pathStack.size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .clickable {
                                if (item.isFolder) {
                                    pathStack = pathStack + item.name
                                } else {
                                    // 点播视频文件：先 locate 再 stream
                                    val full = if (currentPath.isBlank()) item.name else "$currentPath/${item.name}"
                                    playCloudVideo(full)
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (item.isFolder) "📁" else "🎞️", fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (item.isFolder) displayName(item.name, true, depth) else item.name,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                            if (!item.isFolder && item.sizeMb > 0) {
                                Text("${"%.1f".format(item.sizeMb)} MB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (item.isFolder) {
                            Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("▶", fontSize = 14.sp, color = CloudBlue)
                        }
                    }
                }
            }
        }
    }

    // 云端录像播放器
    playing?.let { (title, url) ->
        MonitorPlayer(
            title = title,
            urlProvider = { url },
            isLive = false,
            subtitle = "云端录像回放",
            onClose = { playing = null },
        )
    }
}
