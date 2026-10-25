package my.bridge.com.media

import android.media.MediaPlayer
import timber.log.Timber

/**
 * 轻量音乐播放器（音乐类命令的执行核心，进程内单例）。
 *
 * 状态机（与 docs/api/commands/control.md 对齐）：
 *
 * ```
 * 空闲 --play--> 播放中 --pause--> 暂停中
 *         ^stop/resume 边界见下表^
 * ```
 *
 * 各命令在三种状态下的行为约束（由调用方 CommandModule 转成回执）：
 *
 * | 命令   | 空闲             | 播放中               | 暂停中               |
 * |--------|------------------|----------------------|----------------------|
 * | play   | 开始播放         | 切歌（顶掉当前）     | 新起播放（丢弃暂停） |
 * | pause  | 拒绝             | 暂停                 | 幂等成功             |
 * | resume | 拒绝             | 拒绝                 | 从进度恢复           |
 * | stop   | 拒绝             | 停止并释放           | 停止并释放           |
 *
 * 设计取舍：
 * - 只用系统 [MediaPlayer]，不引 ExoPlayer/Media3（依赖最小化）；
 * - 不与保活模块耦合：播放是业务行为，保活是进程行为；
 * - [play] 会阻塞至 prepare 完成（网络流可能有数秒缓冲），**必须在后台线程调用**
 *   （命令执行器运行在 Dispatchers.IO，满足要求）；
 * - 状态只存内存：进程被杀即回空闲，无需持久化。
 */
object MusicPlayer {

    /** 播放状态。 */
    enum class State { IDLE, PLAYING, PAUSED }

    /** 当前状态（空闲 = 无播放器实例）。 */
    @Volatile
    var state: State = State.IDLE
        private set

    /** 当前曲目描述（音源原样记录，用于回执展示；空闲时为 null）。 */
    @Volatile
    var currentSource: String? = null
        private set

    /** 当前状态的可读描述（用于命令回执，如「播放中：test.mp3」）。 */
    val stateDescription: String
        get() = when (state) {
            State.IDLE -> "空闲"
            State.PLAYING -> "播放中：$currentSource"
            State.PAUSED -> "已暂停：$currentSource"
        }

    /** 停止播放并释放资源，回到空闲态（幂等）。 */
    fun stop() {
        synchronized(this) {
            stopLocked()
            Timber.i("音乐已停止")
        }
    }

    /**
     * 暂停播放（保持进度）。
     *
     * @return 本次操作的实际效果：true = 从播放中转为暂停；false = 已处于暂停（幂等）
     * @throws IllegalStateException 空闲时调用（调用方应先检查 [state]）
     */
    fun pause(): Boolean {
        synchronized(this) {
            val mp = player ?: throw IllegalStateException("当前没有正在播放的音乐")
            if (state == State.PAUSED) return false
            try {
                mp.pause()
            } catch (e: IllegalStateException) {
                stopLocked()
                throw IllegalStateException("暂停失败，播放器已异常终止", e)
            }
            state = State.PAUSED
            Timber.i("音乐已暂停：$currentSource")
            return true
        }
    }

    /**
     * 从暂停进度恢复播放。
     *
     * @throws IllegalStateException 空闲（无可恢复）或正在播放中（无需恢复）时调用
     */
    fun resume() {
        synchronized(this) {
            val mp = player ?: throw IllegalStateException("当前没有可恢复的音乐")
            check(state == State.PAUSED) { "正在播放中，无需恢复" }
            try {
                mp.start()
            } catch (e: IllegalStateException) {
                stopLocked()
                throw IllegalStateException("恢复失败，播放器已异常终止", e)
            }
            state = State.PLAYING
            Timber.i("音乐恢复播放：$currentSource")
        }
    }

    /**
     * 播放音源：本地文件绝对路径或远程 URL（http/https，流式播放）。
     *
     * 播放中再调用 = 切歌（顶掉当前播放器）；暂停中调用 = 丢弃暂停会话新起播放。
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
                mp.setOnCompletionListener {
                    // 非循环曲目自然播完：释放播放器，状态回落空闲（MediaPlayer 回调线程）
                    Timber.i("音乐自然播完，释放播放器")
                    synchronized(this@MusicPlayer) { stopLocked() }
                }
                mp.start()
                state = State.PLAYING
                currentSource = source
                Timber.i("开始播放：$source（loop=$loop）")
            } catch (e: Exception) {
                stopLocked()
                throw e
            }
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
        state = State.IDLE
        currentSource = null
    }
}
