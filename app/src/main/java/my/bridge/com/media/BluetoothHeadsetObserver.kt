package my.bridge.com.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.interact.ToastHelper
import my.bridge.com.BuildConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 蓝牙耳机按键（播放/暂停/上一首/下一首等）监听器。
 *
 * 原理（重要）：蓝牙耳机的「播放/暂停」按键不是普通蓝牙广播，而是通过
 * AVRCP → 系统 → 媒体按键 [KeyEvent]（KEYCODE_MEDIA_*）。这些按键只会分发给
 * 「媒体会话」（MediaSession）的持有者。因此本类注册一个系统原生 [MediaSession]
 * （API 21+，minSdk 24 满足，零第三方依赖）并在
 * [MediaSession.Callback.onMediaButtonEvent] 里解析按键。
 *
 * **关键 1：必须持续持有音频焦点（AUDIOFOCUS_GAIN 持久焦点）**。Android 的媒体
 * 按键（AVRCP）只会路由给「当前持有音频焦点的 MediaSession」。早期用
 * ``AUDIOFOCUS_GAIN_TRANSIENT``（瞬时焦点）时，Bridge 请求后没有实际播放音频，
 * 系统在授予后几百毫秒内就把焦点静默收回，导致「按耳机键那一刻焦点已不在 Bridge
 * 手里、onMediaButtonEvent 永不触发」——这是前几版「收不到任何按键」的真正根因。
 * 改用 ``AUDIOFOCUS_GAIN``（持久焦点）后，Bridge 持续持有焦点，媒体按键稳定路由
 * 到本会话。代价：会抢占/打断其它 App（音乐/视频）的播放，这是 Android 机制使然，
 * 无法两全（用户已确认接受此取舍）。
 *
 * **关键 2：必须设置 PlaybackState**。系统只在 MediaSession 的 [PlaybackState]
 * 声明了对应 ACTION（如 ACTION_PLAY_PAUSE）时才把该媒体按键路由给它。本类设置
 * 一个静态 PlaybackState（含 PLAY/PAUSE/PLAY_PAUSE/NEXT/PREV/STOP 等 action）。
 *
 * **关键 3（本版核心，实现「无论何时都正常」）：激进焦点保持 + 周期自愈巡检**。
 * 用户要求「无论当前运行什么 App，Bridge 都必须能拿到耳机按键，不能依赖结束
 * Aurelay」。因此本类不再「被动等待焦点回调」，而是：
 *   1. 焦点变化回调里，`AUDIOFOCUS_LOSS` / `LOSS_TRANSIENT` / `CAN_DUCK` 一律
 *      视为「失去焦点」，立即（或极短延迟）重新请求持久焦点，绝不主动 abandon；
 *   2. 后台一个周期巡检协程（默认 2 秒）持续核对 `hasFocus` 状态，一旦发现焦点
 *      被其它 App 静默抢走（例如 Aurelay 播放时请求焦点、Bridge 未收到明确回调、
 *      或应用切前台/切后台导致焦点被系统回收），立即重新抢占。
 *  这样 Bridge 会像一台「永远在播放」的媒体设备一样，持续跟其它 App 争夺焦点，
 *  从而尽量保证任何时候按耳机键都能路由到 Bridge。代价：会频繁打断 Aurelay 等
 *  其它播放器，且焦点可能反复易主（这是 Android 焦点机制的固有限制，无法做到
 *  绝对垄断，只能「尽力而为 + 快速抢回」）。
 *
 * **关键 4（v0.1.68，解决「Aurelay 出声时抢不过」）：静音播放锚点**。
 * 实测发现：Aurelay 只在「正在出声播放」时 Bridge 抢不过——因为 Android 的音频
 * 焦点竞争偏向「正在实际出声」的 App，Bridge 只申请焦点却不出声，永远处于下风。
 * 因此本类额外起一个独立的 [AudioTrack]（`setVolume(0f)` 静音）循环播放近静音
 * PCM，让系统认为 Bridge 也是「正在播放」的媒体 App，从而在焦点竞争中与 Aurelay
 * 平起平坐、甚至压过它。这是把「抢焦点」升级为「真·抢占」的关键一步。
 * 静音锚点：只 `setVolume(0f)` 不改系统音量；跟随焦点（拿到焦点才播、丢焦点暂缓），
 * 复用 [HandlerThread] + 每 20ms 写一帧，避免全 0 帧被判空流回收（最低位 ±1 抖动）。
 *
 * 收到按键后：① Toast 展示动作；② 经 WS 业务通道上报 ``media_button`` 事件给
 * 服务端（供服务端日志记录 + 管理后台实时展示，便于远程排查「收没收到按键」）。
 *
 * 局限（如实说明）：
 * 1. 仅能收到「标准媒体键」：播放/暂停/播放暂停切换/上一首/下一首/快进/快退/
 *    停止/录音等。耳机的「触摸滑动（音量触控条）」「佩戴检测」「单击/双击手势」
 *    等是耳机私有协议，Android 不开放，收不到。
 * 2. 持有持久焦点会打断其它 App 播放；Android 的音频焦点是「一对一」的，系统在
 *    任意时刻只会把媒体按键路由给一个焦点持有者。本类通过「快速重抢」尽量保证
 *    Bridge 始终是那个持有者，但无法做到 100% 垄断（其它 App 请求焦点时焦点会
 *    短暂易主，直到本类下一轮巡检/回调把它抢回）。
 */
@Singleton
class BluetoothHeadsetObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: BridgeWebSocketClient,
) {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var hasFocus = false

    /** 是否正在请求焦点（防止重入/重复并发请求导致焦点抖动）。 */
    @Volatile
    private var focusRequestInFlight = false

    /** 巡检间隔（毫秒）。 */
    private val watchdogIntervalMs = 2000L

    // —— 静音播放锚点（关键 4）：让系统认为 Bridge「正在出声」，才能在焦点竞争中压过 Aurelay ——
    private val SAMPLE_RATE = 48000
    private val FRAME_SAMPLES = 960
    private val FRAME_BYTES = FRAME_SAMPLES * 2
    private val TICK_MS = 20L
    private var anchorTrack: AudioTrack? = null
    private var anchorThread: HandlerThread? = null
    private var anchorHandler: Handler? = null
    private var anchorTick: Runnable? = null
    @Volatile
    private var anchorPlaying = false
    private var anchorCounter = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 注册 MediaSession 并请求瞬时音频焦点（应用启动时调用一次）。 */
    fun start() {
        if (mediaSession != null) {
            Timber.i("BluetoothHeadsetObserver.start：已注册，跳过")
            return
        }
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val session = MediaSession(context, "BridgeMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val intent = mediaButtonIntent
                        Timber.d("BluetoothHeadsetObserver.onMediaButtonEvent：action=%s", intent.action)
                        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        val keyEvent =
                            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (keyEvent == null) {
                            Timber.w("BluetoothHeadsetObserver：媒体按键事件缺少 EXTRA_KEY_EVENT")
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        Timber.i(
                            "BluetoothHeadsetObserver：收到媒体按键 keyCode=%d(%s) action=%d",
                            keyEvent.keyCode,
                            KeyEvent.keyCodeToString(keyEvent.keyCode),
                            keyEvent.action,
                        )
                        // 只处理按下（ACTION_DOWN），避免重复 Toast/上报
                        if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        val label = labelFor(keyEvent.keyCode)
                        if (label != null) {
                            showToast("检测到蓝牙耳机按键：$label")
                            reportToServer(keyEvent.keyCode, label)
                        } else {
                            Timber.d("BluetoothHeadsetObserver：忽略非媒体键 keyCode=%d", keyEvent.keyCode)
                        }
                        // 返回 true 表示已消费按键，避免继续分发给其它媒体应用
                        return label != null || super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
                // 关键 2：声明可处理的媒体 action，系统才把对应按键路由到本会话。
                // 关键 3（本轮根因）：必须 setState() 到非 STATE_NONE 状态，否则系统
                // 媒体控制中心认为「该 App 没在播放」，媒体按键根本不路由过来。
                // 之前只 setActions 不 setState，PlaybackState 停在 STATE_NONE，是
                // 「拿到焦点但 onMediaButtonEvent 仍零触发」的真正原因。这里设为
                // STATE_PLAYING 并带有效 updateTime（不能为 0，否则系统仍忽略）。
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY
                                or PlaybackState.ACTION_PAUSE
                                or PlaybackState.ACTION_PLAY_PAUSE
                                or PlaybackState.ACTION_SKIP_TO_NEXT
                                or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                or PlaybackState.ACTION_FAST_FORWARD
                                or PlaybackState.ACTION_REWIND
                                or PlaybackState.ACTION_STOP
                        )
                        .setState(
                            PlaybackState.STATE_PLAYING,
                            0L,
                            1f,
                        )
                        .build()
                )
                isActive = true
            }
            mediaSession = session
            Timber.i(
                "BluetoothHeadsetObserver：MediaSession 已注册并激活（isActive=%s）",
                session.isActive,
            )
            // 请求持久音频焦点：这是稳定收到媒体按键的前提
            requestPersistentFocus()
            // 启动焦点自愈巡检：周期核对 hasFocus，被抢走就立即重抢，保证「无论何时」都尽量持有
            startFocusWatchdog()
            // 监听 WS 握手完成：握手后再补报 started 状态（onCreate 里直接上报会因
            // handshakeReady=false 被 sendBusinessMessage 静默丢弃，导致服务端看不到）
            scope.launch {
                webSocketClient.handshakeReady.collect { ready ->
                    if (ready) {
                        reportToServer(-1, "started")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 注册失败")
            mediaSession = null
        }
    }

    /** 上报媒体按键事件到服务端（供远程排查 + 后台展示）。 */
    private fun reportToServer(keyCode: Int, label: String) {
        try {
            val data = JsonObject().apply {
                addProperty("event", "media_button")
                addProperty("key_code", keyCode)
                addProperty("action", label)
                addProperty("has_focus", hasFocus)
                addProperty("session_active", mediaSession?.isActive == true)
                addProperty("app_version", BuildConfig.VERSION_NAME)
            }
            val sent = webSocketClient.sendBusinessMessage(data)
            Timber.i("蓝牙按键上报：keyCode=%d label=%s sent=%s", keyCode, label, sent)
        } catch (e: Exception) {
            Timber.w(e, "蓝牙按键上报失败")
        }
    }

    /** 请求持久音频焦点（幂等，可被巡检/回调反复调用；成为媒体按键接收者，持续持有）。 */
    private fun requestPersistentFocus() {
        val am = audioManager ?: return
        if (focusRequestInFlight) {
            Timber.d("BluetoothHeadsetObserver：焦点请求进行中，跳过本次")
            return
        }
        focusRequestInFlight = true
        try {
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 复用同一个 focusRequest，避免每次 new 覆盖旧 listener 导致回调失效
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    // 接受延迟焦点：当 Aurelay 等 App 正在播放时，系统会返回 DELAYED，
                    // 只有 setAcceptsDelayedFocusGain(true) 才能在对方停止播放后自动获得焦点
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { change ->
                        Timber.i("BluetoothHeadsetObserver：音频焦点变化 %d", change)
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> setFocusState(true)
                            // 任何形式的失去焦点都视为「被抢走」，立即重新抢占，绝不主动 abandon
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                setFocusState(false)
                                scheduleRefocus()
                            }
                        }
                    }
                    .build()
                focusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { change ->
                        Timber.i("BluetoothHeadsetObserver：音频焦点变化 %d", change)
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> setFocusState(true)
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                setFocusState(false)
                                scheduleRefocus()
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
            setFocusState(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Timber.i(
                "BluetoothHeadsetObserver：请求持久焦点结果=%d（granted=%s）",
                focusResult, hasFocus,
            )
            // 延迟授予：说明有其它 App 正在播放（如 Aurelay），系统会在其停止后回调
            // AUDIOFOCUS_GAIN。此时 hasFocus 仍为 false，交给巡检周期继续尝试即可。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                focusResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            ) {
                Timber.i("BluetoothHeadsetObserver：焦点延迟授予，等待其它播放器释放")
            }
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 请求音频焦点失败")
            setFocusState(false)
        } finally {
            focusRequestInFlight = false
        }
    }

    /**
     * 统一收敛焦点状态切换：更新 [hasFocus] 并联动静音锚点启停。
     * 拿到焦点 → 启动静音播放（让系统认为 Bridge「正在出声」，压过 Aurelay）；
     * 失去焦点 → 暂停静音播放（避免在无焦点状态下徒占音频输出）。
     */
    private fun setFocusState(focused: Boolean) {
        if (hasFocus == focused) return
        hasFocus = focused
        if (focused) {
            startSilentAnchor()
        } else {
            stopSilentAnchor()
        }
    }

    /** 失去焦点后短延迟重新抢占（延迟是为了避开其它 App 正在播放导致的瞬时竞争）。 */
    private fun scheduleRefocus() {
        if (mediaSession == null) return
        scope.launch {
            kotlinx.coroutines.delay(300)
            requestPersistentFocus()
        }
    }

    /**
     * 焦点自愈巡检：周期核对 `hasFocus` 与静音锚点状态，一旦发现焦点丢失（例如被
     * Aurelay 等其它 App 静默抢走、应用切前后台导致系统回收、或请求返回 DELAYED/
     * 失败）立即重抢；焦点还在但锚点异常停止（AudioTrack 被系统回收）则重启锚点。
     * 这是「无论什么时候都正常」的最后一道兜底。
     */
    private fun startFocusWatchdog() {
        scope.launch {
            while (mediaSession != null) {
                try {
                    if (!hasFocus) {
                        Timber.d("BluetoothHeadsetObserver：巡检发现焦点丢失，重新抢占")
                        requestPersistentFocus()
                    } else if (!anchorPlaying) {
                        Timber.d("BluetoothHeadsetObserver：巡检发现静音锚点停止，重启")
                        startSilentAnchor()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "BluetoothHeadsetObserver 巡检异常")
                }
                kotlinx.coroutines.delay(watchdogIntervalMs)
            }
        }
    }

    /** 放弃音频焦点（把媒体按键接收权还给其它播放器）。 */
    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            setFocusState(false)
            Timber.i("BluetoothHeadsetObserver：已放弃音频焦点")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 放弃焦点失败")
        }
    }

    /** 释放会话（进程结束时由系统回收，通常无需手动调用）。 */
    fun stop() {
        try {
            abandonFocus()
            stopSilentAnchor()
            mediaSession?.release()
            Timber.i("BluetoothHeadsetObserver.stop：MediaSession 已释放")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 释放失败")
        }
        mediaSession = null
        audioManager = null
        focusRequest = null
    }

    // —— 静音播放锚点：抢到焦点后真正占用音频输出，让系统认为 Bridge「正在播放」——

    /** 启动静音锚点（幂等）。近静音 PCM + setVolume(0f)，不产生可闻声音。 */
    private fun startSilentAnchor() {
        if (anchorPlaying) return
        try {
            if (anchorThread == null) {
                val thread = HandlerThread("BridgeMediaAnchor", Process.THREAD_PRIORITY_AUDIO)
                thread.start()
                anchorThread = thread
                anchorHandler = Handler(thread.looper)
            }
            if (anchorTrack == null) anchorTrack = buildAnchorTrack()
            val track = anchorTrack ?: return
            track.setVolume(0f)
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            anchorPlaying = true
            scheduleAnchorTick()
            Timber.i("BluetoothHeadsetObserver：静音锚点已启动（抢占音频输出）")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 静音锚点启动失败")
        }
    }

    /** 暂停静音锚点（幂等）。 */
    private fun stopSilentAnchor() {
        if (!anchorPlaying) {
            cancelAnchorTick()
            return
        }
        anchorPlaying = false
        cancelAnchorTick()
        try {
            anchorTrack?.pause()
            anchorTrack?.flush()
            Timber.i("BluetoothHeadsetObserver：静音锚点已暂停")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 静音锚点暂停失败")
        }
    }

    private fun buildAnchorTrack(): AudioTrack? {
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

    private fun scheduleAnchorTick() {
        cancelAnchorTick()
        val handler = anchorHandler ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (!anchorPlaying) return
                try {
                    val buffer = nextAnchorFrame()
                    anchorTrack?.write(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Timber.w(e, "BluetoothHeadsetObserver 静音锚点写入失败")
                }
                if (anchorPlaying) anchorHandler?.postDelayed(this, TICK_MS)
            }
        }
        anchorTick = runnable
        handler.post(runnable)
    }

    private fun cancelAnchorTick() {
        anchorTick?.let { anchorHandler?.removeCallbacks(it) }
        anchorTick = null
    }

    /** 生成近静音帧：最低位 ±1 抖动，避免全 0 帧被判空流回收。 */
    private fun nextAnchorFrame(): ByteArray {
        val buffer = ByteArray(FRAME_BYTES)
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((anchorCounter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }

    /** 媒体键码 → 中文动作描述；非媒体键返回 null。 */
    private fun labelFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> "播放"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "暂停"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放/暂停"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "下一首"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一首"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "快进"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "快退"
        KeyEvent.KEYCODE_MEDIA_STOP -> "停止"
        KeyEvent.KEYCODE_MEDIA_RECORD -> "录音"
        else -> null
    }

    private fun showToast(text: String) {
        try {
            ToastHelper.show(context, text)
            Timber.i("BluetoothHeadsetObserver：已 Toast 展示「%s」", text)
        } catch (e: Exception) {
            Timber.w(e, "Toast 展示失败")
        }
    }
}
