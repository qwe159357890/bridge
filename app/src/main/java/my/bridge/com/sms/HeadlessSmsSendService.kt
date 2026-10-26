package my.bridge.com.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
import android.widget.Toast
import timber.log.Timber

/**
 * 静默快速回复服务（默认短信应用组件之四，RESPOND_VIA_MESSAGE）。
 *
 * 系统规范：Bridge 作为默认短信应用后，通知栏/来电界面的「快捷回复」按钮
 * 会经此服务发短信（系统已授权 SEND_RESPOND_VIA_MESSAGE 权限）。
 * 实现为直接 [SmsManager] 发送（系统要求本服务不弹 UI，Toast 除外）。
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        val address = uri?.schemeSpecificPart?.substringBefore('?')?.trim().orEmpty()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()

        if (address.isEmpty() || body.isEmpty()) {
            Timber.w("快捷回复参数缺失（address=%b, body=%b）", address.isNotEmpty(), body.isNotEmpty())
        } else {
            try {
                val smsManager = getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(body)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(address, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(address, null, body, null, null)
                }
                Toast.makeText(applicationContext, "快捷回复已发送", Toast.LENGTH_SHORT).show()
                Timber.i("快捷回复已发送：%s", address)
            } catch (e: Exception) {
                Timber.e(e, "快捷回复发送失败：%s", address)
                Toast.makeText(applicationContext, "快捷回复发送失败", Toast.LENGTH_SHORT).show()
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
