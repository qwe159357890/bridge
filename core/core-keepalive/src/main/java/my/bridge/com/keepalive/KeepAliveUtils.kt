package my.bridge.com.keepalive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
     * 查询单个危险权限是否已授予。
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 保活用到的危险权限清单（与 MainActivity 保持一致，作为单一事实来源）。
     */
    fun runtimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
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
