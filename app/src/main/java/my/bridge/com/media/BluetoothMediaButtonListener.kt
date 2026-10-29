package my.bridge.com.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.gson.JsonObject
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 蓝牙耳机媒体按键监听器（进程内单例）。
 *
 * 目标：其它 App **不出声**时，Bridge 能稳定收到蓝牙耳机（AVRCP）的
 * 「播放/暂停/上一首/下一首」按键；收到后 Toast 提示 + 经 WS 上报后台。
 *
 * 功耗约束（关键）：**零额外功耗**。不引入任何定时巡检 / 轮询 / 后台线程——
 * 只做两件一次性动作：
 *   1. 创建并激活一个 MediaSession（setFlags + setPlaybackState + isActive=true）；
 *   2. 申请一次持久音频焦点（AUDIOFOCUS_GAIN）。
 * 焦点丢失只在系统回调（onAudioFocusChange）里被动重抢，绝不 abandonFocus、
 * 绝不起定时任务。这样常驻开销 ≈ 0（MediaSession 注册 + 一次焦点申请，
 * 无循环、无 wakeup）。
 *
 * 副作用（用户已确认接受）：申请 GAIN 持久焦点后，其它 App 播放音乐时会被
 * Bridge 「抢停/压低」（GAIN 焦点的平台行为）。这是「其它 App 不出声时能收到
 * 按键」的必然代价，二者不可兼得。
 *
 * 参考：docs/keepalive/design.md 附录「蓝牙耳机按键监听」的四条件结论——
 * 缺一不可：isActive=true / setPlaybackState(非 NONE) / 持久 GAIN 焦点 /
 * LOSS 重抢不 abandon。
 */
@Singleton
class BluetoothMediaButtonListener @Inject constructor(
    private val webSocketClient: BridgeWebSocketClient,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // 复用同一个 Toast 实例：快速连按时 cancel 旧的再 setText+show，实现即时覆盖，
    // 避免每次 makeText().show() 新建 Toast 被系统串行排队导致「延迟数秒才显示」。
    private var toast: Toast? = null

    @Volatile
    private var started = false

    /**
     * 安装监听（应用启动时调用一次，幂等）。
     *
     * 需持有 Context 来创建 MediaSession 与获取 AudioManager。这里用
     * applicationContext（由 BridgeApplication 在 onCreate 传入），避免
     * 持有 Activity 造成泄漏。
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                installMediaSession(appContext)
                requestFocus(appContext)
                Timber.i("蓝牙媒体按键监听已启动")
            } catch (e: Exception) {
                Timber.e(e, "蓝牙媒体按键监听启动失败")
            }
        }
    }

    /** 释放 MediaSession 与焦点（进程退出时调用，幂等）。 */
    fun stop() {
        if (!started) return
        started = false
        mainHandler.post {
            try {
                mediaSession?.isActive = false
                mediaSession?.release()
                mediaSession = null
            } catch (e: Exception) {
                Timber.w(e, "释放 MediaSession 失败")
            }
            abandonFocus()
            Timber.i("蓝牙媒体按键监听已停止")
        }
    }

    private fun installMediaSession(context: Context) {
        val session = MediaSession(context, "BridgeMediaButton").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = onKey("播放")
                override fun onPause() = onKey("暂停")
                override fun onSkipToNext() = onKey("下一首")
                override fun onSkipToPrevious() = onKey("上一首")
                override fun onStop() = onKey("停止")
            })
            // 必须声明处理媒体按键，否则按键永不路由过来（条件 1/2）
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // 必须停在 STATE_PLAYING（非 STATE_NONE），系统媒体中心才认为「在播放」、
            // 才把媒体按键路由过来（position 传 0 而非 UNKNOWN）
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_STOP
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            isActive = true
        }
        mediaSession = session
    }

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        // 持久 GAIN 焦点（条件 3）：瞬时焦点会在不实际出声时被系统秒收回。
        // 用 USAGE_MEDIA + CONTENT_TYPE_MUSIC，与媒体按键语义一致。
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(::onFocusChange, mainHandler)
                .build()
            focusRequest = request
            val result = am.requestAudioFocus(request)
            Timber.i("申请音频焦点结果=$result")
        } else {
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                ::onFocusChange,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
            Timber.i("申请音频焦点结果=$result")
        }
    }

    /** 焦点回调（条件 4）：遇 LOSS 立即重抢、绝不 abandonFocus。 */
    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> Timber.d("音频焦点：获得")
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // 被其它 App 抢走焦点：立即重新申请（不 abandon，保持竞争）
                Timber.d("音频焦点：丢失($change)，立即重抢")
                mainHandler.post { reacquireFocus() }
            }
            else -> Timber.d("音频焦点：其他状态($change)")
        }
    }

    private fun reacquireFocus() {
        val am = audioManager ?: return
        val req = focusRequest
        try {
            if (req != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    ::onFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "重抢焦点失败")
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(::onFocusChange)
            }
        } catch (e: Exception) {
            Timber.w(e, "释放焦点失败")
        }
        audioManager = null
        focusRequest = null
    }

    private fun onKey(action: String) {
        // Toast 展示（主线程）
        try {
            // 用 applicationContext 无法直接 Toast，需借助 Handler 与 Looper。
            // 此处直接在主线程回调里 Toast 需要 Context，改为用全局 Application 上下文。
            toastAction(action)
        } catch (e: Exception) {
            Timber.w(e, "Toast 展示失败")
        }
        // WS 上报后台
        reportToServer(action)
    }

    private fun toastAction(action: String) {
        // 拿到一个可用 Context：优先用 mediaSession 所在进程的 applicationContext。
        val ctx = BridgeApplicationContextHolder.get()
        if (ctx == null) {
            Timber.w("无法获取应用上下文，跳过 Toast")
            return
        }
        mainHandler.post {
            try {
                val t = toast
                if (t == null) {
                    toast = Toast.makeText(ctx, "蓝牙按键：$action", Toast.LENGTH_SHORT)
                    toast?.show()
                } else {
                    // 复用实例：先取消当前展示，再更新文案并立即重新展示，实现即时覆盖
                    t.cancel()
                    t.setText("蓝牙按键：$action")
                    t.duration = Toast.LENGTH_SHORT
                    t.show()
                }
            } catch (e: Exception) {
                Timber.w(e, "Toast 失败")
            }
        }
    }

    private fun reportToServer(action: String) {
        val data = JsonObject().apply {
            addProperty("event", "media_button")
            addProperty("action", action)
        }
        val sent = webSocketClient.sendBusinessMessage(data)
        Timber.d("蓝牙按键上报：$action sent=$sent")
    }
}

/**
 * 应用上下文持有器：由 BridgeApplication 在 onCreate 注入，
 * 供本监听器（无 Activity 上下文）弹 Toast 使用。
 */
object BridgeApplicationContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}
