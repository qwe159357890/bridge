package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 键获取器接口（移植自 autox filter/KeyGetter.kt）。
 */
interface KeyGetter {
    fun getKey(nodeInfo: UiObject): String?
}
