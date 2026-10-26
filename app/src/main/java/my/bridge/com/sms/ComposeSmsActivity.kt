package my.bridge.com.sms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import my.bridge.com.data.SmsSender
import timber.log.Timber

/**
 * 短信编写入口（默认短信应用组件之三，ACTION_SENDTO）。
 *
 * 系统规范：Bridge 作为默认短信应用后，其他 App 的「发短信」分享入口
 * （sms:/smsto: 链接）会落到这里。实现为极简原生对话框：预填号码/正文，
 * 点「发送」转调 [SmsSender]（复用 send_sms 命令的发送+结果确认链路）。
 */
class ComposeSmsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri? = intent.data
        // sms:13800000000 / smsto:13800000000?body=xxx
        val address = uri?.schemeSpecificPart?.substringBefore('?')?.trim().orEmpty()
        val body = intent.getStringExtra("sms_body")
            ?: uri?.getQueryParameter("body").orEmpty()

        if (address.isBlank()) {
            Toast.makeText(this, "未识别收件人号码", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0) }
        val toEdit = EditText(this).apply { setText(address); hint = "收件人" }
        val bodyEdit = EditText(this).apply {
            setText(body); hint = "短信内容"
            minLines = 3; gravity = android.view.Gravity.TOP
        }
        container.orientation = LinearLayout.VERTICAL
        container.addView(toEdit)
        container.addView(bodyEdit)

        AlertDialog.Builder(this)
            .setTitle("发送短信（Bridge）")
            .setView(container)
            .setPositiveButton("发送") { _, _ ->
                val to = toEdit.text.toString().trim()
                val text = bodyEdit.text.toString()
                if (to.isEmpty() || text.isEmpty()) {
                    Toast.makeText(this, "号码或内容为空", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val detail = SmsSender.send(applicationContext, listOf(to), text).first()
                    Timber.i("ComposeSmsActivity 发送结果：%s", detail)
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            if (detail.success) "已发送给 $to" else "发送失败：${detail.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                finish()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
