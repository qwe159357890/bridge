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

    // 快照缩略图缓存：cameraId|snapshotPath -> Bitmap
    var thumbs by remember { mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap()) }

    // 详情弹窗状态
    var detailEvent by remember { mutableStateOf<NvrEvent?>(null) }
    var detailBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // 播放器状态
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) } // (标题, url)

    val listState = rememberLazyListState()

    // 单节点模式下，/api/events 返回的条目不含 node 字段（聚合接口才带），
    // 事件快照/点播需回退到 selectedNode，否则 URL 缺节点前缀 → 404。
    fun eventNode(ev: NvrEvent): String = ev.node.ifBlank { selectedNode }

    /**
     * 事件去重：同节点 + 同摄像头、事件时间差 ≤ 1 秒（「几乎同一秒」）的相邻事件视为
     * 同一次触发（服务端抖动/重复触发），跨 level（人形/普通运动）也合并，保留优先级更高的一条：
     * 有缩略图 > 人形(human) > 普通运动(motion)。优先级相同则保留先出现（时间更新）的那条。
     */
    fun eventPriority(ev: NvrEvent): Int {
        // 有缩略图 +100，保证「有缩略图」永远压过无图的 level 差异
        var p = if (ev.snapshotExists) 100 else 0
        p += when (ev.level) {
            "human" -> 2
            "motion" -> 1
            else -> 0
        }
        return p
    }

    fun mergeEvents(list: List<NvrEvent>): List<NvrEvent> {
        if (list.isEmpty()) return list
        val out = mutableListOf<NvrEvent>()
        for (ev in list) {
            val last = out.lastOrNull()
            val sameCam = last != null && last.cameraId == ev.cameraId && eventNode(last) == eventNode(ev)
            val near = sameCam && timeDiffMillis(last.eventTime, ev.eventTime) <= 1000L
            if (near) {
                // 保留优先级更高的一条；新条目优先级更高则替换
                if (eventPriority(ev) > eventPriority(last)) {
                    out[out.lastIndex] = ev
                }
                // 否则丢弃新条目（相邻重复）
            } else {
                out.add(ev)
            }
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
    fun playEventVideo(node: String, cameraId: String, name: String) {
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
                playing = Pair("${cameraId} · 事件录像", url)
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

    // 加载快照缩略图
    fun loadThumb(ev: NvrEvent) {
        if (!ev.snapshotExists || ev.snapshotPath.isBlank()) return
        val node = eventNode(ev)
        val key = "${node}|${ev.snapshotPath}"
        if (thumbs.containsKey(key)) return
        scope.launch {
            try {
                val api = NvrApiClient(context)
                val url = api.snapshotImageUrl(node, ev.snapshotPath)
                val bytes = withContext(Dispatchers.IO) { api.downloadBytes(url) }
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) thumbs = thumbs + (key to bmp)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 切 Tab 导致协程取消：正常现象，静默（避免海量「离开 composition」噪声日志）
                throw e
            } catch (e: Exception) {
                DiagLog.w(NTAG, "事件缩略图加载失败 ${ev.cameraId}：${e.message}")
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
                    val key = "${node}|${ev.snapshotPath}"
                    loadThumb(ev)
                    EventItem(
                        event = ev,
                        thumb = thumbs[key],
                        nodeNick = nodes.find { it.id == node }?.nick ?: ev.nodeNick,
                        onClick = {
                            detailEvent = ev
                            detailBitmap = thumbs[key]
                        },
                        onPlayVideo = {
                            // 关联录像点播：先 locate 拿 remote，再 stream（否则 Source error）
                            val camId = ev.cameraId
                            val name = ev.segmentName
                            if (name.isNotBlank()) {
                                DiagLog.i(NTAG, "事件录像点播 ${ev.cameraId} segment=${name}")
                                playEventVideo(node, camId, name)
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
                title = "${ev.cameraId} · ${if (ev.level == "human") "人形事件" else "运动事件"}",
                subtitle = formatEventTime(ev.eventTime),
                bitmap = detailBitmap!!,
                onClose = { detailEvent = null },
                actionLabel = if (name.isNotBlank()) "看录像" else "",
                onAction = if (name.isNotBlank()) {
                    {
                        detailEvent = null
                        playEventVideo(node, camId, name)
                    }
                } else null,
            )
        } else {
            // 无快照：退化到原 AlertDialog（仅时间信息 + 看录像）
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { detailEvent = null },
                title = {
                    Text("${ev.cameraId} · ${if (ev.level == "human") "人形事件" else "运动事件"}")
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
                                playEventVideo(node, camId, name)
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
    thumb: android.graphics.Bitmap?,
    nodeNick: String,
    onClick: () -> Unit,
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
        // 缩略图
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 62.dp)
                .background(Color.Black, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(bitmap = thumb.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(if (event.level == "human") "🚶" else "🐕", fontSize = 24.sp)
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
                Text(event.cameraId, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
