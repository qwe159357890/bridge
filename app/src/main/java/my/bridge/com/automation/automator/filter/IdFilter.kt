package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * id 过滤器（移植自 autox filter/IdFilter.kt）。
 */
object IdFilter {

    private val ID_GETTER = object : KeyGetter {
        override fun getKey(nodeInfo: UiObject): String? {
            return nodeInfo.viewIdResourceName
        }

        override fun toString(): String {
            return "id"
        }
    }

    fun equals(id: String): StringEqualsFilter = StringEqualsFilter(id, ID_GETTER)

    fun startsWith(prefix: String): StringStartsWithFilter = StringStartsWithFilter(prefix, ID_GETTER)

    fun endsWith(suffix: String): StringEndsWithFilter = StringEndsWithFilter(suffix, ID_GETTER)

    fun contains(contains: String): StringContainsFilter = StringContainsFilter(contains, ID_GETTER)

    fun matches(regex: String): StringMatchesFilter = StringMatchesFilter(regex, ID_GETTER)
}
