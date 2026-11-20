package my.bridge.com.immich

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

import my.bridge.com.core.common.DiagLog

/** 相册自动备份日志 tag。 */
private const val NTAG = "相册备份"

/** 上传前台服务通知渠道 ID。 */
private const val CHANNEL_ID = "immich_backup"

/** 单文件转前台服务的阈值：256MB（调研报告 §5.4，与 Immich 原版口径一致）。 */
private const val FOREGROUND_THRESHOLD_BYTES = 256L * 1024 * 1024

/**
 * 上传进度通知 ID：2401。
 *
 * ID 分配台账（WechatNotifier.kt 注释记载）：2001=保活/远程、2002=中继、2101=媒体采集、
 * 2201=更新、2202=管理端、2301=微信。v0.2.173~176 曾误用 2001——每次 Worker 启动
 * cancel(2001) 把 KeepAliveService 的保活常驻通知删掉，notify(2001) 又把它顶成
 * 「正在上传（已传 0）」残留，2026-11-17 用户反馈实锤后改用 2401。
 */
private const val NOTIFICATION_ID = 2401

/** 单次备份 Worker 最多处理的文件数（防超长运行；余量下次内容触发继续）。 */
private const val MAX_UPLOADS_PER_RUN = 200

/**
 * 相册自动备份链路（D5 拍板方案，2026-11-16）。
 *
 * 能耗纪律（调研报告 §5.4，核心：空闲态 ≈ 0 消耗）：
 * - 触发：WorkManager addContentUriTrigger 监听 MediaStore 图片+视频 4 个 URI（照抄 Immich 原版），
 *   无新图时任务挂在系统进程、零唤醒零 CPU；不设周期扫描、不连长连接；
 * - 去抖合并：连拍 N 张只唤醒一次（UpdateDelay 30s / MaxDelay 300s）；
 * - 增量扫描：date_added > 水位（纯 DB 读），SHA-1 只算新候选；
 * - 仅 Wi-Fi：UNMETERED 约束由系统把关，不满足时挂起零消耗；
 * - 大文件 >256MB：setForeground 短暂存在，传完即销毁；
 * - 默认关闭：用户在设置页明确开启才注册 Observer。
 *
 * 链路：MediaStore 变更 → ImmichMediaObserver（自我续期）→ ImmichUploadWorker
 * （增量扫描 → SHA-1 → bulk-upload-check 去重 → multipart 上传 → DataStore 记账）。
 */
object ImmichBackup {

    /** Observer Worker 唯一名。 */
    private const val OBSERVER_NAME = "immich_media_observer"

    /** 上传 Worker 唯一名。 */
    private const val UPLOAD_NAME = "immich_upload_worker"

    /** 周期兜底 Worker 唯一名。 */
    private const val PERIODIC_NAME = "immich_backup_periodic"

    /** Wi-Fi 恢复等待 Worker 唯一名（仅 Wi-Fi 门禁拦截时挂起的网络约束任务）。 */
    private const val WIFI_WAIT_NAME = "immich_upload_wifi_wait"

    /** 节流补跑 Worker 唯一名（30 秒节流窗口结束后自动补一次对账，防触发被丢弃）。 */
    private const val CATCHUP_NAME = "immich_upload_catchup"

    /**
     * 注册内容触发 Observer（自动备份开启时调用；幂等，重复注册不打断现有链）。
     * 内容触发仅 OneTime 有效（JobScheduler 语义），Observer 跑完自我续期保持监听
     * （照 Immich MediaObserver 范式）。
     *
     * :param context: 任意 Context
     */
    fun ensureRegistered(context: Context) {
        val appContext = context.applicationContext
        if (!ImmichPrefs.autoBackup(appContext)) {
            DiagLog.i(NTAG, "自动备份未开启，跳过 Observer 注册")
            return
        }
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(300, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<ImmichMediaObserver>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            OBSERVER_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        // 周期兜底（v0.2.178）：OnePlus/ColorOS 的 JobScheduler 内容触发不可靠
        //（2026-11-17 实锤：第二次拍照后「MediaStore 内容变更」事件从未派发，照片一直
        // 不上传，直到进相册页手动兜底），故加 15 分钟周期对账兜底——增量扫描纯 DB 读、
        // 无新图秒退（日志「无新增媒体」），配合保活体系的 15 分钟唤醒，代价≈0。
        val periodic = androidx.work.PeriodicWorkRequestBuilder<ImmichUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints(appContext))
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
        // 进程内 ContentObserver（v0.2.179）：JobScheduler 内容触发在 OnePlus 上随机丢失
        //（15:00:34 拍照后事件未派发，6 分钟后进相册页才传），保活进程常驻故进程内监听可靠；
        // MediaStore 变更才回调、连拍 5 秒去抖合并，零轮询。
        ensureInProcessObserver(appContext)
        // 网络恢复监听（v0.3.010）：蜂窝下拍照被「仅 Wi-Fi」门禁拦截后，此前无任何机制在
        // 切回 Wi-Fi 时唤醒对账（实锤 2026-11-18：18:55 拍照被拦，19:07 恰逢媒体事件才传出，
        // 空窗 11 分钟），只能靠 15 分钟周期兜底或进相册页手动兜底。进程内 NetworkCallback
        // 边沿检测「网络恢复为不计费」即时触发，保活进程常驻故可靠，零轮询。
        ensureNetworkCallback(appContext)
        DiagLog.i(NTAG, "内容触发 Observer 已注册（图片+视频 2 URI，去抖 30s/300s）+ 15 分钟周期兜底 + 进程内 Observer + 网络恢复监听")
    }

    /** 进程内 MediaStore 观察者（进程存活期间有效；null = 未注册）。 */
    private var inProcessObserver: android.database.ContentObserver? = null

    /**
     * 注册进程内 MediaStore ContentObserver（幂等）。相机/截图/下载等入库变更
     * 5 秒去抖后直接触发对账 Worker（绕开 JobScheduler，不再受厂商丢事件影响）。
     *
     * :param appContext: 应用上下文
     */
    private fun ensureInProcessObserver(appContext: Context) {
        if (inProcessObserver != null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(handler) {
            private val trigger = Runnable {
                DiagLog.i(NTAG, "进程内 MediaStore 变更 → 触发对账")
                triggerSyncNow(appContext)
            }

            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                // 连拍去抖：5 秒内的多次变更合并成一次对账
                handler.removeCallbacks(trigger)
                handler.postDelayed(trigger, 5000)
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        appContext.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        inProcessObserver = observer
        DiagLog.i(NTAG, "进程内 MediaStore Observer 已注册（去抖 5s）")
    }

    /**
     * 注销进程内 ContentObserver（自动备份关闭时调用；Worker 侧监听随 unregister 一并撤）。
     *
     * :param appContext: 应用上下文
     */
    private fun unregisterInProcessObserver(appContext: Context) {
        inProcessObserver?.let {
            runCatching { appContext.contentResolver.unregisterContentObserver(it) }
            inProcessObserver = null
            DiagLog.i(NTAG, "进程内 MediaStore Observer 已注销")
        }
    }

    /** 网络恢复监听回调（进程存活期间有效；null = 未注册）。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 网络回调侧最近一次「不按流量计费」状态（null = 未知；用于边沿检测避免信号波动重复触发）。 */
    @Volatile
    private var lastUnmetered: Boolean? = null

    /**
     * 注册默认网络回调（幂等）。边沿检测「网络从不计费可用 → 恢复」：
     * 蜂窝/断网状态下拍照被门禁拦截的照片，在切回 Wi-Fi 的瞬间即时触发对账续传。
     * 回调发生在 binder 线程，触发链（triggerSyncNow → WorkManager 入队）线程安全。
     *
     * :param appContext: 应用上下文
     */
    private fun ensureNetworkCallback(appContext: Context) {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            DiagLog.w(NTAG, "获取 ConnectivityManager 失败，网络恢复监听未注册")
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            /**
             * 网络可用回调（含蜂窝/Wi-Fi 切换、断网重连）。
             *
             * :param network: 新可用的网络
             */
            override fun onAvailable(network: Network) {
                onNetworkMaybeRestored(appContext)
            }

            /**
             * 网络能力变化回调（Wi-Fi 信号波动会高频回调，靠边沿检测去重）。
             *
             * :param network: 当前网络
             * :param networkCapabilities: 最新能力
             */
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onNetworkMaybeRestored(appContext)
            }

            /**
             * 网络丢失回调：只更新边沿状态，不触发对账（断网时无法上传）。
             *
             * :param network: 丢失的网络
             */
            override fun onLost(network: Network) {
                lastUnmetered = ImmichBackup.isUnmeteredNetwork(appContext)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess {
                networkCallback = callback
                DiagLog.i(NTAG, "网络恢复监听已注册（切回 Wi-Fi 自动续传）")
            }
            .onFailure { DiagLog.w(NTAG, "网络恢复监听注册失败：${it.message}") }
    }

    /**
     * 网络变化统一处理：边沿检测「从不按流量计费 → 恢复」时触发对账。
     * 首次回调（prev=null）即处于 Wi-Fi 时也触发一次——进程冷启动即完成积压对账。
     *
     * :param appContext: 应用上下文
     */
    private fun onNetworkMaybeRestored(appContext: Context) {
        val unmetered = ImmichBackup.isUnmeteredNetwork(appContext)
        val prev = lastUnmetered
        lastUnmetered = unmetered
        // 边沿触发：仅「不满足 → 满足」跳变时对账，Wi-Fi 内信号波动（恒满足）不触发
        if (unmetered && prev != true) {
            DiagLog.i(NTAG, "网络恢复为 Wi-Fi（不计费）→ 触发对账")
            triggerSyncNow(appContext)
        }
    }

    /**
     * 注销网络恢复监听（自动备份关闭时调用）。
     *
     * :param appContext: 应用上下文
     */
    private fun unregisterNetworkCallback(appContext: Context) {
        networkCallback?.let {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(it) }
            networkCallback = null
            lastUnmetered = null
            DiagLog.i(NTAG, "网络恢复监听已注销")
        }
    }

    /**
     * 入队「Wi-Fi 恢复等待」Worker（仅 Wi-Fi 门禁拦截时调用）。
     * 带 UNMETERED 网络约束：WorkManager 在网络切回 Wi-Fi 时由系统自动唤醒执行，
     * 进程被杀后任务仍保留在系统侧；KEEP 策略幂等（已在等待中则忽略重复入队）。
     * OnePlus 对 WorkManager 网络约束偶发跟踪故障，故此路径为兜底，
     * 主路径是进程内 NetworkCallback（v0.3.010）+ 15 分钟周期对账。
     *
     * :param appContext: 应用上下文
     */
    fun enqueueWifiWaitWork(appContext: Context) {
        val request = OneTimeWorkRequestBuilder<ImmichUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WIFI_WAIT_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 注销 Observer（自动备份关闭时调用）。
     *
     * :param context: 任意 Context
     */
    fun unregister(context: Context) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        wm.cancelUniqueWork(OBSERVER_NAME)
        wm.cancelUniqueWork(PERIODIC_NAME)
        wm.cancelUniqueWork(WIFI_WAIT_NAME)
        wm.cancelUniqueWork(CATCHUP_NAME)
        unregisterInProcessObserver(appContext)
        unregisterNetworkCallback(appContext)
        DiagLog.i(NTAG, "内容触发 Observer、周期兜底、Wi-Fi 等待任务、节流补跑、进程内 Observer 与网络恢复监听已注销")
    }

    /** 上次手动/观察者触发对账的时刻（elapsedRealtime 毫秒；30 秒节流防打断运行中 Worker）。 */
    private var lastTriggerAt = 0L

    /**
     * 手动触发一次全量对账上传（进相册页兜底/进程内 Observer 时机）。
     * 30 秒节流：相册页每次回前台都会调用，频繁 REPLACE 会打断运行中的上传；
     * Worker 运行中的新照片由本次对账扫描覆盖。
     * 节流命中不再丢弃触发：Worker 常为秒级完成（网络约束为空，入队即跑），
     * 其后再拍的照片不在任何一次已执行扫描内，直接 return 会积压到 15 分钟
     * 周期兜底（2026-11-20 实锤：第 1 张 12:31:33 传完，第 2 张 12:31:58 被
     * 节流丢弃，空窗 14 分钟才由周期任务传出）——改挂 CATCHUP 补跑 Worker，
     * setInitialDelay 到窗口结束后强制对账一次（系统级调度，进程冻结/熄屏不丢）。
     *
     * :param context: 任意 Context
     */
    fun triggerSyncNow(context: Context) {
        val appContext = context.applicationContext
        if (!ImmichPrefs.autoBackup(appContext)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < 30_000L) {
            val remain = 30_000L - (now - lastTriggerAt) + 1_000L
            val catchup = OneTimeWorkRequestBuilder<ImmichUploadWorker>()
                .setInitialDelay(remain, TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints(appContext))
                .build()
            // KEEP：补跑已在等待中则忽略——窗口内连拍多张只需窗口结束后对账一次
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                CATCHUP_NAME,
                ExistingWorkPolicy.KEEP,
                catchup,
            )
            DiagLog.i(NTAG, "对账触发节流：30 秒内已触发过，挂起 ${remain / 1000}s 后补跑对账")
            return
        }
        lastTriggerAt = now
        val request = OneTimeWorkRequestBuilder<ImmichUploadWorker>()
            .setConstraints(networkConstraints(appContext))
            .build()
        // REPLACE：被系统取消/受网络约束等待中的旧 Worker 不再阻塞新触发
        //（KEEP 曾导致「取消后唯一名被占，后续触发全部无效」）
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UPLOAD_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        DiagLog.i(NTAG, "手动触发备份对账")
    }

    /**
     * Worker 约束（不设网络约束）。
     * OnePlus/ColorOS 的 JobScheduler 对网络约束跟踪有故障（网络正常仍判 Unsatisfied、
     * 入队后不启动、作业从队列消失），故「仅 Wi-Fi」门禁改为 Worker 内代码判定，
     * Worker 一入队立即启动，不再等系统约束回调。
     *
     * :param context: 任意 Context
     * :return: 空约束（立即满足）
     */
    internal fun networkConstraints(context: Context): Constraints = Constraints.Builder().build()

    /**
     * 当前默认网络是否不按流量计费（Wi-Fi 等）。
     *
     * OnePlus/ColorOS 上 NET_CAPABILITY_NOT_METERED 偶发缺失（同一 Wi-Fi 下有时
     * 被误判为计费网络，2026-11-17 实锤），故以传输层判定优先：
     * Wi-Fi / 以太网直接视为不按流量计费，蜂窝等再回退 NOT_METERED 能力位。
     *
     * :param context: 应用上下文
     * :return: true = 不按流量计费；无活动网络返回 false
     */
    internal fun isUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return true
        }
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * 查询本机媒体相册（bucket_display_name 去重，设置页勾选列表用）。
     *
     * :param context: 任意 Context
     * :return: 相册名列表（按名称排序）
     */
    fun fetchLocalBuckets(context: Context): List<String> {
        if (!hasMediaPermission(context)) return emptyList()
        val names = mutableSetOf<String>()
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) names.add(name)
                    }
                }
            }
        }
        return names.sorted()
    }

    /**
     * 媒体读取权限检查（API 33+ 分媒体类型；旧版 READ_EXTERNAL_STORAGE；
     * 已授「所有文件访问」时同样放行）。
     *
     * :param context: 任意 Context
     * :return: true = 可读 MediaStore
     */
    fun hasMediaPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 创建前台服务通知渠道（上传大文件时短暂展示进度）。
     *
     * :param context: 任意 Context
     */
    internal fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "相册自动备份", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }
}

/**
 * MediaStore 内容触发 Observer Worker（照抄 Immich MediaObserver 范式）。
 *
 * 职责：内容变更事件到达后入队上传 Worker（去抖由约束层的 UpdateDelay 完成），
 * 并自我重新入队保持监听（跑完即重排，长期存活于系统侧）。
 *
 * :param appContext: 应用上下文
 * :param params: Worker 参数
 */
class ImmichMediaObserver(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * 事件处理：入队上传 Worker（去抖 30s 由约束层完成）并自我续期。
     *
     * :return: 固定成功（监听链不断）
     */
    override suspend fun doWork(): Result {
        DiagLog.i(NTAG, "MediaStore 内容变更 → 入队备份 Worker")
        val wm = WorkManager.getInstance(applicationContext)
        wm.enqueueUniqueWork(
            "immich_upload_worker",
            // REPLACE：与手动触发同策略，避免取消/等待态旧 Worker 阻塞新内容触发
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ImmichUploadWorker>()
                .setConstraints(ImmichBackup.networkConstraints(applicationContext))
                .build(),
        )
        // 自我续期：重排自己（KEEP 不打断现有周期任务），保持内容监听链
        ImmichBackup.ensureRegistered(applicationContext)
        return Result.success()
    }
}

/**
 * 备份上传 Worker：增量扫描 → SHA-1 → 服务端去重 → multipart 上传 → 记账。
 *
 * :param appContext: 应用上下文
 * :param params: Worker 参数
 */
class ImmichUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** 单条媒体记录（扫描结果）。 */
    private data class MediaRow(
        val id: Long,
        val path: String,
        val size: Long,
        val dateAdded: Long,
        /** 真实拍摄时间（epoch 秒；DATE_TAKEN，无则 0 → 回退 dateAdded） */
        val dateTaken: Long,
        /** 文件修改时间（epoch 秒；DATE_MODIFIED，无则 0 → 回退 dateAdded） */
        val dateModified: Long,
        val bucket: String,
        val isVideo: Boolean,
        val durationMs: Long,
    )

    /**
     * 备份主流程。
     *
     * :return: 成功 / 重试（网络类失败）
     */
    override suspend fun doWork(): Result {
        val context = applicationContext
        DiagLog.i(NTAG, "备份 Worker 启动（attempt=$runAttemptCount）")
        // 一次性迁移清理：v0.2.176 及之前备份通知误用 ID 2001（与保活常驻通知冲突），
        // 旧版残留的「正在上传（已传 0）」需要清一次；之后永不触碰 2001（保活通知）
        if (!ImmichPrefs.legacyNotificationCleaned(context)) {
            runCatching { NotificationManagerCompat.from(context).cancel(2001) }
            ImmichPrefs.saveLegacyNotificationCleaned(context)
            DiagLog.i(NTAG, "已一次性清理误用 ID 2001 的历史残留通知")
        }
        // 清掉本链路自己的残留通知（Worker 被取消/进程被杀时通知不会自动消失）
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        // 「仅 Wi-Fi」门禁：不再依赖 JobScheduler 网络约束（OnePlus 跟踪故障会卡住不启动），
        // 改为 Worker 内判定。非 Wi-Fi 时挂起一个 UNMETERED 约束的等待 Worker（网络切回
        // Wi-Fi 由系统自动唤醒；若 OnePlus 约束故障卡住，还有进程内 NetworkCallback 边沿
        // 触发与 15 分钟周期兜底三层冗余），积压照片不再依赖手动进相册页才续传
        if (ImmichPrefs.wifiOnly(context) && !ImmichBackup.isUnmeteredNetwork(context)) {
            DiagLog.i(NTAG, "仅 Wi-Fi 备份：当前非 Wi-Fi 网络，挂起等待 Wi-Fi 恢复自动续传")
            ImmichBackup.enqueueWifiWaitWork(context)
            return Result.success()
        }
        // 总闸：开关关闭直接退出（能耗纪律 #7：出厂关闭，开启才生效）
        if (!ImmichPrefs.autoBackup(context)) {
            DiagLog.i(NTAG, "自动备份已关闭，Worker 退出")
            return Result.success()
        }
        // 未配置服务端账密：上传链路不可用
        if (!ImmichPrefs.isConfigured(context)) {
            DiagLog.i(NTAG, "服务端未配置账号，Worker 退出")
            return Result.success()
        }
        if (!ImmichBackup.hasMediaPermission(context)) {
            DiagLog.w(NTAG, "缺少媒体读取权限（READ_MEDIA_IMAGES/VIDEO），Worker 退出")
            return Result.success()
        }
        try {
            val candidates = scanCandidates(context)
            if (candidates.isEmpty()) {
                DiagLog.i(NTAG, "无新增媒体，本次对账结束")
                return Result.success()
            }
            // 大文件前台化（>256MB 时短暂前台，进度通知）
            if (candidates.any { it.size > FOREGROUND_THRESHOLD_BYTES }) {
                setForeground(createForegroundInfo("正在上传大文件…"))
            }
            val api = ImmichApiClient(context)
            // 第一步：批量预检（2026-11-17 重构）。旧实现逐文件单查且误判响应字段
            //（status vs action），导致官方 APP 已备份的资产全量重传一百多个。
            // 现在一次性算 SHA-1，分批（500/批）bulk-upload-check，已有的全部前置跳过。
            val hashedList = mutableListOf<Pair<MediaRow, String>>()
            for (row in candidates) {
                val file = File(row.path)
                if (!file.exists() || file.length() == 0L) continue
                runCatching { hashedList.add(row to api.sha1Base64(file)) }
                    .onFailure { DiagLog.w(NTAG, "计算校验失败 ${file.name}：${it.message}") }
            }
            DiagLog.i(NTAG, "批量预检：可校验候选 ${hashedList.size} 个")
            val existingChecksums = mutableSetOf<String>()
            hashedList.map { it.second }.chunked(500).forEach { batch ->
                existingChecksums.addAll(api.bulkUploadCheck(batch))
            }
            DiagLog.i(NTAG, "批量预检完成：服务端已有 ${existingChecksums.size} 个 / 待上传 ${hashedList.count { it.second !in existingChecksums }} 个")
            var uploaded = 0
            var skipped = 0
            var failed = 0
            var maxDateAdded = ImmichPrefs.lastSyncDateAdded(context)
            for ((row, checksum) in hashedList) {
                if (uploaded >= MAX_UPLOADS_PER_RUN) {
                    DiagLog.i(NTAG, "单次运行已达上限 $MAX_UPLOADS_PER_RUN，剩余下次继续")
                    break
                }
                // 服务端已有（官方 APP 或此前传过）→ 记账 + 推水位，绝不上传
                if (checksum in existingChecksums) {
                    markSynced(row)
                    maxDateAdded = maxOf(maxDateAdded, row.dateAdded)
                    skipped++
                    continue
                }
                val file = File(row.path)
                try {
                    updateForeground("正在上传 ${file.name}")
                    api.uploadAsset(
                        file = file,
                        isVideo = row.isVideo,
                        // 拍摄时间优先 DATE_TAKEN（DATE_ADDED 只是入库时间，会导致时间线日期错乱）
                        fileCreatedAt = (if (row.dateTaken > 0) row.dateTaken else row.dateAdded) * 1000,
                        fileModifiedAt = (if (row.dateModified > 0) row.dateModified else row.dateAdded) * 1000,
                        deviceAssetId = row.id.toString(),
                        duration = formatDuration(row.durationMs),
                        checksum = checksum,
                    )
                    markSynced(row)
                    maxDateAdded = maxOf(maxDateAdded, row.dateAdded)
                    uploaded++
                    // 成功后立即更新计数（此前只在上传前更新，单张照片全程卡在「已传 0」）
                    updateForeground("已备份 $uploaded 张")
                } catch (e: Exception) {
                    failed++
                    DiagLog.e(NTAG, "上传失败 ${file.name}：${e.message}", e)
                    // 连续失败过多视为服务不可达，整批改日重试
                    if (failed >= 5) {
                        DiagLog.w(NTAG, "连续失败 $failed 次，本轮中止（等待下次触发重试）")
                        break
                    }
                }
            }
            ImmichPrefs.saveLastSyncDateAdded(context, maxDateAdded)
            DiagLog.i(NTAG, "备份对账完成：上传 $uploaded / 跳过 $skipped / 失败 $failed")
            if (uploaded > 0) {
                // 成功反馈：顶部横幅（3 秒消失）+ 相册页前台时自动刷新时间线。
                // 延迟 3 秒递增：Immich 缩略图异步生成（约 2~5 秒），立即刷新会让新照片
                // 以灰色占位进网格（404），直到手动滑动才恢复。
                delay(3000)
                ImmichUiState.banner = "✓ 已备份 $uploaded 张照片到 Immich"
                ImmichUiState.refreshTick += 1
            }
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 系统停止 Worker（超时/省电策略）——记录后按取消传播，等待下次触发
            DiagLog.w(NTAG, "备份 Worker 被系统停止（${e.message}），等待下次触发")
            throw e
        } catch (e: Exception) {
            DiagLog.e(NTAG, "备份对账异常：${e.message}", e)
            return Result.retry()
        } finally {
            // 无论成功/失败/被系统停止，都撤掉本链路进度通知（防残留挂「已传 0」）
            runCatching { NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID) }
        }
    }

    /**
     * 增量扫描 MediaStore 候选（date_added > 水位 + 记账集合去重 + 相册勾选过滤）。
     *
     * :param context: 应用上下文
     * :return: 候选列表（时间降序）
     */
    private fun scanCandidates(context: Context): List<MediaRow> {
        val watermark = ImmichPrefs.lastSyncDateAdded(context)
        val synced = ImmichPrefs.syncedItems(context)
        val selectedBuckets = ImmichPrefs.selectedBuckets(context)
        val out = mutableListOf<MediaRow>()
        val configs = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )
        configs.forEach { (uri, isVideo) ->
            runCatching {
                val projection = mutableListOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                )
                if (isVideo) projection.add(MediaStore.Video.Media.DURATION)
                // 增量水位过滤（纯 DB 读；水位以下但记账缺失的对账由手动触发全量兜底）
                context.contentResolver.query(
                    uri,
                    projection.toTypedArray(),
                    "${MediaStore.MediaColumns.DATE_ADDED} > ?",
                    arrayOf(watermark.toString()),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    // DATE_TAKEN = 真实拍摄时间（DATE_ADDED 只是入库时间）；旧机型可能无此列
                    val takenIdx = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    } else {
                        cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    }
                    val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val durIdx = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val dateAdded = cursor.getLong(dateIdx)
                        // 记账去重（id|size|dateAdded 三元组；删除重拍同 id 不同 size 会重传）
                        if ("$id|${cursor.getLong(sizeIdx)}|$dateAdded" in synced) continue
                        val bucket = cursor.getString(bucketIdx) ?: ""
                        // 勾选相册制：空集合 = 全部相册；非空 = 仅勾选项
                        if (selectedBuckets.isNotEmpty() && bucket !in selectedBuckets) continue
                        val dateTaken = if (takenIdx >= 0) cursor.getLong(takenIdx) else 0L
                        val dateModified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                        out.add(
                            MediaRow(
                                id = id,
                                path = cursor.getString(dataIdx) ?: continue,
                                size = cursor.getLong(sizeIdx),
                                dateAdded = dateAdded,
                                dateTaken = dateTaken,
                                dateModified = dateModified,
                                bucket = bucket,
                                isVideo = isVideo,
                                durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L,
                            ),
                        )
                    }
                }
            }.onFailure {
                DiagLog.w(NTAG, "扫描 ${if (isVideo) "视频" else "图片"} 失败：${it.message}")
            }
        }
        DiagLog.i(NTAG, "增量扫描候选 ${out.size} 个（水位 $watermark，勾选 ${if (selectedBuckets.isEmpty()) "全部" else selectedBuckets.size} 相册）")
        return out.sortedByDescending { it.dateAdded }
    }

    /**
     * 记账一条已同步记录（id|size|dateAdded 追加进集合）。
     *
     * :param row: 媒体记录
     */
    private fun markSynced(row: MediaRow) {
        val item = "${row.id}|${row.size}|${row.dateAdded}"
        ImmichPrefs.addSyncedItem(applicationContext, item)
    }

    /**
     * 视频毫秒时长转 Immich 格式（"0:00:12.00000" = H:mm:ss.sssss；图片空串）。
     *
     * :param durationMs: 毫秒
     * :return: Immich duration 字段格式
     */
    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return ""
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%d:%02d:%02d.00000".format(h, m, s)
    }

    /**
     * 构造前台服务进度通知（API 34+ 须声明 dataSync 前台类型）。
     *
     * :param text: 通知文案
     * :return: ForegroundInfo
     */
    private fun createForegroundInfo(text: String): androidx.work.ForegroundInfo {
        ImmichBackup.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("相册自动备份")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            androidx.work.ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 更新前台通知文案（未前台化时静默跳过）。
     *
     * :param text: 通知文案
     */
    private fun updateForeground(text: String) {
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, createForegroundInfo(text).notification)
        }
    }
}
