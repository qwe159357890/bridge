package my.bridge.com.immich

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import my.bridge.com.core.common.DiagLog

/** 本地媒体日志 tag。 */
private const val NTAG = "相册"

/**
 * 本机媒体（MediaStore）与 Immich 云端资产的桥接（用户反馈第 5 条，2026-11-16）。
 *
 * 借鉴 Immich 原版行为：云端没有但本地有的照片也显示在时间线上，网格块带
 * 「未上传」角标；云端资产带「已备份」角标，用户一眼区分同步状态。
 *
 * 匹配口径：按文件名（originalFileName == 本地文件名）。云端分页只加载了最近部分，
 * 因此本地候选仅取最近 N 天（默认 40 天，与首页 500 条覆盖窗口吻合），窗口外
 * 本地资产不显示，避免「明明已备份却标未上传」的误报。
 */
object ImmichLocalMedia {

    /** MediaStore 内容 URI 表（图片 + 视频）。 */
    private val mediaUris = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
    )

    /**
     * 扫描本机媒体，剔除云端已有的（按文件名），返回未上传资产列表（时间降序）。
     *
     * :param context: 任意 Context
     * :param cloudFileNames: 已加载云端资产的文件名集合
     * :param windowDays: 本地候选窗口天数（仅取最近 N 天）
     * :return: 未上传资产列表（ImmichAsset，id 前缀 local:）
     */
    fun scanLocalOnly(context: Context, cloudFileNames: List<String>, windowDays: Int): List<ImmichAsset> {
        if (!ImmichBackup.hasMediaPermission(context)) {
            DiagLog.w(NTAG, "无媒体读取权限，本地资产不显示")
            return emptyList()
        }
        val cloudNames = cloudFileNames.toHashSet()
        val cutoffEpochSec = System.currentTimeMillis() / 1000 - windowDays * 86400L
        val out = mutableListOf<ImmichAsset>()
        mediaUris.forEach { (uri, isVideo) ->
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.WIDTH,
                        MediaStore.MediaColumns.HEIGHT,
                        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    ) + if (isVideo) arrayOf(MediaStore.Video.Media.DURATION) else emptyArray(),
                    "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                    arrayOf(cutoffEpochSec.toString()),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val durIdx = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: continue
                        // 云端已有同名文件 → 视为已备份，不出「未上传」块
                        if (name in cloudNames) continue
                        val dateAdded = cursor.getLong(dateIdx)
                        out.add(
                            ImmichAsset(
                                id = "local:${uri.lastPathSegment ?: ""}::${cursor.getLong(idIdx)}",
                                type = if (isVideo) "VIDEO" else "IMAGE",
                                localDateTime = epochSecToIso(dateAdded),
                                duration = if (isVideo && durIdx >= 0) msToImmichDuration(cursor.getLong(durIdx)) else "",
                                fileName = name,
                                width = cursor.getInt(widthIdx),
                                height = cursor.getInt(heightIdx),
                                isFavorite = false,
                                isArchived = false,
                                isTrashed = false,
                                thumbhash = "",
                                exif = null,
                                people = emptyList(),
                            ),
                        )
                    }
                }
            }.onFailure {
                DiagLog.w(NTAG, "扫描 ${if (isVideo) "视频" else "图片"} 失败：${it.message}")
            }
        }
        val sorted = out.sortedByDescending { it.localDateTime }
        DiagLog.i(NTAG, "本机扫描完成：候选 ${sorted.size} 个（窗口 ${windowDays} 天，云端同名已剔除）")
        return sorted
    }

    /**
     * 云端资产 + 本地未上传资产合并为统一时间线列表（时间降序）。
     *
     * :param cloud: 云端资产（服务端已降序）
     * :param localOnly: 本地未上传资产（已降序）
     * :return: 合并列表（降序）
     */
    fun merge(cloud: List<ImmichAsset>, localOnly: List<ImmichAsset>): List<ImmichAsset> {
        if (localOnly.isEmpty()) return cloud
        return (cloud + localOnly).sortedByDescending { it.localDateTime }
    }

    /**
     * 手动上传单个本机资产到 Immich（查看器「上传」动作；流式拷贝到缓存再传）。
     *
     * :param context: 任意 Context
     * :param asset: 本地资产（isLocal = true）
     */
    fun uploadLocal(context: Context, asset: ImmichAsset) {
        val uri = localUri(asset) ?: throw ImmichException("找不到本机文件：${asset.fileName}", null)
        val api = ImmichApiClient(context)
        val cacheDir = File(context.cacheDir, "immich_upload").apply { mkdirs() }
        val tmp = File(cacheDir, asset.fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw ImmichException("读取本机文件失败：${asset.fileName}", null)
        try {
            val checksum = api.sha1Base64(tmp)
            val existing = api.bulkUploadCheck(listOf(checksum))
            if (checksum !in existing) {
                api.uploadAsset(
                    file = tmp,
                    isVideo = asset.isVideo,
                    fileCreatedAt = System.currentTimeMillis(),
                    fileModifiedAt = System.currentTimeMillis(),
                    deviceAssetId = asset.id,
                    duration = asset.duration,
                    checksum = checksum,
                )
            } else {
                DiagLog.i(NTAG, "服务端已有同校验文件，跳过上传：${asset.fileName}")
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * 解析本地资产 id 为 MediaStore content URI。
     *
     * :param asset: 本地资产
     * :return: content URI；解析失败返回 null
     */
    fun localUri(asset: ImmichAsset): Uri? {
        if (!asset.isLocal) return null
        val parts = asset.id.removePrefix("local:").split("::")
        if (parts.size != 2) return null
        val (uriPath, id) = parts
        val base = if (asset.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return ContentUris.withAppendedId(base, id.toLongOrNull() ?: return null).takeIf { uriPath.isNotBlank() }
    }

    /**
     * epoch 秒转 Immich localDateTime 格式（ISO 墙钟串，与云端口径一致）。
     *
     * :param epochSec: epoch 秒
     * :return: ISO 串（如 2026-11-16T20:35:15.000Z）
     */
    private fun epochSecToIso(epochSec: Long): String {
        val dt = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
    }

    /**
     * 毫秒时长转 Immich duration 格式（"0:00:12.00000"）。
     *
     * :param durationMs: 毫秒
     * :return: Immich duration 字段格式；无效返回空串
     */
    private fun msToImmichDuration(durationMs: Long): String {
        if (durationMs <= 0) return ""
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%d:%02d:%02d.00000".format(h, m, s)
    }
}
