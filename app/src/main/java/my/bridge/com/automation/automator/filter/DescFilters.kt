package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * contentDescription 过滤器（移植自 autox filter/DescFilters.kt）。
 */
object DescFilters {

    private val DESC_GETTER = object : KeyGetter {
        override fun getKey(nodeInfo: UiObject): String? {
            val charSequence = nodeInfo.contentDescription
            return charSequence?.toString()
        }

        override fun toString(): String {
            return "desc"
        }
    }

    fun equals(text: String): Filter = StringEqualsFilter(text, DESC_GETTER)

    fun contains(str: String): Filter = StringContainsFilter(str, DESC_GETTER)

    fun startsWith(prefix: String): Filter = StringStartsWithFilter(prefix, DESC_GETTER)

    fun endsWith(suffix: String): Filter = StringEndsWithFilter(suffix, DESC_GETTER)

    fun matches(regex: String): Filter = StringMatchesFilter(regex, DESC_GETTER)
}
