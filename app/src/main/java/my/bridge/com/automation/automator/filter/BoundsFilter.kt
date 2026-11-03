package my.bridge.com.automation.automator.filter

import android.graphics.Rect
import my.bridge.com.automation.automator.UiObject
import my.bridge.com.automation.automator.view.AccessibilityNodeInfoHelper
import java.util.Locale

/**
 * bounds 过滤器（移植自 autox filter/BoundsFilter.kt）。
 */
class BoundsFilter(private val mBounds: Rect, private val mType: Int) : Filter {

    override fun filter(node: UiObject): Boolean {
        if (mType == TYPE_CONTAINS) {
            return AccessibilityNodeInfoHelper.getBoundsInScreen(node).contains(mBounds)
        }
        val boundsInScreen = AccessibilityNodeInfoHelper.getBoundsInScreen(node)
        return if (mType == TYPE_EQUALS) boundsInScreen == mBounds else mBounds.contains(boundsInScreen)
    }

    override fun toString(): String {
        return String.format(
            Locale.getDefault(), "bounds%s(%d, %d, %d, %d)", when (mType) {
                TYPE_EQUALS -> ""
                TYPE_INSIDE -> "Inside"
                else -> "Contains"
            },
            mBounds.left, mBounds.top, mBounds.right, mBounds.bottom,
        )
    }

    companion object {
        const val TYPE_EQUALS = 0
        const val TYPE_INSIDE = 1
        const val TYPE_CONTAINS = 2
    }
}
