package my.bridge.com.automation.query

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import my.bridge.com.automation.automator.UiGlobalSelector
import my.bridge.com.automation.automator.UiObject

/**
 * 选择器解析器（从 CommandModule 拆出 + 增强）。
 *
 * 支持两种选择器形式，统一返回 [Target]：
 * 1. **链式 DSL 字符串**：`text("登录")`、`textContains("确定").clickable()`、
 *    `id("btn_submit")`、`className("Button")` 等（autox 风格）。
 * 2. **对象 JSON**：`{"text":"¥99","below":{"text":"加入购物车"}}` 等关系查询
 *    （Droidline Query 风格，支持 row/below/inside/has/bounds）。
 *
 * 选择器参数名统一为 `selector`；元素命令统一带 `nth`（0-based，兼容旧 `index`）。
 */
sealed interface Target {
    /** 在根节点上查找，最多返回 limit 个（文档顺序）。 */
    fun findAll(root: UiObject, limit: Int): List<UiObject>
    fun describe(): String
}

/** 链式 DSL 目标。 */
private class DslTarget(private val selector: UiGlobalSelector, private val desc: String) : Target {
    override fun findAll(root: UiObject, limit: Int): List<UiObject> =
        selector.findAndReturnList(root, limit)
    override fun describe(): String = desc
}

/** 对象 JSON（关系查询）目标。 */
private class QueryTarget(private val query: Query) : Target {
    override fun findAll(root: UiObject, limit: Int): List<UiObject> {
        val view = UiNodeView(root)
        val out = query.findAll(listOf(view)).map { it.node }
        return if (out.size <= limit) out else out.subList(0, limit)
    }
    override fun describe(): String = query.describe()
}

object SelectorParser {

    /**
     * 解析 selector 参数为 [Target]。
     *
     * @param expr selector 字符串：以 `{` 开头视为对象 JSON（关系查询），否则按链式 DSL。
     * @throws IllegalArgumentException 解析失败（命令层转 BAD_ARGS）
     */
    fun parse(expr: String): Target {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("selector 不能为空")
        if (trimmed.startsWith("{")) return parseQuery(trimmed)
        return parseDsl(trimmed)
    }

    /** 对象 JSON → 关系查询。 */
    private fun parseQuery(json: String): Target {
        val el: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("selector JSON 解析失败：${e.message}")
        }
        return QueryTarget(Query.parse(el))
    }

    /** 链式 DSL → UiGlobalSelector（增强：id 裸名匹配由 Query.matchField 语义补齐）。 */
    private fun parseDsl(expr: String): Target {
        val s = UiGlobalSelector()
        val regex = Regex("""(\w+)\s*\(([^)]*)\)""")
        var applied = false
        for (m in regex.findAll(expr)) {
            val name = m.groupValues[1]
            val raw = m.groupValues[2].trim()
            val arg = raw.trim('"', '\'', ' ')
            fun boolArg(): Boolean = arg.isEmpty() || arg.equals("true", true)
            when (name) {
                "text" -> s.text(arg)
                "textContains" -> s.textContains(arg)
                "textStartsWith" -> s.textStartsWith(arg)
                "textEndsWith" -> s.textEndsWith(arg)
                "textMatches" -> s.textMatches(arg)
                "id" -> s.id(arg)
                "idContains" -> s.idContains(arg)
                "idStartsWith" -> s.idStartsWith(arg)
                "idEndsWith" -> s.idEndsWith(arg)
                "idMatches" -> s.idMatches(arg)
                "desc" -> s.desc(arg)
                "descContains" -> s.descContains(arg)
                "descStartsWith" -> s.descStartsWith(arg)
                "descEndsWith" -> s.descEndsWith(arg)
                "descMatches" -> s.descMatches(arg)
                "className" -> s.className(arg)
                "classNameContains" -> s.classNameContains(arg)
                "classNameStartsWith" -> s.classNameStartsWith(arg)
                "classNameEndsWith" -> s.classNameEndsWith(arg)
                "classNameMatches" -> s.classNameMatches(arg)
                "packageName" -> s.packageName(arg)
                "packageNameContains" -> s.packageNameContains(arg)
                "clickable" -> s.clickable(boolArg())
                "longClickable" -> s.longClickable(boolArg())
                "enabled" -> s.enabled(boolArg())
                "scrollable" -> s.scrollable(boolArg())
                "editable" -> s.editable(boolArg())
                "checkable" -> s.checkable(boolArg())
                "checked" -> s.checked(boolArg())
                "focusable" -> s.focusable(boolArg())
                "focused" -> s.focused(boolArg())
                "selected" -> s.selected(boolArg())
                "visibleToUser" -> s.visibleToUser(boolArg())
                "password" -> s.password(boolArg())
                "depth" -> s.depth(arg.toIntOrNull() ?: 0)
                else -> throw IllegalArgumentException("不支持的选择器方法：$name")
            }
            applied = true
        }
        if (!applied) throw IllegalArgumentException("选择器解析失败：$expr")
        return DslTarget(s, expr)
    }
}
