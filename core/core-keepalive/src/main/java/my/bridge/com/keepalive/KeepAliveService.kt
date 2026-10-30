package my.bridge.com.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import my.bridge.com.core.common.DiagLog

/**
 * 常驻保活前台服务。
 *
 * 核心设计（借鉴 Aurelay）：把「保活」与「业务」彻底解耦。
 * 本服务无条件启动，只负责：常驻前台通知 + 保活资源 + 心跳上报。
 * 业务服务独立成另一个 Service，由业务开关控制。
 */
class KeepAliveService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_KEEP_ALIVE = "keep_alive_channel"
        const val ACTION_REFRESH = "my.bridge.com.keepalive.action.REFRESH"

        @Volatile
        var startedAt: Long = 0L
            private set

        /** 取本次启动时刻（毫秒时间戳）；服务未启动时返回 0。用于「运行时长」展示。 */
        fun startedAtMillis(): Long = startedAt

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动保活服务失败：${e.message}")
            }
        }

        /**
         * 刷新保活配置：发送 ACTION_REFRESH，让正在运行的服务重新应用各层开关。
         * 用于设置页切换开关后即时生效。服务未运行时等同于 start。
         */
        fun refresh(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
                .setAction(ACTION_REFRESH)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "刷新保活配置失败：${e.message}")
            }
        }

        private const val TAG = "KeepAliveService"
    }

    private lateinit var notificationManager: NotificationManager

    // ---- v0.1.99 前台化防崩状态 ----
    // startForegroundService 启动的服务必须在 5 秒内成功 startForeground()，否则系统抛
    // ForegroundServiceDidNotStartInTimeException 直接崩溃（v0.1.95~98 线上 8 次
    // 「Bridge 已停止运行」的实锤根因：promoteToForeground 吞了异常 → startForeground
    // 实际未成功 → 超时崩溃）。触发源均为后台兜底（JobScheduler/AlarmManager），
    // 免疫窗口在 startForeground() 时刻二次校验，存在竞态，所以偶发、难复现。
    private var promoted = false
    private var pendingApply = false
    private var promoteAttempts = 0
    private var stopRequested = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        promoteWithRetry()
    }

    /**
     * 带重试的前台化：失败重试 3 次（350ms 间隔，总窗口 ~1.05s，远小于 5s 期限），
     * 覆盖「免疫窗口竞态」类瞬时失败；重试耗尽仍失败 → 立即 stopSelf() 撤销本次
     * 前台启动（系统取消 5 秒倒计时，不再崩溃），等下次兜底任务/用户进 App 再拉起。
     */
    private fun promoteWithRetry() {
        val attempt = ++promoteAttempts
        val ok = try {
            doPromote()
            true
        } catch (e: Exception) {
            DiagLog.e("KeepAliveService", "startForeground 失败（第 $attempt 次）", e)
            false
        }
        if (ok) {
            promoted = true
            pendingApply = false
            applyKeepAlive()
            return
        }
        if (attempt < 4) {
            mainHandler.postDelayed({ if (!stopRequested) promoteWithRetry() }, 350)
        } else {
            DiagLog.e("KeepAliveService", "挂前台重试耗尽，stopSelf 防崩（异常见上一条日志）")
            stopRequested = true
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopRequested) return START_NOT_STICKY
        if (intent?.action == ACTION_REFRESH) {
            if (promoted) {
                runCatching { doPromote() }.onFailure {
                    DiagLog.e("KeepAliveService", "REFRESH 重新挂前台失败（服务已在前台，不影响运行）", it)
                }
                applyKeepAlive()
            } else {
                // onCreate 的重试还在进行：标记待应用，成功后统一 applyKeepAlive
                pendingApply = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try { ResourceHolder.releaseAll(this) } catch (e: Exception) { Log.w(TAG, "释放资源失败：${e.message}") }
        try { OnePixelOverlay.hide(this) } catch (e: Exception) { Log.w(TAG, "隐藏一像素失败：${e.message}") }
        try { SilentPlayer.setScenarioAllowed(this, false) } catch (e: Exception) { Log.w(TAG, "停止无声播放失败：${e.message}") }
        Log.i(TAG, "常驻保活服务已销毁")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyKeepAlive() {
        // 先统一释放，再按当前开关重新持有，确保任意开关切换都能即时生效
        try { ResourceHolder.releaseAll(this) } catch (e: Exception) { Log.w(TAG, "释放资源失败：${e.message}") }

        if (KeepAlivePrefs.getBoolean(this, KeepAlivePrefs.KEY_KEEP_ALIVE_ALWAYS)) {
            ResourceHolder.acquireAll(this)
        }
        if (KeepAlivePrefs.getBoolean(this, KeepAlivePrefs.KEY_KEEP_ALIVE_ONE_PIXEL)) {
            OnePixelOverlay.show(this)
        } else {
            OnePixelOverlay.hide(this)
        }
        SilentPlayer.applyEnabled(this)
    }

    /**
     * 挂前台（不吞异常，失败直接抛给 promoteWithRetry）。
     * v0.1.99 重命名自 promoteToForeground：旧实现 catch 吞掉异常是线上崩溃根因。
     */
    private fun doPromote() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "常驻保活服务已前台化")
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, KeepAliveEntryActivityHolder.entryActivityClass)
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, openApp, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("Bridge 后台服务")
            .setContentText("服务运行中 · 点此管理")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "常驻保活",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "应用运行期间保持后台常驻"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/**
 * 入口 Activity 持有器：由 app 壳工程在启动时注册自己的 MainActivity，
 * 避免保活模块反向依赖业务包名。
 */
object KeepAliveEntryActivityHolder {
    @Volatile
    var entryActivityClass: Class<*> = Class.forName("my.bridge.com.MainActivity")
}
