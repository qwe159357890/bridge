package my.bridge.com.blekey

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用内自检：边播边扫 5 秒，尝试收到**自己**那条 iBeacon。
 *
 * ## 为什么结果要分四档（重要）
 *
 * 「自己扫自己」在多数机型上**不成立**：BLE 单天线分时收发，controller 通常不上报自发包
 * （own-address filter）。实测这台机器就是如此——ESP32 能稳定扫到、还在通电，
 * 但手机自检扫不到自己。所以**扫不到 ≠ 广播有问题**，绝不能报成失败。
 *
 * 因此自检的真实价值是**分层诊断**：靠"扫到了什么"反推扫描链路是否健康。
 *
 * | 结论 | 含义 | 能证明什么 |
 * |---|---|---|
 * | [Outcome.SELF_FOUND] | 收到自己的 UUID | **强证据**：确实在播，且格式正确 |
 * | [Outcome.NO_SELF_SCAN_OK] | 收到别的 iBeacon，但没自己 | 扫描 + Apple 厂商数据解析都正常；纯粹是本机不上报自发包 |
 * | [Outcome.NO_SELF_NO_BEACON] | 只收到别的 BLE 设备，无 iBeacon | 扫描正常，但附近没有 iBeacon，无法据此判断 |
 * | [Outcome.NOTHING_SEEN] | 一包都没收到 | **扫描链路异常**：被其他扫描占用 / 系统限制 / 蓝牙栈异常 |
 *
 * **只在用户点击时执行**，绝不进开播流程——常驻扫描是极空 autoConnect 那种量级（3.75% 占空比），
 * 一次性 5 秒 ≈ 扫一次二维码，日耗电不可测。
 *
 * 设计文档：docs/research/ble-key-broadcast.md §11、§15.2。
 */
object BleKeySelfCheck {

    private const val TAG = "BleKeySelfCheck"
    private const val DURATION_MS = 5_000L

    /** 连续/累计命中这么多帧就提前结束（不必等满 5 秒）。 */
    private const val HIT_TARGET = 3

    enum class Outcome {
        /** 扫到了自己 → 强证据，说明确实在播。 */
        SELF_FOUND,

        /** 扫到了别的 iBeacon、但没自己 → 扫描与解析正常，本机不上报自发包。 */
        NO_SELF_SCAN_OK,

        /** 扫到了别的 BLE 设备、但没有 iBeacon → 扫描正常，附近没有信标。 */
        NO_SELF_NO_BEACON,

        /** 5 秒内一个包都没收到 → **无法判断**（可能被占用，也可能附近确实没有 BLE 设备）。 */
        NOTHING_SEEN,

        /** 启动前/启动时失败（权限、蓝牙关、不支持、异常）。 */
        FAILED,
    }

    data class Result(
        val outcome: Outcome,
        /** 命中自己时的最强 RSSI（dBm）。 */
        val rssi: Int? = null,
        /** 命中自己的帧数。 */
        val hits: Int = 0,
        /** 5 秒内扫到的不同 BLE 设备数（按地址去重）。 */
        val deviceCount: Int = 0,
        /** 其中带 iBeacon 厂商数据的设备数。 */
        val beaconCount: Int = 0,
        val error: String? = null,
    )

    private val handler = Handler(Looper.getMainLooper())

    /** 强引用，防 GC（扫描回调被回收会静默失效）。 */
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    @Volatile
    private var runningFlag = false

    fun isRunning(): Boolean = runningFlag

    /**
     * 开始自检（5 秒）。
     *
     * @param uuidHex 要匹配的完整 32 字符 UUID
     * @param onDone 必然回调一次，**在主线程**
     */
    fun start(context: Context, uuidHex: String, onDone: (Result) -> Unit) {
        val appCtx = context.applicationContext

        if (runningFlag) {
            onDone(Result(Outcome.FAILED, error = "自检正在进行"))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onDone(Result(Outcome.FAILED, error = "缺少「蓝牙扫描」权限，无法自检"))
            return
        }

        val adapter = appCtx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onDone(Result(Outcome.FAILED, error = "蓝牙未开启，无法自检"))
            return
        }
        val le = adapter.bluetoothLeScanner
        if (le == null) {
            onDone(Result(Outcome.FAILED, error = "本机不支持 BLE 扫描"))
            return
        }

        val target = uuidHex.lowercase()

        // 跨线程计数：扫描回调在 binder 线程，判定与收尾在主线程
        val seenAddrs = ConcurrentHashMap.newKeySet<String>()
        val beaconAddrs = ConcurrentHashMap.newKeySet<String>()
        val hits = AtomicInteger(0)
        val bestRssi = AtomicInteger(Int.MIN_VALUE)
        val finished = AtomicBoolean(false)

        fun finish(build: () -> Result) {
            if (!finished.compareAndSet(false, true)) return
            runningFlag = false
            val cb = callback
            callback = null
            scanner = null
            if (cb != null) runCatching { le.stopScan(cb) }
            val res = runCatching(build).getOrElse {
                Result(Outcome.FAILED, error = "自检异常：${it.message}")
            }
            // ★ 结果落日志（v0.3.008）：以前只记「自检开始」，用户报「自检报错」时日志里查不到结论。
            //   四档诊断里「无法自检」才算红色，其余都是环境证据，故按结果分级。
            val line = "自检结果=${res.outcome} RSSI=${res.rssi ?: "-"} 命中=${res.hits} " +
                "设备数=${res.deviceCount} 信标数=${res.beaconCount}" +
                (res.error?.let { " 错误=$it" } ?: "")
            if (res.outcome == Outcome.FAILED) {
                Timber.tag(TAG).w(line)
            } else {
                Timber.tag(TAG).i(line)
            }
            // ★ 统一回主线程：UI 更新 Compose 状态必须在主线程
            handler.post { onDone(res) }
        }

        fun snapshot(hitCount: Int, outcome: Outcome) = Result(
            outcome = outcome,
            rssi = bestRssi.get().takeIf { it != Int.MIN_VALUE },
            hits = hitCount,
            deviceCount = seenAddrs.size,
            beaconCount = beaconAddrs.size,
        )

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val r = result ?: return
                val addr = runCatching { r.device?.address }.getOrNull().orEmpty()
                if (addr.isNotEmpty()) seenAddrs.add(addr)

                val data = r.scanRecord
                    ?.getManufacturerSpecificData(BleKeyProtocol.COMPANY_ID_APPLE)
                    ?: return
                // iBeacon payload：02 15 <uuid 16B> ...（不含公司 ID）
                if (data.size < 18) return
                if (data[0].toInt() != BleKeyProtocol.IBEACON_TYPE) return
                if (data[1].toInt() != BleKeyProtocol.IBEACON_LEN) return
                if (addr.isNotEmpty()) beaconAddrs.add(addr)

                val hex = buildString(32) {
                    for (i in 2 until 18) append("%02x".format(data[i]))
                }
                if (hex != target) return

                val n = hits.incrementAndGet()
                val rssi = r.rssi
                bestRssi.getAndUpdate { prev -> if (rssi > prev) rssi else prev }
                if (n >= HIT_TARGET) {
                    finish { snapshot(n, Outcome.SELF_FOUND) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                finish { Result(Outcome.FAILED, error = "扫描启动失败（code=$errorCode）") }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 先登记再启动：startScan 返回后回调可能立刻到，避免 finish 拿不到 callback 而无法 stopScan
        callback = cb
        scanner = le
        runningFlag = true

        try {
            le.startScan(null, settings, cb)
            Timber.tag(TAG).i("自检开始，5 秒内匹配 %s", target)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "自检启动异常")
            finish { Result(Outcome.FAILED, error = "自检启动异常：${e.message}") }
            return
        }

        handler.postDelayed({
            finish {
                val h = hits.get()
                when {
                    h > 0 -> snapshot(h, Outcome.SELF_FOUND)
                    beaconAddrs.isNotEmpty() -> snapshot(h, Outcome.NO_SELF_SCAN_OK)
                    seenAddrs.isNotEmpty() -> snapshot(h, Outcome.NO_SELF_NO_BEACON)
                    else -> snapshot(h, Outcome.NOTHING_SEEN)
                }
            }
        }, DURATION_MS)
    }

    /** 主动取消（页面销毁时）。 */
    fun cancel() {
        val cb = callback
        val le = scanner
        callback = null
        scanner = null
        runningFlag = false
        if (cb != null && le != null) runCatching { le.stopScan(cb) }
    }
}
