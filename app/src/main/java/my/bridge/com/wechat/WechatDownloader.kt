package my.bridge.com.wechat

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import java.io.File
import java.io.FileOutputStream

/**
 * 微信附件下载器 —— 保存到用户拍板的目录 `/sdcard/bridge/wechat/`。
 *
 * 逻辑路径（对外展示、与用户认知一致）：
 * ```
 * /sdcard/bridge/wechat/images/   图片（msgType 3）
 * /sdcard/bridge/wechat/videos/   视频（43）
 * /sdcard/bridge/wechat/voice/    语音（34）
 * /sdcard/bridge/wechat/files/    其它附件
 * ```
 *
 * **Android 10+ 分区存储不能直接写裸路径**，故按类型落到公共媒体目录（同一逻辑路径）：
 * - 图片 → `Pictures/Bridge/WeChat`
 * - 视频 → `Movies/Bridge/WeChat`
 * - 语音 → `Music/Bridge/WeChat`
 * - 文件 → `Download/Bridge/WeChat`
 *
 * Android 9- 直接写公共目录，写不进去（权限被拒）时回退 App 私有目录并用
 * FileProvider 共享，保证「保存」动作永远可用。
 */
object WechatDownloader {

    private const val TAG = "微信下载"
    private const val ROOT = "Bridge/WeChat"

    enum class Kind(val subDir: String) {
        IMAGE("images"),
        VIDEO("videos"),
        VOICE("voice"),
        FILE("files"),
    }

    /**
     * 下载并保存。
     *
     * @param sourceUrl 带 token 的完整附件 URL
     * @param fileName 保存文件名（带扩展名）
     * @return 成功返回可打开的 [Uri]；失败返回异常信息
     */
    suspend fun save(
        context: Context,
        sourceUrl: String,
        fileName: String,
        kind: Kind,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = WechatImageLoader.bytes(sourceUrl)
                ?: throw IllegalStateException("附件下载失败")
            val name = sanitize(fileName)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveByMediaStore(context, bytes, name, kind)
            } else {
                saveByPublicDir(context, bytes, name, kind)
            }
            DiagLog.i(TAG, "已保存 ${kind.subDir}/$name")
            uri
        }.onFailure { DiagLog.w(TAG, "保存失败：${it.message}") }
    }

    /** 逻辑路径（展示用，与设置页文案一致）。 */
    fun logicalPath(kind: Kind, fileName: String): String =
        "/sdcard/bridge/wechat/${kind.subDir}/$fileName"

    private fun sanitize(name: String): String {
        val n = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return n.ifBlank { "wechat_${System.currentTimeMillis()}" }
    }

    private fun saveByMediaStore(context: Context, bytes: ByteArray, name: String, kind: Kind): Uri {
        val resolver = context.contentResolver
        val (collection, relative, mime) = when (kind) {
            Kind.IMAGE -> Triple(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_PICTURES + "/$ROOT",
                mimeOf(name, "image/jpeg"),
            )
            Kind.VIDEO -> Triple(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_MOVIES + "/$ROOT",
                mimeOf(name, "video/mp4"),
            )
            Kind.VOICE -> Triple(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_MUSIC + "/$ROOT",
                mimeOf(name, "audio/mpeg"),
            )
            Kind.FILE -> Triple(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_DOWNLOADS + "/$ROOT",
                mimeOf(name, "application/octet-stream"),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法创建媒体文件")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入媒体文件")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveByPublicDir(context: Context, bytes: ByteArray, name: String, kind: Kind): Uri {
        val base: File? = when (kind) {
            Kind.IMAGE -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Kind.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            Kind.VOICE -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            Kind.FILE -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        if (base != null) {
            runCatching {
                val dir = File(base, ROOT)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { it.write(bytes) }
                MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }.onFailure { DiagLog.w(TAG, "公共目录写入失败，回退私有目录：${it.message}") }
        }
        // 回退：App 私有目录（对外仍按逻辑路径展示）
        val dir = File(context.getExternalFilesDir(null), "bridge/wechat/${kind.subDir}")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        FileOutputStream(f).use { it.write(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    /** 缓存目录：语音播放前的临时落盘位置（不占用户可见空间）。 */
    fun cacheDir(context: Context): File =
        File(context.cacheDir, "wechat").apply { if (!exists()) mkdirs() }

    private fun mimeOf(name: String, fallback: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".amr", true) -> "audio/amr"
        name.endsWith(".silk", true) -> "audio/silk"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".wav", true) -> "audio/wav"
        else -> fallback
    }
}
