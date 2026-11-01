package my.bridge.com.wechat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 远程图片/头像加载：OkHttp 下载 + 内存 LRU 缓存 + 采样缩放解码。
 *
 * **不引入 Coil/Glide**——项目没有该依赖，且这类附件都要带 `?token=` 或 Bearer 头，
 * 自己实现更可控（也避免为一张缩略图拉整个图片库）。
 *
 * 契约：`{server}/api/files/{object}?token={key}`、头像 `GET /api/contacts/avatar?wxid=`
 * （`web-frontend/src/utils/avatarManager.ts` 第 84 行，需 Bearer）。
 */
object WechatImageLoader {

    private const val TAG = "微信图片"
    private const val MAX_EDGE = 720

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val memory: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    /**
     * 下载并解码。
     *
     * @param url 完整 URL（含 token 参数）
     * @param bearer 需要鉴权的请求（头像）填 API Key；普通 files 不需要（走 ?token=）
     * @param maxEdge 解码后最大边长（缩略图省内存）
     */
    suspend fun load(url: String, bearer: String = "", maxEdge: Int = MAX_EDGE): Bitmap? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            cached(url)?.let { return@withContext it }
            runCatching {
                val b = Request.Builder().url(url)
                if (bearer.isNotBlank()) b.addHeader("Authorization", "Bearer $bearer")
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        DiagLog.w(TAG, "图片下载失败 HTTP ${resp.code}")
                        return@withContext null
                    }
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    val bitmap = decode(bytes, maxEdge)
                    if (bitmap != null) memory.put(url, bitmap)
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

    fun clear() = memory.evictAll()
}
