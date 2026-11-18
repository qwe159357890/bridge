package my.bridge.com.vnt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import my.bridge.com.core.common.DiagLog

/**
 * VNT 组网开机 / 更新后自启 receiver（照 SshBootReceiver 范式补齐，修「冷启动不组网」）。
 *
 * 背景：VNT 组网此前只有 `MainActivity.onStart` 一条自动拉起链，进程冷启动
 * （开机自启 / 覆盖安装 MY_PACKAGE_REPLACED / 完全杀进程后重开）时若从未进入
 * 前台，`onStart` 不触发 → VNT 不组网；而极空、钥匙广播、SSH 各有 onCreate /
 * BootReceiver 兜底，唯独 VNT 缺失。
 *
 * 从 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 启动前台服务属
 * 系统豁免场景，不会撞 Android 12+ 后台 FGS 限制（同 SshBootReceiver）。是否真的
 * 拉起仍由 [VntAutoStart.tryStart] 按三道守卫决定（记忆开 + 未运行 + VPN 授权有效），
 * 此处不做业务判断。
 */
class VntBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        DiagLog.i("组网", "收到广播：$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> VntAutoStart.tryStart(context)
        }
    }
}
