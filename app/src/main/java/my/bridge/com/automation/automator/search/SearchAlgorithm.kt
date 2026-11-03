package my.bridge.com.automation.automator.search

import my.bridge.com.automation.automator.UiObject
import my.bridge.com.automation.automator.filter.Filter

/**
 * 搜索算法接口（移植自 autox search/SearchAlgorithm.kt，剔除 ozobi isRefresh 魔改）。
 */
interface SearchAlgorithm {
    fun search(root: UiObject, filter: Filter, limit: Int = Int.MAX_VALUE): ArrayList<UiObject>
}
