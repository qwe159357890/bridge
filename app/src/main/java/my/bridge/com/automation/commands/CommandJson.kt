package my.bridge.com.automation.commands

import my.bridge.com.automation.automator.UiObject

/**
 * 自动化命令的共享工具：节点 JSON 序列化 + 文本转义（从 CommandModule 拆出，
 * 供所有自动化域命令复用）。
 */
object CommandJson {

    /** 把 UiObject 转成 JSON 描述（供回执展示），depth 传 -1 表示未知。 */
    fun nodeToJson(n: UiObject, depth: Int = -1): String {
        val b = n.bounds()
        val bp = n.boundsInParent()
        return buildString {
            append("{")
            append("\"text\":").append(jsonStr(n.text()))
            append(",\"desc\":").append(jsonStr(n.desc() ?: ""))
            append(",\"id\":").append(jsonStr(n.id() ?: ""))
            append(",\"className\":").append(jsonStr(n.className() ?: ""))
            append(",\"package\":").append(jsonStr(n.packageName() ?: ""))
            append(",\"bounds\":[").append(b.left).append(',').append(b.top)
                .append(',').append(b.right).append(',').append(b.bottom).append("]")
            append(",\"boundsInParent\":[").append(bp.left).append(',').append(bp.top)
                .append(',').append(bp.right).append(',').append(bp.bottom).append("]")
            append(",\"clickable\":").append(n.clickable())
            append(",\"longClickable\":").append(n.longClickable())
            append(",\"checkable\":").append(n.checkable())
            append(",\"checked\":").append(n.checked())
            append(",\"focusable\":").append(n.focusable())
            append(",\"focused\":").append(n.focused())
            append(",\"selected\":").append(n.selected())
            append(",\"enabled\":").append(n.enabled())
            append(",\"editable\":").append(n.editable())
            append(",\"scrollable\":").append(n.scrollable())
            append(",\"visibleToUser\":").append(n.visibleToUser())
            append(",\"password\":").append(n.password())
            append(",\"childCount\":").append(n.childCount())
            append(",\"depth\":").append(depth)
            append(",\"row\":").append(n.row())
            append(",\"column\":").append(n.column())
            append(",\"rowCount\":").append(n.rowCount())
            append(",\"columnCount\":").append(n.columnCount())
            append(",\"error\":").append(jsonStr(n.error?.toString() ?: ""))
            append(",\"hintText\":").append(jsonStr(n.hintText?.toString() ?: ""))
            append("}")
        }
    }

    /** JSON 字符串转义。 */
    fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /** 简单值 JSON 转义（供回执拼接）。 */
    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> jsonStr(v.toString())
    }
}
