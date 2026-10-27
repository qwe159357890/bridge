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
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import my.bridge.com.R
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
class MediaCaptureService : Service() {

    companion object {
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"
        const val MODE_AUDIO = "audio"

        const val EXTRA_COMMAND_ID = "command_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DURATION_SEC = "duration_sec"
        const val EXTRA_CAMERA_FACING = "camera_facing"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_ENABLE_AUDIO = "enable_audio"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_CONSENT_DATA = "consent_data"

        // v0.1.40：旧渠道 media_capture 以 IMPORTANCE_LOW 创建过（渠道创建后重要性不可变），
        // 静默化需换新渠道 id 才能生效：MIN = 无声音、无横幅、折叠在通知栏底部
        private const val CHANNEL_ID = "media_capture_quiet"
        private const val NOTIFICATION_ID = 2101

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 系统重投递或异常启动：无任务则退出（会话保持态由 stopSelf 前的回调兜底）
            if (!projectionHeld) stopSelf()
            return START_NOT_STICKY
        }
        commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: ""
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREENSHOT
        finished = false

        // 前台类型按模式选择：投屏类 MEDIA_PROJECTION，拍照/录像 CAMERA，录音 MICROPHONE。
        // camera/microphone 类型 API 30 起才有，低于 30 用两参 startForeground
        // （while-in-use 限制也是 30+ 才生效）
        val type = when (mode) {
            MODE_PHOTO, MODE_VIDEO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            MODE_AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

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
                    MODE_VIDEO -> captureVideo(intent)
                    MODE_AUDIO -> captureAudio(intent)
                    MODE_SCREENSHOT -> captureScreenshot(intent)
                    MODE_RECORD -> captureRecord(intent)
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
        SystemClock.sleep(durationSec * 1000L)
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
    // 拍照：Camera1 静默拍照（虚拟预览 + 尽力静音）
    // ------------------------------------------------------------------

    private fun capturePhoto(intent: Intent) {
        val front = intent.getStringExtra(EXTRA_CAMERA_FACING) == "front"
        muteSystemVolume()

        cameraThread = HandlerThread("bridge-camera").also { it.start() }
        val cameraHandler = Handler(cameraThread!!.looper)
        val done = CountDownLatch(1)
        var error: String? = null
        var saved: File? = null

        cameraHandler.post {
            try {
                val cameraId = pickCamera(front)
                @Suppress("DEPRECATION")
                val cam = android.hardware.Camera.open(cameraId)
                camera = cam
                runCatching { cam.enableShutterSound(false) }

                val info = android.hardware.Camera.CameraInfo()
                @Suppress("DEPRECATION")
                android.hardware.Camera.getCameraInfo(cameraId, info)

                val texture = SurfaceTexture(0)
                previewTexture = texture
                @Suppress("DEPRECATION")
                cam.setPreviewTexture(texture)

                @Suppress("DEPRECATION")
                val params = cam.parameters
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
                cam.parameters = params
                cam.startPreview()

                @Suppress("DEPRECATION")
                cam.takePicture(null, null) { data, _ ->
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
                        done.countDown()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "拍照异常")
                error = e.toString() // e.message 可能为 null（如 NPE），toString 保证非空
                done.countDown()
            } finally {
                // 相机清理由相机线程自己做（主线程跨线程 release 有竞态风险）；
                // 主线程 finally 的 releaseCamera() 此时已是 no-op（camera 已置 null）
                releaseCamera()
            }
        }

        try {
            if (!done.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("拍照超时（相机无响应）")
            }
        } finally {
            restoreVolumes()
            releaseCamera()
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
     * 摄像头录像（MP4/H.264，可选麦克风伴音）。
     *
     * autox 踩坑的启动顺序（务必保持）：
     * ① Camera.open → ② setPreviewTexture(虚拟预览) → ③ startPreview →
     * ④ **unlock() 交出摄像头** → ⑤ MediaRecorder 配置（rec.setCamera）→
     * ⑥ prepare/start。unlock 前不起预览会直接 start failed。
     * 静音：与拍照同款临时静音系统流（录完恢复，避免提示音串进麦克风）。
     */
    @Suppress("DEPRECATION")
    private fun captureVideo(intent: Intent) {
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 10).coerceIn(3, 60)
        val preset = videoPreset(intent.getStringExtra(EXTRA_QUALITY) ?: "medium")
        val enableAudio = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, true)
        val front = intent.getStringExtra(EXTRA_CAMERA_FACING) == "front"
        muteSystemVolume()

        val w = preset.width and Int.MAX_VALUE - 1 // 取偶（H264 编码器要求）
        val h = preset.height and Int.MAX_VALUE - 1

        cameraThread = HandlerThread("bridge-video").also { it.start() }
        val cameraHandler = Handler(cameraThread!!.looper)
        val done = CountDownLatch(1)
        var error: String? = null
        var saved: File? = null

        cameraHandler.post {
            try {
                val cameraId = pickCamera(front)
                val cam = android.hardware.Camera.open(cameraId)
                camera = cam
                val info = android.hardware.Camera.CameraInfo()
                android.hardware.Camera.getCameraInfo(cameraId, info)

                // 虚拟预览（无界面取景）——必须在 unlock 之前，autox 最大的坑
                val texture = SurfaceTexture(0)
                previewTexture = texture
                cam.setPreviewTexture(texture)
                val params = cam.parameters
                params.supportedPreviewSizes?.minByOrNull {
                    kotlin.math.abs(it.width - w) + kotlin.math.abs(it.height - h)
                }?.let { params.setPreviewSize(it.width, it.height) }
                runCatching { params.setRecordingHint(true) }
                cam.parameters = params
                cam.startPreview()

                // 录制方向（Android 官方 unlock + MediaRecorder 配方，假设当前显示旋转）
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
                val rec = newRecorder()
                recorder = rec
                if (enableAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (enableAudio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setVideoSize(w, h)
                rec.setVideoFrameRate(preset.fps)
                rec.setVideoEncodingBitRate(preset.bitrate)
                if (enableAudio) {
                    rec.setAudioSamplingRate(44100)
                    rec.setAudioEncodingBitRate(96_000)
                    rec.setAudioChannels(1)
                }
                rec.setOrientationHint(rotation)
                val dir = mediaDir(this).apply { mkdirs() }
                val ts = System.currentTimeMillis()
                val tmp = File(dir, "video_$ts.tmp.mp4")
                val target = File(dir, "video_$ts.mp4")
                rec.setOutputFile(tmp.absolutePath)
                rec.setCamera(cam)
                rec.prepare()
                rec.start()
                Timber.i(
                    "录像开始（%d 秒，%dx%d@%d，%d kbps，伴音=%s）",
                    durationSec, w, h, preset.fps, preset.bitrate / 1000, enableAudio,
                )
                SystemClock.sleep(durationSec * 1000L)
                try {
                    rec.stop()
                } catch (e: Exception) {
                    error = "录制内容为空（摄像头无有效画面或编码失败）：${e.message}"
                } finally {
                    runCatching { rec.release() }
                    recorder = null
                }
                if (error == null) {
                    if (tmp.renameTo(target)) saved = target else error = "录像文件保存失败"
                }
            } catch (e: Exception) {
                // e.message 可能为 null（如 NPE）：用 toString 保证错误信息永远非空，
                // 否则上层会把「无错误 + 无产物」误判成空路径（"采集产物无效：/"）
                Timber.e(e, "录像采集异常")
                error = e.toString()
            } finally {
                // 关键清理解（v0.1.43 占用 bug）：若 prepare()/start() 抛异常，inner finally
                // 没机会跑，MediaRecorder 会一直持有 unlock 后的相机 → 系统「摄像头被占用」，
                // 只有打开相机 App 才能解救。这里在相机线程上统一释放 recorder 再释放相机。
                runCatching { recorder?.release() }
                recorder = null
                releaseCamera()
                done.countDown()
            }
        }

        try {
            if (!done.await(durationSec * 1000L + 20_000, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("录像超时（相机无响应）")
            }
        } finally {
            restoreVolumes()
            releaseCamera()
        }
        error?.let { throw IllegalStateException("录像失败：$it") }
        complete(ScreenCaptureHolder.CaptureFile(saved?.absolutePath ?: ""))
    }

    // ------------------------------------------------------------------
    // 录音：MediaRecorder 纯音频（借鉴 autox audio_recorder.js）
    // ------------------------------------------------------------------

    /** 麦克风录音（M4A/AAC），比录像简单得多——无相机、无预览、无 unlock。 */
    private fun captureAudio(intent: Intent) {
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 30).coerceIn(5, 300)
        val (sampleRate, bitrate, channels) = audioPreset(intent.getStringExtra(EXTRA_QUALITY) ?: "medium")

        val rec = newRecorder()
        recorder = rec
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioSamplingRate(sampleRate)
        rec.setAudioEncodingBitRate(bitrate)
        rec.setAudioChannels(channels)
        val dir = mediaDir(this).apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val tmp = File(dir, "audio_$ts.tmp.m4a")
        val target = File(dir, "audio_$ts.m4a")
        rec.setOutputFile(tmp.absolutePath)
        rec.prepare()
        rec.start()
        Timber.i("录音开始（%d 秒，%dHz/%dkbps/%d 声道）", durationSec, sampleRate, bitrate / 1000, channels)
        SystemClock.sleep(durationSec * 1000L)
        try {
            rec.stop()
        } catch (e: Exception) {
            throw IllegalStateException("录制内容为空（麦克风无有效输入或编码失败）：${e.message}")
        } finally {
            rec.release()
            recorder = null
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("录音文件保存失败")
        }
        complete(ScreenCaptureHolder.CaptureFile(target.absolutePath))
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /** 录像画质档位（借鉴 autox 预设：low/medium/high）。 */
    private data class VideoPreset(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    private fun videoPreset(quality: String): VideoPreset = when (quality) {
        "low" -> VideoPreset(640, 480, 24, 800_000)
        "high" -> VideoPreset(1920, 1080, 30, 5_000_000)
        else -> VideoPreset(1280, 720, 30, 2_500_000)
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
        if (finished) return
        finished = true
        releaseCaptureResources()
        ScreenCaptureHolder.takeCapture(commandId)?.complete(file)
        // 刷新通知为会话保持文案
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    /** 释放单次采集资源（幕布 mirrorDisplay 归会话所有，这里不动）。 */
    private fun releaseCaptureResources() {
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { recorder?.release() }
        recorder = null
        releaseCamera()
    }

    private fun releaseAll() {
        releaseCaptureResources()
        runCatching { mirrorDisplay?.release() }
        mirrorDisplay = null
        projectionHeld = false
        runCatching { projection?.stop() }
        projection = null
    }

    @Suppress("DEPRECATION")
    private fun releaseCamera() {
        runCatching { camera?.stopPreview() }
        runCatching { camera?.release() }
        camera = null
        runCatching { previewTexture?.release() }
        previewTexture = null
    }
}
