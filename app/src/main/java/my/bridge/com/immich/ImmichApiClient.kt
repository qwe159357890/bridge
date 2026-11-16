package my.bridge.com.immich

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

import my.bridge.com.core.common.DiagLog

/** 相册功能统一日志 tag（DiagLog 上传链路可见）。 */
private const val NTAG = "相册"

/**
 * Immich 服务端 REST API 客户端（接 API 阶段，2026-11-16）。
 *
 * 直连官方 Immich 服务端（调研报告 §三，端点已逐一实测）：
 * - 登录：POST /api/auth/login → accessToken；校验：POST /api/auth/validateToken
 * - 相册：GET /api/albums?withoutAssets=true（含 albumThumbnailAssetId 封面）；GET /api/albums/{id}
 * - 资产检索：POST /api/search/metadata（分页 order/type/personIds/isFavorite/isArchived/isTrashed）
 * - 资产详情：GET /api/assets/{id}（含 exifInfo + people + faces）
 * - 统计：GET /api/assets/statistics（images/videos/total）
 * - 人物：GET /api/people；人物头像 GET /api/people/{id}/thumbnail
 * - 收藏：PUT /api/assets/{id} {isFavorite}；删除：DELETE /api/assets {ids}
 * - 媒体：GET /api/assets/{id}/thumbnail?size=thumbnail|preview、/original、/video/playback
 * - 上传：POST /api/assets（multipart）+ POST /api/assets/bulk-upload-check（去重兜底）
 *
 * 鉴权：请求头 x-immich-user-token（会话令牌）；401 时自动用账密重登一次并重试。
 * 设计：独立 OkHttpClient（与监控 NvrApiClient 同范式），网络调用为阻塞式，
 * 调用方统一切 IO 线程（ImmichViewModel / Worker 负责）。
 */
class ImmichApiClient(private val appContext: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 服务端 base（补协议、去末尾斜杠）。 */
    private fun base(): String {
        val url = ImmichPrefs.baseUrl(appContext)
        return if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            url
        } else {
            "http://$url"
        }.trimEnd('/')
    }

    private fun token(): String = ImmichPrefs.token(appContext)

    /** 构造带令牌头的请求。 */
    private fun req(method: String, path: String, body: String? = null): Request {
        val url = base().toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments(path.trimStart('/'))
            ?.build()
            ?: throw ImmichException("服务地址非法：${base()}", null)
        val b = Request.Builder().url(url).method(method, body?.toRequestBody("application/json".toMediaType()) ?: ByteArray(0).toRequestBody(null))
        if (token().isNotEmpty()) b.header("x-immich-user-token", token())
        return b.build()
    }

    /** 执行请求并解析 JSON（401 自动重登一次再重试；连接类失败重试一次）。 */
    private fun executeJson(request: Request, allowRelogin: Boolean = true): JSONObject {
        var current = request
        var lastError: Exception? = null
        for (attempt in 0..2) {
            val started = System.currentTimeMillis()
            try {
                client.newCall(current).execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    val cost = System.currentTimeMillis() - started
                    if (resp.code == 401 && allowRelogin && ImmichPrefs.isConfigured(appContext)) {
                        DiagLog.i(NTAG, "令牌失效，自动重登（${request.url.encodedPath}）")
                        login(ImmichPrefs.username(appContext), ImmichPrefs.password(appContext))
                        current = current.newBuilder()
                            .header("x-immich-user-token", token())
                            .build()
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        DiagLog.w(NTAG, "API 失败 ${resp.code} 耗时${cost}ms ${request.url.encodedPath}${if (bodyText.isNotBlank()) " | ${bodyText.take(200)}" else ""}")
                        throw ImmichException("HTTP ${resp.code}", bodyText)
                    }
                    DiagLog.i(NTAG, "API 成功 耗时${cost}ms ${request.url.encodedPath}")
                    return JSONObject(bodyText.ifBlank { "{}" })
                }
            } catch (e: ImmichException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt < 2) {
                    DiagLog.w(NTAG, "API 连接失败，重试（${request.url.encodedPath}）：${e.message}")
                    Thread.sleep(600)
                }
            }
        }
        throw lastError ?: IOException("连接失败")
    }

    // ============ 登录 ============

    /**
     * 账号密码登录，成功后持久化 accessToken / userId / userName。
     *
     * :param username: 登录邮箱
     * :param password: 登录密码
     * :return: accessToken
     */
    fun login(username: String, password: String): String {
        val payload = JSONObject().put("email", username).put("password", password)
        val resp = executeJson(req("POST", "/api/auth/login", payload.toString()), allowRelogin = false)
        val tok = resp.optString("accessToken").orEmpty()
        if (tok.isBlank()) throw ImmichException("登录响应缺 accessToken", resp.toString())
        ImmichPrefs.saveToken(appContext, tok)
        ImmichPrefs.saveUserId(appContext, resp.optString("userId"))
        // 用户名取 owner.name（serverUserName 顶栏首字母用）
        val name = resp.optJSONObject("user")?.optString("name").orEmpty()
        ImmichPrefs.saveServerUserName(appContext, name)
        DiagLog.i(NTAG, "登录成功：$username（token ${tok.length} 字符）")
        return tok
    }

    /**
     * 校验当前 token 有效性（设置页「测试连接」与启动校验用）。
     *
     * :return: true = 令牌有效
     */
    fun validateToken(): Boolean = try {
        val resp = executeJson(req("POST", "/api/auth/validateToken"), allowRelogin = false)
        resp.optBoolean("valid", false).also {
            DiagLog.i(NTAG, "令牌校验：valid=$it")
        }
    } catch (e: Exception) {
        DiagLog.w(NTAG, "令牌校验失败：${e.message}")
        false
    }

    // ============ 相册 ============

    /**
     * 相册列表（withoutAssets=true 轻量拉取，封面用 albumThumbnailAssetId）。
     *
     * :return: 相册列表（按资产数降序）
     */
    fun fetchAlbums(): List<ImmichAlbumInfo> {
        val resp = executeJson(req("GET", "/api/albums?withoutAssets=true"))
        val arr = resp.optJSONArray("albums") ?: JSONArray()
        val out = mutableListOf<ImmichAlbumInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ImmichAlbumInfo(
                    id = o.optString("id"),
                    name = o.optString("albumName").ifBlank { "未命名相册" },
                    assetCount = o.optInt("assetCount"),
                    startDate = o.optString("startDate").take(10),
                    endDate = o.optString("endDate").take(10),
                    thumbnailAssetId = o.optString("albumThumbnailAssetId"),
                ),
            )
        }
        out.sortByDescending { it.assetCount }
        DiagLog.i(NTAG, "相册列表 ${out.size} 个")
        return out
    }

    /**
     * 相册详情（含全部资产数组，本地按三级精细分组渲染）。
     *
     * :param albumId: 相册 ID
     * :return: 相册信息 + 资产列表
     */
    fun fetchAlbumDetail(albumId: String): Pair<ImmichAlbumInfo, List<ImmichAsset>> {
        val resp = executeJson(req("GET", "/api/albums/$albumId"))
        val info = ImmichAlbumInfo(
            id = resp.optString("id"),
            name = resp.optString("albumName").ifBlank { "未命名相册" },
            assetCount = resp.optInt("assetCount"),
            startDate = resp.optString("startDate").take(10),
            endDate = resp.optString("endDate").take(10),
            thumbnailAssetId = resp.optString("albumThumbnailAssetId"),
        )
        val arr = resp.optJSONArray("assets") ?: JSONArray()
        val assets = parseAssets(arr)
        DiagLog.i(NTAG, "相册详情 ${info.name}：${assets.size} 个资产")
        return info to assets
    }

    // ============ 资产检索 ============

    /**
     * 资产检索（时间线/分类/人物页主力端点）。
     *
     * :param page: 页码（从 1 开始）
     * :param size: 每页条数（服务端上限 1000）
     * :param type: IMAGE / VIDEO，空 = 全部
     * :param personIds: 人物 ID 过滤（人物照片页）
     * :param isFavorite: 收藏过滤
     * :param isArchived: 归档过滤
     * :param isTrashed: 回收站过滤
     * :param filename: 文件名模糊搜索
     * :return: 资产页（items + 是否还有下一页）
     */
    fun searchAssets(
        page: Int,
        size: Int,
        type: String = "",
        personIds: List<String> = emptyList(),
        albumId: String = "",
        isFavorite: Boolean? = null,
        isArchived: Boolean? = null,
        isTrashed: Boolean? = null,
        filename: String = "",
    ): ImmichAssetPage {
        val payload = JSONObject().put("page", page).put("size", size).put("order", "desc")
        if (type.isNotBlank()) payload.put("type", type)
        if (personIds.isNotEmpty()) payload.put("personIds", JSONArray(personIds))
        if (albumId.isNotBlank()) payload.put("albumId", albumId)
        isFavorite?.let { payload.put("isFavorite", it) }
        isArchived?.let { payload.put("isArchived", it) }
        isTrashed?.let { payload.put("isTrashed", it) }
        if (filename.isNotBlank()) payload.put("filename", filename)
        val resp = executeJson(req("POST", "/api/search/metadata", payload.toString()))
        val assets = resp.optJSONObject("assets") ?: JSONObject()
        val items = parseAssets(assets.optJSONArray("items") ?: JSONArray())
        // total 字段语义异常（实测恒等于 count），以「本页不足 size = 无下一页」判定翻页结束
        val hasNext = items.size >= size
        DiagLog.i(NTAG, "资产检索 p$page（${filterDesc(type, personIds, isFavorite, isArchived, isTrashed, filename)}）返回 ${items.size} 条")
        return ImmichAssetPage(items = items, hasNext = hasNext)
    }

    /** 生成检索条件描述（日志用）。 */
    private fun filterDesc(
        type: String,
        personIds: List<String>,
        isFavorite: Boolean?,
        isArchived: Boolean?,
        isTrashed: Boolean?,
        filename: String,
    ): String = buildString {
        if (type.isNotBlank()) append("type=$type ")
        if (personIds.isNotEmpty()) append("人物×${personIds.size} ")
        if (isFavorite == true) append("收藏 ")
        if (isArchived == true) append("归档 ")
        if (isTrashed == true) append("回收站 ")
        if (filename.isNotBlank()) append("文件名=$filename ")
        if (isEmpty()) append("全部")
    }.trim()

    /**
     * 资产详情（查看器打开时拉取，含完整 EXIF 与人物）。
     *
     * :param assetId: 资产 ID
     * :return: 资产详情
     */
    fun fetchAsset(assetId: String): ImmichAsset {
        val resp = executeJson(req("GET", "/api/assets/$assetId"))
        return parseAsset(resp)
    }

    /**
     * 服务端资产统计（存储药丸与资料页计数用）。
     *
     * :return: 统计（images / videos / total）
     */
    fun fetchStatistics(): ImmichStatistics {
        val resp = executeJson(req("GET", "/api/assets/statistics"))
        return ImmichStatistics(
            images = resp.optInt("images"),
            videos = resp.optInt("videos"),
            total = resp.optInt("total"),
        ).also { DiagLog.i(NTAG, "资产统计：图 ${it.images} / 视频 ${it.videos} / 共 ${it.total}") }
    }

    // ============ 人物 ============

    /**
     * 人物列表（搜索页人物区 / 人物照片页入口）。
     *
     * :return: 人物列表（隐藏人物已过滤，按名字排序）
     */
    fun fetchPeople(): List<ImmichPerson> {
        val resp = executeJson(req("GET", "/api/people"))
        val arr = resp.optJSONArray("people") ?: JSONArray()
        val out = mutableListOf<ImmichPerson>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("isHidden")) continue
            out.add(ImmichPerson(id = o.optString("id"), name = o.optString("name").ifBlank { "未命名" }))
        }
        out.sortBy { it.name }
        DiagLog.i(NTAG, "人物列表 ${out.size} 个")
        return out
    }

    // ============ 资产操作 ============

    /**
     * 切换收藏（查看器 ♡ 与收藏分类数据源）。
     *
     * :param assetId: 资产 ID
     * :param favorite: 目标收藏态
     */
    fun setFavorite(assetId: String, favorite: Boolean) {
        val payload = JSONObject().put("isFavorite", favorite)
        executeJson(req("PUT", "/api/assets/$assetId", payload.toString()))
        DiagLog.i(NTAG, "收藏切换 $assetId → $favorite")
    }

    /**
     * 删除资产（默认软删进回收站，force=false）。
     *
     * :param assetIds: 资产 ID 列表
     */
    fun trashAssets(assetIds: List<String>) {
        val payload = JSONObject().put("ids", JSONArray(assetIds)).put("force", false)
        executeJson(req("DELETE", "/api/assets", payload.toString()))
        DiagLog.i(NTAG, "删除（入回收站）${assetIds.size} 个资产")
    }

    // ============ 媒体 URL ============

    /**
     * 缩略图 URL（网格用 size=thumbnail webp；查看器用 size=preview jpeg）。
     *
     * :param assetId: 资产 ID
     * :param preview: true = preview 高清预览图（jpeg），false = thumbnail 缩略图（webp）
     * :return: 带 token 查询参数的 URL（Coil 经拦截器加头，此处 query 仅作兜底）
     */
    fun thumbnailUrl(assetId: String, preview: Boolean): String =
        "${base()}/api/assets/$assetId/thumbnail?size=${if (preview) "preview" else "thumbnail"}"

    /** 原图 URL（下载原图 / 系统分享用）。 */
    fun originalUrl(assetId: String): String = "${base()}/api/assets/$assetId/original"

    /** 视频播放 URL（无 HLS 路由 → 统一 video/playback，播放器带令牌头）。 */
    fun playbackUrl(assetId: String): String = "${base()}/api/assets/$assetId/video/playback"

    /** 人物头像 URL（GET /api/people/{id}/thumbnail，实测 200 jpeg）。 */
    fun personThumbnailUrl(personId: String): String = "${base()}/api/people/$personId/thumbnail"

    // ============ 上传（自动备份 / 手动） ============

    /**
     * 批量去重检查（服务端已有同 checksum 资产的集合，上传前兜底）。
     *
     * :param checksums: 文件 SHA-1（base64）列表（≤1000/批）
     * :return: 已存在的 checksum 集合
     */
    fun bulkUploadCheck(checksums: List<String>): Set<String> {
        if (checksums.isEmpty()) return emptySet()
        val arr = JSONArray()
        checksums.forEach { arr.put(JSONObject().put("checksum", it)) }
        val payload = JSONObject().put("assets", arr)
        val resp = executeJson(req("POST", "/api/assets/bulk-upload-check", payload.toString()))
        val out = mutableSetOf<String>()
        val results = resp.optJSONArray("results") ?: JSONArray()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            if (o.optString("status") == "reject") out.add(o.optString("checksum"))
        }
        DiagLog.i(NTAG, "去重检查 ${checksums.size} 个 → 服务端已有 ${out.size} 个")
        return out
    }

    /**
     * 上传单个文件（multipart POST /api/assets；自动备份 Worker 与手动上传共用）。
     *
     * :param file: 本地文件
     * :param isVideo: 是否视频（fileType / duration 字段）
     * :param fileCreatedAt: 文件创建时间（epoch 毫秒）
     * :param fileModifiedAt: 文件修改时间（epoch 毫秒）
     * :param deviceAssetId: 本机唯一标识（MediaStore id）
     * :param duration: 视频时长（"0:00:12.000" 格式，图片传空）
     * :param checksum: SHA-1 base64（上传前已算好，服务端 X-Immich-Check-Sum 头）
     */
    fun uploadAsset(
        file: File,
        isVideo: Boolean,
        fileCreatedAt: Long,
        fileModifiedAt: Long,
        deviceAssetId: String,
        duration: String = "",
        checksum: String,
    ) {
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("deviceAssetId", deviceAssetId)
            addFormDataPart("deviceId", ImmichPrefs.deviceId(appContext))
            addFormDataPart("fileCreatedAt", fileCreatedAt.toString())
            addFormDataPart("fileModifiedAt", fileModifiedAt.toString())
            addFormDataPart("fileType", if (isVideo) "VIDEO" else "IMAGE")
            if (duration.isNotBlank()) addFormDataPart("duration", duration)
            addFormDataPart("filename", file.name)
            addFormDataPart("filedata", file.name, file.asRequestBody(mime.toMediaType()))
        }.build()
        val url = base().toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("api/assets")
            ?.build()
            ?: throw ImmichException("服务地址非法：${base()}", null)
        val request = Request.Builder().url(url).post(body)
            .header("x-immich-user-token", token())
            .header("X-Immich-Check-Sum", checksum)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                DiagLog.w(NTAG, "上传失败 ${resp.code} ${file.name} | ${text.take(200)}")
                throw ImmichException("HTTP ${resp.code}", text)
            }
            DiagLog.i(NTAG, "上传成功 ${file.name}（${file.length() / 1024} KB）")
        }
    }

    /**
     * 下载原图到目标文件（系统分享 / 保存用）。
     *
     * :param assetId: 资产 ID
     * :param dest: 目标文件（调用方给缓存目录文件）
     */
    fun downloadOriginal(assetId: String, dest: File) {
        client.newCall(req("GET", "/api/assets/$assetId/original")).execute().use { resp ->
            if (!resp.isSuccessful) throw ImmichException("HTTP ${resp.code}", null)
            resp.body?.byteStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        DiagLog.i(NTAG, "原图下载完成 ${dest.name}（${dest.length() / 1024} KB）")
    }

    /** 计算文件 SHA-1（base64，与 Immich checksum 口径一致）。 */
    fun sha1Base64(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
}

/** Immich API 异常（携带 HTTP 状态与响应体）。 */
class ImmichException(message: String, val body: String?) : Exception(message)

/**
 * 构造缩略图 URL 的顶层工具（网格块/相册封面/查看器共用，免建 ApiClient 实例）。
 * 鉴权由 ImmichImageLoader 的 OkHttp 拦截器统一补头。
 *
 * :param context: 任意 Context（读 ImmichPrefs 服务地址）
 * :param assetId: 资产 ID
 * :param preview: true = preview 高清预览图（jpeg），false = thumbnail 缩略图（webp）
 * :return: 缩略图 URL
 */
fun immichThumbnailUrl(context: android.content.Context, assetId: String, preview: Boolean): String =
    ImmichPrefs.baseUrl(context).trimEnd('/') + "/api/assets/$assetId/thumbnail?size=${if (preview) "preview" else "thumbnail"}"

// ============ 数据模型 ============

/** 人物条目（GET /api/people / 资产 people 字段）。 */
data class ImmichPerson(
    val id: String,
    val name: String,
)

/** EXIF 信息（资产 exifInfo 子对象）。 */
data class ImmichExif(
    val make: String,
    val model: String,
    val width: Int,
    val height: Int,
    val fileSizeInByte: Long,
    val dateTimeOriginal: String,
    val fNumber: Double,
    val focalLength: Double,
    val iso: Int,
    val exposureTime: String,
    val lensModel: String,
    val city: String,
    val state: String,
    val country: String,
)

/** 资产条目（search/metadata items 与 GET /assets/{id} 统一口径）。 */
data class ImmichAsset(
    val id: String,
    val type: String,                       // IMAGE / VIDEO
    val localDateTime: String,              // 本地拍摄时间（ISO 字符串，按墙钟解析）
    val duration: String,                   // 视频时长 "0:00:12.00000"
    val fileName: String,
    val width: Int,
    val height: Int,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val thumbhash: String,
    val exif: ImmichExif?,
    val people: List<ImmichPerson>,
) {
    /** 是否视频资产。 */
    val isVideo: Boolean get() = type == "VIDEO"

    /** 时长角标文案（"0:00:12.00000" → "0:12"；图片空串）。 */
    val durationLabel: String
        get() {
            if (!isVideo) return ""
            val parts = duration.split(":")
            if (parts.size < 3) return ""
            val m = parts[1].toIntOrNull() ?: 0
            val s = parts[2].takeBefore(".")?.toIntOrNull() ?: 0
            return "%d:%02d".format(m, s)
        }
}

/** 相册条目（GET /api/albums）。 */
data class ImmichAlbumInfo(
    val id: String,
    val name: String,
    val assetCount: Int,
    val startDate: String,
    val endDate: String,
    val thumbnailAssetId: String,
)

/** 资产检索页（hasNext = 本页条数达到 size）。 */
data class ImmichAssetPage(
    val items: List<ImmichAsset>,
    val hasNext: Boolean,
)

/** 服务端资产统计（GET /api/assets/statistics）。 */
data class ImmichStatistics(
    val images: Int,
    val videos: Int,
    val total: Int,
)

// ============ JSON 解析 ============

/**
 * 解析资产数组为 ImmichAsset 列表。
 *
 * :param arr: 服务端 assets JSON 数组
 * :return: 资产列表
 */
private fun parseAssets(arr: JSONArray): List<ImmichAsset> {
    val out = mutableListOf<ImmichAsset>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(parseAsset(o))
    }
    return out
}

/**
 * 解析单个资产对象（列表条目与详情响应同构）。
 *
 * :param o: 资产 JSON 对象
 * :return: ImmichAsset
 */
private fun parseAsset(o: JSONObject): ImmichAsset {
    // EXIF 子对象（列表响应 withExif 未带时为 null）
    val exifObj = o.optJSONObject("exifInfo")
    val exif = exifObj?.let {
        ImmichExif(
            make = it.optString("make").orEmpty(),
            model = it.optString("model").orEmpty(),
            width = it.optInt("exifImageWidth"),
            height = it.optInt("exifImageHeight"),
            fileSizeInByte = it.optLong("fileSizeInByte"),
            dateTimeOriginal = it.optString("dateTimeOriginal").orEmpty(),
            fNumber = it.optDouble("fNumber", 0.0),
            focalLength = it.optDouble("focalLength", 0.0),
            iso = it.optInt("iso"),
            exposureTime = it.optString("exposureTime").orEmpty(),
            lensModel = it.optString("lensModel").orEmpty(),
            city = it.optString("city").orEmpty(),
            state = it.optString("state").orEmpty(),
            country = it.optString("country").orEmpty(),
        )
    }
    // 人物数组（详情响应含 faces，本层只留 id/name；头像独立拉缩略图端点）
    val people = mutableListOf<ImmichPerson>()
    val pArr = o.optJSONArray("people") ?: JSONArray()
    for (i in 0 until pArr.length()) {
        val p = pArr.optJSONObject(i) ?: continue
        people.add(ImmichPerson(id = p.optString("id"), name = p.optString("name").ifBlank { "未命名" }))
    }
    return ImmichAsset(
        id = o.optString("id"),
        type = o.optString("type"),
        localDateTime = o.optString("localDateTime"),
        duration = o.optString("duration").orEmpty(),
        fileName = o.optString("originalFileName").ifBlank { "未命名" },
        width = o.optInt("width"),
        height = o.optInt("height"),
        isFavorite = o.optBoolean("isFavorite"),
        isArchived = o.optBoolean("isArchived"),
        isTrashed = o.optBoolean("isTrashed"),
        thumbhash = o.optString("thumbhash").orEmpty(),
        exif = exif,
        people = people,
    )
}

/**
 * 截取字符串到指定分隔符前（Kotlin String 扩展，ImmichAsset 时长解析用）。
 *
 * :param delimiter: 分隔符
 * :return: 分隔符前的子串（无分隔符返回原串）
 */
private fun String.takeBefore(delimiter: String): String? {
    val idx = indexOf(delimiter)
    return if (idx >= 0) substring(0, idx) else this
}
