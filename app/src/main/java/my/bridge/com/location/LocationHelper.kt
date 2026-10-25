package my.bridge.com.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 设备定位辅助（get_location 命令的实现核心，模式照搬 [my.bridge.com.media.MusicPlayer]）。
 *
 * 定位策略（无 GMS 依赖，全部走 AOSP 原生 [LocationManager]，国产 ROM 可用）：
 * - coarse 粗略：先取 network/passive 的最近缓存位置（5 分钟内视为新鲜直接返回），
 *   无新鲜缓存则监听一次 network 定位；仍超时则回退任意 lastKnown（标注陈旧）。
 * - fine 精确：监听一次 gps 定位；超时自动回退 coarse 结果（回执注明）。
 *
 * 权限：COARSE/FINE 均为运行时权限，调用方（命令执行器）先校验，未授权抛
 * [SecurityException] 由执行器转失败回执。
 *
 * 持久化预留：[LocationFix] 为结构化数据，v1 仅格式化进回执文本，
 * 后续版本可直接序列化上报服务端入库（SQL/MySQL）。
 */
object LocationHelper {

    /** 一次定位结果（结构化，为服务端持久化预留）。 */
    data class LocationFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val provider: String,
        val timestampMs: Long,
        val altitudeMeters: Double?,
        val speedMps: Float?,
    ) {
        /** 回执文本（下发历史直接可见）。 */
        fun toMessage(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestampMs))
            val alt = altitudeMeters?.let { "，海拔 ${"%.1f".format(it)}m" } ?: ""
            val spd = speedMps?.let { "，速度 ${"%.1f".format(it)}m/s" } ?: ""
            return "位置：${"%.6f".format(latitude)}, ${"%.6f".format(longitude)}" +
                "（±${accuracyMeters.toInt()}m，来源 $provider，$time$alt$spd）"
        }
    }

    /** lastKnown 缓存的新鲜度阈值（5 分钟）。 */
    private const val FRESH_MS = 5 * 60 * 1000L

    /** 是否已授予任一定位权限。 */
    fun hasPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** 是否已授予精确定位权限（fine 模式必需）。 */
    fun hasFinePermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * 获取一次位置。
     *
     * @param precision "coarse"（网络定位）/ "fine"（GPS，超时回退网络）
     * @param timeoutMs 等待上限（毫秒）
     * @throws SecurityException 权限未授予
     * @throws IllegalStateException 各 provider 均无法取得位置
     */
    suspend fun getLocation(context: Context, precision: String, timeoutMs: Long): LocationFix {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (precision == "fine") {
            val gps = requestSingle(lm, LocationManager.GPS_PROVIDER, timeoutMs)
            if (gps != null) {
                gps.toFix("gps")
            } else {
                Timber.i("GPS 定位超时，回退网络定位")
                coarse(context, lm, timeoutMs).let {
                    it.copy(provider = "${it.provider}/GPS超时回退")
                }
            }
        } else {
            coarse(context, lm, timeoutMs)
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 粗略定位：新鲜缓存 → 主动监听一次 → 陈旧缓存兜底。 */
    private suspend fun coarse(
        context: Context,
        lm: LocationManager,
        timeoutMs: Long,
    ): LocationFix {
        val cached = lastKnown(lm, LocationManager.NETWORK_PROVIDER)
            ?: lastKnown(lm, LocationManager.PASSIVE_PROVIDER)
        if (cached != null && System.currentTimeMillis() - cached.time <= FRESH_MS) {
            return cached.toFix("network(缓存)")
        }
        val fresh = requestSingle(lm, LocationManager.NETWORK_PROVIDER, timeoutMs)
        if (fresh != null) return fresh.toFix("network")
        // 全部超时：有陈旧缓存也比失败强（标注陈旧时间）
        if (cached != null) {
            val ageMin = (System.currentTimeMillis() - cached.time) / 60000
            return cached.toFix("network(缓存${ageMin}分钟前)")
        }
        throw IllegalStateException("网络定位超时且无历史位置（设备可能无网络/室内信号差）")
    }

    /** 监听一次指定 provider 的定位，超时返回 null（监听自动注销）。 */
    private suspend fun requestSingle(
        lm: LocationManager,
        provider: String,
        timeoutMs: Long,
    ): Location? {
        if (!isProviderUsable(lm, provider)) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                }
                try {
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Timber.w(e, "requestSingleUpdate 失败：$provider")
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
    }

    /** provider 可用性（存在且已启用；GPS 在开关关闭时不可用）。 */
    private fun isProviderUsable(lm: LocationManager, provider: String): Boolean =
        runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)

    /** 取 lastKnown（无权限或无记录返回 null）。 */
    private fun lastKnown(lm: LocationManager, provider: String): Location? {
        if (!isProviderUsable(lm, provider)) return null
        return runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    private fun Location.toFix(provider: String): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else 0f,
        provider = provider,
        timestampMs = time,
        altitudeMeters = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed else null,
    )

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
