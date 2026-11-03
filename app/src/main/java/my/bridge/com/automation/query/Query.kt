package my.bridge.com.automation.query

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.hypot

/**
 * 关系查询引擎（移植 Droidline Query.kt，对象形式选择器）。
 *
 * 支持「对象形式」选择器：多个字段/布尔/关系条件对一个元素 AND 组合，弥补
 * 链式 UiGlobalSelector 无法表达「结构化关系」的短板。关系条件：
 *   has      —— 匹配某后代
 *   inside   —— 匹配某祖先
 *   row      —— 与某目标同属一个列表项
 *   below/above/left_of/right_of —— 相对某锚点的方向关系（结果按距离排序）
 *   bounds   —— 精确 [l,t,r,b] 匹配
 *
 * 输入为 JSON 对象（Gson JsonObject），由命令层把 selector 参数（JSON 字符串）
 * 解析后传入。嵌套深度上限 MAX_DEPTH=8。
 */
class Query private constructor(
    private val source: JsonObject,
    private val conds: List<Cond>,
) {

    private sealed interface Cond
    private class Field(val key: String, val value: String, val regex: Regex?) : Cond
    private class Flag(val key: String, val value: Boolean) : Cond
    private class Exact(val l: Int, val t: Int, val r: Int, val b: Int) : Cond
    private class Relation(val key: String, val sub: Query) : Cond

    /** 在所有根节点上查找匹配（文档顺序；若含方向关系，按与锚点距离升序）。 */
    fun findAll(roots: List<UiNodeView>): List<UiNodeView> {
        val anchors = anchorsFor(roots)
        // 方向关系的锚点未找到 → 整体无匹配
        for (c in conds) {
            if (c is Relation && c.key in DIRECTIONS && anchors[c.key] == null) return emptyList()
        }
        val out = ArrayList<UiNodeView>()
        for (r in roots) r.walk { if (matches(roots, it, anchors)) out += it }
        val firstDir = conds.firstOrNull { it is Relation && it.key in DIRECTIONS } as Relation?
        val anchor = firstDir?.let { anchors[it.key] } ?: return out
        return out.sortedBy { dist(it, anchor) }
    }

    fun describe(): String = source.toString()

    /** 解析方向关系的锚点（每类方向取子查询第一个匹配）。 */
    private fun anchorsFor(roots: List<UiNodeView>): Map<String, UiNodeView> {
        val anchors = HashMap<String, UiNodeView>()
        for (c in conds) {
            if (c is Relation && c.key in DIRECTIONS) {
                c.sub.findAll(roots).firstOrNull()?.let { anchors[c.key] = it }
            }
        }
        return anchors
    }

    private fun matches(roots: List<UiNodeView>, n: UiNodeView, anchors: Map<String, UiNodeView>): Boolean =
        conds.all { c ->
            when (c) {
                is Field -> matchField(c.key, c.value, n, c.regex)
                is Flag -> n.flag(c.key) == c.value
                is Exact -> n.bounds.left == c.l && n.bounds.top == c.t &&
                    n.bounds.right == c.r && n.bounds.bottom == c.b
                is Relation -> when (c.key) {
                    "has" -> subtreeHas(roots, n, self = false, sub = c.sub)
                    "row" -> subtreeHas(roots, rowOf(n), self = true, sub = c.sub)
                    "inside" -> {
                        val sa = c.sub.anchorsFor(roots)
                        n.ancestors().any { c.sub.matches(roots, it, sa) }
                    }
                    else -> {
                        val a = anchors[c.key]
                        if (a == null || n.bounds.isEmpty || n === a) {
                            false
                        } else {
                            when (c.key) {
                                "below" -> centerY(n) > a.bounds.bottom
                                "above" -> centerY(n) < a.bounds.top
                                "left_of" -> centerX(n) < a.bounds.left
                                else -> centerX(n) > a.bounds.right
                            }
                        }
                    }
                }
            }
        }

    private fun subtreeHas(roots: List<UiNodeView>, root: UiNodeView, self: Boolean, sub: Query): Boolean {
        val anchors = sub.anchorsFor(roots)
        var found = false
        root.walk { if (!found && (self || it !== root)) found = sub.matches(roots, it, anchors) }
        return found
    }

    companion object {
        val STRING_KEYS = listOf(
            "text", "textContains", "textMatches", "id", "desc",
            "descContains", "descMatches", "class", "package",
        )
        val BOOL_KEYS = listOf(
            "clickable", "long_clickable", "checkable", "checked", "enabled",
            "focused", "selected", "scrollable", "editable", "password", "visible",
        )
        val RELATIONS = listOf("has", "inside", "row", "below", "above", "left_of", "right_of")
        val DIRECTIONS = listOf("below", "above", "left_of", "right_of")
        private const val MAX_DEPTH = 8

        /**
         * 解析 JSON 对象为 Query。参数非法抛 IllegalArgumentException（命令层转 BAD_ARGS）。
         * @param o Gson JsonElement（JsonObject）或 JSON 字符串，已由命令层解析。
         */
        fun parse(o: JsonElement, depth: Int = 0): Query {
            require(depth <= MAX_DEPTH) { "query is nested more than $MAX_DEPTH levels" }
            require(o.isJsonObject) { "a query must be an object" }
            val obj = o.asJsonObject
            require(obj.entrySet().isNotEmpty()) { "a query needs at least one condition" }
            val conds = ArrayList<Cond>()
            for ((k, v) in obj.entrySet()) {
                when {
                    k in STRING_KEYS -> {
                        require(v.isJsonPrimitive && v.asJsonPrimitive.isString) { "$k must be a string" }
                        val value = v.asString
                        val regex = if (k.endsWith("Matches")) {
                            try {
                                Regex(value)
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException("$k is not a valid regular expression: ${e.message}")
                            }
                        } else {
                            null
                        }
                        conds += Field(k, value, regex)
                    }
                    k in BOOL_KEYS -> {
                        require(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) { "$k must be true or false" }
                        conds += Flag(k, v.asBoolean)
                    }
                    k == "bounds" -> {
                        require(v.isJsonArray && v.asJsonArray.size() == 4 &&
                            (0 until 4).all { v.asJsonArray[it].isJsonPrimitive && v.asJsonArray[it].asJsonPrimitive.isNumber }
                        ) { "bounds must be [left, top, right, bottom]" }
                        val a = v.asJsonArray
                        conds += Exact(a[0].asInt, a[1].asInt, a[2].asInt, a[3].asInt)
                    }
                    k in RELATIONS -> {
                        val sub = try {
                            parse(v, depth + 1)
                        } catch (e: IllegalArgumentException) {
                            throw IllegalArgumentException("$k: ${e.message}")
                        }
                        conds += Relation(k, sub)
                    }
                    else -> throw IllegalArgumentException("unknown query key \"$k\"")
                }
            }
            return Query(obj, conds)
        }

        /** 字段匹配（id 支持裸名匹配：不含 ':' 时按 ":id/$value" 后缀匹配）。 */
        private fun matchField(by: String, value: String, n: UiNodeView, regex: Regex?): Boolean = when (by) {
            "text" -> n.text == value
            "textContains" -> n.text.contains(value)
            "textMatches" -> regex?.matches(n.text) == true
            "id" -> n.id == value || (!value.contains(':') && n.id.endsWith(":id/$value"))
            "desc" -> n.desc == value
            "descContains" -> n.desc.contains(value)
            "descMatches" -> regex?.matches(n.desc) == true
            "class" -> n.cls == value
            "package" -> n.pkg == value
            else -> false
        }

        /** 包含 n 的列表项；不在列表内则取最近可点击祖先，再退化为父节点。 */
        fun rowOf(n: UiNodeView): UiNodeView {
            var a: UiNodeView = n
            while (true) {
                val p = a.parent() ?: break
                if (isList(p)) return a
                a = p
            }
            return n.ancestors().firstOrNull { it.clickable } ?: n.parent() ?: n
        }

        /** 列表控件，或可滚动且有多个子节点的容器。 */
        private fun isList(n: UiNodeView): Boolean =
            listOf("RecyclerView", "ListView", "GridView").any { n.cls.endsWith(it) } ||
                (n.scrollable && n.children().size > 1)

        private fun centerX(n: UiNodeView) = (n.bounds.left + n.bounds.right) / 2.0
        private fun centerY(n: UiNodeView) = (n.bounds.top + n.bounds.bottom) / 2.0
        private fun dist(n: UiNodeView, a: UiNodeView) = hypot(centerX(n) - centerX(a), centerY(n) - centerY(a))
    }
}
