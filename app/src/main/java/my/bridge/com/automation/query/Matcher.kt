package my.bridge.com.automation.query

import my.bridge.com.automation.automator.UiObject

/**
 * 动作鲁棒性辅助（移植 Droidline UiNode.kt 的 Matcher object）。
 *
 * 基于 [UiObject] 直接实现（无需 UiNodeView 快照），供命令层在点击/输入/滚动时
 * 做降级与目标判定。
 */
object Matcher {

    /** 最近的可点击且启用的祖先（用于二级降级）。 */
    fun clickableAncestor(n: UiObject): UiObject? {
        var p = n.parent()
        var guard = 0
        while (p != null && guard < 32) {
            if (p.clickable() && p.enabled()) return p
            p = p.parent()
            guard++
        }
        return null
    }

    /** 最近的可长按且启用的祖先。 */
    fun longClickableAncestor(n: UiObject): UiObject? {
        var p = n.parent()
        var guard = 0
        while (p != null && guard < 32) {
            if (p.longClickable() && p.enabled()) return p
            p = p.parent()
            guard++
        }
        return null
    }

    /** 自身或第一个 editable 后代（兼容 TextInputLayout 等包裹控件）。 */
    fun editableTarget(n: UiObject): UiObject {
        if (n.editable()) return n
        var found: UiObject? = null
        fun walk(node: UiObject) {
            if (found == null && node.editable()) {
                found = node
                return
            }
            for (i in 0 until node.childCount()) {
                if (found != null) return
                node.child(i)?.let { walk(it) }
            }
        }
        walk(n)
        return found ?: n
    }

    /** 最大的 scrollable 节点（通常是列表或页面容器）。 */
    fun mainScrollable(root: UiObject): UiObject? {
        var best: UiObject? = null
        var bestArea = -1L
        fun walk(node: UiObject) {
            if (node.scrollable()) {
                val b = node.bounds()
                val area = if (b.right > b.left && b.bottom > b.top) {
                    (b.right - b.left).toLong() * (b.bottom - b.top)
                } else {
                    0L
                }
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount()) {
                node.child(i)?.let { walk(it) }
            }
        }
        walk(root)
        return best
    }

    /** 可见文本/desc/bounds 的轻量指纹（滚动死循环检测 / wait_idle 画面静止检测）。 */
    fun signature(root: UiObject): Int {
        var h = 17
        fun walk(node: UiObject) {
            h = h * 31 + node.text().hashCode() + (node.desc() ?: "").hashCode() * 7 +
                node.bounds().hashCode()
            for (i in 0 until node.childCount()) {
                node.child(i)?.let { walk(it) }
            }
        }
        walk(root)
        return h
    }
}
