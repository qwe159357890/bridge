package my.bridge.com.automation.commands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import my.bridge.com.automation.a11y.BridgeAccessibilityService
import my.bridge.com.automation.notification.BridgeNotificationListener
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandResult
import timber.log.Timber

/**
 * 通知子域 + 剪贴板命令执行器（批次 5：Droidline NotificationListener + clipboard）。
 */
object NotificationCommandExecutors {

    private fun notifNotReady(command: Command): CommandResult =
        CommandResult(command.commandId, command.command, false,
            "通知监听未授权：请在「设置 → 通知使用权」中授予 Bridge 权限")

    /** ui_notifications：当前通知列表。 */
    fun notifications(command: Command): CommandResult {
        if (!BridgeNotificationListener.isReady()) return notifNotReady(command)
        val pkg = command.strParam("package", "").takeIf { it.isNotEmpty() }
        return try {
            val list = BridgeNotificationListener.list(pkg)
            val sb = StringBuilder("[")
            list.forEachIndexed { i, sbn ->
                if (i > 0) sb.append(',')
                sb.append(BridgeNotificationListener.toJson(sbn))
            }
            sb.append("]")
            CommandResult(command.commandId, command.command, true, "共 ${list.size} 条通知：$sb")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读取通知失败：${e.message}")
        }
    }

    /** ui_has_notification：是否有匹配通知。 */
    fun hasNotification(command: Command): CommandResult {
        if (!BridgeNotificationListener.isReady()) return notifNotReady(command)
        val by = command.strParam("by", "text")
        val value = command.strParam("value", "")
        if (value.isEmpty()) return CommandResult(command.commandId, command.command, false, "value 不能为空")
        return try {
            val has = BridgeNotificationListener.has(by, value)
            CommandResult(command.commandId, command.command, true, has.toString())
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "查询失败：${e.message}")
        }
    }

    /** ui_notification_reply：内联回复。 */
    fun notificationReply(command: Command): CommandResult {
        if (!BridgeNotificationListener.isReady()) return notifNotReady(command)
        val key = command.strParam("key", "")
        val text = command.strParam("text", "")
        if (key.isEmpty() || text.isEmpty()) {
            return CommandResult(command.commandId, command.command, false, "key/text 不能为空")
        }
        return try {
            BridgeNotificationListener.reply(key, text)
            CommandResult(command.commandId, command.command, true, "已回复通知")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "回复失败：${e.message}")
        }
    }

    /** ui_notification_click：点开通知。 */
    fun notificationClick(command: Command): CommandResult {
        if (!BridgeNotificationListener.isReady()) return notifNotReady(command)
        val key = command.strParam("key", "")
        if (key.isEmpty()) return CommandResult(command.commandId, command.command, false, "key 不能为空")
        return try {
            BridgeNotificationListener.click(key)
            CommandResult(command.commandId, command.command, true, "已点开通知")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "点开失败：${e.message}")
        }
    }

    /** ui_notification_dismiss：清除通知。 */
    fun notificationDismiss(command: Command): CommandResult {
        if (!BridgeNotificationListener.isReady()) return notifNotReady(command)
        val key = command.strParam("key", "")
        if (key.isEmpty()) return CommandResult(command.commandId, command.command, false, "key 不能为空")
        return try {
            BridgeNotificationListener.dismiss(key)
            CommandResult(command.commandId, command.command, true, "已清除通知")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "清除失败：${e.message}")
        }
    }

    /** ui_clipboard：读/写剪贴板。 */
    fun clipboard(context: Context, command: Command): CommandResult {
        val text = command.strParam("text", "")
        return try {
            if (text.isNotEmpty()) {
                // 写
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("bridge", text))
                CommandResult(command.commandId, command.command, true, "已写入剪贴板")
            } else {
                // 读（Android 10+ 需 IME；先试系统，再试 IME）
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val direct = runCatching {
                    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                }.getOrNull()
                if (direct != null) {
                    CommandResult(command.commandId, command.command, true, direct)
                } else {
                    val viaIme = BridgeAccessibilityService.imeReadClipboard(context)
                    CommandResult(command.commandId, command.command, true, viaIme ?: "")
                }
            }
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "剪贴板操作失败：${e.message}")
        }
    }
}
