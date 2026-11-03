package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 字符串正则匹配过滤器（移植自 autox filter/StringMatchesFilter.kt）。
 */
class StringMatchesFilter internal constructor(private val mRegex: String, private val mKeyGetter: KeyGetter) : Filter {

    override fun filter(node: UiObject): Boolean {
        val key = mKeyGetter.getKey(node)
        return key != null && key.matches(mRegex.toRegex())
    }

    override fun toString(): String {
        return mKeyGetter.toString() + "Matches(\"" + mRegex + "\")"
    }
}
