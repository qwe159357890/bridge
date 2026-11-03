package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 过滤器接口（移植自 autox filter/Filter.kt）。
 */
interface Filter {
    fun filter(node: UiObject): Boolean
}
