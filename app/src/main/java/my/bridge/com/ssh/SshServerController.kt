package my.bridge.com.ssh

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.bridge.com.core.common.DiagLog

/**
 * SSH 服务状态机（v0.2.34）——对照 VntMeshController 的启停编排范式。
 *
 * 生命周期（调研报告 §6.3 已拍板）：**服务常驻 + 显式开关 + 完整自愈链**。
 * `enabled`（SshPrefs 意愿，持久化）与 `running`（本状态机事实）分离：
 * 开机自启 / 冷启动恢复 / START_STICKY 重建只看意愿；页面与命令只改意愿 + 调启停。
 *
 * 状态机：STOPPED → STARTING → RUNNING → STOPPING → STOPPED，异常进 ERROR（可重试）。
 * 幂等守卫放所有写操作之前，重复 start 回 true（已在运行）、重复 stop 直接收状态——
 * 但对命令回执的「重复下发被忽略」判定由 executeSshStart/Stop 依据本状态机给出（v0.2.33 约定）。
 */
enum class SshStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class SshServerState(
    val status: SshStatus = SshStatus.STOPPED,
    val message: String = "未运行",
    val port: Int = 0,
    val sessions: Int = 0,
    val startedAt: Long = 0L,
)

object SshServerController {

    private const val TAG = "SSH"

    private val _state = MutableStateFlow(SshServerState())
    val state: StateFlow<SshServerState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 状态统一切主线程写（与 VntMeshController.publish 惯例一致）。 */
    private fun publish(block: (SshServerState) -> SshServerState) {
        mainHandler.post { _state.value = block(_state.value) }
    }

    // ------------------------------------------------------------------
    // 由 SshServerService 回调
    // ------------------------------------------------------------------

    fun onStarting(port: Int) = publish {
        it.copy(status = SshStatus.STARTING, message = "正在启动 SSH 服务…", port = port, sessions = 0, startedAt = 0L)
    }

    fun onStarted(port: Int) = publish {
        it.copy(status = SshStatus.RUNNING, message = "运行中", port = port, startedAt = System.currentTimeMillis())
    }

    fun onError(message: String) = publish {
        it.copy(status = SshStatus.ERROR, message = message)
    }

    fun onStopping() = publish {
        it.copy(status = SshStatus.STOPPING, message = "正在停止…")
    }

    fun onStopped() = publish {
        it.copy(status = SshStatus.STOPPED, message = "未运行", port = 0, sessions = 0, startedAt = 0L)
    }

    fun onSessionOpened() = publish { it.copy(sessions = it.sessions + 1) }
    fun onSessionClosed() = publish { it.copy(sessions = (it.sessions - 1).coerceAtLeast(0)) }

    // ------------------------------------------------------------------
    // 启停（服务常驻）
    // ------------------------------------------------------------------

    /**
     * 启动 SSH 服务。`startForegroundService` 每调一次欠一次前台义务，按崩溃台账 §5
     * 由 [SshServerService.onStartCommand] 每次幂等补挂；这里 try/catch 防后台启动限制抛异常。
     */
    fun start(context: Context): Boolean {
        val s = _state.value.status
        if (s == SshStatus.RUNNING || s == SshStatus.STARTING) return true
        val intent = Intent(context, SshServerService::class.java)
            .setAction(SshServerService.ACTION_START)
        return try {
            ContextCompat.startForegroundService(context, intent)
            publish { it.copy(status = SshStatus.STARTING, message = "正在启动 SSH 服务…") }
            true
        } catch (e: Exception) {
            DiagLog.e(TAG, "startForegroundService 启动失败", e)
            publish {
                it.copy(status = SshStatus.ERROR, message = "启动失败：${e.message}（请在前台操作）")
            }
            false
        }
    }

    /**
     * 停止 SSH 服务：走普通 startService（无前台义务），服务内部停 sshd 后 stopSelf。
     *
     * v0.2.33 VNT 教训沿用：未运行且服务不在跑时直接收状态，**不再 startService**——
     * 否则会新建空前台服务、通知常驻且状态卡在 STOPPING（僵尸服务）。
     */
    fun stop(context: Context) {
        val s = _state.value.status
        if ((s == SshStatus.STOPPED || s == SshStatus.ERROR) && !SshServerService.isActive()) {
            onStopped()
            return
        }
        onStopping()
        try {
            context.startService(
                Intent(context, SshServerService::class.java).setAction(SshServerService.ACTION_STOP)
            )
        } catch (e: Exception) {
            DiagLog.e(TAG, "下发停止失败", e)
            publish { it.copy(status = SshStatus.ERROR, message = "停止失败：${e.message}") }
        }
    }

    /** 供 `ssh_status` 命令回执：只读本地快照，不触发网络操作。 */
    fun statusText(): String {
        val s = _state.value
        return when (s.status) {
            SshStatus.RUNNING ->
                "运行中 · 端口 ${s.port} · 用户名 ${SshPrefs.USERNAME} · " +
                    "活动会话 ${s.sessions} · 存储暴露 ${SshPrefs.storageModeText()}" +
                    "（访问密码与 hostkey 指纹见 APP「设置 → 连接 → SSH 服务」状态页）"
            SshStatus.STARTING -> "正在启动（${s.message}）"
            SshStatus.STOPPING -> "正在停止（${s.message}）"
            SshStatus.STOPPED -> "未运行"
            SshStatus.ERROR -> "异常：${s.message}"
        }
    }
}
