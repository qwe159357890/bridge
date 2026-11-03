package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 字符串包含过滤器（移植自 autox filter/StringContainsFilter.kt）。
 */
class StringContainsFilter internal constructor(
    private val mContains: String,
    private val mKeyGetter: KeyGetter,
) : Filter {

    override fun filter(node: UiObject): Boolean {
        val key = mKeyGetter.getKey(node)
        return key != null && key.contains(mContains)
    }

    override fun toString(): String {
        return mKeyGetter.toString() + "Contains(\"" + mContains + "\")"
    }
}
