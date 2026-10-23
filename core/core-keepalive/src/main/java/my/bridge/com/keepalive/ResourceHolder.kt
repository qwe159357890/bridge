package my.bridge.com.keepalive

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 保活资源持有器（借鉴 Aurelay）。
 *
 * 把「使用时申请、用完就释放」改成「App 运行期间一直持有」。
 * 位置/传感器/蓝牙/相机数据不用于任何业务，纯为「持有会话」抬高进程优先级。
 */
object ResourceHolder {

    private const val TAG = "ResourceHolder"

    private const val WAKE_TAG = "Bridge:KeepAlive"
    private const val WIFI_TAG = "Bridge:WifiLock"

    private const val ALARM_REQUEST_CODE = 9001
    private const val JOB_ID = 9002
    private const val PERIOD_MS = 15 * 60 * 1000L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var locationListener: LocationListener? = null
    private var sensorListener: SensorEventListener? = null
    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null

    @Volatile
    private var holding = false

    fun acquireAll(context: Context) {
        if (holding) return
        holding = true
        val app = context.applicationContext
        Log.i(TAG, "开始持有保活资源")

        acquireWakeLock(app)
        acquireWifiLock(app)
        if (KeepAlivePrefs.getBoolean(app, KeepAlivePrefs.KEY_KEEP_ALIVE_FALLBACK)) {
            registerAlarm(app)
            registerJob(app)
        }
        registerNetworkCallback(app)
        if (KeepAlivePrefs.getBoolean(app, KeepAlivePrefs.KEY_KEEP_ALIVE_RESOURCE)) {
            registerLocation(app)
            registerSensor(app)
            startBluetoothScan(app)
            openCamera(app)
        }
    }

    fun releaseAll(context: Context) {
        if (!holding) return
        holding = false
        val app = context.applicationContext
        Log.i(TAG, "释放保活资源")

        releaseWakeLock()
        releaseWifiLock()
        cancelAlarm(app)
        cancelJob(app)
        unregisterNetworkCallback(app)
        unregisterLocation(app)
        unregisterSensor(app)
        stopBluetoothScan(app)
        closeCamera()
    }

    private fun acquireWakeLock(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            wakeLock = lock
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败", e)
        }
    }

    private fun acquireWifiLock(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            Log.e(TAG, "获取 WifiLock 失败", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "释放 WifiLock 失败", e)
        }
    }

    private fun registerAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = keepAlivePendingIntent(context) ?: return
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + PERIOD_MS,
                PERIOD_MS,
                pi
            )
        } catch (e: Exception) {
            Log.e(TAG, "注册 AlarmManager 兜底失败", e)
        }
    }

    private fun cancelAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = keepAlivePendingIntent(context) ?: return
            am.cancel(pi)
        } catch (e: Exception) {
            Log.e(TAG, "取消 AlarmManager 兜底失败", e)
        }
    }

    private fun keepAlivePendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, KeepAliveReceiver::class.java)
        intent.action = KeepAliveReceiver.ACTION_KEEP_ALIVE
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    private fun registerJob(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, KeepAliveJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .setRequiresCharging(false)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
            Log.e(TAG, "注册 JobScheduler 兜底失败", e)
        }
    }

    private fun cancelJob(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            js.cancel(JOB_ID)
        } catch (e: Exception) {
            Log.e(TAG, "取消 JobScheduler 兜底失败", e)
        }
    }

    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { Log.i(TAG, "网络可用") }
                override fun onLost(network: Network) { Log.i(TAG, "网络丢失") }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    Log.i(TAG, "网络能力变化")
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "注册 NetworkCallback 失败", e)
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        try {
            val callback = networkCallback ?: return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.unregisterNetworkCallback(callback)
            networkCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "注销 NetworkCallback 失败", e)
        }
    }

    @Suppress("MissingPermission")
    private fun registerLocation(context: Context) {
        try {
            if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                Log.w(TAG, "未注册位置监听：缺少定位权限")
                return
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 60_000L, 0f, listener)
                }
            }
            locationListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "注册位置监听失败", e)
        }
    }

    private fun unregisterLocation(context: Context) {
        try {
            val listener = locationListener ?: return
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            lm.removeUpdates(listener)
            locationListener = null
        } catch (e: Exception) {
            Log.e(TAG, "注销位置监听失败", e)
        }
    }

    private fun registerSensor(context: Context) {
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {}
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            var registered = false
            for (type in intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val sensor = sm.getDefaultSensor(type)
                if (sensor != null) {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    registered = true
                }
            }
            if (registered) sensorListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "注册传感器监听失败", e)
        }
    }

    private fun unregisterSensor(context: Context) {
        try {
            val listener = sensorListener ?: return
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            sm.unregisterListener(listener)
            sensorListener = null
        } catch (e: Exception) {
            Log.e(TAG, "注销传感器监听失败", e)
        }
    }

    @Suppress("MissingPermission")
    private fun startBluetoothScan(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
                    Log.w(TAG, "未启动蓝牙扫描：缺少 BLUETOOTH_SCAN 权限")
                    return
                }
            }
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            val scanner = adapter.bluetoothLeScanner ?: return
            val callback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {}
                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "蓝牙扫描失败：$errorCode")
                }
            }
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "启动蓝牙扫描失败", e)
        }
    }

    @Suppress("MissingPermission")
    private fun stopBluetoothScan(context: Context) {
        try {
            val callback = scanCallback ?: return
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.bluetoothLeScanner?.stopScan(callback)
            scanCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "停止蓝牙扫描失败", e)
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            if (!hasPermission(context, Manifest.permission.CAMERA)) {
                Log.w(TAG, "未打开相机：缺少相机权限")
                return
            }
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager ?: return
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            cm.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) { cameraDevice = camera }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { cameraDevice = null }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) { cameraDevice = null }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "打开相机失败", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭相机失败", e)
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
