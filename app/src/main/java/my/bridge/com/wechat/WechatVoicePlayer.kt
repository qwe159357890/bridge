package my.bridge.com.wechat

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import java.io.File

/**
 * 语音消息播放（msgType 34）。
 *
 * 参考前端是 `new Audio(url)` 直接播（`ChatPanel.vue` 第 890 行 playVoice）；Android 端
 * MediaPlayer 对网络流不稳定，故**先下载到缓存目录再本地播放**——更稳，也便于重复播放。
 *
 * ⚠️ 与既有音频能力的边界：本项目有蓝牙媒体按键「正常补获」逻辑（v0.2.01），
 * MediaPlayer 播放会占用音频焦点，播放结束后应尽快 release，避免与焦点补获打架。
 */
object WechatVoicePlayer {

    private const val TAG = "微信语音"

    /** 当前正在播放的消息 id（UI 用来显示波形动画）。 */
    var playingMsgId: String? by mutableStateOf(null)
        private set

    @Volatile
    private var player: MediaPlayer? = null

    /**
     * 点击语音气泡：同一条再点一次 = 停止，其它条 = 切播。
     *
     * @param url 语音附件 URL（含 token）
     */
    suspend fun toggle(context: Context, msgId: String, url: String) {
        if (url.isBlank()) return
        if (playingMsgId == msgId) {
            stop()
            return
        }
        stop()
        val file = File(WechatDownloader.cacheDir(context), "voice_${msgId.hashCode()}.mp3")
        if (!file.exists() || file.length() == 0L) {
            val bytes = withContext(Dispatchers.IO) { WechatImageLoader.bytes(url) }
            if (bytes == null) {
                DiagLog.w(TAG, "语音下载失败")
                return
            }
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        }
        withContext(Dispatchers.Main) {
            runCatching {
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { stop() }
                    setOnErrorListener { _, _, _ -> stop(); true }
                    prepare()
                    start()
                }
                playingMsgId = msgId
            }.onFailure {
                DiagLog.w(TAG, "语音播放失败：${it.message}")
                stop()
            }
        }
    }

    fun stop() {
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        player = null
        playingMsgId = null
    }
}
