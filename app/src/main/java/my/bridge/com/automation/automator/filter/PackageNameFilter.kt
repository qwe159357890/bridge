package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * packageName 过滤器（移植自 autox filter/PackageNameFilter.kt）。
 */
object PackageNameFilter {

    private val PACKAGE_NAME_GETTER = object : KeyGetter {
        override fun getKey(nodeInfo: UiObject): String? {
            val charSequence = nodeInfo.packageName
            return charSequence?.toString()
        }

        override fun toString(): String {
            return "packageName"
        }
    }

    fun equals(text: String): Filter = StringEqualsFilter(text, PACKAGE_NAME_GETTER)

    fun contains(str: String): Filter = StringContainsFilter(str, PACKAGE_NAME_GETTER)

    fun startsWith(prefix: String): Filter = StringStartsWithFilter(prefix, PACKAGE_NAME_GETTER)

    fun endsWith(suffix: String): Filter = StringEndsWithFilter(suffix, PACKAGE_NAME_GETTER)

    fun matches(regex: String): Filter = StringMatchesFilter(regex, PACKAGE_NAME_GETTER)
}
