package my.bridge.com.wechat

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import my.bridge.com.R
import my.bridge.com.core.common.DiagLog

/**
 * 微信新消息提示音。
 *
 * v0.2.23 重写：原实现播 `Settings.System.DEFAULT_NOTIFICATION_URI`（系统默认通知铃声），
 * 很多 ROM 上该铃声被设为「无」⇒ **完全听不到**（真机问题 #9）。
 * 改为照参考 PC 客户端 `pc-client/gui/utils/sound_manager.py` 的做法——**自带音源**：
 * - 收到消息：`gui/resources/sounds/MSN消息.wav` → `res/raw/wechat_msg.wav`
 * - 跳转未读会话：`gui/resources/sounds/叮一声.wav` → `res/raw/wechat_jump.wav`
 *
 * 播放通道沿用扫码页 `ScanFeedback` 的验证结论：**USAGE_MEDIA**（媒体通道音量一般最高，
 * 通知/触感通道常被系统压低且受勿扰模式影响）。
 *
 * 单一发声源：通知渠道已改为静音（见 [WechatNotifier]），避免「通知声 + 自播声」双响。
 */
object WechatSound {

    private const val TAG = "微信提示音"

    private var pool: SoundPool? = null
    private var msgId = 0
    private var jumpId = 0

    /**
     * 预热（Application 启动时调一次即可）。SoundPool 解码是异步的，
     * 提前 load 避免第一条消息「无声」。
     */
    @Synchronized
    fun init(context: Context) {
        if (pool != null) return
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build()
            msgId = p.load(context, R.raw.wechat_msg, 1)
            jumpId = p.load(context, R.raw.wechat_jump, 1)
            pool = p
            DiagLog.i(TAG, "提示音已预热 msgId=$msgId jumpId=$jumpId")
        }.onFailure { DiagLog.w(TAG, "提示音初始化失败：${it.message}") }
    }

    /** 新消息提示音（MSN消息.wav）。未预热时先预热再播。 */
    fun play(context: Context) {
        if (pool == null) init(context.applicationContext)
        play(msgId)
    }

    /** 跳转未读会话的「叮一声」（与 PC 端 jump_unread 同款交互）。 */
    fun playJump(context: Context) {
        if (pool == null) init(context.applicationContext)
        play(jumpId)
    }

    private fun play(id: Int) {
        val p = pool ?: return
        if (id == 0) return
        runCatching { p.play(id, 1f, 1f, 1, 0, 1f) }
            .onFailure { DiagLog.w(TAG, "播放提示音失败：${it.message}") }
    }
}
