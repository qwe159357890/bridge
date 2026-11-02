package my.bridge.com.ssh

import android.content.Context
import my.bridge.com.core.common.DiagLog

/**
 * SSH 服务意愿自愈（v0.2.34）——对照 VntAutoStart 范式。
 *
 * 三条自愈链共用本守卫（调研报告 §6.3 拍板「完整自愈链」）：
 * 1. 开机自启：SshBootReceiver（BOOT_COMPLETED / MY_PACKAGE_REPLACED）；
 * 2. 冷启动恢复：MainActivity.onStart；
 * 3. START_STICKY 重建：SshServerService.onStartCommand(null intent)。
 *
 * 守卫语义：**只看意愿（SshPrefs.enabled），不看历史**——用户没开过/手动停过 → 永不自动拉起；
 * 意愿为开且未运行 → 拉起。失败不重试（端口占用等会落 ERROR 状态显示原因，避免循环刷屏耗电）。
 */
object SshAutoStart {

    private const val TAG = "SSH"

    fun tryStart(context: Context) {
        if (!SshPrefs.enabled(context)) {
            DiagLog.i(TAG, "自愈检查：意愿为关，跳过")
            return
        }
        val st = SshServerController.state.value.status
        if (st == SshStatus.RUNNING || st == SshStatus.STARTING) {
            DiagLog.i(TAG, "自愈检查：已在运行/启动中，跳过")
            return
        }
        DiagLog.i(TAG, "自愈检查：意愿为开且未运行，自动启动 SSH 服务")
        SshServerController.start(context)
    }
}
