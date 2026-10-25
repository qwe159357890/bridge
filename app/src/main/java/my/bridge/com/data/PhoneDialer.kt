package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * 拨号器（dial_phone 命令的实现核心）。
 *
 * 借鉴 autoxClient `phone.js` 的 phone_call 双模式：
 * - **direct**：`ACTION_CALL` 直接拨打（跳过拨号盘），需 CALL_PHONE 运行时权限
 *   （Manifest 已声明、App 启动时随批量权限申请）；权限缺失时由调用方降级为 dialer。
 * - **dialer**：`ACTION_DIAL` 仅打开拨号盘并预填号码，**无需任何权限**，
 *   由设备持有者手动按下拨号键——天然的人工确认层。
 *
 * 已知限制（Android 10+ 后台启动 Activity 限制）：客户端进程虽为前台服务，
 * 但息屏/后台时系统可能拦截拨号盘弹出（异常仅在 logcat，不抛给调用方），
 * 授予「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）后不受限。
 */
object PhoneDialer {

    /** 是否持有 CALL_PHONE 运行时权限。 */
    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /** 号码清洗：去掉空格 / 连字符 / 括号。 */
    fun cleanNumber(raw: String): String = raw.replace(Regex("[\\s\\-()]"), "")

    /**
     * 发起拨号。
     *
     * @param direct true=直接拨打（ACTION_CALL）；false=打开拨号盘（ACTION_DIAL）
     * @return 结果描述（成功描述 + 限制提示；抛错由调用方转失败回执）
     */
    fun dial(context: Context, number: String, direct: Boolean): String {
        val cleaned = cleanNumber(number)
        require(cleaned.matches(Regex("^\\+?[0-9]{3,20}$"))) { "号码格式不合法：$number" }

        if (direct) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleaned")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "已直接拨打 $cleaned（若设备息屏且无「其他应用上层」权限，" +
                "系统可能拦截通话界面，请到设备上确认）"
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "已打开拨号盘并填入 $cleaned，请在设备上手动拨出"
    }
}
