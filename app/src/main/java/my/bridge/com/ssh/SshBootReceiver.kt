package my.bridge.com.ssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import my.bridge.com.core.common.DiagLog

/**
 * SSH 服务开机 / 更新后自启 receiver（v0.2.34，拍板「完整自愈链」）。
 *
 * 从 BOOT_COMPLETED / MY_PACKAGE_REPLACED 启动前台服务属系统豁免场景，
 * 不会撞 Android 12+ 后台 FGS 限制。是否真的拉起由 [SshAutoStart.tryStart]
 * 按用户意愿（SshPrefs.enabled）守卫决定。
 */
class SshBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        DiagLog.i("SSH", "收到广播：$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> SshAutoStart.tryStart(context)
        }
    }
}
