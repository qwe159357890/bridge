package my.bridge.com.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** 监控功能统一日志 tag（DiagLog 上传链路可见）。 */
private const val NTAG = "监控"

/**
 * NVR 云同步系统 HTTP API 客户端（v0.2.140）。
 *
 * 直接消费参考项目 `references/NVR` 的服务端 REST API（零服务端改动）：
 * - 节点清单/健康：GET /api/hub/nodes
 * - 摄像头清单：GET /api/live/cameras（经节点代理 /<节点>/api/live/cameras）
 * - 静态快照：GET /api/live/snapshot/{id}?quality=sub|main（抓一帧即断）
 * - 实时音视频：GET /api/live/avstream/{id}?quality=sub|main&conn=（fMP4，H.264+AAC）
 * - 心跳续租：GET /api/live/heartbeat?conn=
 * - 事件：GET /api/events（单节点）/ GET /api/hub/events（聚合）
 * - 事件快照：GET /api/snapshot?path=
 * - 云端目录：GET /api/cloud/list（单节点）/ GET /api/hub/cloud/list（聚合）
 * - 云端点播：GET /api/video/stream?remote=&source= / 事件录像 GET /api/video/stream?camera_id=&name=
 * - 延时摄影：GET /api/timelapse/list（单节点）/ GET /api/hub/timelapse/list（聚合）；GET /api/timelapse/play/{camera_id}/{name}
 *
 * 鉴权：请求头 X-API-Token；媒体流 URL（img/video 无法自定义头）用 query 参数 token=。
 * 多节点：请求经 hub 代理前缀 /<节点ID>/... 路由；聚合接口直接走 /api/hub/...。
 *
 * 设计：独立 OkHttpClient（不与主 WS 心跳耦合），网络调用统一切 IO 线程。
 */
class NvrApiClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // 媒体流长连接不设读超时
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** hub 地址（去斜杠）。 */
    private fun base(): String {
        val url = MonitorPrefs.hubUrl(appContext)
        return if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            url
        } else {
            "http://$url"
        }.trimEnd('/')
    }

    private fun token(): String = MonitorPrefs.token(appContext)

    /** 构造带令牌头的 GET 请求。 */
    private fun req(path: String, params: Map<String, String> = emptyMap()): Request {
        val url = base().toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments(path.trimStart('/'))
            ?.apply { params.forEach { (k, v) -> if (v.isNotEmpty()) addQueryParameter(k, v) } }
            ?.build()
            ?: throw IllegalStateException("监控服务地址非法：${base()}")
        val b = Request.Builder().url(url).get()
        if (token().isNotEmpty()) b.header("X-API-Token", token())
        return b.build()
    }

    /** 执行并解析 JSON（失败抛异常）。连接类失败（VNT 隧道秒级抖动）自动重试一次。 */
    private fun executeJson(request: Request): JSONObject {
        val path = request.url.encodedPath
        val started = System.currentTimeMillis()
        var lastConnError: java.io.IOException? = null
        // 连接类失败重试一次（参考服务端 _get_node_json：VNT 首连抖动重试即通）
        for (attempt in 0..1) {
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    val cost = System.currentTimeMillis() - started
                    if (!resp.isSuccessful) {
                        DiagLog.w(NTAG, "API 失败 ${resp.code} 耗时${cost}ms ${path}${if (!body.isNullOrBlank()) " | ${body.take(200)}" else ""}")
                        throw NvrException("HTTP ${resp.code}", body)
                    }
                    DiagLog.i(NTAG, "API 成功 耗时${cost}ms ${path}")
                    return JSONObject(body ?: "{}")
                }
            } catch (e: NvrException) {
                throw e
            } catch (e: java.io.IOException) {
                lastConnError = e
                if (attempt == 0) {
                    DiagLog.w(NTAG, "API 连接失败，重试一次 ${path}：${e.message}")
                    Thread.sleep(600)
                }
            } catch (e: Exception) {
                val cost = System.currentTimeMillis() - started
                DiagLog.e(NTAG, "API 异常 耗时${cost}ms ${path}", e)
                throw e
            }
        }
        val cost = System.currentTimeMillis() - started
        DiagLog.e(NTAG, "API 连接失败 耗时${cost}ms ${path}", lastConnError)
        throw lastConnError ?: IllegalStateException("连接失败")
    }

    // ============ 节点 ============

    /** 节点清单与健康状态。 */
    fun fetchNodes(): List<NvrNode> {
        val json = executeJson(req("/api/hub/nodes"))
        val arr = json.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<NvrNode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // status.cam_nicks：摄像头昵称表（{camera_id: 昵称}），云端目录摄像头层显示昵称用
            val camNicks = mutableMapOf<String, String>()
            o.optJSONObject("status")?.optJSONObject("cam_nicks")?.let { cn ->
                val keys = cn.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    camNicks[k] = cn.optString(k)
                }
            }
            out.add(
                NvrNode(
                    id = o.optString("id"),
                    nick = o.optString("nick").ifBlank { o.optString("id") },
                    online = o.optBoolean("online"),
                    summary = o.optString("summary"),
                    camNicks = camNicks,
                )
            )
        }
        DiagLog.i(NTAG, "节点清单 ${out.size} 个（在线 ${out.count { it.online }}）")
        return out
    }

    // ============ 摄像头 ============

    /** 摄像头清单（单节点）；node 为空则直连（无代理前缀）。 */
    fun fetchCameras(node: String): List<NvrCamera> {
        val path = if (node.isBlank()) "/api/live/cameras" else "/$node/api/live/cameras"
        val json = executeJson(req(path))
        val arr = json.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<NvrCamera>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(NvrCamera(o.optString("camera_id"), o.optString("nick"), node))
        }
        DiagLog.i(NTAG, "摄像头清单 node=${node.ifBlank { "全部" }} 共 ${out.size} 路")
        return out
    }

    /** 聚合摄像头清单（全部在线节点并发拉取合并，条目附 node 标记所属节点）。
     *  参考前端 Live.vue「全部节点」视图：逐在线节点拉 /<节点>/api/live/cameras，
     *  某节点失败跳过不影响其余；全部失败返回空列表。 */
    fun fetchAllCameras(nodes: List<NvrNode>): List<NvrCamera> {
        val online = nodes.filter { it.online }
        if (online.isEmpty()) return emptyList()
        val out = mutableListOf<NvrCamera>()
        for (n in online) {
            try {
                out.addAll(fetchCameras(n.id))
            } catch (e: Exception) {
                DiagLog.w(NTAG, "聚合摄像头：节点 ${n.id} 拉取失败，跳过（${e.message}）")
            }
        }
        DiagLog.i(NTAG, "聚合摄像头 ${online.size} 个在线节点 共 ${out.size} 路")
        return out
    }

    /** 单帧快照 URL（带令牌 query）。 */
    fun snapshotUrl(node: String, cameraId: String, quality: String = "sub"): String {
        val path = if (node.isBlank()) "/api/live/snapshot/$cameraId" else "/$node/api/live/snapshot/$cameraId"
        return mediaUrl(path, mapOf("quality" to quality))
    }

    /** 实时音视频流 URL（fMP4，带令牌 query + conn 租约）。 */
    fun avstreamUrl(node: String, cameraId: String, quality: String = "sub", conn: String): String {
        val path = if (node.isBlank()) "/api/live/avstream/$cameraId" else "/$node/api/live/avstream/$cameraId"
        return mediaUrl(path, mapOf("quality" to quality, "conn" to conn))
    }

    /** 心跳续租（返回 true=租约仍在，false=已被回收）。
     *  node：该实时流所属节点 ID（空=直连单节点）；必须与 avstream 的节点前缀一致，
     *  否则 hub 代理会把 heartbeat 路由到错误节点（404）。 */
    fun heartbeat(node: String, conn: String): Boolean {
        val path = if (node.isBlank()) "/api/live/heartbeat" else "/$node/api/live/heartbeat"
        val request = req(path, mapOf("conn" to conn))
        return try {
            client.newCall(request).execute().use {
                if (!it.isSuccessful) {
                    DiagLog.w(NTAG, "心跳续租失败 HTTP ${it.code} ${path}")
                }
                it.isSuccessful
            }
        } catch (e: Exception) {
            DiagLog.w(NTAG, "心跳续租异常：${e.message}")
            false
        }
    }

    /** 事件快照图片 URL。 */
    fun snapshotImageUrl(node: String, path: String): String {
        val p = if (node.isBlank()) "/api/snapshot" else "/$node/api/snapshot"
        return mediaUrl(p, mapOf("path" to path))
    }

    // ============ 事件 ============

    /** 事件列表（node 空=聚合 /api/hub/events，否则单节点 /api/events）。 */
    fun fetchEvents(node: String, level: String, page: Int, pageSize: Int): NvrEventPage {
        val path = if (node.isBlank()) "/api/hub/events" else "/$node/api/events"
        val json = executeJson(
            req(path, mapOf("level" to level, "page" to page.toString(), "page_size" to pageSize.toString()))
        )
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<NvrEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                NvrEvent(
                    cameraId = o.optString("camera_id"),
                    level = o.optString("level"),
                    eventTime = o.optString("event_time"),
                    snapshotPath = o.optString("snapshot_path"),
                    snapshotExists = o.optBoolean("snapshot_exists"),
                    segmentName = o.optString("segment_name"),
                    node = o.optString("node"),
                    nodeNick = o.optString("node_nick"),
                )
            )
        }
        return NvrEventPage(
            total = json.optInt("total"),
            page = json.optInt("page", page),
            pageSize = json.optInt("page_size", pageSize),
            items = items,
        ).also {
            DiagLog.i(NTAG, "事件列表 node=${node.ifBlank { "全部" }} level=${level.ifBlank { "全部" }} p$page 返回 ${items.size}/${it.total}")
        }
    }

    // ============ 云端 ============

    /** 云端目录浏览（node 空=聚合 /api/hub/cloud/list）。 */
    fun fetchCloudList(node: String, path: String, source: String): NvrCloudDir {
        val p = if (node.isBlank()) "/api/hub/cloud/list" else "/$node/api/cloud/list"
        val json = executeJson(req(p, mapOf("path" to path, "source" to source)))
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<NvrCloudItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                NvrCloudItem(
                    name = o.optString("name"),
                    isFolder = o.optBoolean("is_folder"),
                    sizeMb = o.optDouble("size_mb"),
                )
            )
        }
        return NvrCloudDir(
            source = json.optString("source"),
            path = json.optString("path", path),
            items = items,
            node = json.optString("node"),
        ).also {
            DiagLog.i(NTAG, "云端目录 node=${node.ifBlank { "全部" }} path=${path.ifBlank { "/" }} 返回 ${items.size} 项")
        }
    }

    /** 云端点播流 URL（remote 模式）。
     *  remote 必须是 locate 返回的完整远端路径（带 /NVR备份/<节点>/ 前缀）。 */
    fun cloudStreamUrl(node: String, remote: String, source: String): String {
        val p = if (node.isBlank()) "/api/video/stream" else "/$node/api/video/stream"
        return mediaUrl(p, mapOf("remote" to remote, "source" to source))
    }

    /** 事件关联录像点播流 URL（camera_id + name 跳转模式）。 */
    fun eventVideoUrl(node: String, cameraId: String, name: String): String {
        val p = if (node.isBlank()) "/api/video/stream" else "/$node/api/video/stream"
        return mediaUrl(p, mapOf("camera_id" to cameraId, "name" to name))
    }

    /** 点播预定位：探测视频实际位置（本地缓冲 / 缓存 / 云端），返回 remote 完整路径。
     *  云端/事件点播必须先走这里拿到 remote，再调 streamUrl，否则 stream 会 Source error。 */
    fun locateVideo(
        node: String,
        cameraId: String = "",
        name: String = "",
        path: String = "",
        source: String = "",
    ): NvrLocate {
        val p = if (node.isBlank()) "/api/video/locate" else "/$node/api/video/locate"
        val params = mutableMapOf<String, String>()
        if (cameraId.isNotEmpty()) params["camera_id"] = cameraId
        if (name.isNotEmpty()) params["name"] = name
        if (path.isNotEmpty()) params["path"] = path
        if (source.isNotEmpty()) params["source"] = source
        val json = executeJson(req(p, params))
        return NvrLocate(
            mode = json.optString("mode"),
            source = json.optString("source"),
            remote = json.optString("remote"),
        ).also {
            DiagLog.i(NTAG, "点播定位 node=${node.ifBlank { "全部" }} mode=${it.mode} remote=${it.remote}")
        }
    }

    // ============ 延时摄影 ============

    /** 延时摄影列表（node 空=聚合 /api/hub/timelapse/list）。 */
    fun fetchTimelapse(node: String): List<NvrTimelapse> {
        val path = if (node.isBlank()) "/api/hub/timelapse/list" else "/$node/api/timelapse/list"
        val json = executeJson(req(path))
        val arr = json.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<NvrTimelapse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                NvrTimelapse(
                    name = o.optString("name"),
                    sizeMb = o.optDouble("size_mb"),
                    cameraId = o.optString("camera_id"),
                    node = o.optString("node"),
                    nodeNick = o.optString("node_nick"),
                )
            )
        }
        DiagLog.i(NTAG, "延时摄影列表 node=${node.ifBlank { "全部" }} 返回 ${out.size} 条")
        return out
    }

    /** 延时摄影点播 URL。 */
    fun timelapsePlayUrl(node: String, cameraId: String, name: String): String {
        val p = if (node.isBlank()) "/api/timelapse/play/$cameraId/$name" else "/$node/api/timelapse/play/$cameraId/$name"
        return mediaUrl(p, emptyMap())
    }

    // ============ 工具 ============

    /** 构造媒体流 URL（img/video 无法自定义头，令牌走 query 参数 token=）。 */
    private fun mediaUrl(path: String, params: Map<String, String>): String {
        val b = base().toHttpUrlOrNull()?.newBuilder()?.addPathSegments(path.trimStart('/'))
            ?: throw IllegalStateException("监控服务地址非法：${base()}")
        params.forEach { (k, v) -> if (v.isNotEmpty()) b.addQueryParameter(k, v) }
        if (token().isNotEmpty()) b.addQueryParameter("token", token())
        return b.build().toString()
    }

    /** 阻塞式下载二进制（快照图），返回字节数组；失败抛异常。 */
    fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get()
            .apply { if (token().isNotEmpty()) header("X-API-Token", token()) }
            .build()
        val started = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DiagLog.w(NTAG, "下载失败 HTTP ${resp.code} ${request.url.encodedPath}")
                    throw NvrException("HTTP ${resp.code}", null)
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                DiagLog.i(NTAG, "下载 ${request.url.encodedPath} ${bytes.size} 字节 耗时${System.currentTimeMillis() - started}ms")
                bytes
            }
        } catch (e: NvrException) {
            throw e
        } catch (e: Exception) {
            DiagLog.e(NTAG, "下载异常 ${request.url.encodedPath}", e)
            throw e
        }
    }
}

/** NVR API 异常（携带 HTTP 状态与响应体）。 */
class NvrException(message: String, val body: String?) : Exception(message)

// ============ 数据模型 ============

data class NvrNode(
    val id: String,
    val nick: String,
    val online: Boolean,
    val summary: String,
    val camNicks: Map<String, String> = emptyMap(),
)

data class NvrCamera(
    val cameraId: String,
    val nick: String,
    val node: String = "",
)

data class NvrEvent(
    val cameraId: String,
    val level: String,
    val eventTime: String,
    val snapshotPath: String,
    val snapshotExists: Boolean,
    val segmentName: String,
    val node: String,
    val nodeNick: String,
)

data class NvrEventPage(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val items: List<NvrEvent>,
)

data class NvrCloudItem(
    val name: String,
    val isFolder: Boolean,
    val sizeMb: Double,
)

data class NvrCloudDir(
    val source: String,
    val path: String,
    val items: List<NvrCloudItem>,
    val node: String,
)

data class NvrTimelapse(
    val name: String,
    val sizeMb: Double,
    val cameraId: String,
    val node: String,
    val nodeNick: String,
)

/** 点播预定位结果（/api/video/locate 返回）。 */
data class NvrLocate(
    val mode: String,      // local / cache / cloud
    val source: String,    // 来源展示名（本地缓冲 / 点播缓存 / 天翼 / 百度）
    val remote: String,    // 云端命中时的完整远端路径（带 /NVR备份/<节点>/ 前缀）
)
