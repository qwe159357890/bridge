package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * text 过滤器（移植自 autox filter/TextFilters.kt）。
 */
object TextFilters {

    private val TEXT_GETTER = object : KeyGetter {
        override fun getKey(nodeInfo: UiObject): String? {
            val charSequence = nodeInfo.text
            return charSequence?.toString()
        }

        override fun toString(): String {
            return "text"
        }
    }

    fun equals(text: String): Filter = StringEqualsFilter(text, TEXT_GETTER)

    fun contains(str: String): Filter = StringContainsFilter(str, TEXT_GETTER)

    fun startsWith(prefix: String): Filter = StringStartsWithFilter(prefix, TEXT_GETTER)

    fun endsWith(suffix: String): Filter = StringEndsWithFilter(suffix, TEXT_GETTER)

    fun matches(regex: String): Filter = StringMatchesFilter(regex, TEXT_GETTER)
}
