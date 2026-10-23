package my.bridge.com.keepalive

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 一像素前台锚点（借鉴 Aurelay）。
 *
 * 常驻 1×1 像素、alpha 0.01、不可交互悬浮窗，把进程伪装成「可见/前台」。
 * alpha 绝不能设 0（会被系统优化掉）；不要跟随 SCREEN_ON/OFF 反复增删。
 */
object OnePixelOverlay {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context.applicationContext)
        } else {
            true
        }
    }

    fun isShowing(): Boolean = overlayView != null

    fun show(context: Context) {
        if (overlayView != null) return
        if (!hasPermission(context)) return
        val app = context.applicationContext
        mainHandler.post {
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val view = View(app)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0
                params.y = 0
                params.alpha = 0.01f
                wm.addView(view, params)
                windowManager = wm
                overlayView = view
            } catch (e: Exception) {
                android.util.Log.e("OnePixelOverlay", "显示失败", e)
            }
        }
    }

    fun hide(context: Context) {
        mainHandler.post {
            try {
                val view = overlayView ?: return@post
                windowManager?.removeView(view)
                overlayView = null
                windowManager = null
            } catch (e: Exception) {
                android.util.Log.e("OnePixelOverlay", "移除失败", e)
            }
        }
    }
}
