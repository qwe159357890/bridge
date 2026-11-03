package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject
import java.util.LinkedList

/**
 * 过滤器组合器（移植自 autox filter/Selector.kt）。
 */
class Selector : Filter {
    private val mFilters = LinkedList<Filter>()

    override fun filter(node: UiObject): Boolean {
        for (filter in mFilters) {
            if (!filter.filter(node)) {
                return false
            }
        }
        return true
    }

    fun add(filter: Filter) {
        mFilters.add(filter)
    }

    override fun toString(): String {
        val str = StringBuilder()
        for (filter in mFilters) {
            str.append(filter.toString()).append(".")
        }
        if (str.isNotEmpty()) {
            str.deleteCharAt(str.length - 1)
        }
        return str.toString()
    }
}
