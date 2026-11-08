package my.bridge.com.jikong

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import timber.log.Timber

/**
 * 极空 BMS 蓝牙 BLE 客户端。
 *
 * 极空官方 APP 通过 BLE 透传与保护板通信。反编译 APK 发现多组候选 UUID
 * （不同固件/硬件代际用不同 service），本实现**运行时自适应探测**：
 *
 * 候选 service（按优先级）：
 *   1. `0000ffe0-0000-1000-8000-00805f9b34fb`（标准透传 service，BT05/CC2541 常用）
 *   2. `0000ff10-...`（JK BMS 自定义主 service）
 *   3. `0000ff80-...`（JK BMS 另一代）
 * 候选 characteristic（写/读/notify）：
 *   1. `0000ffe1-...`（透传 char）
 *   2. `0000ff11/ff12-...`、`0000ff81/ff82-...`
 *
 * 无真机前提下，本类先按「标准透传」实现（JK BMS 绝大多数固件走透传），
 * 真机联调时若连不上，再据抓包调整 UUID 优先级。
 */
object JikongBleClient {

    private const val TAG = "JikongBle"

    // ---- 候选 UUID（运行时逐一尝试）----
    private val SERVICE_UUIDS = listOf(
        "0000ffe0-0000-1000-8000-00805f9b34fb",
        "0000ff10-0000-1000-8000-00805f9b34fb",
        "0000ff80-0000-1000-8000-00805f9b34fb",
        "0000fff0-0000-1000-8000-00805f9b34fb",
    )
    private val WRITE_UUIDS = listOf(
        "0000ffe1-0000-1000-8000-00805f9b34fb",
        "0000ff11-0000-1000-8000-00805f9b34fb",
        "0000ff81-0000-1000-8000-00805f9b34fb",
        "0000fff1-0000-1000-8000-00805f9b34fb",
    )
    private val NOTIFY_UUIDS = listOf(
        "0000ffe1-0000-1000-8000-00805f9b34fb",
        "0000ff12-0000-1000-8000-00805f9b34fb",
        "0000ff82-0000-1000-8000-00805f9b34fb",
        "0000fff2-0000-1000-8000-00805f9b34fb",
    )
    private val CLIENT_CHAR_UUID =
        "00002902-0000-1000-8000-00805f9b34fb"

    // ---- 极空 BMS 广播 service UUID 白名单（用于扫描过滤）----
    // 反编译官方 APK 实证：官方扫描时按 service UUID 过滤广播，而非按设备名
    // （极空设备广播名常为数字串，如 "123456"，不能靠名字识别）。
    // 这些 16bit UUID 会展开成 128bit 完整 UUID 作为 ScanFilter 条件。
    private val JK_SCAN_SERVICE_UUIDS = listOf(
        "0000fe59-0000-1000-8000-00805f9b34fb",
        "0000ff01-0000-1000-8000-00805f9b34fb",
        "0000ff10-0000-1000-8000-00805f9b34fb",
        "0000ffe0-0000-1000-8000-00805f9b34fb",
        "0000ff80-0000-1000-8000-00805f9b34fb",
        "0000ff20-0000-1000-8000-00805f9b34fb",
        "0000fff0-0000-1000-8000-00805f9b34fb",
    )

    // ---- 连接状态 ----
    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var connectedDeviceName: String = ""
        private set

    @Volatile
    var connectedDeviceAddress: String = ""
        private set

    /**
     * 最近一次 GATT 断开的 status 码（v0.2.78）。
     * 8 = 连接监督超时（设备走远/遮挡，信号恢复即可能回连成功，适合高频快连）；
     * 133 等其它值 = 协议栈/链路错误。连接成功后复位为 -1。未经历断开时也是 -1。
     */
    @Volatile
    var lastDisconnectStatus: Int = -1
        private set

    /**
     * v0.2.107：autoConnect 挂起中（挂起≠连接：GATT 对象在、链路未建立）。
     * 挂起后蓝牙控制器按低占空比监听默认设备广播，命中即自动建链（零 CPU、零流量）；
     * 走远断开系统自动持续重试；close GATT 即彻底停止（手动断开抑制的语义根基）。
     */
    @Volatile
    var autoConnectAttached: Boolean = false
        private set

    // ---- 回调 ----
    /** 收到 BLE 通知的原始字节回调（运行在 Binder 线程，调用方自行切线程）。 */
    var onDataReceived: ((ByteArray) -> Unit)? = null

    /** 连接状态变化回调。 */
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // 收到的字节流式缓冲（BLE 通知可能分包，需重组完整帧）
    private val rxBuffer = java.io.ByteArrayOutputStream()
    // 挂起的写操作结果（coroutine 等待）
    private val writeWaiters = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()

    private fun ensureAdapter(context: Context): BluetoothAdapter? {
        if (adapter == null) {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            adapter = bm?.adapter
        }
        return adapter
    }

    /** 蓝牙是否可用（未开启返回 false）。 */
    fun isBluetoothEnabled(context: Context): Boolean =
        ensureAdapter(context)?.isEnabled == true

    // ==================== 扫描 ====================

    /**
     * 扫描周边 BLE 设备，返回设备列表（按信号强度降序）。
     *
     * 与官方 APP 对齐：默认按极空 BMS 的 service UUID 白名单过滤广播，
     * 只返回广播中带极空 service UUID 的设备（极空设备名常为数字串，不能靠名字识别）。
     *
     * @param durationMs 扫描时长，默认 8 秒。
     * @param filterByUuid true=仅返回带极空 service UUID 的设备（官方行为）；
     *                     false=不过滤，返回全部（降级：真机 UUID 不匹配时用）。
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(
        context: Context,
        durationMs: Long = 8000,
        filterByUuid: Boolean = true,
    ): List<BleDevice> = suspendCancellableCoroutine { cont ->
        val a = ensureAdapter(context)
        val leScanner = a?.bluetoothLeScanner
        if (a == null || !a.isEnabled) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        if (leScanner == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        scanner = leScanner
        val results = ConcurrentHashMap<String, BleDevice>()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = safeName(dev) ?: ""
                results[dev.address] = BleDevice(dev.address, name.ifBlank { "未知设备" }, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) {
                Timber.tag(TAG).w("BLE 扫描失败 errorCode=$errorCode")
            }
        }

        // 官方行为：用 ScanFilter 按 service UUID 过滤（硬件层过滤，省电且精准）。
        // 若真机极空设备广播里没带这些 UUID，会扫不到 → 此时切 filterByUuid=false 兜底。
        val filters = if (filterByUuid) {
            JK_SCAN_SERVICE_UUIDS.map { uuid ->
                ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(UUID.fromString(uuid))).build()
            }
        } else {
            null
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        if (filters != null) {
            leScanner.startScan(filters, settings, cb)
        } else {
            leScanner.startScan(cb)
        }
        // 定时停止
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { leScanner.stopScan(cb) }
            if (cont.isActive) cont.resume(results.values.sortedByDescending { it.rssi })
        }, durationMs)
    }

    @SuppressLint("MissingPermission")
    private fun safeName(dev: BluetoothDevice): String? = runCatching { dev.name }.getOrNull()

    // ==================== 连接 ====================

    /**
     * 连接指定地址的设备，连接成功后自动发现服务、选中透传 characteristic 并订阅通知。
     *
     * @param timeoutMs 连接超时（毫秒）。BLE 连接可能因设备不可达/信号弱而长时间
     *   无回调，若不加超时，`connect()` 会永久挂起，UI 的「连接中」状态卡死、Toast 不弹。
     *   超时后主动断开并返回 false。默认 10 秒。
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, address: String, timeoutMs: Long = 10000): Boolean {
        disconnect()
        val a = ensureAdapter(context) ?: return false
        val dev = a.getRemoteDevice(address) ?: return false
        connectedDeviceAddress = address
        connectedDeviceName = runCatching { dev.name }.getOrNull() ?: address

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    dev.connectGatt(context, false, gattCallback)
                }
                gatt = g
                // 连接结果经 gattCallback 回调后 resume（见下方 onConnectionStateChange）
                connectWaiter = cont
            }
        } ?: run {
            // 超时：主动断开并释放资源，返回 false
            Timber.tag(TAG).w("连接超时（${timeoutMs}ms），主动断开")
            disconnect()
            false
        }
    }

    private var connectWaiter: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    // ==================== autoConnect 挂起（v0.2.107 靠近自动连接） ====================

    /**
     * 对指定设备挂起 autoConnect：不挂 waiter、无超时（可能几十秒后才建链，
     * 等控制器回调即可）。建链成功走 gattCallback 全链路（MTU→发现服务→CCCD
     * →setConnected(true)），与手动连接共用一套回调。
     * @return true=已挂起（或此前已挂起，幂等）；false=条件不满足未挂起
     */
    @SuppressLint("MissingPermission")
    fun attachAutoConnect(context: Context, address: String): Boolean {
        if (connected) return false                    // 已连接无需挂起
        if (autoConnectAttached) return true           // 幂等
        val a = ensureAdapter(context) ?: return false
        if (!a.isEnabled) return false
        val dev = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return false
        // 清理陈旧 GATT（直连断链后的死对象），避免句柄泄漏
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        connectedDeviceAddress = address
        connectedDeviceName = runCatching { dev.name }.getOrNull() ?: address
        lastDisconnectStatus = -1
        gatt = dev.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        autoConnectAttached = true
        Timber.tag(TAG).i("autoConnect 已挂起（目标 $address），控制器后台监听广播中")
        return true
    }

    /**
     * 摘除挂起。仅 disconnect 不够——autoConnect GATT 会被系统自动重连复活，必须 close。
     * 已连接时不动链路（正常断开走 [disconnect]，由 controller 的抑制流程调用）。
     */
    @SuppressLint("MissingPermission")
    fun detachAutoConnect() {
        if (!autoConnectAttached) return
        autoConnectAttached = false
        if (!connected) {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            writeChar = null
            notifyChar = null
            Timber.tag(TAG).i("autoConnect 挂起已摘除")
        } else {
            // 已连接：链路保持（connected=true 期间挂起标记本就无意义，系统不会
            // 同时对同一 GATT 既连接又后台重试）
            Timber.tag(TAG).i("autoConnect 标记已清（当前已连接，链路不动）")
        }
    }

    // v0.2.79 首连无数据修复：GATT 操作必须严格串行（MTU → 服务发现 → CCCD 订阅 → 读写）。
    // 旧代码 requestMtu 与 discoverServices 背靠背同时发起，部分协议栈上随后排队的 CCCD
    // 订阅/写命令会失败且无人重试 → 首次连接长期收不到通知（用户 2026-11-06 实证：
    // 首连状态栏长时间不出数据，断开重连才恢复）。servicesRequested 防止兜底定时器重复发现。
    @Volatile
    private var servicesRequested = false

    private var cccdRetries = 0

    /** 通知 hex 日志预算（每会话前 3 条，防灌爆上传缓冲，v0.2.81）。 */
    @Volatile
    private var notifyLogBudget = 3

    private val gattCallback = object : BluetoothGattCallback() {
        /**
         * v0.2.93 陈旧 GATT 守卫：回调实例不是当前 [gatt]（已被 disconnect/close 或被新连接
         * 替换）时一律忽略并 close。不守卫的后果（2026-11-07 用户真机实证）：自动连接与手动
         * 连接交叉时，旧 GATT 迟到的 STATE_DISCONNECTED 会把**正在流数据的新连接**打成
         * 未连接 → 页面停在扫描页；迟到的服务发现成功会 resume 新尝试的 waiter（幽灵接管）。
         */
        private fun isStale(g: BluetoothGatt): Boolean {
            if (g !== gatt) {
                Timber.tag(TAG).w("忽略陈旧 GATT 回调（实例已被替换/关闭）")
                runCatching { g.close() }
                return true
            }
            return false
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (isStale(g)) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.tag(TAG).i("GATT 已连接，先协商 MTU 再发现服务")
                // v0.2.110 实验①：连接建立后立即请求 HIGH 优先级（7.5ms 连接间隔），
                // 复刻官方「高频连接事件持续唤醒休眠 MCU」的时序（v0.2.82 dexdump 逆向实证）。
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                servicesRequested = false
                cccdRetries = 0
                notifyLogBudget = 3
                // 请求更大 MTU（极空单帧超 20B，增大 MTU 减少分包，提升稳定性）。
                // discoverServices 移到 onMtuChanged 里串行执行。
                val requested = runCatching { g.requestMtu(256) }.getOrDefault(false)
                if (!requested) {
                    // 个别 ROM requestMtu 直接失败 → 立即降级发现服务
                    servicesRequested = true
                    runCatching { g.discoverServices() }
                }
                // 兜底：MTU 回调 1.5s 内没来（个别 ROM 不回调）也要发现服务
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!servicesRequested) {
                        servicesRequested = true
                        Timber.tag(TAG).w("MTU 回调超时，兜底发起服务发现")
                        runCatching { g.discoverServices() }
                    }
                }, 1500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.tag(TAG).i("GATT 已断开 status=$status")
                lastDisconnectStatus = status
                setConnected(false)
                connectWaiter?.let { w -> connectWaiter = null; w.resume(false) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isStale(g)) return
            Timber.tag(TAG).i("MTU 协商完成 mtu=$mtu status=$status")
            if (!servicesRequested) {
                servicesRequested = true
                runCatching { g.discoverServices() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (isStale(g)) return   // v0.2.93：陈旧发现结果不得 resume 当前尝试的 waiter
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).w("服务发现失败 status=$status")
                connectWaiter?.let { w -> connectWaiter = null; w.resume(false) }
                return
            }
            val ok = selectCharacters(g)
            if (ok) {
                lastDisconnectStatus = -1   // 连接成功，清除上次断开原因
                setConnected(true)
            }
            connectWaiter?.let { w -> connectWaiter = null; w.resume(ok) }
            if (!ok) {
                Timber.tag(TAG).w("未找到可用的透传 characteristic")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (g !== gatt) return   // v0.2.93：陈旧链路的数据不进当前会话
            val bytes = characteristic.value ?: return
            // v0.2.81 降噪：实时数据流每秒十几个分包，逐条打 hex 会把 device_logs
            // 上传缓冲灌爆（2026-11-06 实证：一次上传 1937 条通知 vs 1 条诊断日志，
            // 连接建立阶段的关键日志全被挤掉）。改为每会话只记前 3 条，其余静默
            // （解析/断流诊断在 JikongController 层有汇总日志，不依赖逐包 hex）。
            if (notifyLogBudget > 0) {
                notifyLogBudget--
                Timber.tag(TAG).d("通知 ${bytes.size}B: ${bytes.toHex()}")
            }
            onDataReceived?.invoke(bytes)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (g !== gatt) return   // v0.2.93：陈旧写回执不误导当前写等待者
            writeWaiters.remove(characteristic.uuid)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (isStale(g)) return   // v0.2.93：陈旧 CCCD 回执不触发重订阅风暴
            Timber.tag(TAG).d("descriptor 写回 status=$status")
            // v0.2.79：CCCD 订阅失败会导致通知永远收不到（首连无数据的另一元凶），
            // 旧代码只打日志不重试。失败时重新订阅（限 3 次，防风暴）。
            if (status != BluetoothGatt.GATT_SUCCESS && cccdRetries < 3) {
                cccdRetries++
                Timber.tag(TAG).w("CCCD 订阅失败 status=$status，第 %d 次重试", cccdRetries)
                runCatching {
                    notifyChar?.let { nc ->
                        g.setCharacteristicNotification(nc, true)
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(descriptor)
                    }
                }
            }
        }
    }

    /** 在已发现的服务里选中透传 write/notify characteristic（按候选 UUID 优先级）。 */
    @SuppressLint("MissingPermission")
    private fun selectCharacters(g: BluetoothGatt): Boolean {
        val services: List<BluetoothGattService> = g.services
        var foundWrite: BluetoothGattCharacteristic? = null
        var foundNotify: BluetoothGattCharacteristic? = null

        // 按 service 优先级遍历
        for (sUuid in SERVICE_UUIDS) {
            val svc = services.firstOrNull { it.uuid.toString().equals(sUuid, true) } ?: continue
            for (wUuid in WRITE_UUIDS) {
                val c = svc.getCharacteristic(UUID.fromString(wUuid)) ?: continue
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                ) {
                    foundWrite = c
                    break
                }
            }
            for (nUuid in NOTIFY_UUIDS) {
                val c = svc.getCharacteristic(UUID.fromString(nUuid)) ?: continue
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    foundNotify = c
                    break
                }
            }
            if (foundWrite != null && foundNotify != null) break
        }

        // 兜底：遍历所有 service 找第一个可写/可通知 char
        if (foundWrite == null || foundNotify == null) {
            for (svc in services) {
                for (c in svc.characteristics) {
                    val canWrite = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                    val canNotify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    if (canWrite && foundWrite == null) foundWrite = c
                    if (canNotify && foundNotify == null) foundNotify = c
                }
            }
        }

        writeChar = foundWrite
        notifyChar = foundNotify
        if (foundWrite == null || foundNotify == null) return false

        // 订阅通知（写 CCCD descriptor）
        if (foundNotify != null) {
            runCatching {
                g.setCharacteristicNotification(foundNotify, true)
                val desc = foundNotify.getDescriptor(UUID.fromString(CLIENT_CHAR_UUID))
                desc?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
            }
        }
        Timber.tag(TAG).i("选定 write=${foundWrite.uuid} notify=${foundNotify.uuid}")
        return true
    }

    /** 写数据到透传 characteristic（无响应写优先，其次有响应写）。 */
    @SuppressLint("MissingPermission")
    suspend fun write(bytes: ByteArray): Boolean {
        val c = writeChar ?: return false
        val g = gatt ?: return false
        val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val withResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0

        return try {
            c.writeType = if (noResp) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else if (withResp) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                return false
            }
            c.value = bytes
            // v0.2.79：GATT 操作串行——上一个操作（MTU/CCCD 等）未完成时
            // writeCharacteristic 返回 false，旧代码直接放弃导致 0x97/0x96 静默丢失
            // （首连设备信息空、数据流迟迟不来的元凶）。加短重试（共 ~2s）。
            var sent = g.writeCharacteristic(c)
            var tries = 0
            while (!sent && tries < 20) {
                delay(100)
                sent = g.writeCharacteristic(c)
                tries++
            }
            if (!sent) {
                Timber.tag(TAG).w("写 characteristic 失败（重试 20 次后放弃）")
                return false
            }
            if (noResp) {
                true // 无响应写，写进队列即认为成功
            } else {
                val waiter = CompletableDeferred<Boolean>()
                writeWaiters[c.uuid] = waiter
                waiter.await()
            }
        } catch (e: Exception) {
            Timber.w(e, TAG, "写 characteristic 失败")
            false
        }
    }

    /** 断开连接并释放资源。 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoConnectAttached = false   // v0.2.107：close 即终止系统后台重连，挂起标记同步失效
        writeWaiters.values.forEach { it.complete(false) }
        writeWaiters.clear()
        // v0.2.93：旧连接等待者必须立即 resume(false) 放掉——只置 null 的话，旧 connect()
        // 的 withTimeoutOrNull 只能等满 10s 超时，新连接请求在 controller.mutex 上排队
        // （2026-11-07 用户实证：点扫描延时 10~50s 才变「扫描中」的元凶之一）
        connectWaiter?.let { w -> connectWaiter = null; runCatching { w.resume(false) } }
        rxBuffer.reset()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        notifyChar = null
        setConnected(false)
    }

    /**
     * v0.2.93：抢占式终结「正在进行的连接尝试」（resume 其 waiter + 关闭其 GATT），
     * 供新连接请求立即接管。与 [disconnect] 的区别：**不触发 setConnected**——
     * 挂起中的尝试本就未 connected，误触 onConnectionChanged(false) 会拉起自动重连
     * 与新请求竞争。仅应在 controller.connect 入口（未连接态）调用。
     */
    fun cancelPendingConnect() {
        autoConnectAttached = false   // v0.2.107：close 掉的 GATT 含挂起态，同步清标记
        connectWaiter?.let { w -> connectWaiter = null; runCatching { w.resume(false) } }
        rxBuffer.reset()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        notifyChar = null
    }

    private fun setConnected(v: Boolean) {
        if (connected != v) {
            connected = v
            if (!v) rxBuffer.reset()
            onConnectionChanged?.invoke(v)
        }
    }

    /** 停止扫描。 */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { scanner?.stopScan(scanCallbackForStop) }
    }

    // 保持一个空回调引用用于 stopScan（实际扫描用的是局部 callback，这里仅兜底）
    private val scanCallbackForStop = object : ScanCallback() {}

    private fun ByteArray.toHex(): String =
        joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}

/** 扫描到的 BLE 设备。 */
data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)
