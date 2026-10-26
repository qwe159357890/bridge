package my.bridge.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import my.bridge.com.core.ui.theme.BridgeTheme
import my.bridge.com.keepalive.KeepAlivePrefs
import my.bridge.com.keepalive.KeepAliveUtils
import my.bridge.com.ui.MainScreen
import timber.log.Timber

/**
 * 应用主入口。
 *
 * 首次进入时：
 * 1. 全量批量申请「所有安卓可申请的危险权限」——借鉴 Aurelay，原则：申请权限只是「资格」、
 *    本身不耗电（真正耗电的是持有资源，已由保活开关控制），故全量申请。
 * 2. 引导关闭电池优化（保活最有效手段，一次性，用户取消后不再打扰）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * 全量可申请的危险权限（单一事实来源：KeepAliveUtils.runtimePermissions）。
         */
        val RUNTIME_PERMISSIONS: List<String> = KeepAliveUtils.runtimePermissions()
    }

    /** 批量申请危险权限（返回 Map<权限, 是否授予>）。 */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.count { it }
            Timber.i("权限申请结果：已授予 $granted / ${results.size}")
            // 二次单独申请「后台定位 / 后台传感器」（与前台权限同批会被系统直接拒绝）
            requestBackgroundPermissions()
        }

    /** 后台定位 / 后台传感器需二次单独申请。 */
    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.i("后台定位权限申请结果：$granted")
        }
    private val backgroundSensorLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.i("后台传感器权限申请结果：$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 全量申请尚未授予的危险权限（任何一条被拒都只记日志，绝不阻断主流程）
        requestRuntimePermissions()
        // 引导关闭电池优化（一次性）
        guideBatteryOptimization()
        // 按存储状态应用「在最近任务中隐藏」（默认开：防上滑/一键清理误杀，见 docs/keepalive/design.md）
        KeepAliveUtils.setExcludeFromRecents(
            this,
            KeepAlivePrefs.getBoolean(this, KeepAlivePrefs.KEY_KEEP_ALIVE_HIDE_RECENTS),
        )

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
            Timber.d("所有权限已授予，无需申请")
            return
        }
        try {
            permissionLauncher.launch(pending.toTypedArray())
            Timber.i("已发起权限申请：${pending.size} 项")
        } catch (e: Exception) {
            Timber.e(e, "批量权限申请失败")
        }
    }

    /** 二次单独申请后台定位 + 后台传感器（与前台权限同批会被系统直接拒绝）。 */
    private fun requestBackgroundPermissions() {
        // 后台定位（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } catch (e: Exception) {
                Timber.e(e, "后台定位权限申请失败")
            }
        }
        // 后台传感器（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS_BACKGROUND) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                backgroundSensorLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
            } catch (e: Exception) {
                Timber.e(e, "后台传感器权限申请失败")
            }
        }
    }

    /** 引导关闭电池优化（已加入白名单则跳过；用户拒绝后系统会阻止再次弹窗）。 */
    private fun guideBatteryOptimization() {
        if (!KeepAliveUtils.isIgnoreBatteryOptimization(this)) {
            KeepAliveUtils.requestIgnoreBatteryOptimization(this)
        }
    }
}
