package my.bridge.com.automation.automator.view

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * 节点 bounds 辅助（移植自 autox view/accessibility/AccessibilityNodeInfoHelper.kt）。
 */
object AccessibilityNodeInfoHelper {

    fun getBoundsInParent(nodeInfo: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        nodeInfo.getBoundsInParent(rect)
        return rect
    }

    fun getBoundsInScreen(nodeInfo: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        nodeInfo.getBoundsInScreen(rect)
        return rect
    }

    fun getBoundsInScreen(nodeInfo: AccessibilityNodeInfoCompat): Rect {
        val rect = Rect()
        nodeInfo.getBoundsInScreen(rect)
        return rect
    }

    fun getBoundsInParent(nodeInfo: AccessibilityNodeInfoCompat): Rect {
        val rect = Rect()
        nodeInfo.getBoundsInParent(rect)
        return rect
    }
}
