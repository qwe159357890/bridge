package my.bridge.com.interact

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import timber.log.Timber

/**
 * 全局悬浮窗对话框（show_dialog 命令）。
 *
 * TYPE_APPLICATION_OVERLAY 系统级悬浮窗：盖在任意应用之上，停留到用户点击「知道了」。
 * 需要「显示在其他应用上层」特殊权限（SYSTEM_ALERT_WINDOW，Manifest 已声明，
 * 需用户在系统设置里开一次，[launchOverlaySettings] 负责跳转引导）。
 *
 * 点击回调在主线程触发（对话框按钮点击），触发后窗口立即移除。
 */
object OverlayDialog {

    /** 当前展示的窗口（同一时刻只保留一个，新弹窗顶掉旧的）。 */
    @Volatile
    private var current: Current? = null

    private class Current(
        val windowManager: WindowManager,
        val view: View,
    )

    /** 悬浮窗权限是否已授予。 */
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 跳转「显示在其他应用上层」设置页（后台启动可能被系统拦截，返回是否发起）。 */
    fun launchOverlaySettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        Timber.w(e, "跳转悬浮窗设置页失败")
        false
    }

    /**
     * 弹出全局对话框（阻塞到主线程投递完成，最长 3 秒）。
     * @param onClicked 用户点击「知道了」后回调（主线程，仅回调一次）
     * @return null = 已成功弹出；非 null = 失败原因
     */
    fun show(context: Context, title: String, content: String, onClicked: () -> Unit): String? {
        val appContext = context.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        var errorMessage: String? = null
        val main = Handler(Looper.getMainLooper())
        main.post {
            // 旧弹窗先撤（静默）
            dismissCurrent()
            try {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = appContext.resources.displayMetrics.density

                fun dp(v: Int): Int = (v * density).toInt()

                val root = LinearLayout(appContext).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(14).toFloat()
                    }
                    setPadding(dp(24), dp(20), dp(24), dp(16))
                }

                if (title.isNotBlank()) {
                    root.addView(TextView(appContext).apply {
                        text = title
                        textSize = 18f
                        setTextColor(Color.parseColor("#303133"))
                        typeface = Typeface.DEFAULT_BOLD
                    })
                }

                root.addView(TextView(appContext).apply {
                    text = content
                    textSize = 15f
                    setTextColor(Color.parseColor("#606266"))
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, dp(10), 0, dp(18))
                })

                root.addView(Button(appContext).apply {
                    text = "知道了"
                    textSize = 15f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#409EFF"))
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener {
                        dismissCurrent()
                        onClicked()
                    }
                })

                val params = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    format = PixelFormat.TRANSLUCENT
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.CENTER
                    horizontalMargin = 0.06f
                }

                wm.addView(root, params)
                current = Current(wm, root)
                Timber.i("show_dialog：悬浮窗已弹出（%d 字正文）", content.length)
            } catch (e: Exception) {
                Timber.e(e, "show_dialog：悬浮窗弹出失败")
                errorMessage = "弹窗显示失败：${e.message}"
            } finally {
                latch.countDown()
            }
        }
        val confirmed = latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return when {
            errorMessage != null -> errorMessage
            !confirmed -> "弹窗未在 3 秒内显示（主线程繁忙）"
            else -> null
        }
    }

    /** 移除当前弹窗（如有）。 */
    private fun dismissCurrent() {
        val c = current ?: return
        current = null
        try {
            c.windowManager.removeView(c.view)
        } catch (e: Exception) {
            Timber.w(e, "移除悬浮窗失败（忽略）")
        }
    }
}
