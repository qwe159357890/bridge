package my.bridge.com.blekey

import android.content.Context
import android.os.Build
import android.os.PowerManager
import my.bridge.com.core.common.DiagLog
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 蓝牙钥匙「健康台账」（跨进程持久 · v0.3.008）。
 *
 * ## 为什么需要它
 *
 * 验收单有 50 项，靠人手逐项点不现实。真实用法是：**挂着跑几天 → 上传诊断日志 → 看结论**。
 * 但日志是「事件流」，翻 5000 行找规律成本高，且「没打日志」与「正常」无法区分
 * （巡检成功时几乎不留痕）。所以这里维护一份**累计计数**：
 *
 * - 服务创建次数 → 判断是否被 ROM 反复杀；
 * - 巡检次数 / 最后巡检时刻 → 判断状态机是否还在跑；
 * - 各档位轮数 + 累计广播毫秒 → 判断「到底有没有在播、播了多久」；
 * - 广播失败次数 → 唯一需要关注的红色指标；
 * - 上次停机间隔 → 服务重建时算出的断档时长，判断被杀还是正常重启。
 *
 * 落盘开销：每 60 秒一次 `apply()`（异步、几字节），可忽略。
 * 对外只暴露两件事：详情页「运行概况」卡片（[snapshot]）、上传日志前插入的一行摘要（[appendSummary]）。
 *
 * 设计文档：docs/research/ble-key-broadcast.md §13「运行观测」。
 */
object BleKeyHealth {

    private const val TAG = "BleKeyHealth"

    /** 日志/上传里这行摘要的 tag（在后台按 tag 过滤即可）。 */
    const val SUMMARY_TAG = "BleKeySummary"

    private const val SP_NAME = "blekey_health"

    // ---- 累计计数 ----
    private const val K_SERVICE_CREATES = "service_creates"
    private const val K_WATCHDOG_RUNS = "watchdog_runs"
    private const val K_ADV_FAILS = "adv_fails"
    private const val K_LAST_FAIL_REASON = "last_fail_reason"
    private const val K_BROADCAST_MS = "broadcast_ms"
    private const val K_FIRST_AT = "first_at"

    // ---- 时刻 / 间隔 ----
    private const val K_LAST_WATCHDOG_AT = "last_watchdog_at"
    private const val K_LAST_CREATE_AT = "last_create_at"
    private const val K_LAST_DESTROY_AT = "last_destroy_at"
    private const val K_LAST_DESTROY_REASON = "last_destroy_reason"
    private const val K_LAST_GAP_MS = "last_gap_ms"

    // ---- 档位分布（轮数） ----
    private const val K_MODE_PREFIX = "mode_"

    /**
     * 停机告警门限：巡检周期 60 秒，正常断档（用户关开开关、服务立即重建）远小于它。
     * 超过 2.5 个周期没巡检 = 中间确实停过（被 ROM 杀 / 手机重启 / 用户手动停）。
     */
    private const val GAP_ALARM_MS = 150_000L

    /** 单次巡检最多计入的广播时长（防止关机一夜后把整天算成在播）。 */
    private const val MAX_TICK_MS = 180_000L

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private val stampFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun stamp(ms: Long): String =
        if (ms <= 0L) "—" else stampFmt.format(Date(ms))

    // ---------------------------------------------------------------- 写入口

    /**
     * 服务 `onCreate` 调用：累计创建次数 + 检测断档。
     *
     * 断档判定是这里唯一有「诊断价值」的逻辑：服务启动时上一轮巡检还在不在 2.5 个周期内。
     * - 在 → 正常重建（START_STICKY 拉起、用户关开开关）；
     * - 不在 → 中间确实死过，把间隔记进台账并打 W 级，日志里一眼可见「15:30 死的、16:40 活」。
     */
    fun onServiceCreate(ctx: Context) {
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        val lastWatchdog = p.getLong(K_LAST_WATCHDOG_AT, 0L)
        val creates = p.getInt(K_SERVICE_CREATES, 0) + 1

        val editor = p.edit()
            .putInt(K_SERVICE_CREATES, creates)
            .putLong(K_LAST_CREATE_AT, now)
        if (p.getLong(K_FIRST_AT, 0L) == 0L) editor.putLong(K_FIRST_AT, now)

        if (lastWatchdog > 0L) {
            val gap = (now - lastWatchdog).coerceAtLeast(0L)
            editor.putLong(K_LAST_GAP_MS, gap)
            if (gap > GAP_ALARM_MS) {
                Timber.tag(TAG).w(
                    "服务重建：距上次巡检 %s（疑似被杀 / 设备重启 / 被手动停过）· 累计第 %d 次创建",
                    humanDuration(gap),
                    creates,
                )
            } else {
                Timber.tag(TAG).i(
                    "服务重建：距上次巡检 %s（正常重建）· 累计第 %d 次创建",
                    humanDuration(gap),
                    creates,
                )
            }
        } else {
            Timber.tag(TAG).i("服务首次创建（v%s）", appVersion(ctx))
        }
        editor.apply()
    }

    /** 服务 `onDestroy` 调用：记下销毁时刻与原因（下轮重建时用于断档解释）。 */
    fun onServiceDestroy(ctx: Context, reason: String) {
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        p.edit()
            .putLong(K_LAST_DESTROY_AT, now)
            .putString(K_LAST_DESTROY_REASON, reason)
            .apply()
        Timber.tag(TAG).i("服务销毁：%s（下次启动会比对断档）", reason)
    }

    /**
     * 巡检心跳：累计轮数、按实际间隔累加广播时长、统计档位分布。
     *
     * @param mode 本轮最终生效档位（[BleKeyMode]）
     * @param broadcasting 底层广播器是否报告在播
     * @return 累计巡检轮数（调用方据此决定「是否打一条 I 级汇总」）
     */
    fun onWatchdog(ctx: Context, mode: BleKeyMode, broadcasting: Boolean): Int {
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        val last = p.getLong(K_LAST_WATCHDOG_AT, 0L)
        // 用真实间隔累加（比固定 60 秒准），关机/长时间断档时钳到上限，避免一次灌进整天
        val tick = if (last > 0L) (now - last).coerceIn(0L, MAX_TICK_MS) else 0L

        val runs = p.getInt(K_WATCHDOG_RUNS, 0) + 1
        val editor = p.edit()
            .putInt(K_WATCHDOG_RUNS, runs)
            .putLong(K_LAST_WATCHDOG_AT, now)
            .putInt(K_MODE_PREFIX + mode.name, p.getInt(K_MODE_PREFIX + mode.name, 0) + 1)

        // 只有真在播的档位才计入「累计广播时长」，静默/BLOCKED 不算
        val playing = broadcasting &&
            (mode == BleKeyMode.FAST || mode == BleKeyMode.SLOW || mode == BleKeyMode.BOOST)
        if (playing && tick > 0L) {
            editor.putLong(K_BROADCAST_MS, p.getLong(K_BROADCAST_MS, 0L) + tick)
        }
        editor.apply()
        return runs
    }

    /**
     * 广播启动失败：累计失败次数（唯一需要盯的红色指标）。
     *
     * 降噪：失败原因**没变**时只打 D 级——同一原因连续失败（如权限被撤）会在巡检里
     * 每 60 秒重复一次，天天 1440 条 W 级会把日志刷满；只有「原因变了」才升 W 级，
     * 那才是新问题。
     */
    fun onAdvertisingFailed(ctx: Context, reason: String) {
        val p = sp(ctx)
        val fails = p.getInt(K_ADV_FAILS, 0) + 1
        val lastReason = p.getString(K_LAST_FAIL_REASON, "") ?: ""
        p.edit()
            .putInt(K_ADV_FAILS, fails)
            .putString(K_LAST_FAIL_REASON, reason)
            .apply()
        if (reason != lastReason) {
            Timber.tag(TAG).w("广播失败累计第 %d 次：%s（原因变化，重点关注）", fails, reason)
        } else {
            Timber.tag(TAG).d("广播失败累计第 %d 次：%s", fails, reason)
        }
    }

    // ---------------------------------------------------------------- 读出口

    /** 快照（详情页「运行概况」卡片直接展示）。 */
    data class Snapshot(
        val serviceCreates: Int,
        val watchdogRuns: Int,
        val advFails: Int,
        val broadcastMs: Long,
        val lastWatchdogAt: Long,
        val lastGapMs: Long,
        val lastDestroyReason: String,
        val firstAt: Long,
        val modeCounts: Map<BleKeyMode, Int>,
    )

    fun snapshot(ctx: Context): Snapshot {
        val p = sp(ctx)
        val counts = BleKeyMode.entries.associateWith { p.getInt(K_MODE_PREFIX + it.name, 0) }
            .filterValues { it > 0 }
        return Snapshot(
            serviceCreates = p.getInt(K_SERVICE_CREATES, 0),
            watchdogRuns = p.getInt(K_WATCHDOG_RUNS, 0),
            advFails = p.getInt(K_ADV_FAILS, 0),
            broadcastMs = p.getLong(K_BROADCAST_MS, 0L),
            lastWatchdogAt = p.getLong(K_LAST_WATCHDOG_AT, 0L),
            lastGapMs = p.getLong(K_LAST_GAP_MS, 0L),
            lastDestroyReason = p.getString(K_LAST_DESTROY_REASON, "") ?: "",
            firstAt = p.getLong(K_FIRST_AT, 0L),
            modeCounts = counts,
        )
    }

    /**
     * 生成一行上传摘要（`[BleKeySummary] ...`）。
     *
     * 放上传日志前，作为**第一眼结论**：即使后面 5000 行被别的模块刷满，
     * 后台按 tag 过滤 `BleKeySummary` 也能立刻判断「播了多久、失败几次、有没有被杀」。
     */
    fun summary(ctx: Context): String {
        val s = snapshot(ctx)
        val number = BleKeyPrefs.number(ctx)?.let { "%02d".format(it) } ?: "未设"
        val modes = s.modeCounts.entries
            .sortedByDescending { it.value }
            .joinToString("/") { "${it.key.name}${it.value}" }
            .ifEmpty { "无" }
        return buildString {
            append("v").append(appVersion(ctx))
            append(" 钥匙=").append(number)
            append(" 启用=").append(if (BleKeyPrefs.enabled(ctx)) "是" else "否")
            append(" 功率=").append(BleKeyPrefs.txPowerText(BleKeyPrefs.txPower(ctx)))
            append(" 静默=").append(
                if (BleKeyPrefs.quietEnabled(ctx)) {
                    "${BleKeyPrefs.quietStart(ctx)}–${BleKeyPrefs.quietEnd(ctx)}"
                } else "关",
            )
            append(" | 服务创建=").append(s.serviceCreates).append("次")
            append(" 巡检=").append(s.watchdogRuns).append("次")
            append(" 最后巡检=").append(stamp(s.lastWatchdogAt))
            append(" 失败=").append(s.advFails).append("次")
            append(" 累计广播=").append(humanDuration(s.broadcastMs))
            append(" 档位轮数=").append(modes)
            append(" 上次断档=").append(
                if (s.lastGapMs > 0) humanDuration(s.lastGapMs) else "—",
            )
            if (s.lastDestroyReason.isNotEmpty()) append(" 上次销毁=").append(s.lastDestroyReason)
            append(" | 首次启用=").append(stamp(s.firstAt))
        }
    }

    /** 把摘要写进 DiagLog（随后 [DiagLog.dumpLines] 上传时自动带上）。 */
    fun appendSummary(ctx: Context) {
        DiagLog.i(SUMMARY_TAG, summary(ctx))
    }

    // ---------------------------------------------------------------- 工具

    /** 设备电源状态（判断「广播停了是 ROM 压制还是我们不播」的关键上下文）。 */
    fun deviceFlags(ctx: Context): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val screen = if (pm?.isInteractive == true) "亮" else "灭"
        val doze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm?.isDeviceIdleMode == true) "是" else "否"
        val save = if (pm?.isPowerSaveMode == true) "开" else "关"
        return "屏幕=$screen Doze=$doze 省电=$save"
    }

    private fun appVersion(ctx: Context): String = runCatching {
        DiagLog.appVersionName(ctx)
    }.getOrDefault("?")

    /** 毫秒 → 「3天4小时」/「2小时13分」/「45秒」。 */
    fun humanDuration(ms: Long): String {
        if (ms <= 0L) return "0秒"
        val totalSec = ms / 1000
        val d = totalSec / 86400
        val h = (totalSec % 86400) / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            d > 0 -> "${d}天${h}小时"
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }
}
