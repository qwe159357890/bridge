package my.bridge.com.jikong

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 时速仪表盘（EV 仪表盘）：GPS 时速 + 行程电耗积分 + 剩余续航估算。
 *
 * ## 方法论来源（2026-11-06 调研，EV 行业通行做法）
 * - **续航 = 剩余可用能量 ÷ 预测电耗**（evkx.net / 科普中国 / CN114572056A 专利一致）。
 *   特斯拉主显用额定电耗、Energy App 用近程滚动平均（论文与实测均认为距离加权平均最稳）。
 * - **EMA 指数滑动平均**平滑瞬时电耗（LUT 硕士论文：α=0.1、参考值暖启动、防乐观下限），
 *   避免单次急加速把平均电耗打爆、续航数字跳水。
 * - **距离加权平均优先**（专利 WO2026099500A1：短区间 3km → 滚动进长区间）：本实现用
 *   `行程总耗电 ÷ 行程总里程`（天然距离加权），里程不足时退回 EMA，再退回参考值。
 * - **参考值（暖启动种子）**：两轮/三轮车实测电耗 10~28 Wh/km（48V 新国标 ~10-15、
 *   60V 电摩 ~16-20、72V 电摩 ~22-28），本机 64V/50Ah 大容量车型取 **20 Wh/km**；
 *   上次行程平均会持久化为下次的种子，越用越准。
 *
 * ## 省电设计（用户最高优先级约束）
 * - 生命周期完全由页面驱动：**仅「开关开 + 极空页可见」时**订阅 GPS（1Hz）+ 跑积分协程；
 *   页面离开（ON_PAUSE / dispose）→ `stop()` 注销监听并取消协程，**GPS 芯片回落，零后台成本**。
 * - 不与位置周期上报（coarse 网络定位）合并——合并会逼 GPS 常开，反而更耗电。
 * - 持续 1Hz 订阅比反复 requestSingleUpdate 省电（避免反复冷启动搜星）。
 *
 * ## 防御性设计（红蓝对抗清单）
 * - **充电不计耗电**：电流正=充电，只积分放电方向功率（P = -I×V > 0），防止充电时虚增续航。
 * - **GPS 毛刺过滤**：speed<0 或 >150km/h 丢弃（两轮/三轮不可能）；瞬时电耗单样本钳位 1~200。
 * - **数据新鲜度门控**：GPS >5s 无定位（隧道/遮挡）→ 时速显示 --、里程/电耗暂停积分
 *   （不虚记）；BMS >5s 无帧（断连/半死链）→ 续航显示 --、积分暂停。
 * - **除零保护**：时速 <2km/h 不算瞬时电耗（分母发散）；行程 <0.5km 不出平均电耗。
 * - **演示模式隔离**：demoMode 时 BMS 数据是假值，积分与续航全部停用（只显示真实 GPS 时速）。
 * - **强跳过陷阱**：所有对外状态为 @Volatile 基元 + version 状态号，UI 只传基元参数
 *   （v0.2.79 冻结教训）。
 * - **快速开关抖动**：start/stop 幂等（running 守卫），监听注销两重兜底。
 * - **累计统计不重复计**：清零行程前先提交（commit）已累计值到持久层，杜绝双计。
 */
object SpeedDash {

    private const val TAG = "SpeedDash"

    /** 参考电耗种子（Wh/km）：64V 大容量两轮/三轮车型典型值。 */
    const val REFERENCE_WH_PER_KM = 20f

    /** 瞬时电耗 EMA 系数（1Hz 采样，时间常数 ≈33s，LUT 论文同思路）。 */
    private const val EMA_ALPHA = 0.03f

    /** 瞬时电耗显示平滑系数（更快，仅展示用）。 */
    private const val INST_ALPHA = 0.2f

    /** 行程平均电耗生效的最小里程（km），防起步抖动。 */
    private const val TRIP_AVG_MIN_KM = 0.5f

    /** 生命周期累计平均电耗生效的最小累计里程（km）（v0.2.90：经验数据从装机起累积）。 */
    private const val LIFETIME_AVG_MIN_KM = 1f

    /** 平均时速生效的最小移动时长（秒），防起步除零抖动（v0.2.96 10→5s，数值更快出现）。 */
    private const val AVG_SPEED_MIN_S = 5f

    /** EMA 信任门槛：移动累计 >30s 才用 EMA 兜底（否则种子/上次历史）。 */
    private const val EMA_MIN_MOVING_S = 30f

    private const val GPS_FRESH_MS = 5_000L
    private const val BMS_FRESH_MS = 5_000L

    /** GPS 毛刺上限（km/h）。 */
    private const val SPEED_MAX_KMH = 150f

    /** 瞬时电耗分母下限（km/h）：低于此值分母发散，显示 --。 */
    private const val SPEED_VALID_KMH = 2f

    /** 判定「移动中」的速度下限（km/h）。 */
    private const val MOVING_KMH = 0.5f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lm: LocationManager? = null
    private var tickJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastTickAt = 0L

    @Volatile
    private var lastFixElapsed = -10_000L

    // ---- 会话基线（累计统计防双计：committed = 进入本会话前的持久值）----
    private var baseTotalKm = 0f
    private var baseTotalWh = 0f

    // ==================== 对外只读状态（全部基元，UI 传值） ====================

    /** 版本号（Compose 可观察）：每次刷新自增，UI 读它建立订阅。 */
    val version = androidx.compose.runtime.mutableStateOf(0L)

    /** GPS 时速（km/h）。<0 = 尚无有效定位。 */
    @Volatile
    var speedKmh: Float = -1f
        private set

    /** GPS 数据是否新鲜（5s 内有定位）。 */
    @Volatile
    var gpsFresh: Boolean = false
        private set

    /** 剩余续航估算（km）。<0 = 不可估算（BMS 数据缺失/演示模式）。 */
    @Volatile
    var rangeKm: Float = -1f
        private set

    /** 瞬时电耗（Wh/km，已平滑）。<0 = 当前不可算（静止/低速/数据缺失）。 */
    @Volatile
    var instWhKm: Float = -1f
        private set

    /** 行程平均电耗（Wh/km，距离加权）。<0 = 里程不足 0.5km。 */
    @Volatile
    var avgWhKm: Float = -1f
        private set

    /** 本次行程里程（km，BMS 放电口径：只累计放电>1W 时的距离，供电耗距离加权用）。 */
    @Volatile
    var tripKm: Float = 0f
        private set

    /** 本次行程 GPS 里程（km，v≥0.5 即积分，与保护板解耦；v0.2.95 平均时速/里程显示口径）。 */
    @Volatile
    var gpsTripKm: Float = 0f
        private set

    /** 本次行程耗电（Wh）。 */
    @Volatile
    var tripWh: Float = 0f
        private set

    /** 本次行程时长（秒，页面运行即计时）。 */
    @Volatile
    var tripSeconds: Float = 0f
        private set

    /** 本次行程平均时速（km/h）＝行程里程 ÷ 移动时间（v≥0.5 计时）。<0 = 移动时间不足。 */
    @Volatile
    var avgSpeedKmh: Float = -1f
        private set

    /** BMS 数据是否停滞（>5s 无帧 / 演示模式）。 */
    @Volatile
    var bmsStale: Boolean = false
        private set

    /** 是否缺精确定位权限（UI 据此显示授权按钮）。 */
    @Volatile
    var gpsPermissionNeeded: Boolean = false
        private set

    // ==================== 生命周期 ====================

    /**
     * 启动（幂等）：订阅 GPS 1Hz + 启动 1Hz 积分协程。
     * 无精确定位权限时不启动 GPS（置 [gpsPermissionNeeded] 供 UI 引导授权）。
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            gpsPermissionNeeded = true
            Timber.tag(TAG).w("缺精确定位权限，仪表盘未启动（等待授权）")
            return
        }
        gpsPermissionNeeded = false
        appContextRef = context.applicationContext
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            Timber.tag(TAG).w("GPS 未开启，仪表盘未启动")
            gpsPermissionNeeded = false
            // GPS 关闭不算权限问题；tick 仍不启动（无数据源），UI 依 gpsFresh=false 显示 --
            return
        }
        lm = manager
        val listener = LocationListener { loc -> onFix(loc) }
        gpsListener = listener
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper(),
            )
        }.onFailure { Timber.tag(TAG).w(it, "GPS 订阅失败") }
        // 会话基线：读持久累计值，本会话累计 = 基线 + 行程值（防清零/切换双计）
        if (baseTotalKm <= 0f && baseTotalWh <= 0f && tripKm <= 0f && tripWh <= 0f) {
            baseTotalKm = JikongPrefs.totalKm(app)
            baseTotalWh = JikongPrefs.totalWh(app)
        }
        lastTickAt = SystemClock.elapsedRealtime()
        tickJob?.cancel()
        tickJob = scope.launch { tickLoop() }
        running = true
        Timber.tag(TAG).i("时速仪表盘启动（GPS 1Hz + 行程积分）")
    }

    /** 停止（幂等）：注销 GPS、取消协程、持久化累计与历史平均电耗。行程数据保留（回到页面继续）。 */
    fun stop() {
        if (!running) return
        running = false
        gpsListener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        gpsListener = null
        lm = null
        tickJob?.cancel()
        tickJob = null
        val app = appContextRef
        if (app != null) persistCumulative(app)
        Timber.tag(TAG).i("时速仪表盘停止（行程保留：%.2fkm / %.1fWh）", tripKm, tripWh)
    }

    /** 清零本次行程（先提交累计值到持久层，再归零，杜绝双计）。 */
    fun resetTrip(context: Context) {
        val app = context.applicationContext
        // 1) 把当前行程提交进累计基线并落盘
        baseTotalKm += tripKm
        baseTotalWh += tripWh
        JikongPrefs.setTotalKm(app, baseTotalKm)
        JikongPrefs.setTotalWh(app, baseTotalWh)
        // 2) 行程归零
        tripKm = 0f; tripWh = 0f; gpsTripKm = 0f; tripSeconds = 0f; movingSeconds = 0f; movingSecondsAvg = 0f
        emaWhKm = -1f; instEma = -1f; avgWhKm = -1f; avgSpeedKmh = -1f
        version.value = version.value + 1
        Timber.tag(TAG).i("行程清零（累计已提交：%.2fkm / %.1fWh）", baseTotalKm, baseTotalWh)
    }

    /** 授权后由 UI 调用：重试启动。 */
    fun onPermissionGranted(context: Context) {
        gpsPermissionNeeded = false
        start(context)
    }

    /**
     * 演示模式数据注入（v0.2.98）：演示模式没有 GPS/保护板，积分协程不跑，
     * 由 [JikongController.refreshDemoData] 直接灌入一套合理的模拟值
     * （进入演示=默认组合；点「刷新」=重新随机）。字段与 tickLoop 写的同源，UI 无感知。
     */
    fun injectDemo(
        speed: Float,
        avgSpeed: Float,
        range: Float,
        instWh: Float,
        avgWh: Float,
        tripKmValue: Float,
        seconds: Float,
    ) {
        speedKmh = speed
        gpsFresh = speed >= 0f
        avgSpeedKmh = avgSpeed
        rangeKm = range
        instWhKm = instWh
        avgWhKm = avgWh
        gpsTripKm = tripKmValue
        tripSeconds = seconds
        bmsStale = false
        version.value = version.value + 1
    }

    private var gpsListener: LocationListener? = null

    @Volatile
    private var appContextRef: Context? = null

    // ---- 内部积分状态 ----
    @Volatile private var emaWhKm = -1f        // EMA 电耗（续航预测用）
    @Volatile private var instEma = -1f        // 瞬时显示平滑
    @Volatile private var movingSeconds = 0f   // 移动累计（EMA 信任门槛）
    @Volatile private var movingSecondsAvg = 0f // 移动累计（平均时速用，v≥0.5 计时，GPS 口径独立于 BMS）
    @Volatile private var lastPersistAt = 0L

    /** GPS 定位回调（主线程）：过滤毛刺后更新时速。 */
    private fun onFix(loc: Location) {
        val v = loc.speed * 3.6f
        if (v < 0f || v > SPEED_MAX_KMH) return  // 毛刺丢弃
        speedKmh = v
        lastFixElapsed = SystemClock.elapsedRealtime()
        gpsFresh = true
        version.value = version.value + 1
    }

    /** 1Hz 积分协程：行驶里程/耗电积分、平均与 EMA 电耗、续航估算。 */
    private fun CoroutineScope.tickLoop() = launch {
        val controller = JikongController.instance
        while (isActive) {
            delay(1_000)
            val nowElapsed = SystemClock.elapsedRealtime()
            val dt = ((nowElapsed - lastTickAt) / 1000f).coerceIn(0.5f, 5f)
            lastTickAt = nowElapsed

            // ---- GPS 新鲜度 ----
            gpsFresh = speedKmh >= 0f && (nowElapsed - lastFixElapsed) <= GPS_FRESH_MS
            val v = if (gpsFresh) speedKmh else -1f

            // ---- BMS 数据新鲜度（演示模式视同停滞，绝不积分假数据）----
            val s = controller.status
            val bmsOk = !controller.demoMode && controller.hasDataFrame &&
                (System.currentTimeMillis() - s.lastUpdateAt) <= BMS_FRESH_MS
            bmsStale = !bmsOk

            tripSeconds += dt
            if (v >= MOVING_KMH) {
                // v0.2.95：平均时速/里程显示口径改 GPS 独立积分（与保护板解耦）——
                // 修「保护板数据未到时平均时速被稀释成 0.0」：tripKm 仍按放电口径供电耗用
                movingSecondsAvg += dt
                gpsTripKm += v * dt / 3600f
            }
            var p = 0f
            if (bmsOk && v >= MOVING_KMH) {
                // 放电功率（W）：电流正=充电，只取放电方向；充电/回充不计
                p = if (s.current < 0f) -s.current * s.totalVoltage else 0f
                if (p > 1f) {
                    tripWh += p * dt / 3600f
                    if (v >= SPEED_VALID_KMH) {
                        tripKm += v * dt / 3600f
                        movingSeconds += dt
                        val eInst = (p / v).coerceIn(1f, 200f)   // 单样本钳位抗毛刺
                        emaWhKm = if (emaWhKm < 0f) eInst else emaWhKm + EMA_ALPHA * (eInst - emaWhKm)
                        instEma = if (instEma < 0f) eInst else instEma + INST_ALPHA * (eInst - instEma)
                    }
                }
            }
            lastPowerW = p

            // ---- 平均电耗（距离加权：总耗电 ÷ 总里程，专利/特斯拉同思路）----
            avgWhKm = if (tripKm >= TRIP_AVG_MIN_KM && tripWh > 0f) tripWh / tripKm else -1f

            // ---- 平均时速（v0.2.97 真因修复）：GPS 里程(km) ÷ 移动时间(秒) × 3600。
            //      v0.2.90~96 少乘 3600（km/s 当 km/h 用），10km/h 算出 0.003 → 永远显示 0.0。
            //      门槛放宽到 0.001km（5s 移动 @10km/h ≈ 0.014km，起步快出数）----
            avgSpeedKmh =
                if (gpsTripKm > 0.001f && movingSecondsAvg >= AVG_SPEED_MIN_S) gpsTripKm * 3600f / movingSecondsAvg else -1f

            // ---- 预测电耗（v0.2.90 估算链改造）：本次行程平均 > 生命周期累计平均（装机起累积，
            //      复用 totalWh/totalKm 持久化，里程越攒越准）> EMA(移动>30s 会话暖场) >
            //      历史种子(老用户 histAvg 兜底) > 参考值；钳位防乐观/发散 ----
            val lifetimeKm = baseTotalKm + tripKm
            val lifetimeWh = baseTotalWh + tripWh
            val lifetimeAvg =
                if (lifetimeKm >= LIFETIME_AVG_MIN_KM && lifetimeWh > 0f) lifetimeWh / lifetimeKm else -1f
            val hist = appContextRef?.let { JikongPrefs.histAvgWhKm(it) } ?: -1f
            val ePred = when {
                avgWhKm > 0f -> avgWhKm
                lifetimeAvg > 0f -> lifetimeAvg
                emaWhKm > 0f && movingSeconds >= EMA_MIN_MOVING_S -> emaWhKm
                hist > 0f -> hist
                else -> REFERENCE_WH_PER_KM
            }.coerceIn(6f, 100f)

            // ---- 剩余续航 = 剩余能量 ÷ 预测电耗（evkx/专利一致公式）----
            rangeKm = if (bmsOk && s.remainCapacity > 0f && s.totalVoltage > 0f) {
                val eRemWh = s.remainCapacity * s.totalVoltage   // Ah × V = Wh
                (eRemWh / ePred).coerceIn(0f, 999f)
            } else {
                -1f
            }

            // ---- 瞬时电耗显示（静止/低速/缺数据 → --）----
            instWhKm = if (bmsOk && gpsFresh && v >= SPEED_VALID_KMH && p > 1f) {
                (p / v).coerceIn(1f, 200f)
            } else {
                -1f
            }

            // ---- 周期持久化（30s 一次；stop 时兜底）----
            val app = appContextRef
            if (app != null && nowElapsed - lastPersistAt > 30_000L) {
                lastPersistAt = nowElapsed
                persistCumulative(app)
            }

            version.value = version.value + 1
        }
    }

    @Volatile private var lastPowerW = 0f

    /** 累计统计落盘：基线 + 本次行程（v0.2.90 起历史平均电耗不再单独落盘，估算链直接用 totalWh÷totalKm）。 */
    private fun persistCumulative(app: Context) {
        runCatching {
            JikongPrefs.setTotalKm(app, baseTotalKm + tripKm)
            JikongPrefs.setTotalWh(app, baseTotalWh + tripWh)
        }
    }
}
