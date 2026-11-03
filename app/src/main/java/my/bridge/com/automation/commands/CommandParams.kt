package my.bridge.com.automation.commands

import com.google.gson.JsonPrimitive
import my.bridge.com.core.network.ws.Command

/**
 * 自动化命令的参数读取扩展（CommandModule 的 strParam/intParam 是其私有扩展，
 * 自动化域命令独立定义一份，语义一致）。
 */
fun Command.strParam(key: String, default: String): String =
    params.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        ?: default

fun Command.intParam(key: String, default: Int, min: Int, max: Int): Int =
    params.get(key)?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(min, max) ?: default

fun Command.longParam(key: String, default: Long, min: Long, max: Long): Long =
    params.get(key)?.takeIf { it.isJsonPrimitive }?.asLong?.coerceIn(min, max) ?: default

fun Command.boolParam(key: String, default: Boolean): Boolean =
    params.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default

fun Command.jsonPrimitive(key: String): JsonPrimitive? =
    params.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
