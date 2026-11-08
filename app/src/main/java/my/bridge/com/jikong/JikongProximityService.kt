package my.bridge.com.jikong

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import timber.log.Timber

/**
 * 极空「靠近自动连接」前台服务（v0.2.107）。
 *
 * 职责（与保活解耦：KeepAliveService 管进程存活，本服务管 BLE 挂起的合规承载）：
 * 1. 持有 connectedDevice 类型前台通知——targetSdk 36 下 Android 14+ 后台做 BLE
 *    连接必须有此类型前台服务，否则 SecurityException；
 * 2. [evaluateInPlace]：幂等评估「是否该挂起 autoConnect」（六个条件，见函数注释）；
 * 3. 蓝牙开关重启守护：STATE_ON 后重挂（部分 ROM 蓝牙重启后挂起丢失）；
 * 4. START_STICKY：进程被杀系统重建时按当前 Prefs 重新评估（自然自愈）。
 *
 * 服务本身无循环、无协程——纯粹持有通知，真正的监听由蓝牙控制器硬件执行
 * （48ms/1280ms 占空比 ≈3.75%，估 <0.5% 电量/天、零流量，见调研文档 §2.10）。
 *
 * 设计文档：docs/research/jikong-proximity-autoconnect.md §3.2。
 */
class JikongProximityService : Service() {

    companion object {
        private const val TAG = "JkProximity"
        const val NOTIFICATION_ID = 2104
        const val CHANNEL = "jikong_proximity_channel"
        const val ACTION_STOP = "my.bridge.com.jikong.action.PROXIMITY_STOP"

        /**
         * 启动服务并评估挂起（前台/进程创建等允许 startForegroundService 的场景用）。
         * 内部查开关：开关关时不启动（幂等，BridgeApplication.onCreate 无脑调用安全）。
         */
        fun evaluate(context: Context) {
            val ctx = context.applicationContext
            if (!JikongPrefs.proximityAutoConnect(ctx)) return
            val intent = Intent(ctx, JikongProximityService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "启动靠近连接服务失败（已忽略）")
            }
        }

        /**
         * 显式停止（开关关闭 / 手动断开抑制时）：摘除挂起 + 撤销前台 + 自停。
         * 与裸 stopService 的区别（照 KeepAliveService.stop 经验）：走 startService
         * 让 onStartCommand 同步执行 STOP 分支，立即撤销前台义务。
         */
        fun stop(context: Context) {
            val intent = Intent(context, JikongProximityService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "停止靠近连接服务失败（已忽略）")
            }
        }

        /**
         * 核心：就地评估并挂/摘（进程内直接执行，不经 startService——Android 8+ 后台
         * startService 抛 IllegalStateException，而 BLE 断开回调、连接尝试收尾等场景
         * 进程活着但可能在后台）。服务运行中 = connectedDevice FGS 在，后台做 BLE 合规。
         *
         * 挂起条件（全部满足）：①开关开 ②默认设备 MAC 非空 ③未手动抑制
         * ④非演示模式 ⑤未连接 ⑥未挂起（幂等）⑦无在飞连接尝试。
         * 抑制/演示期间若已挂起 → 摘除。
         */
        fun evaluateInPlace(context: Context) {
            val appCtx = context.applicationContext
            if (!JikongPrefs.proximityAutoConnect(appCtx)) return
            val controller = JikongController.instance
            // autoConnect 建链可能早于任何手动连接（appContext 原只在 connect() 缓存），
            // 缺上下文会导致 proximityModeActive/播报/重挂全部失效
            controller.attachContext(appCtx)
            if (controller.manualSuppressed || controller.demoMode) {
                if (JikongBleClient.autoConnectAttached) {
                    Timber.tag(TAG).i(
                        "抑制/演示期间摘除挂起（suppressed=%b demo=%b）",
                        controller.manualSuppressed, controller.demoMode,
                    )
                    JikongBleClient.detachAutoConnect()
                }
                return
            }
            val mac = JikongPrefs.defaultMac(appCtx)
            if (mac.isBlank()) return
            if (JikongBleClient.connected || JikongBleClient.autoConnectAttached ||
                controller.connectInFlight
            ) {
                return
            }
            val ok = runCatching { JikongBleClient.attachAutoConnect(appCtx, mac) }
                .getOrDefault(false)
            if (ok) {
                Timber.tag(TAG).i("已对默认设备 %s 挂起 autoConnect（控制器后台监听）", mac)
            } else {
                Timber.tag(TAG).w("挂起 autoConnect 未成功（蓝牙关/地址无效），待下次评估")
            }
        }
    }

    private var stopRequested = false
    private var promoted = false

    /** 蓝牙开关重启守护：STATE_ON 后重评估（部分 ROM 蓝牙重启后挂起丢失）。 */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_ON) {
                    Timber.tag(TAG).i("蓝牙已重新开启，重评估挂起")
                    evaluateInPlace(applicationContext)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || stopRequested) {
            stopRequested = true
            JikongBleClient.detachAutoConnect()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        // Android 12+：服务运行中每次 startForegroundService 拉起都要再 startForeground 一次
        if (promoted) promote()
        evaluateNow()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(btReceiver) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 服务侧评估：开关已关则自停；否则就地评估 + 刷新通知文案。 */
    private fun evaluateNow() {
        val appCtx = applicationContext
        if (!JikongPrefs.proximityAutoConnect(appCtx)) {
            // 开关已关但服务还在（系统重建等）→ 摘除并自停
            Timber.tag(TAG).i("靠近自动连接开关已关，摘除挂起并停止服务")
            JikongBleClient.detachAutoConnect()
            stopRequested = true
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return
        }
        evaluateInPlace(appCtx)
        updateNotificationText()
    }

    /**
     * 挂前台。connectedDevice 类型要求 BLUETOOTH_CONNECT 运行时权限——缺失或系统
     * 拒绝时不能干等 5s 超时崩溃（KeepAliveService v0.1.99 同款教训），立即自停撤销义务。
     */
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
            runCatching { stopSelf() }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = openApp?.let { PendingIntent.getActivity(this, 0, it, piFlags) }

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("极空靠近连接")
            .setContentText("监听默认保护板中 · 靠近自动连接")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
    }

    /** 按当前状态刷新通知文案（连接/挂起/暂停，纯展示不影响服务）。 */
    private fun updateNotificationText() {
        val text = when {
            JikongBleClient.connected -> "已连接默认设备"
            JikongController.instance.manualSuppressed -> "已暂停（极空页断开后不再自动连接）"
            JikongBleClient.autoConnectAttached -> "监听默认保护板中 · 靠近自动连接"
            else -> "等待条件满足（演示中/蓝牙未开等）"
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("极空靠近连接")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                "极空靠近连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "靠近默认保护板时自动连接的监听服务"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
