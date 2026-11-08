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
        const val CLEAR_LOG = "clear_log"
        const val PLAY_MUSIC = "play_music"
        const val PAUSE_MUSIC = "pause_music"
        const val RESUME_MUSIC = "resume_music"
        const val STOP_MUSIC = "stop_music"
        const val GET_LOCATION = "get_location"
        const val GET_CURRENT_PAGE = "get_current_page"
        const val GET_NETWORK = "get_network"
        const val GET_BATTERY = "get_battery"
        const val GET_DEVICE_STATS = "get_device_stats"
        const val GET_CONTACTS = "get_contacts"
        const val GET_SMS = "get_sms"
        const val GET_CALL_LOG = "get_call_log"
        const val GET_SIM_INFO = "get_sim_info"
        const val SEND_SMS = "send_sms"
        const val DIAL_PHONE = "dial_phone"
        const val MARK_SMS_READ = "mark_sms_read"
        const val SET_VOLUME = "set_volume"
        const val GET_VOLUME = "get_volume"
        const val STREAM_STOP = "stream_stop"
        const val LOCK_SCREEN = "lock_screen"
        const val RESTART_APP = "restart_app"
        const val SCREENSHOT = "screenshot"
        const val RECORD_SCREEN = "record_screen"
        const val SCREEN_LIVE = "screen_live"
        const val TAKE_PHOTO = "take_photo"
        const val RECORD_VIDEO = "record_video"
        const val RECORD_AUDIO = "record_audio"
        const val CLEAR_MEDIA = "clear_media"
        const val SHOW_TOAST = "show_toast"
        const val SPEAK = "speak"
        const val VIBRATE = "vibrate"
        const val SHOW_DIALOG = "show_dialog"
        const val SEND_NOTIFICATION = "send_notification"
        const val CHECK_UPDATE = "check_update"
        // ---- VNT 组网（v0.2.30）----
        const val VNT_START = "vnt_start"
        const val VNT_STOP = "vnt_stop"
        const val VNT_STATUS = "vnt_status"
        // ---- SSH 服务器（v0.2.34：内嵌 sshd，SFTP 文件管理 + 远程终端）----
        const val SSH_START = "ssh_start"
        const val SSH_STOP = "ssh_stop"
        const val SSH_STATUS = "ssh_status"
        // ---- 无障碍服务（v0.2.51：开启/关闭/检查状态，复用 BridgeAccessibilityService）----
        const val ACCESSIBILITY_ENABLE = "accessibility_enable"
        const val ACCESSIBILITY_DISABLE = "accessibility_disable"
        const val ACCESSIBILITY_STATUS = "accessibility_status"
        // ---- 微信离开即释放开关（v0.2.51：管理后台控制，关闭时按当前状态决定是否立即释放）----
        const val WECHAT_RELEASE_ENABLE = "wechat_release_enable"
        const val WECHAT_RELEASE_DISABLE = "wechat_release_disable"
        const val WECHAT_RELEASE_STATUS = "wechat_release_status"
        // ---- 无界面模式（v0.2.51：儿童锁 / 防误触，全屏遮挡层，仅后台可解除）----
        const val UI_LOCK_START = "ui_lock_start"
        const val UI_LOCK_STOP = "ui_lock_stop"
        const val UI_LOCK_STATUS = "ui_lock_status"
        // ---- 界面自动化（v0.2.39：无障碍控件读取/操作，对标 autox）----
        const val UI_DUMP = "ui_dump"
        const val UI_FIND = "ui_find"
        const val UI_CLICK = "ui_click"
        const val UI_SET_TEXT = "ui_set_text"
        const val UI_GESTURE = "ui_gesture"
        const val UI_BACK = "ui_back"
        const val UI_HOME = "ui_home"
        // ---- 界面自动化增强（v0.2.40：Droidline UiCommands 全收 + AutoX 补缺）----
        const val UI_LONG_CLICK = "ui_long_click"
        const val UI_SCROLL_TO = "ui_scroll_to"
        const val UI_WAIT_IDLE = "ui_wait_idle"
        const val UI_WAIT_GONE = "ui_wait_gone"
        const val UI_CLEAR = "ui_clear"
        const val UI_SENDKEY = "ui_sendkey"
        const val UI_EXISTS = "ui_exists"
        const val UI_GET_TEXT = "ui_get_text"
        const val UI_CHECKED = "ui_checked"
        const val UI_ENABLED = "ui_enabled"
        const val UI_SELECTED = "ui_selected"
        const val UI_COUNT = "ui_count"
        const val UI_WHICH = "ui_which"
        const val UI_IN_APP = "ui_in_app"
        const val UI_KEYBOARD_SHOWN = "ui_keyboard_shown"
        const val UI_LAST_TOAST = "ui_last_toast"
        const val UI_COLOR = "ui_color"
        const val UI_RECENTS = "ui_recents"
        const val UI_OPEN_NOTIFICATIONS = "ui_open_notifications"
        const val UI_QUICK_SETTINGS = "ui_quick_settings"
        // ---- 设备 + 应用命令（v0.2.40）----
        const val UI_LAUNCH = "ui_launch"
        const val UI_OPEN_URL = "ui_open_url"
        const val UI_INTENT = "ui_intent"
        const val UI_APPS = "ui_apps"
        const val UI_INSTALLED = "ui_installed"
        const val UI_SCREEN_ON = "ui_screen_on"
        const val UI_LOCKED = "ui_locked"
        const val UI_WAKE = "ui_wake"
        const val UI_ORIENTATION = "ui_orientation"
        const val UI_INFO = "ui_info"
        const val UI_LAUNCH_APP = "ui_launch_app"
        const val UI_PACKAGE_NAME = "ui_package_name"
        const val UI_APP_NAME = "ui_app_name"
        const val UI_OPEN_APP_SETTING = "ui_open_app_setting"
        const val UI_SET_BRIGHTNESS = "ui_set_brightness"
        const val UI_GET_BRIGHTNESS = "ui_get_brightness"
        const val UI_GET_MEM = "ui_get_mem"
        const val UI_IS_CHARGING = "ui_is_charging"
        // ---- 图像找色 + 传感器（v0.2.40）----
        const val UI_FIND_COLOR = "ui_find_color"
        const val UI_FIND_MULTI_COLORS = "ui_find_multi_colors"
        const val UI_SENSORS = "ui_sensors"
        // ---- 文件 + 媒体（v0.2.40）----
        const val UI_FILE_READ = "ui_file_read"
        const val UI_FILE_WRITE = "ui_file_write"
        const val UI_FILE_APPEND = "ui_file_append"
        const val UI_FILE_EXISTS = "ui_file_exists"
        const val UI_FILE_LIST = "ui_file_list"
        const val UI_FILE_COPY = "ui_file_copy"
        const val UI_FILE_MOVE = "ui_file_move"
        const val UI_FILE_REMOVE = "ui_file_remove"
        const val UI_MEDIA_SEEK = "ui_media_seek"
        const val UI_MEDIA_VOLUME = "ui_media_volume"
        const val UI_MEDIA_POSITION = "ui_media_position"
        const val UI_MEDIA_DURATION = "ui_media_duration"
        const val UI_MEDIA_STATE = "ui_media_state"
        // ---- 通知子域 + 剪贴板（v0.2.40）----
        const val UI_NOTIFICATIONS = "ui_notifications"
        const val UI_HAS_NOTIFICATION = "ui_has_notification"
        const val UI_NOTIFICATION_REPLY = "ui_notification_reply"
        const val UI_NOTIFICATION_CLICK = "ui_notification_click"
        const val UI_NOTIFICATION_DISMISS = "ui_notification_dismiss"
        const val UI_CLIPBOARD = "ui_clipboard"
        // ---- Python RPC（v0.2.39：手机内嵌 HTTP/REST 服务，供 Python 脚本驱动）----
        const val RPC_START = "rpc_start"
        const val RPC_STOP = "rpc_stop"
        const val RPC_STATUS = "rpc_status"
        // ---- 极空 BMS 保护板（v0.2.55：BLE 连接 + 状态上报 + 远程控制）----
        const val JIKONG_SCAN = "jikong_scan"
        const val JIKONG_CONNECT = "jikong_connect"
        const val JIKONG_DISCONNECT = "jikong_disconnect"
        const val JIKONG_STATUS = "jikong_status"
        const val JIKONG_MOS = "jikong_mos"
        const val JIKONG_BALANCE = "jikong_balance"
        const val JIKONG_WRITE_PARAM = "jikong_write_param"
        // ---- v0.2.102：管理后台↔手机同步（远程播报/主题/播报设置） ----
        const val JIKONG_SPEAK = "jikong_speak"                 // 远程触发仪表盘播报
        const val JIKONG_SET_THEME = "jikong_set_theme"         // 同步背景主题（mode 0/1/2）
        const val JIKONG_SET_SPEAK = "jikong_set_speak"         // 同步小喇叭/播报设置

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
