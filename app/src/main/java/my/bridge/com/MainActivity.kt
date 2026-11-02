package my.bridge.com

import android.Manifest
import android.content.Intent
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
import my.bridge.com.vnt.VntAutoStart
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

    /** v0.2.31：自动组网每个 Activity 实例只尝试一次，避免旋转/回前台重复触发。 */
    private var vntAutoStartAttempted = false

    /** v0.2.34：SSH 服务冷启动恢复，同上每实例只尝试一次。 */
    private var sshAutoStartAttempted = false

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

    /**
     * 处理微信消息通知的 deep link。
     *
     * ⚠️ App 已在前台时，通知 PendingIntent 走 [onNewIntent]，`intent` 属性不会自动更新，
     * 必须 `setIntent()`；且 Compose 不会因此重组，故写入 [WechatState.pendingOpenSession]
     * 由 UI 侧消费（见 `MainScreen` 的 LaunchedEffect）。
     */
    /**
     * 处理微信消息通知的 deep link。
     *
     * ⚠️ App 已在前台时，通知 PendingIntent 走 [onNewIntent]，`intent` 属性不会自动更新，
     * 必须 `setIntent()`；且 Compose 不会因此重组，故写入 [WechatState.pendingOpenSession]
     * 由 UI 侧消费（见 `MainScreen` 的 LaunchedEffect）。
     *
     * v0.2.29：除 extra 外**再从 data 兜底取一次**（`bridge://wechat/session/<wxid>`）——
     * 部分 ROM 的 AMS 会保留 action/data 但裁剪 extras；有日志后能一眼看出
     * 「intent 到底有没有送达」（见台账 §6 验证方法）。
     */
    private fun handleWechatDeepLink(intent: Intent?, from: String) {
        val wxid = intent?.getStringExtra(my.bridge.com.wechat.WECHAT_EXTRA_OPEN_SESSION)
            ?.takeIf { it.isNotBlank() }
            ?: intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
        Timber.i("通知跳转 → $from: ${wxid ?: "(无会话)"}")
        if (!wxid.isNullOrBlank()) {
            setIntent(intent)
            my.bridge.com.wechat.WechatState.setPendingOpenSession(wxid)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWechatDeepLink(intent, "onNewIntent")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWechatDeepLink(intent, "onCreate")
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

    /**
     * v0.2.31：冷启动自动组网。
     *
     * 放在 `onStart`（而非 `Application.onCreate`）：此时 Activity 已可见，
     * `startForegroundService` 不会撞 Android 12+ 后台启动限制；同时每个实例只试一次。
     * 具体守卫（记忆 / VPN 授权 / 失败不重试）见 [VntAutoStart.tryStart]。
     */
    override fun onStart() {
        super.onStart()
        if (!vntAutoStartAttempted) {
            vntAutoStartAttempted = true
            VntAutoStart.tryStart(this)
        }
        if (!sshAutoStartAttempted) {
            sshAutoStartAttempted = true
            my.bridge.com.ssh.SshAutoStart.tryStart(this)
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
