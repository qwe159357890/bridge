package my.bridge.com.blekey

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** 广播器对外状态（UI 与前台服务共用）。 */
enum class BleKeyState {
    STOPPED,        // 未广播
    ADVERTISING,    // 广播中
    BT_OFF,         // 蓝牙未开
    NO_PERMISSION,  // 缺 BLUETOOTH_ADVERTISE
    UNSUPPORTED,    // 芯片/系统不支持 BLE 广播
    FAILED,         // startAdvertising 失败（lastError 有中文原因）
}

/**
 * BLE 广播器封装（手机作为 peripheral 播 iBeacon）。
 *
 * 与极空 `JikongBleClient` 完全独立：极空是 central（扫描/连接），本类是 peripheral（广播）。
 *
 * 四个必须遵守的点（否则会静默失效，见 docs/research/ble-key-broadcast.md §9）：
 * 1. **强引用 `BluetoothLeAdvertiser` 与 `AdvertiseCallback`**——被 GC 后广播会静默停止；
 * 2. **幂等启动**：已在播不重复 `startAdvertising`（否则 `ADVERTISE_FAILED_ALREADY_STARTED`）；
 * 3. **重开会话前先停**：改编号/改功率都要 stop → start，否则拿不到新参数；
 * 4. **失败码必须翻译**：`onStartFailure` 的码要变成人能看懂的文案（UI 第 4 项）。
 */
object BleKeyBroadcaster {

    private const val TAG = "BleKey"

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    /** 当前广播的 UUID，用于幂等判断（同一 UUID + 同一档位已在播则不重启）。 */
    private var currentUuid: String? = null

    /** 当前广播档位（`ADVERTISE_MODE_*`），与 UUID 一起构成幂等键。 */
    private var currentMode: Int = -1

    /** 最近一次组出的厂商数据 hex（排障用，与对端串口日志比对）。 */
    @Volatile
    var lastPayloadHex: String? = null
        private set

    /**
     * 本次启动是否只是「例行重建」——force=true 且 UUID/档位都没变（60 秒巡检的日常动作）。
     *
     * 为什么单独标记：巡检每 60 秒走一次 stop → start，原本会产 2 条 I 级
     * （「广播已停止」+「广播已启动」）≈ 2880 条/天，把上传日志的 5000 行额度一天就吃光。
     * 例行重建降为 1 条 D 级（不刷 Logcat、仍落盘可上传），参数真变或首启才保持 I 级。
     */
    private var silentRebuild = false

    /** 上次失败码：同一码连续失败（巡检会每 60 秒重试）只打一次 E，其余降 D。 */
    private var lastFailCode = Int.MIN_VALUE

    private val _state = MutableStateFlow(BleKeyState.STOPPED)
    val state: StateFlow<BleKeyState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 最近一次失败的中文原因（state = FAILED 时有值）。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun isAdvertising(): Boolean = _state.value == BleKeyState.ADVERTISING

    /**
     * 开播。默认幂等：同一 UUID + 同一档位已在播直接返回 true。
     *
     * [force] = true 时**跳过幂等检查，强制 stop → start 重建会话**。
     * 这是巡检用的「用重新提交代替查询」——Android 没有任何 API 能查
     * "广播当前是否真的在发"（[isAdvertising] 读的是本类的乐观标志位），
     * 所以周期重建是唯一能治「ROM 静默停播且不回调」的手段。
     * 重建空窗约 30~100 ms，对端 15 秒才判失联，安全。
     *
     * @param uuidHex 32 字符小写 hex（由 [BleKeyProtocol.fullUuid] 拼装）
     * @param txPowerLevel `AdvertiseSettings.ADVERTISE_TX_POWER_*` 常量
     * @param advertiseMode `ADVERTISE_MODE_BALANCED`（250ms，活动态）/
     *        `ADVERTISE_MODE_LOW_POWER`（1000ms，后台慢速保底）
     * @param force 跳过幂等，强制重建会话（巡检 / 蓝牙重新打开时用）
     */
    fun start(
        context: Context,
        uuidHex: String,
        txPowerLevel: Int,
        advertiseMode: Int = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
        force: Boolean = false,
    ): Boolean {
        val appCtx = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_ADVERTISE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _error.value = "缺少「蓝牙广播」权限，请在系统设置中授予"
            _state.value = BleKeyState.NO_PERMISSION
            Timber.tag(TAG).w("缺少 BLUETOOTH_ADVERTISE 权限")
            return false
        }

        val adapter = appCtx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _error.value = "蓝牙未开启"
            _state.value = BleKeyState.BT_OFF
            return false
        }

        // 芯片不支持广播时 bluetoothLeAdvertiser 为 null
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            _error.value = "本机不支持 BLE 广播（芯片或系统限制）"
            _state.value = BleKeyState.UNSUPPORTED
            Timber.tag(TAG).w("bluetoothLeAdvertiser == null")
            return false
        }

        // 幂等：同一 UUID + 同一档位已在播（force 时跳过，用于巡检无条件重建）
        if (!force &&
            _state.value == BleKeyState.ADVERTISING &&
            currentUuid == uuidHex &&
            currentMode == advertiseMode
        ) {
            Timber.tag(TAG).d("已在广播 %s（mode=%d），忽略重复启动", uuidHex, advertiseMode)
            return true
        }
        // 换参数（编号/功率）、force 重建、或异常残留：先停再启
        // 例行重建（force 且 UUID+档位都没变）标记为静默，让这一对 stop/start 只留一条 D 级日志
        val routine = force &&
            currentUuid == uuidHex &&
            currentMode == advertiseMode &&
            _state.value == BleKeyState.ADVERTISING
        if (advertiser != null || callback != null) stopInternal(silent = routine)
        silentRebuild = routine

        val payload = try {
            BleKeyProtocol.buildIbeaconPayload(
                uuidHex = uuidHex,
                major = BleKeyProtocol.DEFAULT_MAJOR,
                minor = uuidHex.takeLast(2).toIntOrNull(16) ?: 0, // Minor = 编号，日志更好认
            )
        } catch (e: Exception) {
            _error.value = "UUID 非法：${e.message}"
            _state.value = BleKeyState.FAILED
            Timber.tag(TAG).e(e, "组包失败")
            return false
        }
        lastPayloadHex = BleKeyProtocol.payloadHex(payload)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode) // BALANCED≈250ms（活动态）/ LOW_POWER≈1000ms（后台保底）
            .setTxPowerLevel(txPowerLevel)
            .setConnectable(false)   // iBeacon 标准就是不可连接
            .setTimeout(0)           // 0 = 无限期（默认非 0 会自动停）
            .build()

        // ★ legacy 广播无需也无法显式设置：这套 startAdvertising(settings, data, cb) 老 API 本身
        //   只产 legacy PDU（31 字节上限）。扩展广播要用 startAdvertisingSet + AdvertisingSetParameters，
        //   其对端解不出 —— 所以本类**永不调用 startAdvertisingSet**。
        //   （P0 实证：AdvertiseSettings.Builder 上并无 setLegacyMode，它只存在于
        //    AdvertisingSetParameters.Builder，见 docs/research/ble-key-broadcast.md §5.1）

        // 主包：只放厂商数据。字节预算 27 + Flags 3 = 30 / 上限 31，多一个字段就爆
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)    // ★ 必须关，否则超 31 字节
            .setIncludeTxPowerLevel(false)  // ★ 必须关，否则 33 字节 → DATA_TOO_LARGE
            .addManufacturerData(BleKeyProtocol.COMPANY_ID_APPLE, payload)
            .build()

        // 扫描响应：设备名（主包放不下）。用系统蓝牙名，Android 无"自定义名进广播"的 API
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (silentRebuild) {
                    // 例行重建：D 级（不刷 Logcat，仍落盘可上传），每天 1440 条也不撑爆缓冲
                    Timber.tag(TAG).d("例行重建完成（mode=%d）", advertiseMode)
                } else {
                    Timber.tag(TAG).i("广播已启动 UUID=%s mode=%d payload=%s", uuidHex, advertiseMode, lastPayloadHex)
                }
                silentRebuild = false
                lastFailCode = Int.MIN_VALUE
                currentUuid = uuidHex
                currentMode = advertiseMode
                _error.value = null
                _state.value = BleKeyState.ADVERTISING
                BleKeyRuntime.onAdvertisingStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                val msg = failureText(errorCode)
                // 同一失败码连续出现（巡检每 60 秒重试）只在第一次打 E 级，之后降 D 级，
                // 否则一天 1440 条 E 级把日志刷满；失败次数由 BleKeyHealth 累计。
                if (errorCode != lastFailCode) {
                    Timber.tag(TAG).e("广播启动失败 code=%d %s", errorCode, msg)
                    lastFailCode = errorCode
                } else {
                    Timber.tag(TAG).d("广播启动失败（重复同码）code=%d %s", errorCode, msg)
                }
                silentRebuild = false
                _error.value = msg
                _state.value = BleKeyState.FAILED
                // 失败后清引用，允许下次重建
                advertiser = null
                callback = null
                currentUuid = null
                currentMode = -1
            }
        }

        return try {
            adv.startAdvertising(settings, advData, scanResponse, cb)
            advertiser = adv      // ★ 强引用，防 GC 导致广播静默停止
            callback = cb
            _state.value = BleKeyState.ADVERTISING // 乐观置位，回调会再确认/纠正
            true
        } catch (e: SecurityException) {
            _error.value = "缺少蓝牙广播权限"
            _state.value = BleKeyState.NO_PERMISSION
            Timber.tag(TAG).e(e, "startAdvertising 权限异常")
            false
        } catch (e: Exception) {
            _error.value = "广播启动异常：${e.message}"
            _state.value = BleKeyState.FAILED
            Timber.tag(TAG).e(e, "startAdvertising 异常")
            false
        }
    }

    /** 停播（幂等，失败也视为已停）。 */
    fun stop() {
        stopInternal()
        _state.value = BleKeyState.STOPPED
        _error.value = null
        BleKeyRuntime.onAdvertisingStopped()
    }

    private fun stopInternal(silent: Boolean = false) {
        val adv = advertiser
        val cb = callback
        advertiser = null
        callback = null
        currentUuid = null
        currentMode = -1
        if (adv != null && cb != null) {
            try {
                adv.stopAdvertising(cb)
                if (silent) {
                    Timber.tag(TAG).d("例行重建：旧会话已停")
                } else {
                    Timber.tag(TAG).i("广播已停止")
                }
            } catch (e: Exception) {
                // 蓝牙已关/栈忙时 stopAdvertising 会抛，视为已停
                Timber.tag(TAG).w(e, "stopAdvertising 异常（忽略）")
            }
        }
    }

    /** 失败码 → 中文（UI 直接展示）。 */
    private fun failureText(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已有广播在运行（内部状态异常）"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据超长（超过 31 字节）"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "本机不支持该广播特性"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "系统内部错误，请重试"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播槽位被占用（可能其他 App 正在广播）"
        else -> "未知识别码 $code"
    }
}
