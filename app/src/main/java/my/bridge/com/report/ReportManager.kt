package my.bridge.com.report

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.core.network.ws.WsMessageListener
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.location.LocationHelper
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动上报总管（进程内单例，应用启动 start 一次）。
 *
 * 三件事：
 * 1. **配置同步**：注册 [BridgeWebSocketClient.globalPushListener] 接收服务端
 *    ``config_update`` 推送（后台改开关/间隔即生效），落 [ReportPrefs]；
 *    配置流每次变化实时重排周期任务与短信监听。
 * 2. **周期位置上报**（两级策略）：
 *    - 主级：本类协程定时循环（间隔 1~120 分钟可配）——保活服务活着就稳定跑；
 *    - 兜底：WorkManager 周期任务 [LocationReportWorker]（系统级 ≥15 分钟，
 *      Doze 也能跑）——进程被杀后兜底；间隔变化自动 REPLACE 重排。
 * 3. **新短信即推**：开关控制 [SmsPushObserver] 注册/注销（ContentObserver
 *    方案，不要求默认短信应用）。
 *
 * 上报通道全部复用既有 REST（POST /api/locations、POST /api/sms），
 * 服务端按既有去重/合并逻辑入库，后台轨迹页与短信页无感知。
 */
@Singleton
class ReportManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val prefs: ReportPrefs,
    private val deviceStore: DeviceStore,
    private val apiClient: BridgeApiClient,
    private val webSocketClient: BridgeWebSocketClient,
    private val smsObserver: SmsPushObserver,
) {

    companion object {
        /** WorkManager 周期任务下限（系统硬约束 15 分钟）。 */
        private const val WORK_MIN_INTERVAL_MIN = 15
        private const val WORK_NAME = "bridge_location_report"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 主级周期循环句柄（配置变化时取消重建）。 */
    private var locationJob: Job? = null

    /** 应用启动调用一次：装配 Worker 依赖 → 挂配置流 → 挂 config_update 推送。 */
    fun start() {
        // WorkManager Worker 的静态依赖（Worker 不走 Hilt，启动时装配即可）
        LocationReportWorker.Deps(
            prefs = prefs, deviceStore = deviceStore, apiClient = apiClient,
        ).also { LocationReportWorker.Deps.instance = it }

        // 服务端 config_update 推送 → 落配置（配置流随后自动重排任务）
        webSocketClient.globalPushListener = WsMessageListener { data ->
            val payload = data
            if (payload != null && payload.get("event")?.asString == "config_update") {
                scope.launch {
                    try {
                        val saved = prefs.save(
                            ReportPrefs.Config(
                                locationEnabled = payload.get("location_enabled")?.asBoolean ?: true,
                                locationIntervalMin = payload.get("location_interval_min")?.asInt ?: 10,
                                smsEnabled = payload.get("sms_enabled")?.asBoolean ?: true,
                            ),
                        )
                        Timber.i("收到上报配置推送并生效：%s", saved)
                    } catch (e: Exception) {
                        Timber.w(e, "应用 config_update 推送失败")
                    }
                }
            }
        }

        // 配置流：变化即重排两级位置上报与短信监听
        scope.launch {
            prefs.configFlow.collect { cfg ->
                applyConfig(cfg)
            }
        }
        Timber.i("ReportManager 已启动（主动上报配置同步就绪）")
    }

    /** 按配置重排：主级循环 + WorkManager 兜底 + 短信观察者。 */
    private fun applyConfig(cfg: ReportPrefs.Config) {
        // ---- 主级：协程定时循环 ----
        locationJob?.cancel()
        locationJob = if (cfg.locationEnabled) {
            scope.launch {
                Timber.i("周期位置上报启动（间隔 %d 分钟）", cfg.locationIntervalMin)
                while (isActive) {
                    reportOnce()
                    delay(cfg.locationIntervalMin * 60_000L)
                }
            }
        } else {
            Timber.i("周期位置上报已关闭")
            null
        }

        // ---- 兜底：WorkManager（开关关=取消；间隔变化=REPLACE 重排）----
        val wm = WorkManager.getInstance(appContext)
        if (cfg.locationEnabled) {
            val minutes = cfg.locationIntervalMin.coerceAtLeast(WORK_MIN_INTERVAL_MIN)
            val request = PeriodicWorkRequestBuilder<LocationReportWorker>(
                minutes.toLong(), TimeUnit.MINUTES,
            ).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request,
            )
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }

        // ---- 新短信即推 ----
        if (cfg.smsEnabled) {
            smsObserver.register(appContext)
        } else {
            smsObserver.unregister()
        }
    }

    /** 主级单次定位 + 上报（fire-and-forget：失败只记日志，下个周期再试）。 */
    private suspend fun reportOnce() {
        try {
            if (!LocationHelper.hasPermission(appContext)) return
            val fix = LocationHelper.getLocation(appContext, "coarse", 30_000)
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            apiClient.reportLocation(
                deviceId = deviceId,
                token = token,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracy = fix.accuracyMeters.toDouble(),
                provider = "${fix.provider}（周期上报）",
                altitude = fix.altitudeMeters,
                speed = fix.speedMps?.toDouble(),
                fixTimeMs = fix.timestampMs,
            )
            Timber.i("周期位置上报成功：%s", fix.toMessage())
        } catch (e: Exception) {
            Timber.w(e, "周期位置上报失败（下个周期再试）")
        }
    }
}
