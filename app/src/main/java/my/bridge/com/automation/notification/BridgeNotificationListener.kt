package my.bridge.com.automation.notification

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber

/**
 * 通知监听服务（批次 5，借鉴 Droidline DroidNotificationListener）。
 *
 * 提供通知域能力：list / has / reply / click / dismiss。需用户手动授权
 * 「通知使用权」，Manifest 注册 BIND_NOTIFICATION_LISTENER_SERVICE。
 */
class BridgeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Timber.i("Bridge 通知监听已连接")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Timber.w("Bridge 通知监听已断开")
    }

    companion object {
        @Volatile
        private var instance: BridgeNotificationListener? = null

        fun isReady(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun component(context: Context): ComponentName =
            ComponentName(context, BridgeNotificationListener::class.java)

        private fun svc(): BridgeNotificationListener = instance
            ?: throw IllegalStateException("通知监听未授权")

        private fun active(): List<StatusBarNotification> =
            runCatching { svc().activeNotifications?.toList() }.getOrNull().orEmpty()

        /** 单条通知转 JSON。 */
        fun toJson(sbn: StatusBarNotification): String {
            val ex = sbn.notification.extras
            val text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()
            val actions = sbn.notification.actions.orEmpty().map { a ->
                if (a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || !a.remoteInputs.isNullOrEmpty()) "reply"
                else a.title?.toString().orEmpty()
            }
            val sb = StringBuilder()
            sb.append("{\"key\":").append(jsonStr(sbn.key))
            sb.append(",\"package\":").append(jsonStr(sbn.packageName))
            sb.append(",\"title\":").append(jsonStr(ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()))
            sb.append(",\"text\":").append(jsonStr(text?.toString().orEmpty()))
            sb.append(",\"lines\":[")
            lines.forEachIndexed { i, l -> if (i > 0) sb.append(','); sb.append(jsonStr(l)) }
            sb.append("]")
            sb.append(",\"time\":").append(sbn.postTime)
            sb.append(",\"actions\":[")
            actions.forEachIndexed { i, a -> if (i > 0) sb.append(','); sb.append(jsonStr(a)) }
            sb.append("]")
            sb.append("}")
            return sb.toString()
        }

        private fun jsonStr(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            return sb.append("\"").toString()
        }

        /** 通知列表（可选按 package 过滤）。 */
        fun list(pkg: String?): List<StatusBarNotification> =
            active().filter { pkg == null || it.packageName == pkg }

        /** 是否有匹配通知（by: text/textContains/title/package）。 */
        fun has(by: String, value: String): Boolean = active().any { sbn ->
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            when (by) {
                "text" -> title == value || text == value
                "textContains" -> title.contains(value) || text.contains(value)
                "title" -> title == value
                "package" -> sbn.packageName == value
                else -> false
            }
        }

        private fun byKey(key: String): StatusBarNotification =
            active().firstOrNull { it.key == key }
                ?: throw IllegalStateException("未找到通知 key=$key")

        /** 内联回复。 */
        fun reply(key: String, text: String) {
            val sbn = byKey(key)
            val action = sbn.notification.actions.orEmpty().firstOrNull { a ->
                a.remoteInputs.orEmpty().any { it.allowFreeFormInput }
            } ?: throw IllegalStateException("该通知无回复动作")
            val intent = Intent()
            val results = Bundle()
            for (ri in action.remoteInputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            send(action.actionIntent, intent)
        }

        /** 点开通知。 */
        fun click(key: String) {
            val sbn = byKey(key)
            val pi = sbn.notification.contentIntent
                ?: throw IllegalStateException("该通知不可点开")
            send(pi, null)
        }

        /** 清除通知。 */
        fun dismiss(key: String) {
            byKey(key)
            svc().cancelNotification(key)
        }

        private fun send(pi: PendingIntent, fill: Intent?) {
            try {
                val options = if (Build.VERSION.SDK_INT >= 34) {
                    val mode = if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    } else {
                        @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
                    ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
                } else {
                    null
                }
                pi.send(svc(), 0, fill, null, null, null, options)
            } catch (e: PendingIntent.CanceledException) {
                throw IllegalStateException("应用取消了该动作")
            }
        }
    }
}
