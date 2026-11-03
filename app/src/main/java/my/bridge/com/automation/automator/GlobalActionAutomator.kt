package my.bridge.com.automation.automator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 全局动作 + 手势注入（移植自 autox automator/GlobalActionAutomator.kt）。
 *
 * 裁剪：去除 com.stardust.concurrent.VolatileBox/VolatileDispose（改用 CountDownLatch）、
 * 去除 ScreenMetrics（直接屏幕物理坐标，不缩放）。
 */
class GlobalActionAutomator(private val serviceProvider: () -> AccessibilityService) {

    private val service: AccessibilityService
        get() = serviceProvider()

    fun back(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    fun powerDialog(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)

    private fun performGlobalAction(globalAction: Int): Boolean = service.performGlobalAction(globalAction)

    fun notifications(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    fun quickSettings(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

    fun recents(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    @RequiresApi(Build.VERSION_CODES.P)
    fun takeScreenshot(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)

    @RequiresApi(Build.VERSION_CODES.P)
    fun lockScreen(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    @RequiresApi(Build.VERSION_CODES.S)
    fun dismissNotificationShade(): Boolean =
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun splitScreen(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    //// 手势（API 24+）

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun gesture(start: Long, duration: Long, vararg points: IntArray): Boolean {
        val path = pointsToPath(points)
        return gestures(GestureDescription.StrokeDescription(path, start, duration))
    }

    private fun pointsToPath(points: Array<out IntArray>): Path {
        val path = Path()
        path.moveTo(points[0][0].toFloat(), points[0][1].toFloat())
        for (i in 1 until points.size) {
            val point = points[i]
            path.lineTo(point[0].toFloat(), point[1].toFloat())
        }
        return path
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun gestures(vararg strokes: GestureDescription.StrokeDescription): Boolean {
        val builder = GestureDescription.Builder()
        for (stroke in strokes) {
            builder.addStroke(stroke)
        }
        val result = CountDownLatch(1)
        var completed = false
        val dispatched = service.dispatchGesture(
            builder.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    completed = true
                    result.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    completed = false
                    result.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        return try {
            result.await(10, TimeUnit.SECONDS)
            completed
        } catch (e: InterruptedException) {
            false
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun click(x: Int, y: Int): Boolean = press(x, y, ViewConfiguration.getTapTimeout() + 50)

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun press(x: Int, y: Int, delay: Int): Boolean = gesture(0, delay.toLong(), intArrayOf(x, y))

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun longClick(x: Int, y: Int): Boolean =
        gesture(0, (ViewConfiguration.getLongPressTimeout() + 200).toLong(), intArrayOf(x, y))

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, delay: Long): Boolean =
        gesture(0, delay, intArrayOf(x1, y1), intArrayOf(x2, y2))
}
