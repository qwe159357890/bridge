package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 字符串前缀匹配过滤器（移植自 autox filter/StringStartsWithFilter.kt）。
 */
class StringStartsWithFilter(private val mPrefix: String, private val mKeyGetter: KeyGetter) : Filter {

    override fun filter(node: UiObject): Boolean {
        val key = mKeyGetter.getKey(node)
        return key != null && key.startsWith(mPrefix)
    }

    override fun toString(): String {
        return mKeyGetter.toString() + "StartsWith(\"" + mPrefix + "\")"
    }
}
