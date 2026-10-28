package my.bridge.com.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import my.bridge.com.interact.ToastHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 蓝牙耳机按键（播放/暂停/上一首/下一首等）监听器 —— 最小验证版。
 *
 * 原理（重要）：蓝牙耳机的「播放/暂停」按键不是普通蓝牙广播，而是通过
 * AVRCP → 系统 → 媒体按键 [KeyEvent]（KEYCODE_MEDIA_*）。这些按键只会分发给
 * 「媒体会话」（MediaSession）的持有者。因此本类注册一个系统原生 [MediaSession]
 * （API 21+，minSdk 24 满足，零第三方依赖）并在
 * [MediaSession.Callback.onMediaButtonEvent] 里解析按键。
 *
 * 局限（如实说明）：
 * 1. 只有当 Bridge 持有媒体焦点（audio focus）时才能稳定收到按键——最可靠的
 *    场景是 Bridge 自己在播放音乐（[MusicPlayer]），此时按键天然路由到本会话。
 *    若别的 App（音乐/视频）正在播放，焦点在它手里，Bridge 收不到。
 * 2. 仅能收到「标准媒体键」：播放/暂停/播放暂停切换/上一首/下一首/快进/快退/
 *    停止/媒体重播等。耳机的「触摸滑动（音量触控条）」「佩戴检测」「单击/双击
 *    手势」等是耳机私有协议，Android 不开放，收不到。
 *
 * 本版本定位「最小验证」：仅注册会话 + 收到媒体键时 Toast 展示动作，不主动
 * 争抢音频焦点、不干扰其它 App 正常播放。
 */
@Singleton
class BluetoothHeadsetObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var mediaSession: MediaSession? = null

    /** 注册 MediaSession 并开始监听媒体按键（应用启动时调用一次）。 */
    fun start() {
        if (mediaSession != null) return
        try {
            val session = MediaSession(context, "BridgeMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val intent = mediaButtonIntent
                        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        val keyEvent =
                            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                                ?: return super.onMediaButtonEvent(mediaButtonIntent)
                        // 只处理按下（ACTION_DOWN），避免重复 Toast
                        if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        val label = labelFor(keyEvent.keyCode)
                        if (label != null) {
                            showToast("检测到蓝牙耳机按键：$label")
                        }
                        // 返回 true 表示已消费按键，避免继续分发给其它媒体应用
                        return label != null || super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                // 标记本会话可处理媒体按键（FLAG_HANDLES_MEDIA_BUTTONS 等价能力）；
                // 不 setActive 抢焦点——最小验证，不干扰其它 App 正常播放
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            }
            mediaSession = session
            Timber.i("BluetoothHeadsetObserver：MediaSession 已注册（媒体按键监听开启）")
        } catch (e: Exception) {
            Timber.w(e, "BluetoothHeadsetObserver 注册失败")
            mediaSession = null
        }
    }

    /** 释放会话（进程结束时由系统回收，通常无需手动调用）。 */
    fun stop() {
        try {
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null
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
        } catch (e: Exception) {
            Timber.w(e, "Toast 展示失败")
        }
    }
}
