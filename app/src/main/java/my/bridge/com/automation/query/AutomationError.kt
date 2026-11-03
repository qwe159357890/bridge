package my.bridge.com.automation.query

/**
 * 自动化域结构化错误码（对齐 Droidline CmdError，供 RPC 统一返回）。
 *
 * 约定：凡「元素/动作/状态」类命令，失败时通过 [AutomationResult] 携带
 * errorCode + retryable，让 Python 脚本能程序化区分「可重试」（超时未找到、
 * 点击失败）与「不可重试」（参数非法、无障碍未开、输入法未选）。
 */
enum class AutomationError(val code: String, val retryable: Boolean, val defaultMsg: String) {
    NOT_FOUND("NOT_FOUND", true, "未找到目标"),
    AMBIGUOUS("AMBIGUOUS", false, "多个元素匹配"),
    NOT_CLICKABLE("NOT_CLICKABLE", true, "找到但点击失败"),
    TIMEOUT("TIMEOUT", true, "操作超时"),
    NO_ACCESSIBILITY("NO_ACCESSIBILITY", false, "无障碍服务未开启"),
    NO_IME("NO_IME", false, "输入法未启用/未选中"),
    NO_PERMISSION("NO_PERMISSION", false, "权限缺失"),
    APP_NOT_FOUND("APP_NOT_FOUND", false, "应用未安装"),
    BAD_ARGS("BAD_ARGS", false, "参数无效"),
    UNSUPPORTED("UNSUPPORTED", false, "当前系统版本不支持"),
    UNKNOWN_CMD("UNKNOWN_CMD", false, "未知命令"),
    ;
}

/**
 * 自动化命令的统一结果封装：既承载面向后台的 message 文本，也承载面向脚本的
 * 结构化错误码。success=true 时 errorCode 为 null。
 */
data class AutomationResult(
    val success: Boolean,
    val message: String,
    val data: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false,
) {
    companion object {
        fun ok(message: String, data: String? = null) = AutomationResult(true, message, data)

        fun fail(message: String, error: AutomationError, data: String? = null) =
            AutomationResult(false, message, data, error.code, error.retryable)
    }
}
