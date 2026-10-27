package my.bridge.com.report

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import my.bridge.com.location.LocationHelper
import timber.log.Timber

/**
 * 位置兜底上报 Worker（WorkManager 周期任务，≥15 分钟，系统级可靠）。
 *
 * 定位：两级策略的**兜底级**——保活服务活着时由 [ReportManager] 的协程定时
 * 上报（间隔可 1~120 分钟）；服务被杀/进程死后再靠本 Worker 兜底。
 * 依赖经 [Deps] 静态注入（ReportManager.start 时装配），避免引入
 * hilt-work 额外依赖。
 *
 * 语义：开关关闭/无权限/定位失败一律 Result.success()（周期任务不做重试风暴，
 * 下个周期自然再试）；上报失败同样不重试（fire-and-forget，与命令链路一致）。
 */
class LocationReportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = Deps.instance ?: return Result.success()
        val ctx = applicationContext
        val cfg = deps.prefs.current()
        if (!cfg.locationEnabled) return Result.success()
        if (!LocationHelper.hasPermission(ctx)) return Result.success()
        return try {
            val fix = LocationHelper.getLocation(ctx, "coarse", 30_000)
            val deviceId = deps.deviceStore.deviceId()
            val token = runCatching { deps.deviceStore.token() }.getOrNull()
            deps.apiClient.reportLocation(
                deviceId = deviceId,
                token = token,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracy = fix.accuracyMeters.toDouble(),
                provider = "${fix.provider}（兜底周期上报）",
                altitude = fix.altitudeMeters,
                speed = fix.speedMps?.toDouble(),
                fixTimeMs = fix.timestampMs,
            )
            Timber.i("位置兜底上报成功：%s", fix.toMessage())
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "位置兜底上报失败（下个周期再试）")
            Result.success()
        }
    }

    /** Worker 静态依赖（应用启动时由 ReportManager 装配）。 */
    data class Deps(
        val prefs: ReportPrefs,
        val deviceStore: my.bridge.com.core.storage.DeviceStore,
        val apiClient: my.bridge.com.core.network.api.BridgeApiClient,
    ) {
        companion object {
            @Volatile
            var instance: Deps? = null
        }
    }
}
