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
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
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
     * @param durationMs 扫描时长，默认 8 秒。
     * @param nameFilter 名称过滤（极空设备名常含 "JK"/"BMS"/"JK-BMS"/"jk" 等），
     *                   为空则不过滤。极空设备广播名通常形如 "JK_B2A8S20P"。
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(
        context: Context,
        durationMs: Long = 8000,
        nameFilter: String? = null,
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
                if (nameFilter != null && !name.contains(nameFilter, true)) return
                results[dev.address] = BleDevice(dev.address, name.ifBlank { "未知设备" }, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) {
                Timber.w(TAG, "BLE 扫描失败 errorCode=$errorCode")
            }
        }
        leScanner.startScan(cb)
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
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, address: String): Boolean {
        disconnect()
        val a = ensureAdapter(context) ?: return false
        val dev = a.getRemoteDevice(address) ?: return false
        connectedDeviceAddress = address
        connectedDeviceName = runCatching { dev.name }.getOrNull() ?: address

        return suspendCancellableCoroutine { cont ->
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
    }

    private var connectWaiter: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.i(TAG, "GATT 已连接，开始发现服务")
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i(TAG, "GATT 已断开 status=$status")
                setConnected(false)
                connectWaiter?.resume(false)
                connectWaiter = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w(TAG, "服务发现失败 status=$status")
                connectWaiter?.resume(false)
                connectWaiter = null
                return
            }
            val ok = selectCharacters(g)
            if (ok) {
                setConnected(true)
                connectWaiter?.resume(true)
            } else {
                Timber.w(TAG, "未找到可用的透传 characteristic")
                connectWaiter?.resume(false)
            }
            connectWaiter = null
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = characteristic.value ?: return
            Timber.d(TAG, "收到通知 ${bytes.size} 字节：${bytes.toHex()}")
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
            Timber.d(TAG, "descriptor 写回 status=$status")
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
        Timber.i(TAG, "选定 write=${foundWrite.uuid} notify=${foundNotify.uuid}")
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
            val sent = g.writeCharacteristic(c)
            if (!sent) return false
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
        rxBuffer.reset()
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
