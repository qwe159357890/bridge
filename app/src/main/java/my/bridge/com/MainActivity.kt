package my.bridge.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import my.bridge.com.core.ui.theme.BridgeTheme
import my.bridge.com.keepalive.KeepAliveUtils
import my.bridge.com.ui.MainScreen
import timber.log.Timber

/**
 * 应用主入口。
 *
 * 首次进入时：
 * 1. 批量申请保活用到的危险权限（通知/定位/相机/蓝牙/后台定位）——借鉴 Aurelay，
 *    但收敛为「保活实际需要」的项，不照搬其 30+ 项全量测试申请。
 * 2. 引导关闭电池优化（保活最有效手段，一次性，用户取消后不再打扰）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_CODE_PERMISSIONS = 1001

        /**
         * 保活实际用到的危险权限。
         * - POST_NOTIFICATIONS：前台服务通知可见（Android 13+）
         * - ACCESS_FINE/COARSE_LOCATION：资源锚点·位置监听
         * - ACCESS_BACKGROUND_LOCATION：后台定位（单独申请，避免与前台同批被拒）
         * - CAMERA：资源锚点·相机持有
         * - BLUETOOTH_SCAN/CONNECT：资源锚点·蓝牙扫描（Android 12+）
         */
        val RUNTIME_PERMISSIONS = buildList {
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

        // 需「二次单独申请」：与前台权限同批会被系统直接拒绝
        val SECOND_ROUND_PERMISSIONS = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 批量申请尚未授予的保活权限（任何一条被拒都只记日志，绝不阻断主流程）
        requestRuntimePermissions()
        // 引导关闭电池优化（一次性）
        guideBatteryOptimization()

        setContent {
            BridgeTheme {
                MainScreen()
            }
        }
    }

    /** 申请尚未授予的危险权限；若无待申请项则跳过。 */
    private fun requestRuntimePermissions() {
        val pending = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (pending.isEmpty()) {
            Timber.d("所有保活权限已授予，无需申请")
            return
        }
        try {
            ActivityCompat.requestPermissions(this, pending.toTypedArray(), REQ_CODE_PERMISSIONS)
            Timber.i("已发起保活权限申请：${pending.size} 项")
        } catch (e: Exception) {
            Timber.e(e, "批量权限申请失败")
        }
    }

    /** 引导关闭电池优化（已加入白名单则跳过；用户拒绝后系统会阻止再次弹窗）。 */
    private fun guideBatteryOptimization() {
        if (!KeepAliveUtils.isIgnoreBatteryOptimization(this)) {
            KeepAliveUtils.requestIgnoreBatteryOptimization(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_PERMISSIONS) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            Timber.i("保活权限申请结果：已授予 $granted / ${permissions.size}")
            // 二次单独申请后台定位
            val secondRound = SECOND_ROUND_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (secondRound.isNotEmpty()) {
                try {
                    ActivityCompat.requestPermissions(this, secondRound.toTypedArray(), 1002)
                } catch (e: Exception) {
                    Timber.e(e, "二次权限申请失败")
                }
            }
        } else if (requestCode == 1002) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            Timber.i("二次权限申请结果：已授予 $granted / ${permissions.size}")
        }
    }
}
