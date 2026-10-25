package my.bridge.com.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 设备管理员接收器（lock_screen 命令的授权载体）。
 *
 * 策略见 `res/xml/device_admin_policies.xml`：只申请 force-lock（锁屏），
 * 不申请密码策略/擦除数据等其他管理员能力。用户在设备上激活一次后，
 * 客户端即可通过 `DevicePolicyManager.lockNow()` 随时锁屏。
 *
 * 注意：激活后应用受管理员保护，需先在系统设置停用管理员才能卸载——
 * 对自控设备而言这是防误卸的优点，文档已注明。
 */
class BridgeAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Timber.i("设备管理员已激活：远程锁屏可用")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Timber.i("设备管理员已停用：远程锁屏不可用")
    }
}
