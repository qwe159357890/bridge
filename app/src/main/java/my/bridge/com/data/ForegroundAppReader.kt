package my.bridge.com.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import timber.log.Timber

/**
 * 前台应用读取器（get_current_page 命令核心）。
 *
 * 系统限制：Android 5.1 起 `getRunningTasks` 对第三方应用只返回自己的任务，
 * 拿不到别的应用；无障碍服务能到 Activity 级但权限过重。故采用
 * **UsageStatsManager** 方案：
 * - 特殊权限「使用情况访问权」（PACKAGE_USAGE_STATS，AppOp 级），需用户在
 *   设备上开一次（本类会尝试直接弹系统设置页，类似电池白名单引导）；
 * - 从最近事件里取最后一次 `ACTIVITY_RESUMED`（API 29 前同值
 *   MOVE_TO_FOREGROUND，常量在编译期内联，低版本运行时同样可用）；
 * - 事件缺失时兜底 `queryUsageStats` 取 lastTimeUsed 最新的应用。
 * 结果为**应用级**（应用名+包名），拿不到具体 Activity 页面——系统不允许。
 */
object ForegroundAppReader {

    /** 「使用情况访问权」是否已授予（AppOp 检查）。 */
    fun hasUsageAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        Timber.w(e, "检查使用情况访问权失败")
        false
    }

    /** 尝试在设备上打开「使用情况访问权」设置页；被后台启动限制拦截时返回 false。 */
    fun launchUsageAccessSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        Timber.w(e, "打开使用情况访问权设置页失败")
        false
    }

    /**
     * 读取当前前台应用，返回如「当前前台应用：微信（com.tencent.mm）」。
     * 无权限抛 [IllegalStateException]（调用方回执附指引）；找不到事件抛同款。
     */
    fun read(context: Context): String {
        if (!hasUsageAccess(context)) {
            throw IllegalStateException(NOT_GRANTED_MSG)
        }
        val usm = context.getSystemService(UsageStatsManager::class.java)
            ?: throw IllegalStateException("设备使用统计服务不可用")
        val now = System.currentTimeMillis()

        // ① 主路径：最近 10 分钟事件里最后一次「回到前台」
        var pkg: String? = null
        try {
            val events = usm.queryEvents(now - LOOKBACK_MS, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    pkg = e.packageName
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "读取使用事件失败，走 queryUsageStats 兜底")
        }

        // ② 兜底：24 小时用量统计里 lastTimeUsed 最新的应用
        if (pkg == null) {
            try {
                pkg = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    now - FALLBACK_MS,
                    now,
                )?.filter { it.lastTimeUsed > 0 }
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.packageName
            } catch (e: Exception) {
                Timber.w(e, "读取用量统计失败")
            }
        }

        if (pkg.isNullOrBlank()) {
            throw IllegalStateException(
                "最近未采集到前台应用记录（设备可能停留在本应用/锁屏中，或刚开启权限统计尚未生成），请稍后重试",
            )
        }

        val label = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        } catch (e: Exception) {
            pkg
        }
        Timber.i("前台应用：%s（%s）", label, pkg)
        return "当前前台应用：$label（$pkg）"
    }

    /** 未授权时的完整指引（已在设备上弹出设置页则用短版）。 */
    fun notGrantedMessage(settingsLaunched: Boolean): String = buildString {
        append("尚未授予「使用情况访问权」，无法读取前台应用。")
        append(
            if (settingsLaunched) {
                "已在设备上打开设置页，请找到 Bridge 并开启，完成后重新下发本命令"
            } else {
                "请在设备上：设置 → 应用 → 特殊应用权限 → 使用情况访问权 → Bridge，开启后重新下发本命令"
            },
        )
    }

    private const val LOOKBACK_MS = 10 * 60 * 1000L
    private const val FALLBACK_MS = 24 * 60 * 60 * 1000L
    private const val NOT_GRANTED_MSG = "尚未授予「使用情况访问权」"
}
