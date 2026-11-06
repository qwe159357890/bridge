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
import kotlinx.coroutines.launch
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

    // v0.2.79 首连无数据修复：GATT 操作必须严格串行（MTU → 服务发现 → CCCD 订阅 → 读写）。
    // 旧代码 requestMtu 与 discoverServices 背靠背同时发起，部分协议栈上随后排队的 CCCD
    // 订阅/写命令会失败且无人重试 → 首次连接长期收不到通知（用户 2026-11-06 实证：
    // 首连状态栏长时间不出数据，断开重连才恢复）。servicesRequested 防止兜底定时器重复发现。
    @Volatile
    private var servicesRequested = false

    private var cccdRetries = 0

    /** 官方握手帧是否已发（每条链路一次；STATE_CONNECTED 复位，v0.2.82）。 */
    @Volatile
    private var handshakeSent = false

    /** 官方时序的握手回调执行域（CCCD 成功后 40ms 发握手帧 + 协商 MTU，v0.2.82）。 */
    private val cbScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** 通知 hex 日志预算（每会话前 3 条，防灌爆上传缓冲，v0.2.81）。 */
    @Volatile
    private var notifyLogBudget = 3

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.tag(TAG).i("GATT 已连接（对齐官方时序：HIGH 优先级 → 80ms → 发现服务）")
                servicesRequested = false
                cccdRetries = 0
                handshakeSent = false
                notifyLogBudget = 3
                // v0.2.82 官方字节码实证：连接成功后立即 requestConnectionPriority(HIGH)
                // （7.5ms 高优先级连接参数），再等 80ms 才 discoverServices。
                // 我们旧时序（先 requestMtu(256) 再发现服务）是首连半死链的候选诱因之一。
                runCatching {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
                // 官方 80ms 延时（比旧 1.5s 兜底更快也更贴合官方；Handler 定时必然触发，
                // 不存在旧 MTU 回调不来的不确定性，故无需第二重兜底）
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!servicesRequested) {
                        servicesRequested = true
                        runCatching { g.discoverServices() }
                    }
                }, 80)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.tag(TAG).i("GATT 已断开 status=$status")
                lastDisconnectStatus = status
                setConnected(false)
                connectWaiter?.let { w -> connectWaiter = null; w.resume(false) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Timber.tag(TAG).i("MTU 协商完成 mtu=$mtu status=$status")
            // v0.2.82：MTU 协商已挪到 CCCD 之后（官方时序），此回调到达时服务早已发现，
            // servicesRequested 守卫保证不重复 discoverServices。
            if (!servicesRequested) {
                servicesRequested = true
                runCatching { g.discoverServices() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
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
            writeWaiters.remove(characteristic.uuid)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Timber.tag(TAG).d("descriptor 写回 status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // v0.2.79：CCCD 订阅失败会导致通知永远收不到（首连无数据的另一元凶），
                // 旧代码只打日志不重试。失败时重新订阅（限 3 次，防风暴）。
                if (cccdRetries < 3) {
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
                return
            }
            // v0.2.82 官方时序对齐（字节码逆向实证）：CCCD 订阅成功后 40ms 做两件事——
            // ① 发官方 21B 握手帧 `AA 55 90 EB 97 00×15 11`（推测为 BMS 应用层会话激活
            //    信号；官方无任何重连机制却总能一次连接出数，此帧是最大嫌疑）
            // ② requestMtu(517)（官方是 CCCD 之后再协商 MTU，与我们旧时序相反）
            if (!handshakeSent) {
                handshakeSent = true
                cbScope.launch {
                    delay(40)
                    if (!JikongBleClient.connected) return@launch
                    val ok = runCatching { write(JikongProtocol.buildOfficialHandshake()) }
                        .getOrDefault(false)
                    Timber.tag(TAG).i("官方 21B 握手帧发送%s（CCCD+40ms）", if (ok) "成功" else "失败")
                    runCatching { g.requestMtu(517) }
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
        writeWaiters.values.forEach { it.complete(false) }
        writeWaiters.clear()
        // 清空挂起的连接等待，避免后续 GATT 回调 resume 一个已取消的 continuation
        connectWaiter = null
        rxBuffer.reset()
        handshakeSent = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        notifyChar = null
        setConnected(false)
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
