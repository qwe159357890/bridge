package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * className 过滤器（移植自 autox filter/ClassNameFilters.kt）。
 */
object ClassNameFilters {

    private val CLASS_NAME_GETTER = object : KeyGetter {
        override fun getKey(nodeInfo: UiObject): String? {
            val charSequence = nodeInfo.className
            return charSequence?.toString()
        }

        override fun toString(): String {
            return "className"
        }
    }

    fun equals(text: String): Filter {
        var className = text
        if (!className.contains(".")) {
            className = "android.widget.$className"
        }
        return StringEqualsFilter(className, CLASS_NAME_GETTER)
    }

    fun contains(str: String): Filter = StringContainsFilter(str, CLASS_NAME_GETTER)

    fun startsWith(prefix: String): Filter = StringStartsWithFilter(prefix, CLASS_NAME_GETTER)

    fun endsWith(suffix: String): Filter = StringEndsWithFilter(suffix, CLASS_NAME_GETTER)

    fun matches(regex: String): Filter = StringMatchesFilter(regex, CLASS_NAME_GETTER)
}
