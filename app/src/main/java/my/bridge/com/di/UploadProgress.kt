package my.bridge.com.di

import com.google.gson.JsonObject
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.core.network.ws.CommandResult
import my.bridge.com.core.storage.DeviceStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import timber.log.Timber
import java.io.File

/**
 * 采集产物上传 + 两阶段进度上报（v0.1.47，从 CommandModule 抽出独立文件）。
 *
 * 进度协议：WS ``message`` 通道（``event=command_progress``）→ 服务端 SSE 转发
 * ``command_progress`` 事件 → 管理后台「待回执」状态列实时显示。
 * - phase=executing：执行阶段，由 [my.bridge.com.media.MediaCaptureService]
 *   上报（录制类按参数时长百分比；截屏/拍照瞬间完成不报）；
 * - phase=uploading：上传阶段，由本文件的 [ProgressRequestBody] 按真实写到
 *   网络的字节上报（每 300ms 或 ≥5% 节流）。
 *
 * 进度只转发不落库：命令历史只保留最终回执。
 */
internal object UploadProgressHolder {
    /** 由 CommandModule @Provides 装配时注入（dagger.Lazy 断依赖环，运行期才取用）。 */
    lateinit var webSocketClient: dagger.Lazy<BridgeWebSocketClient>
}

/** 上传阶段进度上报（fire-and-forget：WS 不在线/发送失败静默，不影响上传链路）。 */
internal fun reportUploadProgress(commandId: String, sent: Long, total: Long) {
    if (commandId.isEmpty() || total <= 0) return
    try {
        val data = JsonObject().apply {
            addProperty("event", "command_progress")
            addProperty("command_id", commandId)
            addProperty("phase", "uploading")
            addProperty("sent_bytes", sent)
            addProperty("total_bytes", total)
            addProperty("percent", ((sent * 100) / total).toInt().coerceIn(0, 100))
        }
        UploadProgressHolder.webSocketClient.get().sendBusinessMessage(data)
    } catch (_: Exception) {
    }
}

/**
 * 上传进度请求体：经典 OkHttp 计数 sink 包装，统计真正写到网络的字节。
 * 节流：每 300ms 或每 ≥5% 变化才回调一次，结束强制回调 100%。
 */
internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        var lastReportAt = 0L
        var lastPct = -1
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                val now = System.currentTimeMillis()
                val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                if (now - lastReportAt >= 300 || pct - lastPct >= 5) {
                    lastReportAt = now
                    lastPct = pct
                    onProgress(sent, total)
                }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
        onProgress(total, total) // 结束强制 100%
    }
}

/** 上传采集产物并在成功后清理本地临时文件；上传阶段按字节上报进度（phase=uploading）。 */
internal suspend fun uploadCaptureFile(
    deviceStore: DeviceStore,
    apiClient: BridgeApiClient,
    id: String,
    name: String,
    kind: String,
    file: File,
): CommandResult {
    if (!file.isFile || file.length() == 0L) {
        return CommandResult(id, name, false, "采集产物无效：${file.absolutePath}")
    }
    val mediaType = when (file.extension) {
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        else -> "image/jpeg"
    }
    val body = ProgressRequestBody(RequestBody.create(mediaType.toMediaTypeOrNull(), file)) { sent, total ->
        reportUploadProgress(id, sent, total)
    }
    val part = MultipartBody.Part.createFormData("file", file.name, body)
    return try {
        val deviceId = deviceStore.deviceId()
        val token = runCatching { deviceStore.token() }.getOrNull()
        val r = apiClient.uploadMedia(deviceId, token, kind, part)
        file.delete()
        val sizeText = if (r.size >= 1024 * 1024) "%.1f MB".format(r.size / 1048576.0)
        else "%.0f KB".format(r.size / 1024.0)
        Timber.i("命令 %s：%s", name, r.message)
        CommandResult(id, name, true, "${r.message}：${r.filename}（$sizeText），点回执旁「查看」可直接预览")
    } catch (e: Exception) {
        Timber.e(e, "命令 %s 上传失败", name)
        CommandResult(
            id, name, false,
            "已采集（${file.name}）但上传失败：${e.message}；文件已保留在设备 media/ 目录，可重试下发或下发 clear_media 清理",
        )
    }
}
