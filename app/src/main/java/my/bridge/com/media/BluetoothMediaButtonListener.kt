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
 * 蓝牙耳机媒体按键监听器（进程内单例）——「正常补获」版。
 *
 * 目标：其它 App **没在播放**时，Bridge 能收到蓝牙耳机（AVRCP）的
 * 「播放/暂停/上一首/下一首」按键；收到后 Toast 提示 + 经 WS 上报后台。
 *
 * 行为契约（v0.2.01 起，取代旧的「永久抢占」策略）：
 * - **不抢占**：任何其它媒体开始播放 → 收到焦点丢失立即让位（abandonFocus），
 *   绝不重抢；对方的播放和按键完全不受 Bridge 干扰。
 * - **正常补获**：其它媒体停止后 → 自动恢复申请焦点，按键回到 Bridge。
 * - 恢复时机由系统事件驱动（AudioPlaybackCallback 的 onPlaybackConfigChanged），
 *   **零轮询、零定时器**，常驻开销 ≈ 0。
 *
 * 结构（两部分缺一不可）：
 * 1. MediaSession：isActive=true + STATE_PLAYING 注册为按键路由候选
 *    （按键永远路由给「持有焦点的 MediaSession」，且 PlaybackState 不得为
 *    STATE_NONE，见 docs/keepalive/design.md 附录）；
 * 2. 音频焦点：空闲期必须持有 GAIN 焦点；但持有策略从「丢了就抢」改为
 *    「丢了就让、媒体停了再补」。
 *
 * API 24/25 兜底：AudioPlaybackCallback 需 API 26，低版本仅在启动时检查一次
 * isMusicActive（设备为老机型时按键恢复需重启进程，可接受）。
 */
@Singleton
class BluetoothMediaButtonListener @Inject constructor(
    private val webSocketClient: BridgeWebSocketClient,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /** 当前是否持有焦点（由焦点回调维护，避免重复申请）。 */
    @Volatile
    private var holdsFocus = false

    // 复用同一个 Toast 实例：快速连按时仅 setText+show 覆盖文案，
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
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager = am
                installMediaSession(appContext)
                registerPlaybackObserver(am)
                syncFocus("启动")
                Timber.i("蓝牙媒体按键监听已启动（正常补获模式）")
            } catch (e: Exception) {
                Timber.e(e, "蓝牙媒体按键监听启动失败")
            }
        }
    }

    /** 释放 MediaSession、焦点与播放监听（进程退出时调用，幂等）。 */
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
            playbackCallback?.let { cb ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager?.unregisterAudioPlaybackCallback(cb)
                }
            }
            playbackCallback = null
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
            // 必须声明处理媒体按键，否则按键永不路由过来
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

    /**
     * 注册全局播放起停监听（事件驱动，零轮询）：
     * 任意 App 的播放配置变化（开播/停播）都会回调 onPlaybackConfigChanged，
     * 在回调里按 isMusicActive 现场判定「有没有媒体在出声」并补获/让位。
     */
    private fun registerPlaybackObserver(am: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // API 24/25：无 AudioPlaybackCallback，仅启动时同步一次焦点状态
            Timber.d("API<26，跳过播放监听注册（仅启动时补获一次）")
            return
        }
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                // 判定用 isMusicActive 而非 configs：部分播放器暂停后仍残留配置条目，
                // isMusicActive 反映「此刻是否真有声音输出」，语义更准。
                val musicActive = try {
                    am.isMusicActive
                } catch (e: Exception) {
                    Timber.w(e, "isMusicActive 查询失败")
                    false
                }
                mainHandler.post {
                    if (musicActive) {
                        yieldFocus("其它媒体开播")
                    } else {
                        syncFocus("其它媒体停止")
                    }
                }
            }
        }
        playbackCallback = callback
        am.registerAudioPlaybackCallback(callback, mainHandler)
    }

    /**
     * 按「此刻有没有媒体在出声」对齐焦点状态：
     * - 没人在出声且未持焦点 → 补获（申请一次 GAIN）；
     * - 有媒体在出声 → 让位（不应发生，让位逻辑在 yieldFocus）。
     */
    private fun syncFocus(reason: String) {
        val am = audioManager ?: return
        val musicActive = try {
            am.isMusicActive
        } catch (e: Exception) {
            false
        }
        if (musicActive) {
            yieldFocus(reason)
            return
        }
        if (!holdsFocus) {
            requestFocusOnce(am, reason)
        }
    }

    /** 让位：主动放弃焦点，把按键路由权完全交还给正在播放的 App，绝不重抢。 */
    private fun yieldFocus(reason: String) {
        if (holdsFocus) {
            abandonFocus()
            Timber.i("音频焦点：$reason，让位不重抢")
        }
    }

    /** 申请一次持久 GAIN 焦点（仅在空闲补获时调用；丢失后绝不在这里重抢）。 */
    private fun requestFocusOnce(am: AudioManager, reason: String) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest
                    ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(::onFocusChange, mainHandler)
                        .build()
                        .also { focusRequest = it }
                val result = am.requestAudioFocus(request)
                holdsFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Timber.i("音频焦点补获（$reason）：result=$result holdsFocus=$holdsFocus")
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    ::onFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
                holdsFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Timber.i("音频焦点补获（$reason）：result=$result holdsFocus=$holdsFocus")
            }
        } catch (e: Exception) {
            Timber.w(e, "音频焦点补获失败")
        }
    }

    /**
     * 焦点回调：任何形式的丢失都**只标记、只让位**，绝不重抢——
     * 这是与旧「永久抢占」版本的核心区别。真正的恢复时机由
     * AudioPlaybackCallback 在「其它媒体停止」时触发 syncFocus 补获。
     */
    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                holdsFocus = true
                Timber.d("音频焦点：获得")
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                holdsFocus = false
                mainHandler.post { abandonFocus() }
                Timber.d("音频焦点：丢失($change)，让位不重抢")
            }
            else -> Timber.d("音频焦点：其他状态($change)")
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
        // 保留 focusRequest 对象以便后续补获复用；仅标记未持有
        holdsFocus = false
    }

    private fun onKey(action: String) {
        // Toast 展示（主线程）
        try {
            toastAction(action)
        } catch (e: Exception) {
            Timber.w(e, "Toast 展示失败")
        }
        // WS 上报后台
        reportToServer(action)
    }

    private fun toastAction(action: String) {
        val ctx = BridgeApplicationContextHolder.get()
        if (ctx == null) {
            Timber.w("无法获取应用上下文，跳过 Toast")
            return
        }
        // 主线程串行展示，避免 MediaSession 回调线程与 UI 线程竞争 Toast。
        // 关键：高频连按时**绝不 cancel**——cancel 会打断系统 Toast 队列，
        // 导致「频率一快就什么都不显示」。正确做法是复用同一个 Toast 实例，
        // 每次仅 setText 覆盖文案后重新 show，由系统自行决定展示时长与排队，
        // 这样无论按键多快，最新一次按键的文案一定会稳定出现在屏幕上。
        mainHandler.post {
            try {
                val t = toast ?: Toast.makeText(ctx, "", Toast.LENGTH_SHORT).also {
                    toast = it
                }
                t.setText("蓝牙按键：$action")
                t.duration = Toast.LENGTH_SHORT
                t.show()
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
