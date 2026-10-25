package my.bridge.com.media

import android.media.MediaPlayer
import timber.log.Timber

/**
 * 轻量音乐播放器（play_music 命令落地，进程内单例）。
 *
 * 设计取舍：
 * - 只用系统 [MediaPlayer]，不引 ExoPlayer/Media3（依赖最小化，需求只是「播起来」）；
 * - 不与保活模块耦合：播放是业务行为，保活是进程行为，职责分离；
 * - [play] 会阻塞至 prepare 完成（网络流可能有数秒缓冲），**必须在后台线程调用**
 *   （命令执行器运行在 Dispatchers.IO，满足要求）；
 * - 播放器持有权：每次 play 顶掉上一次（stop + release），同命令重复下发即「切歌」。
 */
object MusicPlayer {

    /** 当前播放器实例（null = 空闲）。 */
    private var player: MediaPlayer? = null

    /** 停止当前播放并释放资源（幂等；供 pause_music/切歌使用）。 */
    fun stop() {
        synchronized(this) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        player?.run {
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {
                // 已处于错误/结束态，忽略
            }
            try {
                release()
            } catch (_: Exception) {
                // release 本身不抛，防御性兜底
            }
        }
        player = null
    }

    /**
     * 播放音源：本地文件绝对路径或远程 URL（http/https，流式播放）。
     *
     * @param source 本地路径或远程 URL（MediaPlayer.setDataSource(String) 两者兼容）
     * @param loop 是否循环播放
     * @throws Exception prepare 失败（文件损坏/网络不可达等）时抛出，由命令执行器转失败回执
     */
    fun play(source: String, loop: Boolean) {
        synchronized(this) {
            stopLocked()
            val mp = MediaPlayer()
            player = mp
            mp.setOnErrorListener { _, what, extra ->
                Timber.e("MediaPlayer 错误：what=$what extra=$extra")
                true
            }
            try {
                mp.isLooping = loop
                mp.setDataSource(source)
                mp.prepare() // 阻塞至可播（网络流含缓冲时间）
                mp.start()
                Timber.i("开始播放：$source（loop=$loop）")
            } catch (e: Exception) {
                stopLocked()
                throw e
            }
        }
    }
}
