package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 字符串精确匹配过滤器（移植自 autox filter/StringEqualsFilter.kt）。
 */
class StringEqualsFilter(private val mValue: String, private val mKeyGetter: KeyGetter) : Filter {

    override fun filter(node: UiObject): Boolean {
        val key = mKeyGetter.getKey(node)
        return if (key != null) {
            key == mValue
        } else false
    }

    override fun toString(): String {
        return mKeyGetter.toString() + "(\"" + mValue + "\")"
    }
}
