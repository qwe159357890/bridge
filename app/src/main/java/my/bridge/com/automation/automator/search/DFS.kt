package my.bridge.com.automation.automator.search

import my.bridge.com.automation.automator.UiObject
import my.bridge.com.automation.automator.filter.Filter
import java.util.LinkedList

/**
 * 深度优先搜索（移植自 autox search/DFS.kt，剔除 ozobi 的 root.refresh()/parent.refresh()
 * 魔改——UiObject 无 refresh 方法，照抄会编译失败）。
 */
object DFS : SearchAlgorithm {

    override fun search(root: UiObject, filter: Filter, limit: Int): ArrayList<UiObject> {
        val result = ArrayList<UiObject>()
        val stack = LinkedList<UiObject>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val parent = stack.pop()
            for (i in parent.childCount - 1 downTo 0) {
                val child = parent.child(i) ?: continue
                stack.push(child)
            }
            if (filter.filter(parent)) {
                result.add(parent)
                if (result.size >= limit) {
                    break
                }
            } else {
                if (parent !== root) {
                    parent.recycle()
                }
            }
        }
        return result
    }
}
