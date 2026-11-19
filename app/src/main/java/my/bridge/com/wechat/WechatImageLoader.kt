package my.bridge.com.wechat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 远程图片/头像加载：三级缓存（内存 LRU → 磁盘 → 服务器）+ 采样缩放解码。
 *
 * **不引入 Coil/Glide**——项目没有该依赖，且这类附件都要带 `?token=` 或 Bearer 头，
 * 自己实现更可控（也避免为一张缩略图拉整个图片库）。
 *
 * 契约：`{server}/api/files/{object}?token={key}`、头像 `GET /api/contacts/avatar?wxid=`
 * （`web-frontend/src/utils/avatarManager.ts` 第 84 行，需 Bearer）。
 *
 * ## 磁盘缓存（v0.2.34，补齐「头像/缩略图/封面每次整张重下」的根本漏洞）
 *
 * 参考项目三方源码的两套成熟做法：
 * - web-frontend：IndexedDB 三级缓存（内存 → 磁盘 → 服务器）+ 失败去重 + pending 去重；
 * - pc-client `avatar_manager.py`：头像落盘 `{wxid}.jpg` + 失败记录持久化 + 过期清理。
 *
 * 本实现照 pc-client 的「落盘 + 失败去重 + 过期清理」，落盘目录用 App 私有目录
 * `getExternalFilesDir(null)/bridge/wechat/cache/{avatars,media}`——**缓存不是用户可见
 * 保存**（保存走 [WechatDownloader] 的公共媒体目录），私有目录无需存储权限、不污染相册、
 * 卸载自动清理。
 *
 * 缓存 key 用 URL 的 SHA-256：URL 里的 `?token=` 会变（密钥轮换），直接用 URL 作文件名
 * 既含非法字符又会在换 token 后失效；哈希后文件名稳定，换 token 也能命中。
 */
object WechatImageLoader {

    private const val TAG = "微信图片"
    private const val MAX_EDGE = 720

    /** 磁盘缓存最大保留天数（照 pc-client cleanup_manager 的 7 天）。 */
    private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** 失败去重保留时长（头像 404 等：这个时长内不再重复请求，照 pc-client 的失败记录持久化）。 */
    private const val FAIL_TTL_MS = 30L * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val memory: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 磁盘缓存根目录（App 私有，卸载自动清理）。 */
    @Volatile
    private var cacheRoot: File? = null

    /** Application context（由 [WechatRuntime.start] 注入，避免用隐藏 API ActivityThread）。 */
    @Volatile
    private var appContext: Context? = null

    /** 失败去重：key → 首次失败时间戳（内存态即可，进程重启自然重置，不必持久化）。 */
    private val failMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 同 URL 并发去重（pending 去重，照 web-frontend）：避免列表里同一张图被并发拉多次。 */
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 由 [WechatRuntime.start] 注入 application context（在 Application 启动时调用一次）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    /** 初始化磁盘缓存根目录（首次调用时惰性建目录，避免 Application 启动无谓 IO）。 */
    private fun ensureCacheRoot(): File {
        val ctx = appContext ?: error("WechatImageLoader 未初始化（应先在 WechatRuntime.start 里 init）")
        cacheRoot?.let { return it }
        synchronized(this) {
            cacheRoot?.let { return it }
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "bridge/wechat/cache")
            if (!dir.exists()) dir.mkdirs()
            runCatching { cleanupExpired(dir) }  // 惰性清理，不阻塞主流程
            cacheRoot = dir
            return dir
        }
    }

    private fun subDir(root: File, kind: String): File {
        val d = File(root, kind)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** URL → 稳定文件名（SHA-256，换 token 也命中；`.img` 后缀避开相册/媒体扫描）。 */
    private fun keyOf(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return hex
    }

    /** 磁盘命中：读文件 + 解码。 */
    private fun diskHit(url: String, kind: String, maxEdge: Int): Bitmap? {
        if (appContext == null) return null
        val root = ensureCacheRoot()
        val f = File(subDir(root, kind), keyOf(url) + ".img")
        if (!f.exists()) return null
        // 命中即刷新 mtime（LRU 语义，最近用过的晚被清）
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return runCatching { decode(f.readBytes(), maxEdge) }
            .onFailure { DiagLog.w(TAG, "磁盘缓存损坏，删除重下：${it.message}"); runCatching { f.delete() } }
            .getOrNull()
    }

    private fun diskWrite(url: String, kind: String, bytes: ByteArray) {
        if (appContext == null) return
        runCatching {
            val f = File(subDir(ensureCacheRoot(), kind), keyOf(url) + ".img")
            f.writeBytes(bytes)
        }.onFailure { DiagLog.w(TAG, "写磁盘缓存失败：${it.message}") }
    }

    /**
     * 下载并解码。
     *
     * @param url 完整 URL（含 token 参数）
     * @param bearer 需要鉴权的请求（头像）填 API Key；普通 files 不需要（走 ?token=）
     * @param maxEdge 解码后最大边长（缩略图省内存）
     * @param kind 磁盘缓存子目录（`avatars` 头像 / `media` 图片缩略图、封面、表情等）
     */
    suspend fun load(
        url: String,
        bearer: String = "",
        maxEdge: Int = MAX_EDGE,
        kind: String = "media",
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        // L1 内存
        cached(url)?.let { return@withContext it }
        // L2 磁盘
        diskHit(url, kind, maxEdge)?.let { bmp ->
            memory.put(url, bmp)
            return@withContext bmp
        }
        // 失败去重：最近 30min 内失败过的不再重试（头像 404 最常见）
        val lastFail = failMap[url]
        if (lastFail != null && System.currentTimeMillis() - lastFail < FAIL_TTL_MS) {
            return@withContext null
        }

        // pending 去重：同一 URL 已有在途下载时，等它完成再读一次缓存
        if (!pending.add(url)) {
            // 已有并发下载：短暂等待后重读（最多等 ~8s，避免无限等）
            repeat(8) {
                kotlinx.coroutines.delay(1000)
                cached(url)?.let { bmp -> return@withContext bmp }
                diskHit(url, kind, maxEdge)?.let { bmp ->
                    memory.put(url, bmp); return@withContext bmp
                }
            }
            return@withContext null
        }
        try {
            val bmp = download(url, bearer, maxEdge, kind)
            if (bmp != null) {
                memory.put(url, bmp)
                failMap.remove(url)
            } else {
                failMap[url] = System.currentTimeMillis()
            }
            bmp
        } finally {
            pending.remove(url)
        }
    }

    /** 网络下载（成功同时落磁盘）。 */
    private fun download(url: String, bearer: String, maxEdge: Int, kind: String): Bitmap? {
        return runCatching {
            val b = Request.Builder().url(url)
            if (bearer.isNotBlank()) b.addHeader("Authorization", "Bearer $bearer")
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DiagLog.w(TAG, "图片下载失败 HTTP ${resp.code}")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                val bitmap = decode(bytes, maxEdge)
                if (bitmap != null) diskWrite(url, kind, bytes)
                bitmap
            }
        }.onFailure { DiagLog.w(TAG, "图片加载异常：${it.message}") }.getOrNull()
    }

    /** 原始字节（语音/视频/文件下载与保存用）。 */
    suspend fun bytes(url: String, bearer: String = ""): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val b = Request.Builder().url(url)
            if (bearer.isNotBlank()) b.addHeader("Authorization", "Bearer $bearer")
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        }.onFailure { DiagLog.w(TAG, "下载失败：${it.message}") }.getOrNull()
    }

    private fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        var sample = 1
        while (maxOf(w, h) / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** 清理超过保留期的缓存文件（惰性触发，照 pc-client cleanup_manager）。 */
    private fun cleanupExpired(root: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        var removed = 0
        root.listFiles()?.forEach { sub ->
            if (!sub.isDirectory) return@forEach
            sub.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    if (f.delete()) removed++
                }
            }
        }
        if (removed > 0) DiagLog.i(TAG, "清理过期缓存 $removed 个（保留 ${CACHE_MAX_AGE_MS / 86400000} 天）")
    }

    /** 清空内存 + 磁盘缓存（设置页「清缓存」调用）。 */
    fun clear() {
        memory.evictAll()
        pending.clear()
        failMap.clear()
        val root = cacheRoot
        root?.let { r ->
            runCatching { r.listFiles()?.forEach { it.deleteRecursively() } }
        }
        cacheRoot = null
    }
}
