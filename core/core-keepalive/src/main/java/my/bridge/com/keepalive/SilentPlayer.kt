package my.bridge.com.keepalive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * 无声播放锚点（借鉴 Aurelay）。
 *
 * 独立 AudioTrack 循环播放 ±1 LSB 近静音 PCM。
 * 约束：只 setVolume(0f) 不改系统音量；不申请 AudioFocus；只 pause+flush 绝不 stop。
 */
object SilentPlayer {

    private const val TAG = "SilentPlayer"

    private const val SAMPLE_RATE = 48000
    private const val FRAME_SAMPLES = 960
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val TICK_MS = 20L

    private var track: AudioTrack? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var tickRunnable: Runnable? = null

    @Volatile
    private var playing = false

    @Volatile
    private var scenarioAllowed = true

    private var counter = 0L

    fun applyEnabled(context: Context) {
        val enabled = KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_RES_AUDIO)
        if (enabled) start(context, "开关打开") else stop("开关关闭")
    }

    fun setScenarioAllowed(context: Context, allowed: Boolean) {
        scenarioAllowed = allowed
        val enabled = KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_RES_AUDIO)
        if (!enabled) {
            stop("开关关闭")
            return
        }
        if (allowed) start(context, "场景空闲") else stop("业务占用音频，让位")
    }

    fun start(context: Context, reason: String) {
        if (playing) return
        if (!scenarioAllowed) return
        try {
            if (workerThread == null) {
                val thread = HandlerThread("BridgeSilent", Process.THREAD_PRIORITY_AUDIO)
                thread.start()
                workerThread = thread
                workerHandler = Handler(thread.looper)
            }
            if (track == null) track = buildTrack()
            val built = track ?: return
            built.setVolume(0f)
            if (built.playState != AudioTrack.PLAYSTATE_PLAYING) built.play()
            playing = true
            scheduleTick()
            Log.i(TAG, "无声播放开始（$reason）")
        } catch (e: Exception) {
            Log.e(TAG, "无声播放开始失败", e)
        }
    }

    fun stop(reason: String) {
        if (!playing) {
            cancelTick()
            return
        }
        playing = false
        cancelTick()
        try {
            track?.pause()
            track?.flush()
            Log.i(TAG, "无声播放暂停（$reason）")
        } catch (e: Exception) {
            Log.e(TAG, "无声播放暂停失败", e)
        }
    }

    fun release() {
        stop("释放")
        try { track?.release() } catch (e: Exception) { Log.e(TAG, "释放音轨失败", e) }
        track = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                workerThread?.quitSafely()
            } else {
                workerThread?.quit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "退出工作线程失败", e)
        }
        workerThread = null
        workerHandler = null
    }

    private fun buildTrack(): AudioTrack? {
        val bufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 4)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun scheduleTick() {
        cancelTick()
        val handler = workerHandler ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (!playing) return
                try {
                    val buffer = nextFrame()
                    track?.write(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "写入失败", e)
                }
                if (playing) workerHandler?.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelTick() {
        tickRunnable?.let { workerHandler?.removeCallbacks(it) }
        tickRunnable = null
    }

    /** 生成近静音帧：最低位 ±1 抖动，避免全 0 帧被判空流回收 */
    private fun nextFrame(): ByteArray {
        val buffer = ByteArray(FRAME_BYTES)
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((counter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }
}
