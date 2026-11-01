package my.bridge.com.wechat

import android.content.Context
import android.media.RingtoneManager
import android.provider.Settings
import my.bridge.com.core.common.DiagLog

/**
 * 微信新消息提示音。
 *
 * 设计取舍：参考项目有「提示音开关 + 音量 + 蜂鸣保活」，Bridge 只保留**开关**，
 * 音量跟随系统通知音量（不自造音量条，也不做无声蜂鸣保活——保活由 core-keepalive 负责）。
 *
 * 后台场景的实际发声主要走通知渠道（[WechatNotifier] 的 CHANNEL_SOUND）；
 * 这里用于 App 在前台时的轻提示，避免通知栏噪音。
 */
object WechatSound {

    private const val TAG = "微信提示音"

    fun play(context: Context) {
        runCatching {
            val uri = Settings.System.DEFAULT_NOTIFICATION_URI
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone?.play()
        }.onFailure {
            DiagLog.w(TAG, "播放提示音失败：${it.message}")
        }
    }
}
