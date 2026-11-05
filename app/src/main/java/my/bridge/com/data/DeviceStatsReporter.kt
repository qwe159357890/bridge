package my.bridge.com.data

import android.app.usage.UsageStatsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.net.TrafficStats
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 设备统计读取器（get_device_stats 命令核心）。
 *
 * 聚合「电量 + 活跃时长 + 流量」三类信息，**全部下发时读一次，零持续后台开销**：
 * - 电量：复用 [BatteryReporter]（粘性广播零权限）。
 * - 活跃时长：`UsageStatsManager.queryUsageStats` 系统已归档的
 *   `totalTimeInForeground` / `totalTimeVisible`，直读不计算；需「使用情况访问权」
 *   （复用 [ForegroundAppReader.hasUsageAccess] 探测，未授权回执提示、不阻断其它项）。
 * - 流量（开机累计，不分接口）：`TrafficStats` 零权限精确到字节（真·断电清零的开机累计；
 *   系统不提供「分接口的开机以来流量」，分接口口径由「本月」表达）。
 * - 流量（本月 + 分 WiFi/移动）：`NetworkStatsManager` 系统按自然月归档，直读；
 *   需「使用情况访问权」，未授权回执提示。
 *
 * 明确不做「APP 耗电 mAh」：Android 无第三方可用的官方 API（需 shell/dumpsys batterystats），
 * 以「活跃时长」作活跃度参考替代。
 */
object DeviceStatsReporter {

    /** 读取设备统计，返回人类可读回执；电量拿不到广播时抛异常。 */
    fun read(context: Context): String {
        val battery = BatteryReporter.read(context)
        val active = activeTimeText(context)
        val appTraffic = appTrafficText(context)
        val monthTraffic = monthTrafficText(context)
        val totalTraffic = totalTrafficText()

        return buildString {
            append("【电量】").append(battery)
            if (active.isNotBlank()) append("｜").append(active)
            append('\n')
            append("【本 App 流量（开机累计）】").append(appTraffic)
            append('\n')
            append("【本 App 流量（本月）】").append(monthTraffic)
            append('\n')
            append("【整机流量（开机累计）】").append(totalTraffic)
        }
    }

    /** 本 App 活跃时长（前后台 + 可见），未授权返回空串。 */
    private fun activeTimeText(context: Context): String {
        if (!ForegroundAppReader.hasUsageAccess(context)) return ""
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return ""
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - ACTIVE_LOOKBACK_MS,
                now,
            )?.firstOrNull { it.packageName == context.packageName } ?: return ""
            val fg = stats.totalTimeInForeground
            val visible = stats.totalTimeVisible
            val sb = StringBuilder("本 App 活跃时长：")
            sb.append("前台 ").append(formatMs(fg))
            if (visible >= 0) sb.append("／可见 ").append(formatMs(visible))
            sb.append("（非耗电值，仅活跃度参考）")
            sb.toString()
        } catch (e: Exception) {
            Timber.w(e, "读取活跃时长失败")
            ""
        }
    }

    /**
     * 本 App 开机累计流量（真·开机累计，零权限）。
     *
     * ★ v0.2.54 修复：改前用 NetworkStatsManager.querySummary 回看 365 天冒充「开机累计」，
     * 导致数值 = 近一年流量（用户昨天开机却显示 13GB），且分接口把 UID_ALL 整机也混入。
     * 系统并不提供「分 WiFi/移动 的开机以来流量」（NetworkStatsManager 按自然日归档、
     * 无「本次开机」时间锚点）。故「开机累计」只用 TrafficStats（断电清零的真开机累计，
     * 不分接口）；分接口的口径交给「本月」去表达。
     */
    private fun appTrafficText(context: Context): String {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val total = if (rx >= 0 && tx >= 0) rx + tx else -1L
        return buildString {
            append("上行 ").append(formatBytes(tx))
            append("／下行 ").append(formatBytes(rx))
            if (total >= 0) append("｜合计 ").append(formatBytes(total))
            append("（本次开机累计，不分接口）")
        }
    }

    /** 本 App 本月流量（分 WiFi/移动，系统按自然月归档；需使用情况访问权）。 */
    private fun monthTrafficText(context: Context): String {
        if (!ForegroundAppReader.hasUsageAccess(context)) {
            return "需「使用情况访问权」才能统计本月流量（未授权）"
        }
        val uid = Process.myUid()
        val byIf = uidTrafficByInterface(context, uid)
        if (byIf == null) return "本月流量统计失败（设备网络统计服务异常）"
        val wifi = byIf.first
        val mobile = byIf.second
        return buildString {
            append("WiFi ").append(formatBytes(wifi))
            append("｜移动 ").append(formatBytes(mobile))
            append("（自然月累计）")
        }
    }

    /** 整机开机累计流量（零权限）：移动总量 + WiFi 总量（= 总 - 移动）。 */
    private fun totalTrafficText(): String {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()
        val mobile = if (mobileRx >= 0 && mobileTx >= 0) mobileRx + mobileTx else -1L
        val wifi = if (totalRx >= 0 && totalTx >= 0 && mobile >= 0) (totalRx + totalTx) - mobile else -1L
        return buildString {
            append("移动 ").append(formatBytes(mobile))
            append("｜WiFi ").append(formatBytes(wifi))
            append("（本次开机累计）")
        }
    }

    /**
     * 按 uid 分接口统计「本月」流量（NetworkStatsManager，需使用情况访问权）。
     * @return Pair(wifi, mobile)，某类拿不到时为 -1；整体失败返回 null。
     */
    private fun uidTrafficByInterface(
        context: Context,
        uid: Int,
    ): Pair<Long, Long>? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
                ?: return null
            val wifi = sumUidForNetwork(nsm, uid, ConnectivityManager.TYPE_WIFI)
            val mobile = sumUidForNetwork(nsm, uid, ConnectivityManager.TYPE_MOBILE)
            wifi to mobile
        } catch (e: Exception) {
            Timber.w(e, "按接口统计流量失败（可能未授权使用情况访问权）")
            null
        }
    }

    /**
     * 汇总某 uid 在指定网络类型下「本月」流量（-1 表示拿不到/无数据）。
     * 用 querySummary（返回按 uid 聚合的 NetworkStats，遍历其 Bucket）。
     *
     * ★ v0.2.54 修复：改前 `if (bUid == uid || bUid == UID_ALL)` 会把 UID_ALL（整机）也
     * 累加进「本 App」，导致数值虚高；现已只认 `bUid == uid`。
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun sumUidForNetwork(
        nsm: NetworkStatsManager,
        uid: Int,
        networkType: Int,
    ): Long {
        val now = System.currentTimeMillis()
        val start = monthStartMs(now)
        return try {
            val stats = nsm.querySummary(networkType, null, start, now)
            val bucket = android.app.usage.NetworkStats.Bucket()
            var sum = 0L
            var hasData = false
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                if (bucket.uid == uid) {
                    sum += bucket.rxBytes + bucket.txBytes
                    hasData = true
                }
            }
            stats.close()
            if (hasData) sum else -1L
        } catch (e: Exception) {
            Timber.w(e, "querySummary 失败（networkType=%d）", networkType)
            -1L
        }
    }

    /** 本月起始毫秒时间戳（1 号 0 点）。 */
    private fun monthStartMs(now: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun formatMs(ms: Long): String = when {
        ms < 0 -> "未知"
        ms < 60_000 -> "${ms / 1000}秒"
        ms < 3_600_000 -> "${ms / 60_000}分${ms % 60_000 / 1000}秒"
        else -> "${ms / 3_600_000}小时${ms % 3_600_000 / 60_000}分"
    }

    private const val ACTIVE_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000 // 活跃时长回看 7 天
}
