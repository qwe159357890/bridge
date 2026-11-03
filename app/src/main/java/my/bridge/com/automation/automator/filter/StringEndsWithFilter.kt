package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 字符串后缀匹配过滤器（移植自 autox filter/StringEndsWithFilter.kt）。
 */
class StringEndsWithFilter(private val mSuffix: String, private val mKeyGetter: KeyGetter) : Filter {

    override fun filter(node: UiObject): Boolean {
        val key = mKeyGetter.getKey(node)
        return key != null && key.endsWith(mSuffix)
    }

    override fun toString(): String {
        return mKeyGetter.toString() + "EndsWith(\"" + mSuffix + "\")"
    }
}
