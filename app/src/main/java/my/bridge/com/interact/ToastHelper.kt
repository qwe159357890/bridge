package my.bridge.com.interact

import android.content.Context
import android.widget.Toast
import timber.log.Timber

/**
 * Toast 提示工具（show_toast 命令）。
 *
 * 标准 Toast：后台服务上下文直接可弹（Android 11+ 仅拦截「后台自定义 View Toast」，
 * 文本 Toast 不受限）。需主线程调用（Toast 内部依赖主线程 Handler）。
 */
object ToastHelper {

    /** 在设备上显示一条 Toast（自动切主线程）。返回回执消息。 */
    fun show(context: Context, text: String): String {
        val appContext = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
        }
        Timber.i("show_toast：已投递（%d 字）", text.length)
        return "已显示提示：${text.take(30)}${if (text.length > 30) "…" else ""}"
    }
}
