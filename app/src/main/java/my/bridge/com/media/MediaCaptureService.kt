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
 * 媒体采集前台服务（screenshot / record_screen / take_photo 三模式）。
 *
 * Manifest 声明 ``foregroundServiceType="camera|mediaProjection"``：
 * - 截屏/录屏：MediaProjection 授权结果经 Intent 传入，先以 MEDIA_PROJECTION
 *   类型 startForeground（Android 14+ 硬性要求，先声明再建 VirtualDisplay）；
 * - 拍照：Camera1 静音拍照（enableShutterSound(false) + 系统音量临时静音兜底），
 *   后台访问相机要求 CAMERA 类型前台服务（Android 11+ while-in-use 规则）。
 *
 * 结果经 [ScreenCaptureHolder.capture]（key = commandId）回传执行器：
 * 成功为产物文件路径（App 私有 media/ 目录，上传后由执行器清理）。
 */
class MediaCaptureService : Service() {

    companion object {
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
        const val MODE_PHOTO = "photo"

        const val EXTRA_COMMAND_ID = "command_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DURATION_SEC = "duration_sec"
        const val EXTRA_CAMERA_FACING = "camera_facing"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_CONSENT_DATA = "consent_data"

        private const val CHANNEL_ID = "media_capture"
        private const val NOTIFICATION_ID = 2101

        /** 采集目录（App 私有 media/，与上传后清理配套）。 */
        fun mediaDir(context: Context): File =
            context.getExternalFilesDir("media") ?: File(context.filesDir, "media")
    }

    private lateinit var workThread: HandlerThread
    private lateinit var handler: Handler

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: ""
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREENSHOT

        // 前台类型按模式选择：投屏类 MEDIA_PROJECTION，拍照 CAMERA。
        // camera 类型 API 30 起才有，低于 30 用两参 startForeground（while-in-use 限制也是 30+ 才生效）
        val type = if (mode == MODE_PHOTO) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        workThread = HandlerThread("bridge-capture").also { it.start() }
        handler = Handler(workThread.looper)

        val startedAt = SystemClock.elapsedRealtime()
        handler.post {
            try {
                when (mode) {
                    MODE_PHOTO -> capturePhoto(intent)
                    MODE_SCREENSHOT -> captureScreenshot(intent)
                    MODE_RECORD -> captureRecord(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "采集失败（mode=%s）", mode)
                complete(ScreenCaptureHolder.failure("采集失败：${e.message}"))
            } finally {
                Timber.i("采集服务结束（mode=%s，耗时 %d ms）", mode, SystemClock.elapsedRealtime() - startedAt)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseAll()
        restoreVolumes()
        workThread.looper?.quit()
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

        virtualDisplay = p.createVirtualDisplay(
            "bridge-shot", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )

        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("截屏超时（屏幕可能受 DRM 保护）")
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

        virtualDisplay = p.createVirtualDisplay(
            "bridge-record", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, handler,
        )
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
                error = e.message
                done.countDown()
            }
        }

        try {
            if (!done.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("拍照超时（相机无响应）")
            }
        } finally {
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

    /** 校验并构建 MediaProjection（Android 14+ 要求先 startForeground 再调）。 */
    private fun requireProjection(intent: Intent): MediaProjection {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getBundleExtra(EXTRA_CONSENT_DATA)?.getParcelable("data")
        if (data == null) throw IllegalStateException("投屏授权结果缺失")
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("获取投屏会话失败（授权结果无效）")
        projection = p
        return p
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
                NotificationChannel(CHANNEL_ID, "媒体采集", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val text = when (mode) {
            MODE_PHOTO -> "正在拍照…"
            MODE_RECORD -> "正在录制屏幕…"
            else -> "正在截取屏幕…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bridge 媒体采集")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    /** 回传结果（只回一次）。 */
    private fun complete(file: ScreenCaptureHolder.CaptureFile) {
        if (finished) return
        finished = true
        releaseAll()
        ScreenCaptureHolder.takeCapture(commandId)?.complete(file)
        handler.post { stopSelf() }
    }

    private fun releaseAll() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { recorder?.release() }
        recorder = null
        releaseCamera()
        runCatching {
            projection?.stop()
            projection = null
        }
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
