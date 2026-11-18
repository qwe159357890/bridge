package my.bridge.com.core.network.ws

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 消息信封 data class。
 *
 * 与后端 `Envelope` 字段一一对应（见 docs/server/design.md 第九节）：
 *   {"type": "hello|ping|pong|close|message", "ts": 毫秒时间戳, "data": {...}}
 *
 * 客户端用于序列化出站消息、反序列化入站消息。
 */
data class WsEnvelope(
    val type: String,
    val ts: Long? = null,
    val data: JsonObject? = null,
) {
    /** 序列化为 JSON 字符串（排除 null 字段）。 */
    fun toJson(gson: Gson): String = gson.toJson(this)

    companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"
        const val TYPE_CLOSE = "close"
        const val TYPE_MESSAGE = "message"
        const val TYPE_COMMAND = "command"
        const val TYPE_COMMAND_RESULT = "command_result"

        /** 构造一条带当前时间戳的出站消息。 */
        fun make(type: String, data: JsonObject? = null): WsEnvelope =
            WsEnvelope(type = type, ts = System.currentTimeMillis(), data = data)

        /** 从 JSON 解析入站消息；解析失败返回 null（由调用方忽略）。 */
        fun fromJson(gson: Gson, raw: String): WsEnvelope? =
            try {
                gson.fromJson(raw, WsEnvelope::class.java)
            } catch (e: Exception) {
                null
            }
    }
}
