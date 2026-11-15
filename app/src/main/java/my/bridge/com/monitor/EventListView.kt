package my.bridge.com.monitor

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog

private const val NTAG = "监控"

private val EvRed = Color(0xFFE5484D)
private val EvAmber = Color(0xFFF5A623)
private val EvBlue = Color(0xFF1A6EE8)

/**
 * 事件快照缩略图的加载状态（v0.2.159，v0.2.16x 起扩展补图两态）。
 * 区分互斥状态，供列表缩略图框渲染对应视觉：
 * - [Loading] 下载中（转圈 + 文字）
 * - [Success] 已下载（真实快照 Bitmap）
 * - [Failed]  下载失败（醒目 ⚠️ + 可点击重试）
 * - [RegenLoading] 补图中（复用 Loading 样式，文字「补图中」）
 * - [RegenFailed]  补图失败（复用 Failed 样式，文字「无法补图·点按重试」）
 * 无快照（snapshotExists=false）不进入本状态机，由调用方按 canRegen 分流显示「补图」按钮或「无快照」占位。
 */
private sealed class ThumbState {
    object Loading : ThumbState()
    class Success(val bitmap: android.graphics.Bitmap) : ThumbState()
    object Failed : ThumbState()
    object RegenLoading : ThumbState()
    object RegenFailed : ThumbState()
}

/** 事件时间字符串常见格式（服务端 event_time 为 ISO/空格分隔，可能带 3/6 位小数秒），按顺序尝试解析。 */
private val EVENT_TIME_FORMATS = listOf(
    "yyyy-MM-dd HH:mm:ss.SSSSSS",   // 微秒
    "yyyy-MM-dd HH:mm:ss.SSS",      // 毫秒
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
)

/** 解析事件时间字符串为 epoch 毫秒；解析失败返回 null（调用方跳过合并）。 */
private fun parseEventTime(s: String): Long? {
    if (s.isBlank()) return null
    for (fmt in EVENT_TIME_FORMATS) {
        try {
            return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern(fmt))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            // 尝试下一格式
        }
    }
    return null
}

/** 两条事件时间差的绝对值（毫秒）；任一时间解析失败返回 Long.MAX_VALUE（不合并）。 */
private fun timeDiffMillis(a: String, b: String): Long {
    val ta = parseEventTime(a) ?: return Long.MAX_VALUE
    val tb = parseEventTime(b) ?: return Long.MAX_VALUE
    return kotlin.math.abs(ta - tb)
}

/**
 * 事件时间显示格式化（v0.2.148）：服务端 event_time 为摄像头设备上报的原始字符串，
 * 格式不可预测（如 "2026-11-14T0:9:16:30.034390"），不能用标准日期解析。
 * 用字符串截取：去掉年份、"T" 换空格、去掉小数秒（. 之后），输出 "11-14 0:9:16:30"。
 */
private fun formatEventTime(raw: String): String {
    if (raw.isBlank()) return raw
    // 分割日期与时间（"T" 或空格分隔）
    val sepIdx = raw.indexOfFirst { it == 'T' || it == ' ' }
    val datePart = if (sepIdx > 0) raw.substring(0, sepIdx) else raw
    val timePart = if (sepIdx > 0) raw.substring(sepIdx + 1) else ""
    // 日期 "2026-11-14" -> "11-14"（去掉年份）；"20261114" -> "11-14"
    val shortDate = when {
        datePart.length == 10 && datePart[4] == '-' -> datePart.substring(5)
        datePart.length == 8 -> "${datePart.substring(4, 6)}-${datePart.substring(6, 8)}"
        else -> datePart
    }
    // 时间去掉小数秒（. 之后）
    val timeShort = timePart.substringBefore('.')
    return if (timeShort.isBlank()) shortDate else "$shortDate $timeShort"
}

/**
 * 事件页（v0.2.140）。
 *
 * 时间倒序列表 + 人形/运动筛选 + 快照缩略图 + 详情（大图快照）+ 关联录像点播。
 * 支持节点聚合（全部节点）/ 单节点。
 */
@Composable
fun EventListView(
    nodes: List<NvrNode>,
    selectedNode: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf("") } // "" / "human" / "motion"
    var events by remember { mutableStateOf<List<NvrEvent>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }

    // 快照缩略图状态缓存：cameraId|snapshotPath -> ThumbState
    var thumbs by remember { mutableStateOf<Map<String, ThumbState>>(emptyMap()) }

    // 详情弹窗状态
    var detailEvent by remember { mutableStateOf<NvrEvent?>(null) }
    var detailBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // 播放器状态
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) } // (标题, url)

    val listState = rememberLazyListState()

    // 单节点模式下，/api/events 返回的条目不含 node 字段（聚合接口才带），
    // 事件快照/点播需回退到 selectedNode，否则 URL 缺节点前缀 → 404。
    fun eventNode(ev: NvrEvent): String = ev.node.ifBlank { selectedNode }

    // 摄像头昵称：优先用节点状态里的 cam_nicks 映射（{camera_id: 昵称}），回退 cameraId。
    // 与 TimelapseView / CloudBrowserView 一致，避免事件列表显示原始 cam_2 之类的 ID。
    fun camNick(ev: NvrEvent): String {
        val nodeId = eventNode(ev)
        return nodes.find { it.id == nodeId }?.camNicks?.get(ev.cameraId) ?: ev.cameraId
    }

    /** 同摄像头事件合并窗口（毫秒）：窗口内的连续触发合并为一条。 */
    val EVENT_MERGE_WINDOW_MS = 3000L

    /**
     * 事件优先级：有缩略图 > 人形(human) > 普通运动(motion)。
     * 有缩略图 +100，保证「有缩略图」永远压过无图的 level 差异。
     */
    fun eventPriority(ev: NvrEvent): Int {
        var p = if (ev.snapshotExists) 100 else 0
        p += when (ev.level) {
            "human" -> 2
            "motion" -> 1
            else -> 0
        }
        return p
    }

    /**
     * 事件窗口去重（v0.2.158）：同一「节点 + 摄像头」，与**该摄像头最近一条已保留事件**
     * 的时间差 ≤ 3 秒即视为同一次触发（同一人经过时的连续抖动/重复上报），合并为一条，
     * 保留优先级更高的一条（有缩略图 > 人形 > 普通运动；优先级相同保留时间更新者）。
     *
     * 与旧版「仅相邻比较」不同：本版按摄像头各自维护最近一条的索引，**不依赖 hub 返回
     * 的排序**——即使中间夹了其它摄像头的事件，也能正确合并。跨 level 同样合并。
     */
    fun mergeEvents(list: List<NvrEvent>): List<NvrEvent> {
        if (list.isEmpty()) return list
        val out = mutableListOf<NvrEvent>()
        // 每个「节点|cameraId」最近一条已保留事件在 out 中的下标
        val lastIndexByCam = mutableMapOf<String, Int>()
        for (ev in list) {
            val camKey = "${eventNode(ev)}|${ev.cameraId}"
            val lastIdx = lastIndexByCam[camKey]
            if (lastIdx != null) {
                val last = out[lastIdx]
                val near = timeDiffMillis(last.eventTime, ev.eventTime) <= EVENT_MERGE_WINDOW_MS
                if (near) {
                    // 3 秒窗口内：合并，保留优先级更高的一条
                    if (eventPriority(ev) > eventPriority(last)) {
                        out[lastIdx] = ev
                        // 时间更新者作为新基准（index 不变，事件已替换）
                    }
                    continue
                }
            }
            // 超窗 / 该摄像头首条：追加为新事件，记录其下标
            lastIndexByCam[camKey] = out.size
            out.add(ev)
        }
        if (out.size != list.size) {
            DiagLog.i(NTAG, "事件去重：${list.size} 条 → ${out.size} 条")
        }
        return out
    }

    suspend fun loadPage(p: Int, append: Boolean) {
        if (p == 1) loading = true else loadingMore = true
        error = null
        try {
            val api = NvrApiClient(context)
            val result = withContext(Dispatchers.IO) { api.fetchEvents(selectedNode, filter, p, 50) }
            val merged = mergeEvents(result.items)
            events = if (append) events + merged else merged
            total = result.total
            page = p
            hasMore = events.size < result.total
        } catch (e: Exception) {
            error = e.message ?: "无法加载事件"
            DiagLog.e(NTAG, "事件加载失败 node=${selectedNode.ifBlank { "全部" }} filter=$filter p$p：${error}", e)
        } finally {
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(selectedNode, filter) {
        loadPage(1, false)
    }

    // 事件录像点播：先 locate 拿 remote（本地/缓存/云端），再构造 stream URL。
    // 直接跳 stream 会因 remote 缺失/格式错导致 Source error。
    fun playEventVideo(node: String, cameraId: String, name: String, displayName: String = cameraId) {
        scope.launch {
            try {
                val api = NvrApiClient(context)
                val loc = withContext(Dispatchers.IO) {
                    api.locateVideo(node, cameraId = cameraId, name = name)
                }
                val url = if (loc.mode == "local") {
                    // 本地缓冲段：直接用 camera_id + name 跳转模式
                    api.eventVideoUrl(node, cameraId, name)
                } else {
                    // cache / cloud：用 locate 返回的 remote
                    api.cloudStreamUrl(node, loc.remote, loc.source)
                }
                playing = Pair("${displayName} · 事件录像", url)
            } catch (e: Exception) {
                DiagLog.e(NTAG, "事件录像定位失败 ${cameraId}：${e.message}", e)
            }
        }
    }

    // 上拉加载更多
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val totalCount = listState.layoutInfo.totalItemsCount
                if (lastVisible != null && lastVisible >= totalCount - 3 && hasMore && !loadingMore && !loading) {
                    scope.launch { loadPage(page + 1, true) }
                }
            }
    }

    // 加载快照缩略图（v0.2.159）：进入 Loading → 成功后 Success → 失败 Failed（可重试）。
    // force=true 用于失败后点击重试（绕过「已有状态即跳过」的守卫）。
    fun loadThumb(ev: NvrEvent, force: Boolean = false) {
        if (!ev.snapshotExists || ev.snapshotPath.isBlank()) return
        val node = eventNode(ev)
        val key = "${node}|${ev.snapshotPath}"
        val cur = thumbs[key]
        if (!force && cur != null) return  // 已有状态（含 Failed）不重复加载；重试走 force
        thumbs = thumbs + (key to ThumbState.Loading)
        scope.launch {
            try {
                val api = NvrApiClient(context)
                val url = api.snapshotImageUrl(node, ev.snapshotPath)
                val bytes = withContext(Dispatchers.IO) { api.downloadBytes(url) }
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) thumbs = thumbs + (key to ThumbState.Success(bmp))
                else thumbs = thumbs + (key to ThumbState.Failed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 切 Tab 导致协程取消：正常现象，静默（避免海量「离开 composition」噪声日志）
                throw e
            } catch (e: Exception) {
                DiagLog.w(NTAG, "事件缩略图加载失败 ${ev.cameraId}：${e.message}")
                thumbs = thumbs + (key to ThumbState.Failed)
            }
        }
    }

    // 补图（v0.2.16x）：无图事件按需补救快照。进入 RegenLoading → POST → ok/already 则
    // 用服务端返回的 snapshot_path 直接下载缩略图（无需刷新列表），failed 则 RegenFailed。
    // 守卫与 loadThumb 一致：已有补图状态（含 RegenFailed）不重复触发，重试走 force。
    // 补图过程态用 regenKey（node|eventId），成功后的图统一落到缩略图键（node|snapshotPath），
    // 渲染时无图可补走 regenKey、有图走 snapKey，互不干扰。
    fun regenThumb(ev: NvrEvent, force: Boolean = false) {
        if (ev.eventId.isBlank()) return
        val node = eventNode(ev)
        val regenKey = "${node}|${ev.eventId}"
        val cur = thumbs[regenKey]
        if (!force && cur != null) return
        thumbs = thumbs + (regenKey to ThumbState.RegenLoading)
        scope.launch {
            try {
                val api = NvrApiClient(context)
                val res = withContext(Dispatchers.IO) { api.regenSnapshot(node, ev.eventId) }
                if (res.status == "ok" || res.status == "already") {
                    // 补图成功/已有图：服务端已回填 snapshot_path，直接用返回路径下载缩略图
                    val path = res.snapshotPath.ifBlank { ev.snapshotPath }
                    if (path.isNotBlank()) {
                        val snapKey = "${node}|${path}"
                        thumbs = thumbs + (snapKey to ThumbState.Loading)
                        val url = api.snapshotImageUrl(node, path)
                        val bytes = withContext(Dispatchers.IO) { api.downloadBytes(url) }
                        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        if (bmp != null) {
                            thumbs = thumbs + (snapKey to ThumbState.Success(bmp))
                            // 同步更新事件对象：snapshotExists=true + 新 snapshotPath，渲染切到有图分支
                            events = events.map { if (it.eventId == ev.eventId && eventNode(it) == node) it.copy(snapshotExists = true, snapshotPath = path) else it }
                        } else {
                            thumbs = thumbs + (regenKey to ThumbState.RegenFailed)
                        }
                    } else {
                        thumbs = thumbs + (regenKey to ThumbState.RegenFailed)
                    }
                } else {
                    thumbs = thumbs + (regenKey to ThumbState.RegenFailed)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.w(NTAG, "事件补图失败 ${ev.cameraId}：${e.message}")
                thumbs = thumbs + (regenKey to ThumbState.RegenFailed)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选 chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("" to "全部", "human" to "人形", "motion" to "普通运动").forEach { (value, label) ->
                val on = filter == value
                Box(
                    modifier = Modifier
                        .background(if (on) EvBlue else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .clickable { filter = value }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(label, fontSize = 13.sp, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.background(EvBlue, RoundedCornerShape(16.dp)).clickable { scope.launch { loadPage(1, false) } }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("重试", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无事件", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(events, key = { it.cameraId + it.eventTime + eventNode(it) }) { ev ->
                    val node = eventNode(ev)
                    val snapKey = "${node}|${ev.snapshotPath}"
                    val regenKey = "${node}|${ev.eventId}"
                    if (ev.snapshotExists && ev.snapshotPath.isNotBlank()) {
                        loadThumb(ev)
                    }
                    // 有图走缩略图状态机；无图可补走补图状态机；无图不可补为 null（显示无快照占位）
                    val thumbState = when {
                        ev.snapshotExists -> thumbs[snapKey]
                        ev.canRegen -> thumbs[regenKey]
                        else -> null
                    }
                    EventItem(
                        event = ev,
                        thumbState = thumbState,
                        camNick = camNick(ev),
                        nodeNick = nodes.find { it.id == node }?.nick ?: ev.nodeNick,
                        onClick = {
                            detailEvent = ev
                            detailBitmap = (thumbState as? ThumbState.Success)?.bitmap
                        },
                        onRetryThumb = if (thumbState is ThumbState.Failed) {
                            { loadThumb(ev, force = true) }
                        } else null,
                        onRegen = if (ev.canRegen && !ev.snapshotExists && thumbState != ThumbState.RegenLoading) {
                            { regenThumb(ev, force = thumbState is ThumbState.RegenFailed) }
                        } else null,
                        onPlayVideo = {
                            // 关联录像点播：先 locate 拿 remote，再 stream（否则 Source error）
                            val camId = ev.cameraId
                            val name = ev.segmentName
                            if (name.isNotBlank()) {
                                DiagLog.i(NTAG, "事件录像点播 ${ev.cameraId} segment=${name}")
                                playEventVideo(node, camId, name, camNick(ev))
                            } else {
                                DiagLog.w(NTAG, "事件 ${ev.cameraId} 无关联录像（segment_name 为空）")
                            }
                        },
                    )
                }
                if (loadingMore) {
                    item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) } }
                }
            }
        }
    }

    // 详情：全屏可缩放图片查看器（大图快照 + 看录像）
    detailEvent?.let { ev ->
        val node = eventNode(ev)
        val camId = ev.cameraId
        val name = ev.segmentName
        if (detailBitmap != null) {
            ZoomableImageView(
                title = "${camNick(ev)} · ${if (ev.level == "human") "人形事件" else "运动事件"}",
                subtitle = formatEventTime(ev.eventTime),
                bitmap = detailBitmap!!,
                onClose = { detailEvent = null },
                actionLabel = if (name.isNotBlank()) "看录像" else "",
                onAction = if (name.isNotBlank()) {
                    {
                        detailEvent = null
                        playEventVideo(node, camId, name, camNick(ev))
                    }
                } else null,
            )
        } else {
            // 无快照：退化到原 AlertDialog（仅时间信息 + 看录像）
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { detailEvent = null },
                title = {
                    Text("${camNick(ev)} · ${if (ev.level == "human") "人形事件" else "运动事件"}")
                },
                text = {
                    Column {
                        Text(formatEventTime(ev.eventTime), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("暂无快照", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            detailEvent = null
                            if (name.isNotBlank()) {
                                playEventVideo(node, camId, name, camNick(ev))
                            }
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("看录像") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { detailEvent = null }) { Text("关闭") }
                },
            )
        }
    }

    // 关联录像播放器
    playing?.let { (title, url) ->
        MonitorPlayer(
            title = title,
            urlProvider = { url },
            isLive = false,
            subtitle = "事件关联录像回放",
            onClose = { playing = null },
        )
    }
}

@Composable
private fun EventItem(
    event: NvrEvent,
    thumbState: ThumbState?,
    camNick: String,
    nodeNick: String,
    onClick: () -> Unit,
    onRetryThumb: (() -> Unit)?,
    onRegen: (() -> Unit)?,
    onPlayVideo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图：区分「无快照 / 下载中 / 失败(可重试) / 成功」
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 62.dp)
                .background(Color.Black, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !event.snapshotExists && !event.canRegen -> {
                    // 无快照且不可补：灰色占位 + 文案（与加载中/失败明确区分）
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🈳", fontSize = 20.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("无快照", fontSize = 9.sp, color = Color(0xFF9AA0A6))
                    }
                }
                !event.snapshotExists && event.canRegen -> {
                    // 无图但可补：按补图状态分流（初始/失败=「⟳ 补图」按钮，进行中=转圈「补图中」）
                    when (thumbState) {
                        is ThumbState.RegenLoading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = EvBlue)
                                Spacer(Modifier.height(3.dp))
                                Text("补图中", fontSize = 9.sp, color = EvBlue)
                            }
                        }
                        is ThumbState.RegenFailed -> {
                            // 补图失败：⚠️ + 点按重试（重新 POST）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (onRegen != null) Modifier.clickable { onRegen() } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("⚠️", fontSize = 20.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text("无法补图·点按重试", fontSize = 8.sp, color = EvAmber, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                        else -> {
                            // 初始可补：⟳ 补图 按钮
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (onRegen != null) Modifier.clickable { onRegen() } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("⟳", fontSize = 20.sp, color = EvBlue)
                                    Spacer(Modifier.height(2.dp))
                                    Text("补图", fontSize = 9.sp, color = EvBlue)
                                }
                            }
                        }
                    }
                }
                thumbState == null -> {
                    // 尚未开始加载（即将进入 Loading）
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF9AA0A6))
                }
                thumbState is ThumbState.Loading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = EvBlue)
                        Spacer(Modifier.height(3.dp))
                        Text("下载中", fontSize = 9.sp, color = EvBlue)
                    }
                }
                thumbState is ThumbState.Failed -> {
                    // 失败：醒目 ⚠️ + 可点击重试
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (onRetryThumb != null) Modifier.clickable { onRetryThumb() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️", fontSize = 20.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(if (onRetryThumb != null) "加载失败·点按重试" else "加载失败", fontSize = 8.sp, color = EvAmber, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                thumbState is ThumbState.Success -> {
                    Image(bitmap = thumbState.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(if (event.level == "human") Color(0x22E5484D) else Color(0x22F5A623), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (event.level == "human") "人形" else "运动",
                        fontSize = 11.sp,
                        color = if (event.level == "human") EvRed else EvAmber,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(camNick, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(4.dp))
            Text(formatEventTime(event.eventTime), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (nodeNick.isNotBlank()) {
                Text(nodeNick, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (event.segmentName.isNotBlank()) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { onPlayVideo() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("看录像", fontSize = 11.sp, color = EvBlue)
            }
        }
    }
}
