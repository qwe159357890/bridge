package my.bridge.com.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import dagger.hilt.android.AndroidEntryPoint
import my.bridge.com.R
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 媒体采集前台服务（screenshot / record_screen / take_photo / record_video / record_audio 五模式）。
 *
 * Manifest 声明 ``foregroundServiceType="mediaProjection|camera|microphone"``：
 * - 截屏/录屏：MediaProjection 授权结果经 Intent 传入，先以 MEDIA_PROJECTION
 *   类型 startForeground（Android 14+ 硬性要求，先声明再建 VirtualDisplay）；
 * - 拍照：Camera1 静音拍照（enableShutterSound(false) + 系统音量临时静音兜底），
 *   后台访问相机要求 CAMERA 类型前台服务（Android 11+ while-in-use 规则）。
 *
 * 投屏会话复用（借鉴 autox 思路，v0.1.37）：首次授权后 MediaProjection 由本服务
 * **持有不释放**，采集完成只释放 VirtualDisplay/Reader 等单次资源，服务转入
 * 「会话保持中」常驻态——进程不重启就一直有效，后续截屏/录屏免系统确认框。
 * 会话失效（用户下拉取消投屏/服务被杀）时 [projectionHeld] 归位 false，
 * 下一条命令重新走授权。Android 14+ 硬性要求：getMediaProjection 后必须先
 * [registerCallback] 再 createVirtualDisplay，否则抛
 * 「Must register a callback before starting capture」——这是 v0.1.36 及以前
 * 每次采集必报错的根因。
 *
 * 结果经 [ScreenCaptureHolder.capture]（key = commandId）回传执行器：
 * 成功为产物文件路径（App 私有 media/ 目录，上传后由执行器清理）。
 */
@AndroidEntryPoint
class MediaCaptureService : Service() {

    @Inject
    lateinit var wsClient: BridgeWebSocketClient

    companion object {
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
        const val MODE_SCREEN_LIVE = "screen_live"
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"
        const val MODE_AUDIO = "audio"

        const val EXTRA_COMMAND_ID = "command_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DURATION_SEC = "duration_sec"
        const val EXTRA_CAMERA_FACING = "camera_facing"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_ENABLE_AUDIO = "enable_audio"
        const val EXTRA_STREAM = "stream"
        const val EXTRA_UPLOAD = "upload"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_CONSENT_DATA = "consent_data"

        // ---- 流式采集（stream=true 的录像/录音，借鉴 autox stream* 系列）----
        // 分片推送节奏（毫秒）：每 500ms 读一次产物文件增量，二进制帧经 WS 上行
        private const val STREAM_CHUNK_INTERVAL_MS = 500L

        /** 当前是否有流式采集进行中（stream_stop 命令据此回执）。 */
        @Volatile
        var streamActive: Boolean = false
            private set

        /** 停止请求标志：stream_stop 命令置位，流式循环每个分片周期检查后退出。 */
        @Volatile
        private var streamStopFlag: Boolean = false

        /**
         * 请求停止当前流式采集（stream_stop 命令入口）。
         * @return 是否成功请求（无流式进行中返回 false）
         */
        fun requestStreamStop(): Boolean {
            if (!streamActive) return false
            streamStopFlag = true
            return true
        }

        // v0.1.40：旧渠道 media_capture 以 IMPORTANCE_LOW 创建过（渠道创建后重要性不可变），
        // 静默化需换新渠道 id 才能生效：MIN = 无声音、无横幅、折叠在通知栏底部
        private const val CHANNEL_ID = "media_capture_quiet"
        private const val NOTIFICATION_ID = 2101

        /** 客户端媒体残留自动清理：孤儿文件保留最新 N 个、超过 M 天全删（正常上传成功即删，残留=失败孤儿）。 */
        private const val MEDIA_KEEP_COUNT = 50
        private const val MEDIA_MAX_AGE_MS = 7L * 24 * 3600 * 1000

        /** 是否有采集任务进行中（clear_media 命令据此拒绝并发清理，避免误删正在生成的产物）。 */
        @Volatile
        var captureBusy: Boolean = false
            private set

        /**
         * 投屏会话是否已保持（进程级状态，执行器据此跳过授权确认）。
         * true = MediaProjection 已持有且有效，截屏/录屏可免确认直接采集。
         */
        @Volatile
        var projectionHeld: Boolean = false
            private set

        /** 采集目录（App 私有 media/，与上传后清理配套）。 */
        fun mediaDir(context: Context): File =
            context.getExternalFilesDir("media") ?: File(context.filesDir, "media")

        /** 清空本地媒体目录（clear_media 命令）：返回 (删除数, 释放字节)。 */
        fun clearMediaFiles(context: Context): Pair<Int, Long> {
            var count = 0
            var bytes = 0L
            mediaDir(context).listFiles()?.filter { it.isFile }?.forEach {
                bytes += it.length()
                if (it.delete()) count++
            }
            Timber.i("clear_media：清理 %d 个本地媒体文件（%d 字节）", count, bytes)
            return count to bytes
        }
    }

    private var workThread: HandlerThread? = null
    private var handler: Handler? = null

    private var projection: MediaProjection? = null

    /**
     * 持久镜像虚拟显示器（v0.1.41 核心修复）。
     *
     * Android 14+ 投屏令牌**一次性**：同一个 MediaProjection 实例只能
     * createVirtualDisplay 一次，第二次直接抛 SecurityException「Don't re-use
     * the resultData…」——这就是旧版「录屏第 1 次成功、第 2 次报错、第 3 次
     * 重新授权、第 4 次又报错」死循环的根因。
     * 修复：授权后只建**一面幕布**（VirtualDisplay，初始无 surface），此后截屏
     * 挂 ImageReader 的 surface、录屏挂 MediaRecorder 的 surface，用完
     * [VirtualDisplay.setSurface](null) 摘下——不再触发第二次 createVirtualDisplay，
     * 一次授权跨截屏/录屏永久复用（autox 级体验）。
     */
    private var mirrorDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var camera: android.hardware.Camera? = null
    private var previewTexture: SurfaceTexture? = null
    private var cameraThread: HandlerThread? = null

    /**
     * 相机所有权代数（v0.1.51 防御网，「摄像头被占用」复现的根因修复）：
     *
     * 旧版问题：拍照/录像**超时**后 complete() 会在工作线程 releaseCamera()，
     * 而相机线程（拍照的 JPEG 回调 / 录像卡在 stop() 的线程）稍后在自己的
     * finally 里**再次 release 同一实例**——Camera1 的 release() 不幂等，
     * 双释放打坏 native camera client 状态，之后每次 Camera.open 都报
     * 「摄像头被占用」，只能重启进程才能恢复。
     *
     * 解法 = 代数令牌（era）：每次相机类采集开始 bump 一次 era 作为本代身份；
     * 所有释放入口都必须「持当前代」才能执行，且每代只允许一次释放
     * （释放成功即作废本代）。三个入口——相机线程 finally / complete 兜底 /
     * watchdog 强制接管——天然互斥：宁可漏放（还有下次采集前的接管兜底），
     * 绝不双放。所有相关操作都在 [cameraLock] 下串行化。
     */
    private val cameraLock = Any()
    private var cameraEra = 0

    private var commandId = ""
    private var mode = MODE_SCREENSHOT
    private var finished = false

    private var mutedStreams: Int = -1
    private var savedVolumes: MutableMap<Int, Int> = mutableMapOf()

    /**
     * 投屏会话回调（Android 14+ 必须在 createVirtualDisplay 前注册）。
     * 系统侧会话停止（用户取消投屏、系统回收）时释放保持状态并停服务，
     * 下一条截屏/录屏命令会重新走授权流程。
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.i("投屏会话被系统停止，释放保持状态")
            projectionHeld = false
            handler?.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 按当前 mode 挂前台（幂等，重复调用仅刷新通知）。
     * 前台类型按模式选择：投屏类 MEDIA_PROJECTION，拍照/录像 CAMERA，录音 MICROPHONE。
     * camera/microphone 类型 API 30 起才有，低于 30 用两参 startForeground
     * （while-in-use 限制也是 30+ 才生效）。
     *
     * v0.2.01 防崩：startForeground 若抛异常（如 FGS 类型/权限校验失败）且不接住，
     * 异常直接崩进程（v0.1.99 在 KeepAliveService/AudioRelayService 的同款教训）——
     * 这里 catch 后 stopSelf 撤销本次 startForegroundService 义务。
     */
    private fun startForegroundForMode() {
        val type = when (mode) {
            MODE_PHOTO, MODE_VIDEO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            MODE_AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Timber.e(e, "采集服务挂前台失败，stopSelf 防崩（mode=%s）", mode)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 系统重投递或异常启动：无任务则退出（会话保持态由 stopSelf 前的回调兜底）
            if (!projectionHeld) stopSelf()
            return START_NOT_STICKY
        }

        // 并发采集拒绝（三层防线的最后兜底：服务端互斥守卫 + 后台按钮禁用之后）：
        // 截屏/录屏/拍照/录像/录音共用本服务且硬件互斥（摄像头/麦克风/投屏单实例），
        // 第二条命令若照常处理会覆盖共享状态（commandId/mode），污染在途任务的回执。
        if (captureBusy && !finished) {
            val incomingId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: ""
            Timber.w("拒绝并发采集命令 %s：已有采集任务进行中（mode=%s）", incomingId, mode)
            ScreenCaptureHolder.takeCapture(incomingId)?.complete(
                ScreenCaptureHolder.failure(
                    "设备正在执行其他采集任务（截屏/录屏/拍照/录像/录音互斥），请等当前任务回执后再下发",
                ),
            )
            // v0.2.01 防崩：本服务经 startForegroundService 拉起，前台化义务按次计——
            // 并发拒绝路径同样履行义务（服务已在前台，重复 startForeground 幂等，
            // 类型沿用当前在途任务的 mode），否则 ~30s 后 FGS 超时崩溃
            // （见 docs/keepalive/design.md 附录 5）。
            startForegroundForMode()
            return START_NOT_STICKY
        }

        commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: ""
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREENSHOT
        finished = false
        captureBusy = true

        startForegroundForMode()

        // 工作线程只在服务生命周期内创建一次（会话复用后服务常驻，多次采集共用）
        val h: Handler = handler ?: run {
            val thread = HandlerThread("bridge-capture").also { it.start() }
            workThread = thread
            Handler(thread.looper).also { handler = it }
        }

        val startedAt = SystemClock.elapsedRealtime()
        h.post {
            try {
                when (mode) {
                    MODE_PHOTO -> capturePhoto(intent)
                    MODE_VIDEO ->
                        captureVideo(intent, intent.getBooleanExtra(EXTRA_STREAM, false))
                    MODE_AUDIO ->
                        captureAudio(intent, intent.getBooleanExtra(EXTRA_STREAM, false))
                    MODE_SCREENSHOT -> captureScreenshot(intent)
                    MODE_RECORD -> captureRecord(intent)
                    MODE_SCREEN_LIVE -> captureScreenLive(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "采集失败（mode=%s）", mode)
                // 复用会话的采集失败：多半是会话已失效（服务被杀后残留状态），
                // 释放会话让下一条命令重新走授权，避免连续失败
                if (mode != MODE_PHOTO && projectionHeld) {
                    Timber.w("释放可能失效的投屏会话，下次采集将重新授权")
                    releaseAll()
                    runCatching { stopSelf() }
                }
                complete(ScreenCaptureHolder.failure("采集失败：${e.toString().take(300)}"))
            } finally {
                Timber.i("采集任务结束（mode=%s，耗时 %d ms）", mode, SystemClock.elapsedRealtime() - startedAt)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        projectionHeld = false
        releaseAll()
        restoreVolumes()
        workThread?.looper?.quit()
        cameraThread?.looper?.quit()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 截屏：MediaProjection + ImageReader 单帧
    // ------------------------------------------------------------------

    private fun captureScreenshot(intent: Intent) {
        val p = requireProjection(intent)
        val metrics = realDisplayMetrics()
        val latch = CountDownLatch(1)
        var error: String? = null
        var savedFile: File? = null
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (latch.count == 0L) return@setOnImageAvailableListener
                val bitmap = imageToBitmap(image)
                val dir = mediaDir(this).apply { mkdirs() }
                val file = File(dir, "screenshot_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                savedFile = file
                latch.countDown()
            } catch (e: Exception) {
                error = e.message
                latch.countDown()
            } finally {
                image.close()
            }
        }, handler)

        // 幕布挂上 ImageReader 的 surface（不触发新的 createVirtualDisplay，规避令牌一次性）
        val display = obtainMirrorDisplay(p, metrics)
        display.setSurface(reader.surface)
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw IllegalStateException("截屏超时（屏幕可能受 DRM 保护）")
            }
        } finally {
            display.setSurface(null)
        }
        error?.let { throw IllegalStateException("截屏失败：$it") }
        val file = savedFile ?: throw IllegalStateException("截屏产物未生成")
        complete(ScreenCaptureHolder.CaptureFile(file.absolutePath))
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride == 4 && rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // 行间距不连续时逐行拷贝（RGBA_8888：R,G,B,A 各 1 字节）
            val row = ByteArray(width * 4)
            val pixels = IntArray(width)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row)
                for (x in 0 until width) {
                    val o = x * 4
                    pixels[x] = android.graphics.Color.argb(
                        row[o + 3].toInt() and 0xFF,
                        row[o].toInt() and 0xFF,
                        row[o + 1].toInt() and 0xFF,
                        row[o + 2].toInt() and 0xFF,
                    )
                }
                bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
            }
        }
        return bitmap
    }

    // ------------------------------------------------------------------
    // 录屏：MediaProjection + MediaRecorder 定时录制
    // ------------------------------------------------------------------

    private fun captureRecord(intent: Intent) {
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 10).coerceIn(3, 60)
        val p = requireProjection(intent)
        val metrics = realDisplayMetrics()

        // 视频尺寸：长边压到 1280，宽高取偶（H264 编码器要求）
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val longest = maxOf(w, h)
        if (longest > 1280) {
            val scale = 1280f / longest
            w = (w * scale).toInt() and Int.MAX_VALUE - 1 // 取偶
            h = (h * scale).toInt() and Int.MAX_VALUE - 1
        }

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = rec
        val dir = mediaDir(this).apply { mkdirs() }
        val tmp = File(dir, "screen_${System.currentTimeMillis()}.tmp.mp4")
        val target = File(dir, "screen_${System.currentTimeMillis()}.mp4")

        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(w, h)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(6_000_000)
        rec.setOutputFile(tmp.absolutePath)
        rec.prepare()

        // 幕布挂上 MediaRecorder 的 surface（复用同一面 VirtualDisplay，规避令牌一次性）
        val display = obtainMirrorDisplay(p, metrics)
        display.setSurface(rec.surface)
        rec.start()
        Timber.i("录屏开始（%d 秒，%dx%d）", durationSec, w, h)
        sleepWithProgress(durationSec)
        try {
            rec.stop()
        } catch (e: Exception) {
            throw IllegalStateException("录制内容为空（屏幕无有效画面或编码失败）：${e.message}")
        } finally {
            rec.release()
            recorder = null
            display.setSurface(null)
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("录屏文件保存失败")
        }
        complete(ScreenCaptureHolder.CaptureFile(target.absolutePath))
    }

    // ------------------------------------------------------------------
    // 投屏直播：MediaCodec H.264 编码 + FLV 复用（边编边推，复用流式通道）
    // ------------------------------------------------------------------

    /**
     * 投屏实时直播（screen_live 命令，方案见 docs/architecture/live-screen-research.md）。
     *
     * 与录屏的本质区别：不走 MediaRecorder（录完才有产物），而是把镜像幕布的
     * surface 直接挂到 **MediaCodec 编码器输入面**——屏幕每一帧经 GPU 合成进编码器，
     * 输出 H.264 AnnexB → [FlvMuxer] 打成 FLV 落盘 → 每 [STREAM_CHUNK_INTERVAL_MS]
     * 读文件增量推 WS（event=media_stream，kind=screen），管理后台 mpegts.js
     * 以 type:'flv' 实时播放，端到端延迟约 0.6~1.5 秒。
     *
     * - 授权：与截屏/录屏一致（复用 [requireProjection] 会话保持，首次弹一次授权）；
     * - surface 挂/摘复用持久幕布（不触发第二次 createVirtualDisplay）；
     * - duration = 最长直播时长倒计时（10~3600 秒），stream_stop 随时可提前停；
     * - 停止后 FLV 存档按正常媒体上传（含完整直播内容，可回放）；
     * - 分片推送失败不中断直播（fire-and-forget，服务端无订阅者时分片直接丢弃）。
     */
    private fun captureScreenLive(intent: Intent) {
        // duration_sec=0 = 不限时（管理后台流式下发默认），直到 stream_stop 手动停
        val rawSec = intent.getIntExtra(EXTRA_DURATION_SEC, 300)
        val unlimited = rawSec <= 0
        val durationSec = if (unlimited) 0 else rawSec.coerceIn(10, 3600)
        // 回执上传开关（v0.1.55）：false = 直播结束即弃，不上传 FLV 存档
        val uploadArchive = intent.getBooleanExtra(EXTRA_UPLOAD, false)
        // 画质档位（v0.1.56）：smooth=流畅 720P/1.5Mbps/15fps（省 55% 带宽），
        // hd=高清 1080P/2.5Mbps/30fps（默认）
        val tier = if (intent.getStringExtra(EXTRA_QUALITY) == "smooth") "smooth" else "hd"
        val bitrate = if (tier == "smooth") 1_500_000 else 2_500_000
        val fps = if (tier == "smooth") 15 else 30
        val targetShort = if (tier == "smooth") 720 else 1080
        val startedAllAt = SystemClock.elapsedRealtime()
        val p = requireProjection(intent)
        Timber.i("投屏打点：授权就绪 +%dms", SystemClock.elapsedRealtime() - startedAllAt)
        streamStopFlag = false
        streamActive = true
        val metrics = realDisplayMetrics()

        // 视频尺寸：按**短边**缩放到档位目标（竖屏 1080x2400 → 流畅 720x1600 /
        // 高清原生 1080x2400），宽高取偶。横屏同理（短边=高）。
        // v0.1.52 修复：旧写法 `w and 1.inv().coerceAtLeast(2)` 因运算优先级实际是
        // `w and 2`——把宽高清成 0，MediaCodec.configure 直接抛 IllegalArgumentException
        // （「每次都让授权」的连带根因：首次失败释放投影会话 → 下次 projectionHeld=false）
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val shortest = minOf(w, h)
        if (shortest > targetShort) {
            val scale = targetShort.toFloat() / shortest
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        w = maxOf(2, w - (w % 2)) // 取偶（H.264 要求）且至少 2px
        h = maxOf(2, h - (h % 2))

        val codec = android.media.MediaCodec.createEncoderByType("video/avc")
        val display = obtainMirrorDisplay(p, metrics)
        var inputSurface: Surface? = null
        // 流式分片游标（收尾 finishStream 要用，声明在函数作用域）
        var offset = 0L
        var chunkIndex = 0

        try {
            val format = android.media.MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(
                    android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate)
                // 显式 CBR（2026-10-27 码率失控排查）：未设 bitrate mode 时设备默认
                // 多为 VBR（KEY_BIT_RATE 只是均值，动态画面可数倍超出——实测 22Mbps
                // 打爆 WiFi 上行 → 发送积压爆发 → 浏览器端「断流十几秒→猛跳」）。
                // CBR 恒定 2.5Mbps，发送速率可预测；不支持 CBR 的编码器降级回退。
                try {
                    setInteger(
                        android.media.MediaFormat.KEY_BITRATE_MODE,
                        android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "CBR 设置失败（降级默认 VBR）")
                }
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
                // GOP 2s→1s（v0.1.56）：关键帧密度翻倍，中途加入/重建订阅者的
                // 关键帧对齐等待减半（起播更快），代价带宽 +5~10%
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // 静止期重复帧（2026-10-27 用户反馈根治）：Surface 无新帧时编码器
                // 按 500ms 间隔自动重编上一帧（内容不变 P 帧近零码率）——时间轴
                // 连续、服务端/浏览器持续有数据，管理端不再出现「静止=断流十几秒
                // → 爆发到达 → 猛跳」的卡顿观感，也无需定义「什么算静止」。
                // 不支持的设备会静默忽略（无害）。
                setLong(android.media.MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            }
            codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            display.setSurface(inputSurface)
            codec.start()
            Timber.i(
                "投屏打点：编码器就绪 +%dms（%dx%d@%d，CBR %dMbps，%s 档，%s）",
                SystemClock.elapsedRealtime() - startedAllAt, w, h, fps,
                bitrate / 1_000_000, tier,
                if (unlimited) "不限时" else "最长 ${durationSec}s",
            )
            // 立即请求同步帧：屏幕静止时 Surface 无新帧 → 首帧要等下一次画面更新
            // 才编码输出（启动黑屏 ~14s 的主要嫌疑）。request-sync 让编码器立即
            // 重编当前 Surface 内容，首帧即刻产出（2026-10-27 启动黑屏优化）。
            runCatching {
                codec.setParameters(
                    android.os.Bundle().apply {
                        putInt(android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }

            val dir = mediaDir(this).apply { mkdirs() }
            val file = File(dir, "screen_${System.currentTimeMillis()}.flv")
            val startInfo = JsonObject().apply {
                addProperty("format", "flv")
                addProperty("video", "h264")
                addProperty("audio", "none")
                addProperty("width", w)
                addProperty("height", h)
                addProperty("duration_sec", durationSec)
            }
            sendStreamMessage("screen", 0, "", 0, 0, false, startInfo)

            val info = android.media.MediaCodec.BufferInfo()
            var firstPtsUs = -1L
            var csd0: ByteArray? = null
            var csd1: ByteArray? = null
            var metadataWritten = false
            var firstFrameLogged = false
            var lastStatAt = 0L
            var lastStatBytes = 0L

            FileOutputStream(file).use { out ->
                val muxer = FlvMuxer(out)
                muxer.writeHeader()
                val startAt = SystemClock.elapsedRealtime()
                // 不限时模式用 24h 上限兜底（真实停止靠 streamStopFlag）
                val totalMs = if (unlimited) 86_400_000L else durationSec * 1000L
                var lastPushAt = 0L
                var lastPct = -1

                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - startAt
                    if (streamStopFlag || elapsed >= totalMs) break

                    val obi = codec.dequeueOutputBuffer(info, 10_000)
                    if (!firstFrameLogged && obi >= 0) {
                        firstFrameLogged = true
                        Timber.i("投屏打点：首帧输出 +%dms", SystemClock.elapsedRealtime() - startedAllAt)
                    }
                    when {
                        obi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            csd0 = of.getByteBuffer("csd-0")?.let { b ->
                                ByteArray(b.remaining()).also { b.get(it) }
                            }
                            csd1 = of.getByteBuffer("csd-1")?.let { b ->
                                ByteArray(b.remaining()).also { b.get(it) }
                            }
                        }
                        obi >= 0 -> {
                            val ob = runCatching { codec.getOutputBuffer(obi) }.getOrNull()
                            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs.coerceAtLeast(0)
                            // v0.1.53 修复：metadata 必须写在首帧前——旧顺序第一个 IDR
                            // 输出时 metadataWritten 还是 false 被丢弃，前端要再等数秒
                            if (!metadataWritten && csd0 != null) {
                                muxer.writeMetadata(w, h, fps, csd0!!, csd1)
                                metadataWritten = true
                            }
                            if (ob != null && info.size > 0 && metadataWritten) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val frame = ByteArray(info.size).also { ob.get(it) }
                                val isKey = info.flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                val ptsMs = ((info.presentationTimeUs - firstPtsUs.coerceAtLeast(0)) / 1000)
                                    .coerceAtLeast(0).toInt()
                                muxer.writeVideoFrame(frame, ptsMs, isKey)
                            }
                            runCatching { codec.releaseOutputBuffer(obi, false) }
                        }
                    }

                    // 周期推分片（追帧：高码率时单周期增量可能多条消息，循环到追平）
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPushAt >= STREAM_CHUNK_INTERVAL_MS) {
                        lastPushAt = now
                        while (true) {
                            val (off, idx) = pushStreamChunks(file, offset, "screen", chunkIndex)
                            if (off == offset) break
                            offset = off
                            chunkIndex = idx
                        }
                        // 不限时模式无固定进度，只按 10s 周期打实际码率（验证 CBR 是否生效）
                        if (unlimited) {
                            if (now - lastStatAt >= 10_000) {
                                val bytes = file.length()
                                Timber.i(
                                    "投屏码率采样：%d kbps（累计 %d KB / %d 分片）",
                                    (bytes - lastStatBytes) * 8 / 10 / 1000, bytes / 1024, chunkIndex,
                                )
                                lastStatAt = now
                                lastStatBytes = bytes
                            }
                        } else {
                            val pct = (minOf(elapsed, totalMs) * 100 / totalMs).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                reportCaptureProgress(pct)
                            }
                        }
                    }
                }
            }

            runCatching { codec.stop() }
            if (file.length() <= 0L) {
                throw IllegalStateException("直播产物为空（屏幕无有效画面或编码器异常）")
            }
            Timber.i(
                "投屏直播结束（%d ms，FLV %d 字节，%d 分片，平均 %d kbps，回执上传=%b）",
                SystemClock.elapsedRealtime() - startedAllAt, file.length(), chunkIndex,
                file.length() * 8 / 1000 / maxOf(1, (SystemClock.elapsedRealtime() - startedAllAt) / 1000),
                uploadArchive,
            )
            finishStream(file, "screen", offset, chunkIndex)
            complete(
                ScreenCaptureHolder.CaptureFile(
                    file.absolutePath,
                    skipUpload = !uploadArchive,
                ),
            )
        } catch (e: Exception) {
            runCatching { codec.stop() }
            // 异常路径也补一个结束标志，让后台播放器及时收尾
            sendStreamMessage("screen", chunkIndex + 1, "", 0, offset, true)
            // 空消息异常（如编码器参数问题）补上下文，回执可直接定位阶段
            if (e.message.isNullOrBlank()) {
                val frame = e.stackTrace.firstOrNull()
                val stage = frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.lineNumber})" } ?: "unknown"
                throw IllegalStateException("${e.javaClass.simpleName}（无消息）@ $stage，直播参数 w=$w h=$h", e)
            }
            throw e
        } finally {
            display.setSurface(null)
            runCatching { inputSurface?.release() }
            runCatching { codec.release() }
            streamActive = false
            streamStopFlag = false
        }
    }

    // ------------------------------------------------------------------
    // 拍照：Camera1 静默拍照（虚拟预览 + 尽力静音）
    // ------------------------------------------------------------------

    private fun capturePhoto(intent: Intent) {
        val front = intent.getStringExtra(EXTRA_CAMERA_FACING) == "front"
        muteSystemVolume()

        // 接管相机资源 + 领取本代 era：上一次残留强制释放，且旧线程/回调的
        // 释放权从此失效（双释放免疫）；Camera.open 带重试消化 HAL 关闭延迟
        val myEra = takeoverCameraResources()
        // watchdog：拍照 35 秒无回调 = 相机线程硬卡死，主线程强制接管释放，
        // 保证下一条命令还能打开相机（本条命令由执行器超时先行报错）
        val watchdog = Runnable { watchdogTakeover(myEra) }
        Handler(Looper.getMainLooper()).postDelayed(watchdog, 35_000)
        val thread = HandlerThread("bridge-camera").also { it.start() }
        runCatching { cameraThread?.quitSafely() }
        cameraThread = thread
        val cameraHandler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var error: String? = null
        var saved: File? = null

        cameraHandler.post {
            var cam: android.hardware.Camera? = null
            var texture: SurfaceTexture? = null
            try {
                val cameraId = pickCamera(front)
                cam = openCameraWithRetry(cameraId)
                camera = cam
                val opened = cam!!
                runCatching { opened.enableShutterSound(false) }

                val info = android.hardware.Camera.CameraInfo()
                @Suppress("DEPRECATION")
                android.hardware.Camera.getCameraInfo(cameraId, info)

                texture = SurfaceTexture(0)
                previewTexture = texture
                @Suppress("DEPRECATION")
                opened.setPreviewTexture(texture)

                @Suppress("DEPRECATION")
                val params = opened.parameters
                @Suppress("DEPRECATION")
                params.supportedPictureSizes?.maxByOrNull { it.width * it.height }?.let { size ->
                    params.setPictureSize(size.width, size.height)
                }
                val rotation = if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    (360 - (info.orientation % 360)) % 360
                } else {
                    info.orientation % 360
                }
                params.setRotation(rotation)
                @Suppress("DEPRECATION")
                opened.parameters = params
                opened.startPreview()

                @Suppress("DEPRECATION")
                opened.takePicture(null, null) { data, _ ->
                    try {
                        if (data != null && data.isNotEmpty()) {
                            val dir = mediaDir(this@MediaCaptureService).apply { mkdirs() }
                            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                            file.writeBytes(data)
                            saved = file
                            BitmapFactory.decodeFile(file.absolutePath) // 校验可解码
                        } else {
                            error = "相机返回空数据"
                        }
                    } catch (e: Exception) {
                        error = "照片保存失败：${e.message}"
                    } finally {
                        // ★ 死锁修复（v0.1.47）：JPEG 回调投递在 open() 所在线程的 Looper 上，
                        // 即本相机线程——绝不能在本线程阻塞等回调（v0.1.46 的拍照超时根因）。
                        // 回调天然在相机线程执行，在回调里释放相机既线程安全又保证回调已完成。
                        // v0.1.51：era 校验释放——若超时路径/watchdog 已接管，这里自动跳过
                        // （旧版此处与 complete() 双释放同一实例，是「摄像头被占用」复现根因）
                        releaseCameraOwned(cam, null, texture, myEra)
                        done.countDown()
                        runCatching { thread.quitSafely() }
                    }
                }
                // post 块到此结束，相机线程 Looper 继续转（等回调投递）；回调里负责释放+countDown
            } catch (e: Exception) {
                Timber.e(e, "拍照异常")
                error = describeCameraError(e)
                releaseCameraOwned(cam, null, texture, myEra)
                done.countDown()
                runCatching { thread.quitSafely() }
            }
        }

        try {
            if (!done.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("拍照超时（相机无响应）")
            }
        } finally {
            Handler(Looper.getMainLooper()).removeCallbacks(watchdog)
            restoreVolumes()
        }
        error?.let { throw IllegalStateException("拍照失败：$it") }
        complete(ScreenCaptureHolder.CaptureFile(saved?.absolutePath ?: ""))
    }

    @Suppress("DEPRECATION")
    private fun pickCamera(front: Boolean): Int {
        val info = android.hardware.Camera.CameraInfo()
        val wanted =
            if (front) android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
            else android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
        var fallback = -1
        for (id in 0 until android.hardware.Camera.getNumberOfCameras()) {
            android.hardware.Camera.getCameraInfo(id, info)
            if (info.facing == wanted) return id
            if (fallback < 0) fallback = id
        }
        if (fallback < 0) throw IllegalStateException("设备没有可用摄像头")
        Timber.w("没有请求方向的摄像头，回退第一个（请求 front=%s）", front)
        return fallback
    }

    // ------------------------------------------------------------------
    // 录像：Camera1 + MediaRecorder（借鉴 autox video_recorder.js）
    // ------------------------------------------------------------------

    /**
     * 摄像头录像（MP4/H.264，可选麦克风伴音）；stream=true 时流式模式。
     *
     * autox 踩坑的启动顺序（务必保持）：
     * ① Camera.open → ② setPreviewTexture(虚拟预览) → ③ startPreview →
     * ④ **unlock() 交出摄像头** → ⑤ MediaRecorder 配置（rec.setCamera）→
     * ⑥ prepare/start。unlock 前不起预览会直接 start failed。
     * 静音：与拍照同款临时静音系统流（录完恢复，避免提示音串进麦克风）。
     *
     * 流式模式（stream=true，借鉴 autox streamVideo）：
     * - 容器 MPEG_2_TS（TS 每包自带时间戳，可边收边播；执行器已保证 API 26+）；
     * - start 后每 [STREAM_CHUNK_INTERVAL_MS] 读一次文件增量 → 二进制帧 → WS 上行
     *   （event=media_stream，服务端转二进制喂给后台 mpegts.js 实时播放）；
     * - duration 变为自动停止倒计时，管理后台 stream_stop 命令可提前停；
     * - 停止后仍走完整文件上传（TS 文件本身即可播放/存档）。
     */
    @Suppress("DEPRECATION")
    private fun captureVideo(intent: Intent, stream: Boolean) {
        // 流式模式 duration<=0 = 不限时（管理后台自动传 0），直到 stream_stop
        val rawSec = intent.getIntExtra(EXTRA_DURATION_SEC, 10)
        val unlimited = stream && rawSec <= 0
        val durationSec = when {
            unlimited -> 0
            stream -> rawSec.coerceIn(10, 600)
            else -> rawSec.coerceIn(3, 60)
        }
        val uploadArchive = intent.getBooleanExtra(EXTRA_UPLOAD, false)
        val preset = videoPreset(intent.getStringExtra(EXTRA_QUALITY) ?: "medium")
        val enableAudio = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, true)
        val front = intent.getStringExtra(EXTRA_CAMERA_FACING) == "front"
        muteSystemVolume()
        if (stream) {
            streamStopFlag = false
            streamActive = true
        }

        // 接管相机资源 + 领取本代 era + watchdog（与拍照同款防御，见 capturePhoto）
        val myEra = takeoverCameraResources()
        val watchdog = Runnable { watchdogTakeover(myEra) }
        val watchdogMs = if (unlimited) 86_400_000L + 45_000 else durationSec * 1000L + 45_000
        Handler(Looper.getMainLooper()).postDelayed(watchdog, watchdogMs)
        val thread = HandlerThread("bridge-video").also { it.start() }
        runCatching { cameraThread?.quitSafely() }
        cameraThread = thread
        val cameraHandler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var error: String? = null
        var saved: File? = null
        // 流式分片游标（finishStream 收尾要在 stop 之后用，声明在闭包顶层）
        var offset = 0L
        var chunkIndex = 0

        cameraHandler.post {
            // 线程局部引用：finally 释放的就是本线程打开的实例（era 校验防双释放）
            var cam: android.hardware.Camera? = null
            var rec: MediaRecorder? = null
            var texture: SurfaceTexture? = null
            try {
                val cameraId = pickCamera(front)
                cam = openCameraWithRetry(cameraId)
                camera = cam
                val info = android.hardware.Camera.CameraInfo()
                android.hardware.Camera.getCameraInfo(cameraId, info)

                // 虚拟预览（无界面取景）——必须在 unlock 之前，autox 最大的坑
                texture = SurfaceTexture(0)
                previewTexture = texture
                cam.setPreviewTexture(texture)
                val params = cam.parameters
                params.supportedPreviewSizes?.minByOrNull {
                    kotlin.math.abs(it.width - preset.width) + kotlin.math.abs(it.height - preset.height)
                }?.let { params.setPreviewSize(it.width, it.height) }
                runCatching { params.setRecordingHint(true) }
                cam.parameters = params
                cam.startPreview()

                // 录制方向（Android 官方 unlock + MediaRecorder 配方，假设当前显示旋转）
                // 仅 MP4 有 moov 旋转元数据；TS 不支持旋转元数据（浏览器按原始帧播放，可接受）
                val degrees = try {
                    @Suppress("DEPRECATION")
                    val r = (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .defaultDisplay.rotation
                    when (r) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                } catch (_: Exception) {
                    0
                }
                val rotation = if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    (info.orientation + degrees) % 360
                } else {
                    (info.orientation - degrees + 360) % 360
                }

                cam.unlock() // 交出摄像头给 MediaRecorder
                rec = newRecorder()
                val recorderRef = rec!!
                recorder = rec
                // autox 顺序：setCamera 紧跟创建、先于一切源/格式设置（放在 setOutputFile
                // 后曾触发 start 阶段裸 IllegalStateException，部分 ROM 对顺序敏感）
                recorderRef.setCamera(cam)
                // 音源 CAMCORDER（autox 同款）：与相机采集绑定的麦克风源；MIC 源在部分
                // ROM 上与视频录制组合会失败。且不再硬编码采样率/码率/声道（显式音频
                // 参数是 start 抛裸 ISE 的另一高危因素，交给编码器默认值）
                if (enableAudio) recorderRef.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                recorderRef.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                recorderRef.setOutputFormat(
                    if (stream) MediaRecorder.OutputFormat.MPEG_2_TS
                    else MediaRecorder.OutputFormat.MPEG_4,
                )
                recorderRef.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (enableAudio) recorderRef.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 参数以 CamcorderProfile（设备侧保证支持的组合）为准，硬编码 fps/码率/
                // 分辨率组合在部分 ROM（如 ColorOS）上 prepare/start 必失败
                val p = camcorderParams(cameraId, preset)
                recorderRef.setVideoSize(p.width, p.height)
                recorderRef.setVideoFrameRate(p.fps)
                recorderRef.setVideoEncodingBitRate(p.bitrate)
                if (!stream) recorderRef.setOrientationHint(rotation)
                val dir = mediaDir(this).apply { mkdirs() }
                val ts = System.currentTimeMillis()
                val ext = if (stream) "ts" else "mp4"
                val tmp = File(dir, "video_$ts.tmp.$ext")
                val target = File(dir, "video_$ts.$ext")
                recorderRef.setOutputFile((if (stream) target else tmp).absolutePath)
                recorderRef.prepare()
                recorderRef.start()
                Timber.i(
                    "录像开始（%s，%dx%d@%d，%d kbps，伴音=%s）",
                    when {
                        unlimited -> "流式不限时"
                        stream -> "流式最长 ${durationSec} 秒"
                        else -> "普通 ${durationSec} 秒"
                    },
                    p.width, p.height, p.fps, p.bitrate / 1000, enableAudio,
                )
                if (stream) {
                    // 流式循环：分片推送 + 停止标志轮询（间隔与分片节奏一致）
                    val streamInfo = JsonObject().apply {
                        addProperty("format", "mpeg2ts")
                        addProperty("video", "h264")
                        addProperty("audio", if (enableAudio) "aac" else "none")
                        addProperty("width", p.width)
                        addProperty("height", p.height)
                        addProperty("duration_sec", durationSec)
                    }
                    sendStreamMessage("video", 0, "", 0, 0, false, streamInfo)
                    val startAt = SystemClock.elapsedRealtime()
                    // 流式不限时：24h 兜底（真实停止靠 streamStopFlag）
                    val totalMs = if (unlimited) 86_400_000L else durationSec * 1000L
                    var lastPct = -1
                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - startAt
                        if (elapsed >= totalMs || streamStopFlag) break
                        SystemClock.sleep(STREAM_CHUNK_INTERVAL_MS)
                        if (streamStopFlag) break
                        // 追帧推送：单周期增量可能超单条 WS 消息承载（高码率），循环直到追平
                        while (true) {
                            val (off, idx) = pushStreamChunks(target, offset, "video", chunkIndex)
                            if (off == offset) break
                            offset = off
                            chunkIndex = idx
                        }
                        if (!unlimited) {
                            val pct = (minOf(SystemClock.elapsedRealtime() - startAt, totalMs) * 100 / totalMs).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                reportCaptureProgress(pct)
                            }
                        }
                    }
                } else {
                    sleepWithProgress(durationSec)
                }
                try {
                    recorderRef.stop()
                } catch (e: Exception) {
                    error = "录制内容为空（摄像头无有效画面或编码失败）：${e.toString().take(120)}"
                } finally {
                    runCatching { recorderRef.release() }
                    recorder = null
                }
                if (error == null) {
                    // TS 直接写最终名无需 rename；MP4 保持 tmp→target（moov 在停止时才写入）
                    if (stream) {
                        if (target.length() > 0) {
                            saved = target
                            finishStream(target, "video", offset, chunkIndex)
                        } else error = "录像文件生成失败"
                    } else if (tmp.renameTo(target)) {
                        saved = target
                    } else {
                        error = "录像文件保存失败"
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "录像采集异常")
                // 带类名+首个栈帧：裸 IllegalStateException（message 为 null）也能定位失败阶段
                error = describeCameraError(e)
            } finally {
                if (stream) {
                    streamActive = false
                    streamStopFlag = false
                }
                // 释放顺序 = autox 同款：先 recorder 再 reconnect+release 相机。
                // unlock 后相机归属 MediaRecorder，reconnect() 先收回再 release 才干净，
                // 否则 MediaRecorder 侧残留会话会让系统报「摄像头被占用」。
                // v0.1.51：era 校验——超时路径 complete()/watchdog 已接管时这里自动跳过，
                // 杜绝旧版「两处释放同一实例」的双释放（占用复现根因）
                releaseCameraOwned(cam, rec, texture, myEra)
                done.countDown()
                runCatching { thread.quitSafely() }
            }
        }

        try {
            val awaitMs = if (unlimited) 86_400_000L + 20_000 else durationSec * 1000L + 20_000
            if (!done.await(awaitMs, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("录像超时（相机无响应）")
            }
        } finally {
            Handler(Looper.getMainLooper()).removeCallbacks(watchdog)
            // 不在此跨线程释放相机（竞态）：相机线程的 finally 必然执行释放；
            // 硬卡死场景由 watchdog（duration+45s）强制接管兜底
            restoreVolumes()
        }
        error?.let { throw IllegalStateException("录像失败：$it") }
        complete(
            ScreenCaptureHolder.CaptureFile(
                saved?.absolutePath ?: "",
                skipUpload = stream && !uploadArchive,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 录音：MediaRecorder 纯音频（借鉴 autox audio_recorder.js）
    // ------------------------------------------------------------------

    /**
     * 麦克风录音（M4A/AAC），比录像简单得多——无相机、无预览、无 unlock。
     * stream=true 流式模式：AAC_ADTS 容器（每帧自带头，可累积实时解码播放），
     * 每 [STREAM_CHUNK_INTERVAL_MS] 读文件增量 → 二进制帧 → WS 上行；
     * 后台 Web Audio 逐段解码实时出声（约 1~2 秒延迟）。
     */
    private fun captureAudio(intent: Intent, stream: Boolean) {
        // 流式模式 duration<=0 = 不限时（管理后台自动传 0），直到 stream_stop
        val rawSec = intent.getIntExtra(EXTRA_DURATION_SEC, 30)
        val unlimited = stream && rawSec <= 0
        val durationSec = when {
            unlimited -> 0
            stream -> rawSec.coerceIn(10, 600)
            else -> rawSec.coerceIn(5, 300)
        }
        val uploadArchive = intent.getBooleanExtra(EXTRA_UPLOAD, false)
        val (sampleRate, bitrate, channels) = audioPreset(intent.getStringExtra(EXTRA_QUALITY) ?: "medium")
        if (stream) {
            streamStopFlag = false
            streamActive = true
        }
        // 流式分片游标（finishStream 收尾要用，声明在函数作用域）
        var offset = 0L
        var chunkIndex = 0

        val rec = newRecorder()
        recorder = rec
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(
            if (stream) MediaRecorder.OutputFormat.AAC_ADTS
            else MediaRecorder.OutputFormat.MPEG_4,
        )
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioSamplingRate(sampleRate)
        rec.setAudioEncodingBitRate(bitrate)
        rec.setAudioChannels(channels)
        val dir = mediaDir(this).apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val ext = if (stream) "aac" else "m4a"
        val tmp = File(dir, "audio_$ts.tmp.$ext")
        val target = File(dir, "audio_$ts.$ext")
        rec.setOutputFile((if (stream) target else tmp).absolutePath)
        rec.prepare()
        rec.start()
        Timber.i(
            "录音开始（%s %d 秒，%dHz/%dkbps/%d 声道）",
            if (stream) "流式" else "普通", durationSec, sampleRate, bitrate / 1000, channels,
        )
        if (stream) {
            val streamInfo = JsonObject().apply {
                addProperty("format", "aac_adts")
                addProperty("sample_rate", sampleRate)
                addProperty("bit_rate", bitrate)
                addProperty("channels", channels)
                addProperty("duration_sec", durationSec)
            }
            sendStreamMessage("audio", 0, "", 0, 0, false, streamInfo)
            val startAt = SystemClock.elapsedRealtime()
            // 流式不限时：24h 兜底（真实停止靠 streamStopFlag）
            val totalMs = if (unlimited) 86_400_000L else durationSec * 1000L
            var lastPct = -1
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startAt
                if (elapsed >= totalMs || streamStopFlag) break
                SystemClock.sleep(STREAM_CHUNK_INTERVAL_MS)
                if (streamStopFlag) break
                val (off, idx) = pushStreamChunks(target, offset, "audio", chunkIndex)
                if (off > offset) {
                    offset = off
                    chunkIndex = idx
                }
                if (!unlimited) {
                    val pct = (minOf(SystemClock.elapsedRealtime() - startAt, totalMs) * 100 / totalMs).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        reportCaptureProgress(pct)
                    }
                }
            }
        } else {
            sleepWithProgress(durationSec)
        }
        try {
            rec.stop()
        } catch (e: Exception) {
            throw IllegalStateException("录制内容为空（麦克风无有效输入或编码失败）：${e.message}")
        } finally {
            rec.release()
            recorder = null
            if (stream) {
                streamActive = false
                streamStopFlag = false
            }
        }
        // TS/ADTS 直接写最终名无需 rename；MP4 保持 tmp→target
        if (!stream && !tmp.renameTo(target)) {
            throw IllegalStateException("录音文件保存失败")
        }
        if (stream && target.length() <= 0L) {
            throw IllegalStateException("录音文件生成失败")
        }
        if (stream) finishStream(target, "audio", offset, chunkIndex)
        complete(
            ScreenCaptureHolder.CaptureFile(
                target.absolutePath,
                skipUpload = stream && !uploadArchive,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 流式分片推送（借鉴 autox streamStart/_pushStreamChunk）
    // ------------------------------------------------------------------

    /**
     * 读取产物文件 [fromOffset, len) 的增量字节，以**二进制 WS 帧**上行
     * （v0.1.56：替代 base64 文本通道，省 33% 上行流量；服务端按 WS binary
     * opcode 解包 2B 头长 + JSON 头 + 原始负载，走同一 _handle_media_stream）。
     *
     * @return 新的 (读偏移, 分片序号)；文件无增量返回原值（不发送）
     */
    private fun pushStreamChunks(
        file: File,
        fromOffset: Long,
        kind: String,
        fromIndex: Int,
    ): Pair<Long, Int> {
        var offset = fromOffset
        var index = fromIndex
        try {
            val len = file.length()
            if (len > offset) {
                val buf = ByteArray((len - offset).toInt())
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    raf.readFully(buf)
                }
                offset += buf.size
                index++
                val header = JsonObject().apply {
                    addProperty("event", "media_stream")
                    addProperty("kind", kind)
                    addProperty("command_id", commandId)
                    addProperty("chunk_index", index)
                    addProperty("bytes", buf.size)
                    addProperty("total_bytes", offset)
                    addProperty("is_end", false)
                }
                wsClient.sendBusinessBinary(header, buf)
            }
        } catch (e: Exception) {
            // 分片失败不中断录制：服务端文件仍在，结束后还有完整文件上传兜底
            Timber.w(e, "流式分片推送失败（忽略，继续录制）")
        }
        return offset to index
    }

    /** 发送流式 WS 消息（fire-and-forget：WS 不在线/失败静默，不影响采集链路）。 */
    private fun sendStreamMessage(
        kind: String,
        chunkIndex: Int,
        data: String,
        bytes: Long,
        totalBytes: Long,
        isEnd: Boolean,
        info: JsonObject? = null,
    ) {
        try {
            val payload = JsonObject().apply {
                addProperty("event", "media_stream")
                addProperty("kind", kind)
                addProperty("command_id", commandId)
                addProperty("chunk_index", chunkIndex)
                addProperty("data", data)
                addProperty("bytes", bytes)
                addProperty("total_bytes", totalBytes)
                addProperty("is_end", isEnd)
                if (info != null) add("info", info)
            }
            wsClient.sendBusinessMessage(payload)
        } catch (_: Exception) {
        }
    }

    /** 流式结束收尾：推送剩余分片 + 结束标志（is_end=true，供后台收尾下载/停止播放）。 */
    private fun finishStream(file: File, kind: String, offset: Long, index: Int) {
        val (off, idx) = pushStreamChunks(file, offset, kind, index)
        sendStreamMessage(kind, idx + 1, "", 0, off, true)
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /** 录像画质档位（借鉴 autox 预设：low/medium/high；实际参数以 CamcorderProfile 校准）。 */
    private data class VideoPreset(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    private fun videoPreset(quality: String): VideoPreset = when (quality) {
        "low" -> VideoPreset(640, 480, 24, 800_000)
        "high" -> VideoPreset(1920, 1080, 30, 5_000_000)
        else -> VideoPreset(1280, 720, 30, 2_500_000)
    }

    /**
     * 用 CamcorderProfile 校准录制参数（设备侧保证支持的组合）。
     * CamcorderProfile 取不到时回退硬编码 preset（仍比裸手写可靠：至少 preview 尺寸已就近匹配）。
     */
    @Suppress("DEPRECATION")
    private fun camcorderParams(cameraId: Int, wanted: VideoPreset): VideoPreset {
        val quality = when {
            wanted.height <= 480 -> CamcorderProfile.QUALITY_480P
            wanted.height <= 720 -> CamcorderProfile.QUALITY_720P
            else -> CamcorderProfile.QUALITY_1080P
        }
        val profile = runCatching {
            if (CamcorderProfile.hasProfile(cameraId, quality)) {
                CamcorderProfile.get(cameraId, quality)
            } else {
                CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW)
            }
        }.getOrNull() ?: return wanted
        return VideoPreset(profile.videoFrameWidth, profile.videoFrameHeight, profile.videoFrameRate, profile.videoBitRate)
    }

    // ------------------------------------------------------------------
    // 相机资源所有权（era 代数令牌 + 强制接管 + open 重试，v0.1.51 防御网）
    // ------------------------------------------------------------------

    /**
     * Camera.open 带重试：摄像头 HAL 完全关闭有延迟（几百 ms 级），上一次采集
     * 刚释放就立刻开下一次，open 可能瞬时报 "Camera is being used by another
     * process"——密集采集（连拍/连续录像/测试）时必现。固定重试消化该延迟。
     */
    private fun openCameraWithRetry(
        cameraId: Int,
        retries: Int = 3,
        delayMs: Long = 400,
    ): android.hardware.Camera {
        var last: Exception? = null
        repeat(retries + 1) { attempt ->
            try {
                return android.hardware.Camera.open(cameraId)
            } catch (e: Exception) {
                last = e
                if (attempt < retries) {
                    Timber.w("Camera.open 第 %d 次失败（%s），%d ms 后重试", attempt + 1, e.message, delayMs)
                    SystemClock.sleep(delayMs)
                }
            }
        }
        throw last ?: IllegalStateException("Camera.open 失败（无异常信息）")
    }

    /**
     * 强制接管相机资源：**无条件** bump 代数（使旧线程/回调持有的一切释放权失效）
     * 并安全释放共享引用，返回接管后的新 era。
     *
     * 调用时机：① 相机类采集启动前（清上一代残留 + 领取新代）；
     * ② complete() 兜底（含超时路径——接管后卡死线程稍后的释放会被 era 校验跳过）；
     * ③ onDestroy。
     */
    private fun takeoverCameraResources(): Int = synchronized(cameraLock) {
        cameraEra++
        recorder?.let { r ->
            runCatching { r.reset() }
            runCatching { r.release() }
        }
        recorder = null
        camera?.let { c ->
            runCatching { c.stopPreview() }
            runCatching { c.reconnect() }
            runCatching { c.release() }
        }
        camera = null
        runCatching { previewTexture?.release() }
        previewTexture = null
        cameraEra
    }

    /**
     * watchdog 强制接管（相机线程硬卡死场景）：仅当采集仍在「当前代」（即没有
     * 其他入口已接管/已正常释放）时才执行，否则静默跳过——与线程 finally、
     * complete 兜底互斥，保证同一代只释放一次。
     */
    private fun watchdogTakeover(expectedEra: Int) {
        synchronized(cameraLock) {
            if (expectedEra != cameraEra) return
            cameraEra++
            recorder?.let { r ->
                runCatching { r.reset() }
                runCatching { r.release() }
            }
            recorder = null
            camera?.let { c ->
                runCatching { c.stopPreview() }
                runCatching { c.reconnect() }
                runCatching { c.release() }
            }
            camera = null
            runCatching { previewTexture?.release() }
            previewTexture = null
        }
        Timber.e("相机 watchdog 触发（era=%d）：相机线程无响应，已强制接管释放相机资源", expectedEra)
    }

    /**
     * 相机线程侧释放（拍照/录像的线程 finally 与 JPEG 回调用）：
     * 持 [myEra] 校验 + 释放权一次性消费。era 不匹配 = 已被 complete 兜底 /
     * watchdog / 下一次采集接管，**直接跳过**——这就是双释放免疫的关键。
     * 共享字段仅在仍指向本线程实例时才清空（避免覆盖新采集的引用）。
     */
    private fun releaseCameraOwned(
        cam: android.hardware.Camera?,
        rec: MediaRecorder?,
        texture: SurfaceTexture?,
        myEra: Int,
    ) {
        synchronized(cameraLock) {
            if (myEra != cameraEra) return
            cameraEra++ // 本代释放权已消费，同代任何后续释放（含本线程重复调用）全部失效
            if (recorder != null && recorder === rec) recorder = null
            if (camera === cam) camera = null
            if (previewTexture === texture) previewTexture = null
        }
        // 释放在锁外执行（release 可能阻塞数百 ms，勿持锁）
        rec?.let { runCatching { it.release() } }
        cam?.let { c ->
            runCatching { c.stopPreview() }
            runCatching { c.reconnect() }
            runCatching { c.release() }
        }
        runCatching { texture?.release() }
    }

    /**
     * 相机类异常描述增强：类名 + message（可能为 null）+ 首个业务栈帧。
     * MediaRecorder.start/stop 常抛「无 message 的裸 IllegalStateException」，
     * 只给类名无法定位失败阶段（prepare 还是 start），附栈帧后回执可直接看到出处。
     * 占用类错误追加可操作提示（v0.1.51：open 已带 3 次重试，仍失败才走到这）。
     */
    private fun describeCameraError(e: Exception): String {
        val frame = e.stackTrace.firstOrNull()
        val stage = frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.lineNumber})" } ?: "unknown"
        val msg = e.message ?: "(无 message)"
        val hint = if (e.message?.contains("being used", ignoreCase = true) == true ||
            e.message?.contains("Camera is in use", ignoreCase = true) == true
        ) {
            "（相机被占用且重试无效：若反复出现，请强杀 Bridge 进程或重启手机释放相机 HAL）"
        } else ""
        return "${e.javaClass.simpleName}: $msg @ $stage$hint"
    }

    /** 录音音质档位：(采样率 Hz, 码率 bps, 声道数)。 */
    private fun audioPreset(quality: String): Triple<Int, Int, Int> = when (quality) {
        "low" -> Triple(16000, 32_000, 1)
        "high" -> Triple(48000, 192_000, 2)
        else -> Triple(44100, 96_000, 1)
    }

    /** 尽力静音：临时静音系统/铃声/提示音流，结束后 [restoreVolumes] 恢复。 */
    private fun muteSystemVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (stream in intArrayOf(
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_RING,
            )) {
                savedVolumes[stream] = am.getStreamVolume(stream)
                am.setStreamVolume(stream, 0, 0)
            }
            mutedStreams = 1
        } catch (e: Exception) {
            Timber.w(e, "静音系统音量失败（忽略，快门声可能仍在）")
        }
    }

    private fun restoreVolumes() {
        if (mutedStreams != 1) return
        mutedStreams = -1
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for ((stream, volume) in savedVolumes) {
                runCatching { am.setStreamVolume(stream, volume, 0) }
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /**
     * 执行阶段进度上报（fire-and-forget：WS 不在线/发送失败静默，不影响采集链路）。
     * 服务端转发 SSE → 管理后台把「等待回执」替换为「执行中 XX%」。
     */
    private fun reportCaptureProgress(percent: Int) {
        if (commandId.isEmpty()) return
        try {
            val data = JsonObject().apply {
                addProperty("event", "command_progress")
                addProperty("command_id", commandId)
                addProperty("phase", "executing")
                addProperty("percent", percent.coerceIn(0, 100))
            }
            wsClient.sendBusinessMessage(data)
        } catch (_: Exception) {
        }
    }

    /**
     * 带进度的录制等待：按秒醒来上报执行进度（录屏/录像/录音共用），
     * 替代一次性 sleep(durationSec*1000)——后台命令卡片能看到录制走到哪了。
     */
    private fun sleepWithProgress(durationSec: Int) {
        val totalMs = durationSec * 1000L
        val start = SystemClock.elapsedRealtime()
        var lastPct = -1
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= totalMs) return
            SystemClock.sleep(minOf(1000L, totalMs - elapsed))
            val pct = (minOf(SystemClock.elapsedRealtime() - start, totalMs) * 100 / totalMs).toInt()
            if (pct != lastPct) {
                lastPct = pct
                reportCaptureProgress(pct)
            }
        }
    }

    /**
     * 客户端媒体残留自动清理（complete 时调用）。
     * 正常路径上传成功即删本地文件，残留基本都是上传失败的孤儿：超过 7 天全删、
     * 超过 50 个时只留最新 50 个（正在生成的产物是最新文件，天然受保护）。
     */
    private fun cleanupMediaLeftovers() {
        runCatching {
            val dir = mediaDir(this)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            val now = System.currentTimeMillis()
            var removed = 0
            for (f in files) {
                if (now - f.lastModified() > MEDIA_MAX_AGE_MS && f.delete()) removed++
            }
            val rest = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
            if (rest.size > MEDIA_KEEP_COUNT) {
                for (f in rest.drop(MEDIA_KEEP_COUNT)) {
                    if (f.delete()) removed++
                }
            }
            if (removed > 0) Timber.i("媒体残留自动清理：%d 个", removed)
        }
    }

    /**
     * 校验并获取 MediaProjection（v0.1.37：会话复用 + registerCallback）。
     *
     * - Intent 带授权结果（首次）：停掉旧会话 → getMediaProjection → **必须先
     *   registerCallback 再 createVirtualDisplay**（Android 14+ 硬性要求，否则
     *   抛 SecurityException「Must register a callback…」，即旧版每次报错的根因）
     *   → 置 projectionHeld=true 转入会话保持；
     * - Intent 不带授权结果（复用）：直接返回已持有的 projection
     *   （projectionHeld=true 保证有效；失效场景执行器不会走到这里）。
     */
    private fun requireProjection(intent: Intent): MediaProjection {
        projection?.let { existing ->
            if (projectionHeld && intent.hasExtra(EXTRA_CONSENT_DATA).not()) {
                Timber.i("复用已保持的投屏会话（免系统确认）")
                return existing
            }
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getBundleExtra(EXTRA_CONSENT_DATA)?.getParcelable("data")
        if (data == null) throw IllegalStateException("投屏授权结果缺失")
        // 换新会话前先释放旧幕布与旧投影（旧幕布绑定旧实例，不能跨会话复用）
        runCatching { mirrorDisplay?.release() }
        mirrorDisplay = null
        runCatching { projection?.stop() }
        projection = null
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("获取投屏会话失败（授权结果无效）")
        // Android 14+：createVirtualDisplay 前必须注册回调（资源回收响应）
        p.registerCallback(projectionCallback, handler ?: Handler(mainLooper))
        projection = p
        projectionHeld = true
        Timber.i("投屏会话已建立并转入保持（后续采集免确认）")
        return p
    }

    /**
     * 获取持久镜像幕布（每面投影会话只 createVirtualDisplay 一次）。
     * 初始无 surface，由截屏/录屏各自 [VirtualDisplay.setSurface] 挂载。
     */
    private fun obtainMirrorDisplay(p: MediaProjection, metrics: DisplayMetrics): VirtualDisplay {
        mirrorDisplay?.let { return it }
        val vd = p.createVirtualDisplay(
            "bridge-mirror", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler,
        ) ?: throw IllegalStateException("创建投屏虚拟显示器失败")
        mirrorDisplay = vd
        Timber.i("镜像幕布已创建（本会话内复用，不随单次采集释放）")
        return vd
    }

    private fun realDisplayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "媒体采集（静默）", NotificationManager.IMPORTANCE_MIN),
            )
        }
        // 尽最大可能静默（用户要求）：前台服务通知是 Android 8+ 系统硬性要求无法去除，
        // 能做到的上限 = IMPORTANCE_MIN 渠道（无声音/无横幅/折叠底部）+ 中性文案不透露动作内容
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bridge")
            .setContentText("运行中")
            .setOngoing(true)
            .build()
    }

    /**
     * 回传结果（只回一次）。采集类资源（VirtualDisplay/Reader/Recorder）即用即释，
     * 但 **不释放 MediaProjection**——保持会话供后续免确认复用；服务不 stopSelf，
     * 以「会话保持中」常驻（借鉴 autox：一次授权进程不重启一直有效）。
     */
    private fun complete(file: ScreenCaptureHolder.CaptureFile) {
        captureBusy = false
        // 流式状态复位（覆盖异常路径残留，正常路径在各自 finally 已复位）
        streamActive = false
        streamStopFlag = false
        if (finished) return
        finished = true
        releaseCaptureResources()
        cleanupMediaLeftovers()
        ScreenCaptureHolder.takeCapture(commandId)?.complete(file)
        // 刷新通知为会话保持文案
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    /**
     * 释放单次采集资源（幕布 mirrorDisplay 归会话所有，这里不动）。
     * 相机/recorder 走 **era 接管**（bump 代数 + 释放残留）：正常路径相机线程
     * finally 已释放（共享字段为 null，此处为无操作）；超时/异常路径相机线程
     * 可能还活着，接管使其后续释放自动失效——v0.1.51 起不再与线程并发双释放。
     */
    private fun releaseCaptureResources() {
        runCatching { imageReader?.close() }
        imageReader = null
        takeoverCameraResources()
    }

    private fun releaseAll() {
        releaseCaptureResources()
        runCatching { mirrorDisplay?.release() }
        mirrorDisplay = null
        projectionHeld = false
        runCatching { projection?.stop() }
        projection = null
    }
}
