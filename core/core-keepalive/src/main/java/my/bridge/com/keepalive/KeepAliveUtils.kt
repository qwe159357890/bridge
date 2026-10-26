package my.bridge.com.keepalive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 电池优化白名单引导工具（借鉴 SmsForwarder）。
 * 国产 ROM 上最有效的保活手段之一。
 */
object KeepAliveUtils {

    private const val TAG = "KeepAliveUtils"

    fun isIgnoreBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "查询电池优化状态失败", e)
            false
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (isIgnoreBatteryOptimization(activity)) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + activity.packageName)
            val resolveInfo: ResolveInfo? = activity.packageManager.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                activity.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "跳转电池优化设置失败", e)
            false
        }
    }

    /**
     * 在最近任务列表中隐藏/恢复显示本应用（借鉴 SmsForwarder 的 enableExcludeFromRecents）。
     *
     * 原理：把本应用的任务标记为 excludeFromRecents，最近任务列表不再出现 Bridge 卡片，
     * 用户上滑移除 / 一键清理都碰不到它，防误杀，保活加分。
     * 与 SmsForwarder 的差异：关闭时同步调 setExcludeFromRecents(false) 即时恢复显示
     * （原版只存偏好不恢复，需重启应用）。
     *
     * @param hide true=从最近任务隐藏；false=恢复显示。minSdk 24 ≥ 21 无需版本判断。
     */
    fun setExcludeFromRecents(activity: Activity, hide: Boolean) {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val task = am.appTasks.firstOrNull()
            if (task == null) {
                Log.w(TAG, "appTasks 为空，跳过最近任务隐藏设置")
                return
            }
            task.setExcludeFromRecents(hide)
            Log.i(TAG, "最近任务隐藏 -> ${if (hide) "已隐藏" else "显示"}")
        } catch (e: Exception) {
            Log.e(TAG, "设置最近任务可见性失败", e)
        }
    }

    /**
     * 查询单个危险权限是否已授予。
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 全量可申请的危险权限（仿 Aurelay，申请权限=资格不耗电，故全量申请）。
     * 供 MainActivity 首次批量申请 + 设置页权限管理展示。与 AndroidManifest 声明一一对应。
     */
    fun runtimePermissions(): List<String> = buildList {
        // 位置
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // 相机 / 麦克风
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        // 蓝牙（Android 12+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        // 通知（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 电话
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.WRITE_CALL_LOG)
        // 通讯录 / 账号
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.GET_ACCOUNTS)
        // 日历
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        // 短信
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        // 传感器 / 活动识别
        add(Manifest.permission.BODY_SENSORS)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        // 媒体（Android 13+ 分类型；13 以下用 READ_EXTERNAL_STORAGE）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 申请尚未授予的保活危险权限（供设置页手动触发）。
     */
    fun requestRuntimePermissions(activity: Activity) {
        val pending = runtimePermissions().filter { !hasPermission(activity, it) }
        if (pending.isEmpty()) {
            Log.i(TAG, "所有保活权限已授予")
            return
        }
        try {
            ActivityCompat.requestPermissions(activity, pending.toTypedArray(), 1001)
        } catch (e: Exception) {
            Log.e(TAG, "批量权限申请失败", e)
        }
    }
}
