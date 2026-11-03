package my.bridge.com.automation.automator

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CONTEXT_CLICK
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN
import my.bridge.com.automation.automator.view.AccessibilityNodeInfoHelper
import java.util.Arrays

/**
 * 无障碍节点模型（移植自 autox automator/UiObject.kt）。
 *
 * 裁剪：去除 allocator（AccessibilityNodeInfoAllocator 节点池，非必需），
 * 构造器简化为 (info, depth, indexInParent)。
 */
open class UiObject(
    info: Any?,
    depth: Int = 0,
    private val mIndexInParent: Int = -1,
) : AccessibilityNodeInfoCompat(info) {

    private var mStackTrace = ""
    private var mDepth = 0

    val isHierarchically: Boolean
        get() = collectionInfo != null && collectionInfo.isHierarchical

    init {
        mDepth = depth
        if (DEBUG) mStackTrace = Arrays.toString(Thread.currentThread().stackTrace)
    }

    open fun parent(): UiObject? {
        try {
            val parent = super.getParent() ?: return null
            return UiObject(parent.info, mDepth - 1, -1)
        } catch (e: IllegalStateException) {
            return null
        }
    }

    open fun child(i: Int): UiObject? {
        try {
            val child = super.getChild(i) ?: return null
            return UiObject(child.info, mDepth + 1, i)
        } catch (e: IllegalStateException) {
            return null
        }
    }

    fun indexInParent(): Int = mIndexInParent

    fun find(selector: UiGlobalSelector): UiObjectCollection = selector.findOf(this)

    fun findOne(selector: UiGlobalSelector): UiObject? = selector.findOneOf(this)

    fun children(): UiObjectCollection {
        val list = ArrayList<UiObject?>(childCount)
        for (i in 0 until childCount) {
            list.add(child(i))
        }
        return UiObjectCollection.of(list)
    }

    open fun childCount(): Int = childCount

    open fun bounds(): Rect = AccessibilityNodeInfoHelper.getBoundsInScreen(this)

    open fun boundsInParent(): Rect = AccessibilityNodeInfoHelper.getBoundsInParent(this)

    open fun drawingOrder(): Int = drawingOrder

    open fun id(): String? = viewIdResourceName

    open fun text(): String {
        val t = text
        return t?.toString() ?: ""
    }

    override fun getText(): CharSequence? {
        return if (isPassword) "" else super.getText()
    }

    open fun desc(): String? {
        val d = contentDescription
        return d?.toString()
    }

    open fun className(): String? {
        val d = className
        return d?.toString()
    }

    open fun packageName(): String? {
        val d = packageName
        return d?.toString()
    }

    open fun depth(): Int = mDepth

    fun performAction(action: Int, vararg arguments: ActionArgument): Boolean {
        val bundle = argumentsToBundle(arguments)
        return performAction(action, bundle)
    }

    override fun performAction(action: Int, bundle: Bundle): Boolean {
        return try {
            super.performAction(action, bundle)
        } catch (e: IllegalStateException) {
            false
        }
    }

    override fun performAction(action: Int): Boolean {
        return try {
            super.performAction(action)
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun click(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_CLICK)

    /**
     * 点击三级降级（借鉴 Droidline click 契约）：
     * 1. 节点自身 ACTION_CLICK；
     * 2. 沿祖先链找最近的 clickable 祖先并 ACTION_CLICK；
     * 3. 全部失败则对节点 bounds 中心做坐标手势点击。
     * 返回 Triple(成功, 实际策略描述, 实际点击的节点 JSON 键名)。
     */
    fun clickWithFallback(): Pair<Boolean, String> {
        // 第一级：自身可点击
        if (click()) return true to "节点 ACTION_CLICK"
        // 第二级：最近可点击祖先
        var p = parent()
        var guard = 0
        while (p != null && guard < 32) {
            if (p.clickable() && p.enabled() && p.click()) {
                return true to "祖先 ACTION_CLICK"
            }
            p = p.parent()
            guard++
        }
        // 第三级：坐标手势点击（需无障碍服务实例，故这里仅标记失败交由上层坐标降级）
        return false to "节点与祖先均不可点击"
    }

    /** 最近的可点击祖先（用于二级降级，返回 null 表示无）。 */
    fun findClickableAncestor(): UiObject? {
        var p = parent()
        var guard = 0
        while (p != null && guard < 32) {
            if (p.clickable() && p.enabled()) return p
            p = p.parent()
            guard++
        }
        return null
    }

    fun longClick(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)

    fun accessibilityFocus(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)

    fun clearAccessibilityFocus(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS)

    fun focus(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)

    fun clearFocus(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS)

    fun copy(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_COPY)

    fun paste(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_PASTE)

    fun select(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_SELECT)

    fun cut(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_CUT)

    fun collapse(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_COLLAPSE)

    fun expand(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_EXPAND)

    fun dismiss(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_DISMISS)

    fun show(): Boolean = performAction(ACTION_SHOW_ON_SCREEN.id)

    fun scrollForward(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)

    fun scrollBackward(): Boolean = performAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)

    fun scrollUp(): Boolean = performAction(ACTION_SCROLL_UP.id)

    fun scrollDown(): Boolean = performAction(ACTION_SCROLL_DOWN.id)

    fun scrollLeft(): Boolean = performAction(ACTION_SCROLL_LEFT.id)

    fun scrollRight(): Boolean = performAction(ACTION_SCROLL_RIGHT.id)

    fun contextClick(): Boolean = performAction(ACTION_CONTEXT_CLICK.id)

    fun setSelection(s: Int, e: Int): Boolean = performAction(
        AccessibilityNodeInfoCompat.ACTION_SET_SELECTION,
        ActionArgument.IntActionArgument(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, s),
        ActionArgument.IntActionArgument(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, e),
    )

    fun setText(text: String): Boolean = performAction(
        AccessibilityNodeInfoCompat.ACTION_SET_TEXT,
        ActionArgument.CharSequenceActionArgument(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text),
    )

    fun setProgress(value: Float): Boolean = performAction(
        ACTION_SET_PROGRESS.id,
        ActionArgument.FloatActionArgument(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_PROGRESS_VALUE, value),
    )

    fun scrollTo(row: Int, column: Int): Boolean = performAction(
        ACTION_SCROLL_TO_POSITION.id,
        ActionArgument.IntActionArgument(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_ROW_INT, row),
        ActionArgument.IntActionArgument(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_COLUMN_INT, column),
    )

    open fun checkable(): Boolean = isCheckable

    open fun checked(): Boolean = isChecked

    open fun focusable(): Boolean = isFocusable

    open fun focused(): Boolean = isFocused

    open fun visibleToUser(): Boolean = isVisibleToUser

    open fun accessibilityFocused(): Boolean = isAccessibilityFocused

    open fun selected(): Boolean = isSelected

    open fun clickable(): Boolean = isClickable

    open fun longClickable(): Boolean = isLongClickable

    open fun enabled(): Boolean = isEnabled

    fun password(): Boolean = isPassword

    open fun scrollable(): Boolean = isScrollable

    open fun editable(): Boolean = isEditable

    open fun row(): Int = if (collectionItemInfo == null) -1 else collectionItemInfo.rowIndex

    open fun column(): Int = if (collectionItemInfo == null) -1 else collectionItemInfo.columnIndex

    open fun rowSpan(): Int = if (collectionItemInfo == null) -1 else collectionItemInfo.rowSpan

    open fun columnSpan(): Int = if (collectionItemInfo == null) -1 else collectionItemInfo.columnSpan

    open fun rowCount(): Int = if (collectionInfo == null) 0 else collectionInfo.rowCount

    open fun columnCount(): Int = if (collectionInfo == null) 0 else collectionInfo.columnCount

    fun findByText(text: String): List<UiObject> =
        UiGlobalSelector().textContains(text).findAndReturnList(this, Int.MAX_VALUE)

    fun findByViewId(viewId: String): List<UiObject> =
        UiGlobalSelector().id(viewId).findAndReturnList(this, Int.MAX_VALUE)

    override fun recycle() {
        try {
            super.recycle()
        } catch (e: Exception) {
            Log.w(TAG, mStackTrace, e)
        }
    }

    companion object {
        const val ACTION_APPEND_TEXT = 0x00200001

        private const val TAG = "UiObject"
        private const val DEBUG = false

        fun createRoot(root: AccessibilityNodeInfo): UiObject = UiObject(root, 0, -1)

        private fun argumentsToBundle(arguments: Array<out ActionArgument>): Bundle {
            val bundle = Bundle()
            for (arg in arguments) {
                arg.putIn(bundle)
            }
            return bundle
        }
    }
}
