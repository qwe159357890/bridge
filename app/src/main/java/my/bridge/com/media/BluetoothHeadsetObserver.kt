package my.bridge.com.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import my.bridge.com.interact.ToastHelper
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
 * **关键：必须请求音频焦点**。Android 的媒体按键（AVRCP）只会路由给「当前持有
 * 音频焦点的 MediaSession」。只 setActive(true) 而不 requestAudioFocus 时，媒体
 * 焦点在别的 App（音乐/视频播放器）手里，Bridge 永远收不到按键——这是早期
 * 版本「收不到任何通知」的根因。
 *
 * 本实现用 [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT]（瞬时焦点）：请求成功后
 * 立即成为媒体按键接收者，收到一次按键后马上 abandonAudioFocus 把焦点还回去，
 * 对其它播放器的干扰降到最低（短暂抢占，不长期霸占）。
 *
 * 局限（如实说明）：
 * 1. 仅能收到「标准媒体键」：播放/暂停/播放暂停切换/上一首/下一首/快进/快退/
 *    停止/录音等。耳机的「触摸滑动（音量触控条）」「佩戴检测」「单击/双击手势」
 *    等是耳机私有协议，Android 不开放，收不到。
 * 2. 若系统/其它 App 拒绝让出焦点，瞬时请求可能失败，此时收不到按键（罕见）。
 */
@Singleton
class BluetoothHeadsetObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

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
                        // 只处理按下（ACTION_DOWN），避免重复 Toast
                        if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        val label = labelFor(keyEvent.keyCode)
                        if (label != null) {
                            showToast("检测到蓝牙耳机按键：$label")
                        } else {
                            Timber.d("BluetoothHeadsetObserver：忽略非媒体键 keyCode=%d", keyEvent.keyCode)
                        }
                        // 收到按键后立即放弃焦点，把媒体焦点还给其它播放器
                        abandonFocus()
                        // 返回 true 表示已消费按键，避免继续分发给其它媒体应用
                        return label != null || super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
                isActive = true
            }
            mediaSession = session
            Timber.i(
                "BluetoothHeadsetObserver：MediaSession 已注册并激活（isActive=%s）",
                session.isActive,
            )
            // 请求瞬时音频焦点：这是收到媒体按键的前提
            requestTransientFocus()
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 注册失败")
            mediaSession = null
        }
    }

    /** 请求瞬时音频焦点（成为媒体按键接收者）。 */
    private fun requestTransientFocus() {
        val am = audioManager ?: return
        try {
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { change ->
                        Timber.i("BluetoothHeadsetObserver：音频焦点变化 %d", change)
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            hasFocus = false
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                            hasFocus = true
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
                        hasFocus = change == AudioManager.AUDIOFOCUS_GAIN
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
            hasFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Timber.i(
                "BluetoothHeadsetObserver：请求瞬时焦点结果=%d（granted=%s）",
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
