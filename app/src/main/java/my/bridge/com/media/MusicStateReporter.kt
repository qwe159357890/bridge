package my.bridge.com.media

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音乐播放状态上报器（进程内单例）。
 *
 * 职责：把 [MusicPlayer] 的状态机变化通过 WS 业务通道（TYPE_MESSAGE）上报给服务端，
 * 供管理后台实时展示与命令按钮联动（pause/resume/stop 按设备状态禁用）。
 *
 * 上报时机：
 * 1. 播放状态一变（播/暂停/恢复/停止/自然播完/出错）→ 立即上报；
 * 2. WS 重连成功 → 补报当前状态（服务端重启或断连后状态会丢，借此对齐）。
 *
 * 上报载荷：{"event":"music_state","state":"playing|paused|idle","source":"test.mp3"}
 * 未连接时静默丢弃（下次状态变化或重连会对齐），不做本地重试队列。
 */
@Singleton
class MusicStateReporter @Inject constructor(
    private val webSocketClient: BridgeWebSocketClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 安装监听并开始随连接状态补报（应用启动时调用一次）。 */
    fun start() {
        MusicPlayer.addStateListener { state, source -> report(state, source) }
        scope.launch {
            // 监听「握手完成」而非 CONNECTED：hello 发出前发消息会被服务端判 4002 断开。
            // 每次重连握手完成后补报当前状态（服务端重启或断连后状态会丢，借此对齐）。
            webSocketClient.handshakeReady.collect { ready ->
                if (ready) {
                    report(MusicPlayer.state, MusicPlayer.currentSource)
                }
            }
        }
        Timber.i("MusicStateReporter 已启动")
    }

    private fun report(state: MusicPlayer.State, source: String?) {
        val data = JsonObject().apply {
            addProperty("event", "music_state")
            addProperty("state", state.name.lowercase())
            source?.let { addProperty("source", it) }
        }
        val sent = webSocketClient.sendBusinessMessage(data)
        Timber.d("音乐状态上报：${state.name.lowercase()} source=$source sent=$sent")
    }
}
