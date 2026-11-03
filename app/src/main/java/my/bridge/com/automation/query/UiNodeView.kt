package my.bridge.com.automation.query

import android.graphics.Rect
import my.bridge.com.automation.automator.UiObject

/**
 * 关系查询引擎的「节点字段适配层」（借鉴 Droidline UiNode.kt）。
 *
 * 把 automator 的 [UiObject] 暴露成 Droidline 查询语义需要的纯字段视图：
 * text/id/desc/cls/pkg/bounds + 一组布尔 flag。查询引擎只读这些字段做匹配，
 * 匹配命中的对象仍是 [UiObject]（保留 action 执行能力），避免「快照」与「动作」
 * 脱节。
 *
 * 关键点：UiObject 本身有 parent()/child() 遍历，本视图不复制树结构，只做字段映射。
 */
class UiNodeView(val node: UiObject) {

    val text: String get() = node.text()
    val id: String get() = node.id() ?: ""
    val desc: String get() = node.desc() ?: ""
    val cls: String get() = node.className() ?: ""
    val pkg: String get() = node.packageName() ?: ""

    val bounds: Rect get() = node.bounds()

    val clickable: Boolean get() = node.clickable()
    val longClickable: Boolean get() = node.longClickable()
    val checkable: Boolean get() = node.checkable()
    val checked: Boolean get() = node.checked()
    val enabled: Boolean get() = node.enabled()
    val focused: Boolean get() = node.focused()
    val selected: Boolean get() = node.selected()
    val scrollable: Boolean get() = node.scrollable()
    val editable: Boolean get() = node.editable()
    val password: Boolean get() = node.password()
    val visible: Boolean get() = node.visibleToUser()

    /** 字段布尔 flag 读取（供 Query 的 BOOL_KEYS 统一映射）。 */
    fun flag(key: String): Boolean = when (key) {
        "clickable" -> clickable
        "long_clickable" -> longClickable
        "checkable" -> checkable
        "checked" -> checked
        "enabled" -> enabled
        "focused" -> focused
        "selected" -> selected
        "scrollable" -> scrollable
        "editable" -> editable
        "password" -> password
        "visible" -> visible
        else -> false
    }

    /** 父节点视图（null = 无父）。 */
    fun parent(): UiNodeView? = node.parent()?.let { UiNodeView(it) }

    /** 子节点视图列表。 */
    fun children(): List<UiNodeView> {
        val out = ArrayList<UiNodeView>(node.childCount())
        for (i in 0 until node.childCount()) {
            node.child(i)?.let { out.add(UiNodeView(it)) }
        }
        return out
    }

    /** 前序遍历（文档顺序，nth 计数依据）。 */
    fun walk(visit: (UiNodeView) -> Unit) {
        visit(this)
        for (c in children()) c.walk(visit)
    }

    /** 祖先序列（自父向上）。 */
    fun ancestors(): Sequence<UiNodeView> = generateSequence(parent()) { it.parent() }

    override fun equals(other: Any?): Boolean = other is UiNodeView && other.node === node
    override fun hashCode(): Int = System.identityHashCode(node)
}
