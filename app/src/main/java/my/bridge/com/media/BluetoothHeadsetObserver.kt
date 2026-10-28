package my.bridge.com.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
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
 * 收到按键后：① Toast 展示动作；② 经 WS 业务通道上报 ``media_button`` 事件给
 * 服务端（供服务端日志记录 + 管理后台实时展示，便于远程排查「收没收到按键」）。
 *
 * 局限（如实说明）：
 * 1. 仅能收到「标准媒体键」：播放/暂停/播放暂停切换/上一首/下一首/快进/快退/
 *    停止/录音等。耳机的「触摸滑动（音量触控条）」「佩戴检测」「单击/双击手势」
 *    等是耳机私有协议，Android 不开放，收不到。
 * 2. 持有持久焦点会打断其它 App 播放；其它 App 请求焦点时 Bridge 会收到
 *    AUDIOFOCUS_LOSS，本类会自动重新请求以尽量保持接收能力。
 */
@Singleton
class BluetoothHeadsetObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: BridgeWebSocketClient,
) {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

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
                // 关键 2：声明可处理的媒体 action，系统才把对应按键路由到本会话
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

    /** 请求持久音频焦点（成为媒体按键接收者，持续持有）。 */
    private fun requestPersistentFocus() {
        val am = audioManager ?: return
        try {
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { change ->
                        Timber.i("BluetoothHeadsetObserver：音频焦点变化 %d", change)
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                // 永久失去焦点（如被电话/系统占用）：放弃，等下次 start 或用户操作再请求
                                hasFocus = false
                                abandonFocus()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                // 暂时失去焦点（其它 App 播放）：标记失去，稍后尝试重新抢占
                                hasFocus = false
                                // 短暂延迟后重新请求，尽量保持接收能力
                                scope.launch {
                                    kotlinx.coroutines.delay(1000)
                                    if (mediaSession != null) requestPersistentFocus()
                                }
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
                            AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                hasFocus = false
                                abandonFocus()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                hasFocus = false
                                scope.launch {
                                    kotlinx.coroutines.delay(1000)
                                    if (mediaSession != null) requestPersistentFocus()
                                }
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
            hasFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Timber.i(
                "BluetoothHeadsetObserver：请求持久焦点结果=%d（granted=%s）",
                focusResult, hasFocus,
            )
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 请求音频焦点失败")
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
            hasFocus = false
            Timber.i("BluetoothHeadsetObserver：已放弃音频焦点")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 放弃焦点失败")
        }
    }

    /** 释放会话（进程结束时由系统回收，通常无需手动调用）。 */
    fun stop() {
        try {
            abandonFocus()
            mediaSession?.release()
            Timber.i("BluetoothHeadsetObserver.stop：MediaSession 已释放")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 释放失败")
        }
        mediaSession = null
        audioManager = null
        focusRequest = null
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
