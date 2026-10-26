package my.bridge.com.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Telephony
import android.app.role.RoleManager
import timber.log.Timber

/**
 * 默认短信应用助手（Bridge 短信页「快速切换」+ mark_sms_read 命令的公共底层）。
 *
 * 背景（源自 references/autoxClient modules/sms.js 的弃用教训）：
 * Android 4.4+ 写入短信库（含 read 字段）要求应用持有「默认短信应用」身份，
 * 仅 WRITE_SMS 权限会被部分 ROM（一加/ColorOS 等）静默拒绝（update 返回 0）。
 * autoxClient 因未声明默认短信应用必需组件而无法成为候选；Bridge 已在
 * Manifest 声明完整四组件（SmsDeliverReceiver/MmsReceiver/ComposeSmsActivity/
 * HeadlessSmsSendService），系统允许用户通过弹窗将 Bridge 设为默认。
 *
 * 切换必须经系统弹窗由用户确认（Android 安全设计，无静默 API）：
 * - 设为默认：API 29+ 走 RoleManager（SMS 角色）；旧版本走 ACTION_CHANGE_DEFAULT。
 * - 切回原应用：仅 ACTION_CHANGE_DEFAULT 可指定他人包名（RoleManager 只能为
 *   自己请求角色）。原包名在「成为默认」前的瞬间记录到本地（capture 时机：
 *   短信页加载 / 设为默认按钮点击 / mark_sms_read 引导弹窗前）。
 */
object SmsDefaultHelper {

    private const val PREFS = "sms_default"
    private const val KEY_PREVIOUS = "previous_default_package"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前默认短信应用包名（获取失败返回 null）。 */
    fun currentDefault(context: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (e: Exception) {
        Timber.w(e, "获取默认短信应用包名失败")
        null
    }

    /** Bridge 是否为默认短信应用。 */
    fun isDefault(context: Context): Boolean =
        currentDefault(context) == context.packageName

    /** 记录「原短信应用」包名（仅当当前默认不是 Bridge 时覆盖）。 */
    fun recordPreviousDefault(context: Context) {
        val cur = currentDefault(context) ?: return
        if (cur != context.packageName) {
            prefs(context).edit().putString(KEY_PREVIOUS, cur).apply()
            Timber.d("记录原默认短信应用：%s", cur)
        }
    }

    /** 已记录的原短信应用包名（可能为 null：从未在 Bridge 里切换过）。 */
    fun previousDefault(context: Context): String? =
        prefs(context).getString(KEY_PREVIOUS, null)

    /**
     * 拉起「设为默认短信应用」系统确认框。
     * @return 是否成功拉起（拉起后仍需用户在设备上点确认）
     */
    fun launchBecomeDefault(context: Context): Boolean = try {
        recordPreviousDefault(context) // 成为默认前记住原应用，供「切回」用
        val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                Timber.w("设备不支持 RoleManager 短信角色，回退 ACTION_CHANGE_DEFAULT")
                changeDefaultIntent(context.packageName)
            } else {
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            }
        } else {
            changeDefaultIntent(context.packageName)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Timber.i("已拉起「设为默认短信应用」确认框")
        true
    } catch (e: Exception) {
        Timber.e(e, "拉起默认短信应用设置失败")
        false
    }

    /**
     * 拉起「切回原短信应用」系统确认框（预选已记录的原应用包名）。
     * @return 拉起结果：true=已弹窗；false=无记录或拉起失败（调用方引导手动去系统设置）
     */
    fun launchRestorePrevious(context: Context): Boolean {
        val prev = previousDefault(context) ?: run {
            Timber.w("未记录原默认短信应用，无法预选切回目标")
            return false
        }
        if (prev == context.packageName) return false
        return try {
            context.startActivity(changeDefaultIntent(prev).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Timber.i("已拉起「切回原短信应用」确认框：%s", prev)
            true
        } catch (e: Exception) {
            Timber.e(e, "拉起切回确认框失败")
            false
        }
    }

    private fun changeDefaultIntent(targetPackage: String): Intent =
        Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, targetPackage)
        }
}
