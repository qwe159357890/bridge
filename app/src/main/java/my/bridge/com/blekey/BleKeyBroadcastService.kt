package my.bridge.com.blekey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import my.bridge.com.AppExit
import my.bridge.com.MainActivity
import my.bridge.com.R
import my.bridge.com.jikong.JikongBleClient
import my.bridge.com.ui.BleKeyUiState
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 蓝牙钥匙广播前台服务（v0.3.001 · P1）。
 *
 * 职责（设计见 docs/research/ble-key-broadcast.md §7 状态机、§8 自愈）：
 * 1. 持有 `connectedDevice` 前台通知（Android 14+ 后台做 BLE 广播的合规前提）；
 * 2. 生命周期状态机：
 *    - `F` App 前台 或 `K` 极空已连接 → **FAST 250ms**
 *    - 两者都失去 → **宽限 5 分钟**（仍 FAST），之后 → **SLOW 1000ms 保底**
 *    - 静默时段（默认 21:00–05:00）且既非前台也未连极空 → **停播（QUIET）**
 *    - 总开关关 / 未设编号 / 蓝牙未开 / 无权 / 不支持 → 停播并给出中文原因
 * 3. **事件驱动**重算：蓝牙开关、亮灭屏、服务重建、UI 开关、通知按钮；
 * 4. **60 秒巡检**：ROM 会静默停掉广播且不回调，只信 `onStartSuccess` 会被骗。
 *    对端「15 秒判 FAR + 60 秒断电 ≈ 75 秒断电门」，故巡检周期必须 ≤60 秒。
 *
 * 铁律：`promote()` 失败立即 `stopSelf` 撤销前台义务（照 KeepAlive「义务按次计」）。
 */
class BleKeyBroadcastService : Service() {

    companion object {
        private const val TAG = "BleKeyService"

        const val NOTIFICATION_ID = 2105
        const val CHANNEL = "blekey_broadcast_channel"
        const val ACTION_STOP = "my.bridge.com.blekey.action.STOP"

        /** 静默前提醒：独立 ID + 独立渠道，避免覆盖常驻通知。 */
        private const val QUIET_NOTICE_ID = 2106
        private const val CHANNEL_QUIET = "blekey_quiet_notice"

        /** 失去前台/极空后的快速档宽限（给「下车搬东西又回来」留余量）。 */
        private const val GRACE_MS = 5 * 60 * 1000L

        /** 巡检周期：必须 ≤60 秒（对端 75 秒断电门）。 */
        private const val WATCHDOG_MS = 60_000L

        /** 从 reason 里抠「剩 N 分」，用于通知去重（倒计时按分钟刷新足够）。 */
        private val COUNTDOWN_MINUTE = Regex("剩 (\\d+) 分")

        /**
         * 亮屏提速窗口：亮屏后切 100 ms 广播并维持 2 分钟。
         *
         * 为什么用亮屏：对端（ESP32）为了省电，平时跑基档「中」（扫描占空比 25%）、
         * `hit_frames=3`；后台 1000 ms 广播时「走近 → 通电」约 8 秒。亮屏是唯一免费且
         * 准确的「人可能正在用车」信号——屏幕本来就亮着，100 ms 广播的边际功耗可忽略。
         *
         * 推算（100 ms ÷ 25% 首帧 0.4 s，再 2 帧约 0.3 s，对端升档等待 0~2 s）≈ **1.5 秒**。
         * 能耗实测估算：100 次/天 × 2 分钟 ≈ +3.0 mAh ≈ 0.07%/天（4500 mAh 电池）。
         */
        private const val BOOST_MS = 2 * 60 * 1000L

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var running = false

        /** 幂等拉起：总开关关时内部直接返回（Application.onCreate 无脑调用安全）。 */
        fun evaluate(context: Context) {
            val ctx = context.applicationContext
            if (!BleKeyPrefs.enabled(ctx)) return
            val intent = Intent(ctx, BleKeyBroadcastService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                // 只在「服务其实没在跑」时记一条（Application/MainActivity 会高频调用本方法，
                // 每次都记会把日志刷爆）；这行是「用户开过开关 / App 被拉起过」的时间证据。
                if (!running) Timber.tag(TAG).d("请求拉起蓝牙钥匙服务（此前未运行）")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "拉起蓝牙钥匙服务失败（已忽略）")
            }
        }

        /** 显式停止（关总开关 / 通知「停止」）：走 START 分支同步撤销前台义务。 */
        fun stop(context: Context) {
            val intent = Intent(context, BleKeyBroadcastService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
                Timber.tag(TAG).d("请求停止蓝牙钥匙服务（关开关或点通知停止）")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "停止蓝牙钥匙服务失败（已忽略）")
            }
        }

        fun isRunning(): Boolean = running
    }

    private var stopRequested = false
    private var promoted = false
    private var graceUntil = 0L
    private var lastDesiredMode: BleKeyMode? = null

    /** 亮屏提速窗口的截止时刻（0 = 不在提速）。灭屏不清零，仍走完 [BOOST_MS]。 */
    private var boostUntil = 0L

    /**
     * 本次 [evaluateNow] 是否要**跳过幂等、强制重建广播会话**。
     *
     * 用途：① 60 秒巡检（唯一能治「ROM 静默停播且不回调」的手段）；
     * ② 蓝牙重新打开（底层会话可能已被系统丢弃，状态却还停在 ADVERTISING）。
     * 由 [evaluateNow] 的外层 `finally` 统一清理，任何 early return 都不会漏。
     */
    private var forceRebuild = false

    /** 通知去重（见 [applyMode]）：档位 / 错误 / 倒计时分钟，三者都没变就不刷通知。 */
    private var notifiedMode: BleKeyMode? = null
    private var notifiedError: String? = null
    private var notifiedMinute: Int? = null

    /** 提速到期：精确到点重算，不靠 60 秒巡检（否则实际窗口会变成 2~3 分钟）。 */
    private val boostExpire = Runnable {
        if (stopRequested) return@Runnable
        boostUntil = 0L
        Timber.tag(TAG).i("亮屏提速窗口结束，回落到常规档位")
        evaluateNow()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopRequested) return
            // ★ 无条件重建（不看 isAdvertising()，它只是本类的乐观标志位）：
            //   Android 没有任何 API 能查"广播当前是否真的在发"，所以只能周期性重新提交。
            //   60 秒 = 对端「15 秒判 FAR + 60 秒断电 ≈ 75 秒断电门」内唯一能救回来的周期；
            //   空窗约 30~100 ms，远小于 15 秒失联门，对端无感。
            forceRebuild = true
            evaluateNow()

            // ---- 巡检心跳（v0.3.008）----
            // 每轮必记台账（累计轮数/广播时长/档位分布），但日志分两级：
            //   · 常态 → D 级：不刷 Logcat，仍落盘 + 可上传，一天 1440 条不撑爆缓冲；
            //   · 每 10 轮 → I 级汇总：给出「档位 + 屏幕/Doze/省电 + 广播器真实状态 + 累计失败」，
            //     这是判断「广播停了是 ROM 压制还是我们自己不播」的唯一上下文。
            val st = BleKeyRuntime.state.value
            val playing = BleKeyBroadcaster.isAdvertising()
            val runs = BleKeyHealth.onWatchdog(applicationContext, st.mode, playing)
            if (runs % 10 == 0) {
                Timber.tag(TAG).i(
                    "巡检 #%d 汇总：档位=%s %s 广播器=%s 累计失败=%d",
                    runs,
                    st.mode.name,
                    BleKeyHealth.deviceFlags(applicationContext),
                    if (playing) "在播" else "未播",
                    BleKeyHealth.snapshot(applicationContext).advFails,
                )
            } else {
                Timber.tag(TAG).d(
                    "巡检 #%d：档位=%s %s 广播器=%s",
                    runs,
                    st.mode.name,
                    BleKeyHealth.deviceFlags(applicationContext),
                    if (playing) "在播" else "未播",
                )
            }
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                // 蓝牙重新打开：底层广播会话已被系统丢弃，但我们这边的状态可能还停在
                // ADVERTISING（onStartFailure 不会回调）→ 必须强制重建，否则会一直"假装在播"。
                if (state == BluetoothAdapter.STATE_ON) forceRebuild = true
                Timber.tag(TAG).i("蓝牙状态变化 state=%d，重评估", state)
                evaluateNow()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                onScreenOn()
            } else {
                // 灭屏**不缩短**提速窗口（覆盖「灭屏后揣兜走两步」），但仍顺手核对一次
                evaluateNow()
            }
        }
    }

    /**
     * 亮屏 → 重置提速窗口为整整 [BOOST_MS]。
     *
     * 不做去抖：广播器的幂等键是 `uuid + advertiseMode`，窗口内重复亮屏都不会改变 mode，
     * 因此不会重建广播会话（`stopAdvertising` + `startAdvertising` 只在 mode 真变时才发生）。
     */
    private fun onScreenOn() {
        val now = System.currentTimeMillis()
        val alreadyBoosting = now < boostUntil
        boostUntil = now + BOOST_MS
        mainHandler.removeCallbacks(boostExpire)
        mainHandler.postDelayed(boostExpire, BOOST_MS)
        if (!alreadyBoosting) {
            Timber.tag(TAG).i("亮屏 → 进入提速窗口（100ms，%d 分钟）", BOOST_MS / 60_000)
        }
        evaluateNow()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        // ★ 生命周期取证（v0.3.008）：服务被 ROM 杀掉后何时复活、中间断了多久，
        //   全靠这一行 + 台账里的断档间隔。没有它，日志里看不出「服务死过一次」。
        BleKeyHealth.onServiceCreate(applicationContext)
        createNotificationChannel()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        promote()
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || stopRequested) {
            Timber.tag(TAG).i("收到停止请求，停播并撤前台")
            stopRequested = true
            BleKeyPrefs.setEnabled(applicationContext, false)
            BleKeyBroadcaster.stop()
            BleKeyRuntime.reset()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        // START_STICKY 重建时 intent 为 null —— 这是「服务被系统回收后自己活过来」的唯一证据
        if (intent == null) {
            Timber.tag(TAG).i("服务被系统重建（intent=null，START_STICKY 生效）")
        }
        if (promoted) promote()
        evaluateNow()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        BleKeyHealth.onServiceDestroy(
            applicationContext,
            if (stopRequested) "主动停止（用户关闭或通知停止）" else "被动销毁（系统回收 / 低内存 / 省电策略）",
        )
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(boostExpire)
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        // 服务被系统杀掉时也要撤广播（否则残留一个无主广播）
        BleKeyBroadcaster.stop()
        BleKeyRuntime.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 状态机

    /** 重评估入口。外层 `finally` 统一清 [forceRebuild]，任何 early return 都不会漏。 */
    private fun evaluateNow() {
        try {
            evaluateNowInner()
        } finally {
            forceRebuild = false
        }
    }

    private fun evaluateNowInner() {
        val ctx = applicationContext
        if (!BleKeyPrefs.enabled(ctx)) {
            gracefulStop("总开关已关闭")
            return
        }

        val number = BleKeyPrefs.number(ctx)
        if (number == null) {
            enterBlocked(BleKeyMode.BLOCKED, "未设置钥匙编号（请到设置 → 连接 → 蓝牙钥匙填写）")
            return
        }

        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            enterBlocked(BleKeyMode.BLOCKED, "蓝牙未开启 · 点击通知去开启")
            return
        }

        val uuid = BleKeyProtocol.fullUuid(BleKeyPrefs.prefix(ctx), number)
        val txPower = BleKeyPrefs.txPower(ctx)

        val foreground = AppExit.isForeground()
        val jikongConnected = JikongBleClient.connected
        val active = foreground || jikongConnected
        val now = System.currentTimeMillis()

        // 宽限计时：失去活动态那一刻起算 5 分钟
        if (active) {
            graceUntil = 0L
        } else if (graceUntil == 0L) {
            graceUntil = now + GRACE_MS
        }

        val quiet = BleKeyPrefs.inQuietWindow(ctx)
        val inGrace = !active && graceUntil > now
        val boosting = now < boostUntil

        when {
            // 亮屏提速：优先级最高（夜里亮屏 = 要用车），压过静默与前台档。
            // 只改「取哪个 advertiseMode」，不改任何判定依据。
            boosting -> {
                val left = ((boostUntil - now) / 1000).coerceAtLeast(0)
                startAdvertising(
                    BleKeyMode.BOOST,
                    "亮屏提速（100ms）· 剩 ${left / 60} 分 ${left % 60} 秒",
                    uuid,
                    txPower,
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                )
            }

            active || inGrace -> {
                val remain = ((graceUntil - now) / 1000).coerceAtLeast(0)
                val reason = when {
                    foreground && jikongConnected -> "前台 + 极空已连接 · 快速广播"
                    foreground -> "App 在前台 · 快速广播"
                    jikongConnected -> "极空已连接 · 快速广播"
                    else -> "后台宽限中 · 剩 ${remain / 60} 分 ${remain % 60} 秒"
                }
                startAdvertising(BleKeyMode.FAST, reason, uuid, txPower, AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            }

            quiet -> {
                if (BleKeyBroadcaster.isAdvertising()) BleKeyBroadcaster.stop()
                applyMode(
                    BleKeyMode.QUIET,
                    "时段静默中（${BleKeyPrefs.quietStart(ctx)}–${BleKeyPrefs.quietEnd(ctx)}）· 打开 App 或用极空连接可恢复",
                )
            }

            else -> {
                startAdvertising(
                    BleKeyMode.SLOW,
                    "后台慢速保底（1000ms）· 打开 App 或连接极空可提速",
                    uuid,
                    txPower,
                    AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                )
            }
        }
        maybeNotifyQuietSoon()
    }

    /**
     * 静默生效前 5 分钟提醒（每天最多一次）。
     *
     * 砍掉「运动安全阀」之后，夜里后台停播是**有意的产品行为**；不提醒就会被当成故障。
     * 只在「当前确实在播」时提醒——本来就没播（未设编号 / 蓝牙关）再提醒只会添乱。
     */
    private fun maybeNotifyQuietSoon() {
        val ctx = applicationContext
        if (!BleKeyPrefs.quietNoticeOn(ctx)) return
        val mode = BleKeyRuntime.state.value.mode
        if (mode != BleKeyMode.FAST && mode != BleKeyMode.SLOW && mode != BleKeyMode.BOOST) return
        val mins = BleKeyPrefs.minutesUntilQuietStart(ctx) ?: return
        if (mins !in 1..5) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (BleKeyPrefs.quietNoticeDate(ctx) == today) return
        BleKeyPrefs.setQuietNoticeDate(ctx, today)

        val start = BleKeyPrefs.quietStart(ctx)
        val end = BleKeyPrefs.quietEnd(ctx)
        val notice = NotificationCompat.Builder(this, CHANNEL_QUIET)
            .setContentTitle("${BleKeyPrefs.label(ctx)} · $start 起暂停后台广播")
            .setContentText("$start–$end 只省待机电。夜间用车请打开 App，或让极空连上。")
            .setSmallIcon(R.drawable.ic_ble_key)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(piFlags()))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(QUIET_NOTICE_ID, notice) }
        Timber.tag(TAG).i("已发静默前提醒：%d 分钟后进入 %s", mins, start)
    }

    /**
     * 开播。
     *
     * 幂等由 [BleKeyBroadcaster.start] 负责（幂等键 = uuid + mode）；
     * [forceRebuild] 为真时跳过幂等、强制重建会话（60 秒巡检 / 蓝牙重新打开）。
     *
     * ★ 不再用 `isAdvertising()` 判断「是否被静默停播」：它读的是本类的乐观标志位，
     *   在「期望在播 + 实际已被 ROM 停掉」时恒为 true，等于永远查不出问题
     *   （v0.3.006 及以前巡检一直是空转，`巡检修复` 恒为 0）。
     */
    private fun startAdvertising(
        mode: BleKeyMode,
        reason: String,
        uuid: String,
        txPower: Int,
        advertiseMode: Int,
    ) {
        val force = forceRebuild
        val ok = BleKeyBroadcaster.start(this, uuid, txPower, advertiseMode, force = force)
        if (!ok) {
            val err = BleKeyBroadcaster.error.value ?: "广播启动失败"
            // 失败计数入台账：这是「挂着跑几天后唯一需要盯的红色指标」。
            // 日志降噪交给 BleKeyHealth（原因没变只打 D 级），避免每 60 秒一条 W。
            BleKeyHealth.onAdvertisingFailed(applicationContext, err)
            applyMode(BleKeyMode.BLOCKED, err, error = err)
            return
        }
        applyMode(mode, reason)
    }

    private fun enterBlocked(mode: BleKeyMode, reason: String) {
        if (BleKeyBroadcaster.isAdvertising()) BleKeyBroadcaster.stop()
        applyMode(mode, reason, error = if (mode == BleKeyMode.BLOCKED) reason else null)
    }

    private fun gracefulStop(reason: String) {
        Timber.tag(TAG).i("停止广播：%s", reason)
        BleKeyBroadcaster.stop()
        BleKeyRuntime.reset()
        stopRequested = true
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun applyMode(mode: BleKeyMode, reason: String, error: String? = null) {
        BleKeyRuntime.update {
            it.copy(
                mode = mode,
                reason = reason,
                error = error,
                quietWindowActive = BleKeyPrefs.inQuietWindow(applicationContext),
            )
        }
        if (lastDesiredMode != mode) {
            Timber.tag(TAG).i("档位 %s → %s（%s）", lastDesiredMode ?: "-", mode, reason)
            lastDesiredMode = mode
        }
        // 通知去重：例行重建（每 60 秒）文案不变，就不该刷通知——否则一天 1440 次无谓的
        // Binder + SystemUI 重绘，还可能在通知栏看到闪动。
        // 只按「档位 / 错误 / 倒计时分钟」三个维度判断；UI 侧的 BleKeyRuntime 仍然每次更新。
        val minute = COUNTDOWN_MINUTE.find(reason)?.groupValues?.get(1)?.toIntOrNull()
        if (mode != notifiedMode || error != notifiedError || minute != notifiedMinute) {
            notifiedMode = mode
            notifiedError = error
            notifiedMinute = minute
            updateNotification()
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun promote() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            promoted = true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "startForeground 失败，停止服务防 5s 超时崩溃")
            stopRequested = true
            BleKeyPrefs.setEnabled(applicationContext, false)
            runCatching { stopSelf() }
        }
    }

    private fun buildNotification(): Notification {
        val st = BleKeyRuntime.state.value
        val label = BleKeyPrefs.label(this)
        val title = when (st.mode) {
            BleKeyMode.BOOST, BleKeyMode.FAST, BleKeyMode.SLOW -> "$label · 广播中"
            BleKeyMode.QUIET -> "$label · 已暂停（时段静默）"
            BleKeyMode.BLOCKED -> "$label · 未广播"
            BleKeyMode.OFF -> "$label · 已停止"
        }
        val piFlags = piFlags()

        // 点击通知：蓝牙未开时跳系统蓝牙设置；否则拉起 App 直达详情页
        val contentIntent = buildContentIntent(piFlags)

        val stopIntent = Intent(this, BleKeyBroadcastService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(st.reason)
            .setSmallIcon(R.drawable.ic_ble_key)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, "停止", stopPi)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    /**
     * 通知点击意图。
     *
     * - **蓝牙未开** → 直接跳系统蓝牙设置（通知文案承诺「点击去开启」，就必须真跳）；
     * - 其余 → 拉起 App 并带 [BleKeyUiState.EXTRA_OPEN]，由 MainActivity 打开详情页。
     *
     * ★ 不在构建通知时置 `BleKeyUiState.showPage = true`：构建通知每次状态刷新都会执行，
     *   那样会在用户毫无操作时把详情页挂起来（v0.3.002 的 bug）。
     */
    private fun buildContentIntent(piFlags: Int): PendingIntent? {
        val btOff = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true
        val target = if (btOff) {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        } else {
            (packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java))
                .putExtra(BleKeyUiState.EXTRA_OPEN, true)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching { PendingIntent.getActivity(this, 2, target, piFlags) }.getOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "蓝牙钥匙",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "手机作为车钥匙广播 iBeacon 的状态"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_QUIET,
                    "蓝牙钥匙提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "静默时段生效前的一次性提醒"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }
}
