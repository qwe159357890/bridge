package my.bridge.com.automation.automator.filter

import android.os.Build
import androidx.annotation.RequiresApi
import my.bridge.com.automation.automator.UiObject

/**
 * 布尔属性过滤器（移植自 autox filter/BooleanFilter.kt）。
 */
class BooleanFilter(private val mBooleanSupplier: BooleanSupplier, private val mExceptedValue: Boolean) : Filter {

    interface BooleanSupplier {
        operator fun get(node: UiObject): Boolean
    }

    override fun filter(node: UiObject): Boolean {
        return mBooleanSupplier[node] == mExceptedValue
    }

    override fun toString(): String {
        return mBooleanSupplier.toString() + "(" + mExceptedValue + ")"
    }

    companion object {

        private val cache = HashMap<BooleanSupplier, Array<BooleanFilter>>()

        operator fun get(supplier: BooleanSupplier, b: Boolean): BooleanFilter {
            var booleanFilters: Array<BooleanFilter>? = cache[supplier]
            if (booleanFilters == null) {
                booleanFilters = Array(2) { BooleanFilter(supplier, it != 0) }
                cache[supplier] = booleanFilters
            }
            val i = if (b) 1 else 0
            return booleanFilters[i]
        }

        val CHECKABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isCheckable
            override fun toString(): String = "checkable"
        }

        val CHECKED: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isChecked
            override fun toString(): String = "checked"
        }

        val FOCUSABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isFocusable
            override fun toString(): String = "focusable"
        }

        val FOCUSED: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isFocused
            override fun toString(): String = "focused"
        }

        val VISIBLE_TO_USER: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isVisibleToUser
            override fun toString(): String = "visibleToUser"
        }

        val ACCESSIBILITY_FOCUSED: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isAccessibilityFocused
            override fun toString(): String = "accessibilityFocused"
        }

        val SELECTED: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isSelected
            override fun toString(): String = "selected"
        }

        val CLICKABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isClickable
            override fun toString(): String = "clickable"
        }

        val LONG_CLICKABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isLongClickable
            override fun toString(): String = "longClickable"
        }

        val ENABLED: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isEnabled
            override fun toString(): String = "enabled"
        }

        val PASSWORD: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isPassword
            override fun toString(): String = "password"
        }

        val SCROLLABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isScrollable
            override fun toString(): String = "scrollable"
        }

        val EDITABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isEditable
            override fun toString(): String = "editable"
        }

        val CONTENT_INVALID: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isContentInvalid
            override fun toString(): String = "contentInvalid"
        }

        val CONTEXT_CLICKABLE: BooleanSupplier = object : BooleanSupplier {
            @RequiresApi(api = Build.VERSION_CODES.M)
            override fun get(node: UiObject): Boolean = node.isContextClickable
            override fun toString(): String = "checkable"
        }

        val MULTI_LINE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isMultiLine
            override fun toString(): String = "multiLine"
        }

        val DISMISSABLE: BooleanSupplier = object : BooleanSupplier {
            override fun get(node: UiObject): Boolean = node.isDismissable
            override fun toString(): String = "dismissable"
        }
    }
}
