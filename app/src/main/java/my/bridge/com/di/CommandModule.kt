package my.bridge.com.di

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApi
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandExecutor
import my.bridge.com.core.network.ws.CommandResult
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.admin.BridgeAdminReceiver
import my.bridge.com.data.BatteryReporter
import my.bridge.com.data.CallLogReader
import my.bridge.com.data.DeviceStatsReporter
import my.bridge.com.data.ContactsReader
import my.bridge.com.data.ForegroundAppReader
import my.bridge.com.data.NetworkReporter
import my.bridge.com.data.PhoneDialer
import my.bridge.com.data.SimHelper
import my.bridge.com.data.SmsDefaultHelper
import my.bridge.com.data.SmsReader
import my.bridge.com.data.SmsRepository
import my.bridge.com.data.SmsSender
import my.bridge.com.data.VolumeController
import my.bridge.com.interact.DeviceVibrator
import my.bridge.com.interact.NotificationPoster
import my.bridge.com.interact.OverlayDialog
import my.bridge.com.interact.ToastHelper
import my.bridge.com.interact.TtsSpeaker
import my.bridge.com.location.LocationHelper
import my.bridge.com.media.MediaCaptureService
import my.bridge.com.media.ProjectionConsentActivity
import my.bridge.com.automation.a11y.BridgeAccessibilityService
import my.bridge.com.media.ScreenCaptureHolder
import my.bridge.com.media.MusicPlayer
import my.bridge.com.vnt.VntMeshController
import my.bridge.com.vnt.VntMeshPrefs
import my.bridge.com.vnt.VntServerAddress
import my.bridge.com.vnt.VntStatus
import my.bridge.com.wechat.WechatController
import my.bridge.com.wechat.WechatRuntime
import my.bridge.com.ssh.SshPrefs
import my.bridge.com.ssh.SshServerController
import my.bridge.com.ssh.SshStatus
import retrofit2.HttpException
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * 命令执行器装配。
 *
 * 注册服务端可下发的命令（与服务端 ALLOWED_COMMANDS 白名单一致）：
 * - upload_log：采集诊断日志并上传到后端。
 * - play_music / pause_music / resume_music / stop_music：音乐播放与控制
 *   （状态机与回执语义见 [MusicPlayer] 与 docs/api/commands/control.md）。
 * - get_location：获取设备位置（定位策略见 [LocationHelper] 与 docs/api/commands/data.md）。
 */
@Module
@InstallIn(SingletonComponent::class)
object CommandModule {

    /** fire-and-forget 上报用的独立作用域：失败不影响命令执行链路。 */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideCommandExecutors(
        @ApplicationContext context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        // dagger.Lazy 断依赖环：BridgeWebSocketClient 构造需要本执行器表，
        // 执行器表只在弹窗点击回调时才真正取用 client（运行期已装配完成）
        webSocketClient: dagger.Lazy<BridgeWebSocketClient>,
    ): Map<String, CommandExecutor> = run {
        // 注入进度上传通道（UploadProgress.kt）：dagger.Lazy 断依赖环，运行期才取用
        UploadProgressHolder.webSocketClient = webSocketClient
        mapOf(
            Command.UPLOAD_LOG to CommandExecutor { command ->
                executeUploadLog(context, deviceStore, apiClient, command)
            },
            Command.CLEAR_LOG to CommandExecutor { command ->
                executeClearLog(command)
            },
        Command.PLAY_MUSIC to CommandExecutor { command ->
            executePlayMusic(context, deviceStore, apiClient, command)
        },
        Command.PAUSE_MUSIC to CommandExecutor { command ->
            executeMusicControl(command) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.IDLE -> fail("当前没有正在播放的音乐")
                    MusicPlayer.State.PAUSED -> ok("已处于暂停状态：${MusicPlayer.currentSource}")
                    MusicPlayer.State.PLAYING -> {
                        MusicPlayer.pause()
                        ok("已暂停：${MusicPlayer.currentSource}")
                    }
                }
            }
        },
        Command.RESUME_MUSIC to CommandExecutor { command ->
            executeMusicControl(command) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.IDLE -> fail("当前没有可恢复的音乐")
                    MusicPlayer.State.PLAYING -> fail("正在播放中，无需恢复")
                    MusicPlayer.State.PAUSED -> {
                        MusicPlayer.resume()
                        ok("恢复播放：${MusicPlayer.currentSource}")
                    }
                }
            }
        },
        Command.STOP_MUSIC to CommandExecutor { command ->
            executeMusicControl(command) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.IDLE -> fail("当前没有正在播放的音乐")
                    else -> {
                        MusicPlayer.stop()
                        ok("已停止")
                    }
                }
            }
        },
        Command.GET_LOCATION to CommandExecutor { command ->
            executeGetLocation(context, deviceStore, apiClient, command)
        },
        Command.GET_CURRENT_PAGE to CommandExecutor { command ->
            executeGetCurrentPage(context, command)
        },
        Command.GET_NETWORK to CommandExecutor { command ->
            executeGetNetwork(context, command)
        },
        Command.GET_BATTERY to CommandExecutor { command ->
            executeGetBattery(context, command)
        },
        Command.GET_DEVICE_STATS to CommandExecutor { command ->
            executeGetDeviceStats(context, command)
        },
        Command.GET_CONTACTS to CommandExecutor { command ->
            executeGetContacts(context, deviceStore, apiClient, command)
        },
        Command.GET_SMS to CommandExecutor { command ->
            executeGetSms(context, deviceStore, apiClient, command)
        },
        Command.GET_CALL_LOG to CommandExecutor { command ->
            executeGetCallLog(context, deviceStore, apiClient, command)
        },
        Command.GET_SIM_INFO to CommandExecutor { command ->
            executeGetSimInfo(context, deviceStore, apiClient, command)
        },
        Command.SEND_SMS to CommandExecutor { command ->
            executeSendSms(context, command)
        },
        Command.DIAL_PHONE to CommandExecutor { command ->
            executeDialPhone(context, command)
        },
        Command.MARK_SMS_READ to CommandExecutor { command ->
            executeMarkSmsRead(context, command)
        },
        Command.SET_VOLUME to CommandExecutor { command ->
            executeSetVolume(context, command)
        },
        Command.GET_VOLUME to CommandExecutor { command ->
            executeGetVolume(context, command)
        },
        Command.STREAM_STOP to CommandExecutor { command ->
            executeStreamStop(command)
        },
        Command.LOCK_SCREEN to CommandExecutor { command ->
            executeLockScreen(context, command)
        },
        Command.RESTART_APP to CommandExecutor { command ->
            executeRestartApp(context, command)
        },
        Command.SCREENSHOT to CommandExecutor { command ->
            executeProjectionCapture(context, deviceStore, apiClient, command, MediaCaptureService.MODE_SCREENSHOT)
        },
        Command.RECORD_SCREEN to CommandExecutor { command ->
            executeProjectionCapture(context, deviceStore, apiClient, command, MediaCaptureService.MODE_RECORD)
        },
        Command.SCREEN_LIVE to CommandExecutor { command ->
            executeProjectionCapture(context, deviceStore, apiClient, command, MediaCaptureService.MODE_SCREEN_LIVE)
        },
        Command.TAKE_PHOTO to CommandExecutor { command ->
            executeTakePhoto(context, deviceStore, apiClient, command)
        },
        Command.RECORD_VIDEO to CommandExecutor { command ->
            executeRecordVideo(context, deviceStore, apiClient, command)
        },
        Command.RECORD_AUDIO to CommandExecutor { command ->
            executeRecordAudio(context, deviceStore, apiClient, command)
        },
        Command.CLEAR_MEDIA to CommandExecutor { command ->
            executeClearMedia(context, command)
        },
        Command.SHOW_TOAST to CommandExecutor { command ->
            executeShowToast(context, command)
        },
        Command.SPEAK to CommandExecutor { command ->
            executeSpeak(context, command)
        },
        Command.VIBRATE to CommandExecutor { command ->
            executeVibrate(context, command)
        },
        Command.SHOW_DIALOG to CommandExecutor { command ->
            executeShowDialog(context, webSocketClient::get, command)
        },
        Command.SEND_NOTIFICATION to CommandExecutor { command ->
            executeSendNotification(context, command)
        },
        Command.CHECK_UPDATE to CommandExecutor { command ->
            executeCheckUpdate(context, deviceStore, apiClient, command)
        },
        // ---- VNT 组网（v0.2.30）：三条命令走「catalog → Command.kt → 本执行器」三步法 ----
        Command.VNT_START to CommandExecutor { command ->
            executeVntStart(context, command)
        },
        Command.VNT_STOP to CommandExecutor { command ->
            executeVntStop(context, command)
        },
        Command.VNT_STATUS to CommandExecutor { command ->
            executeVntStatus(command)
        },
        // ---- SSH 服务器（v0.2.34）：同三步法，状态联动 + 幂等守卫（调研报告 §6.2/§6.3）----
        Command.SSH_START to CommandExecutor { command ->
            executeSshStart(context, command)
        },
        Command.SSH_STOP to CommandExecutor { command ->
            executeSshStop(context, command)
        },
        Command.SSH_STATUS to CommandExecutor { command ->
            executeSshStatus(context, command)
        },
        // ---- 无障碍服务（v0.2.51：开启/关闭/检查状态）----
        Command.ACCESSIBILITY_ENABLE to CommandExecutor { command ->
            executeAccessibilityEnable(context, command)
        },
        Command.ACCESSIBILITY_DISABLE to CommandExecutor { command ->
            executeAccessibilityDisable(context, command)
        },
        Command.ACCESSIBILITY_STATUS to CommandExecutor { command ->
            executeAccessibilityStatus(context, command)
        },
        // ---- 微信离开即释放开关（v0.2.51）----
        Command.WECHAT_RELEASE_ENABLE to CommandExecutor { command ->
            executeWechatReleaseEnable(context, command)
        },
        Command.WECHAT_RELEASE_DISABLE to CommandExecutor { command ->
            executeWechatReleaseDisable(context, command)
        },
        Command.WECHAT_RELEASE_STATUS to CommandExecutor { command ->
            executeWechatReleaseStatus(context, command)
        },
        // ---- 无界面模式（v0.2.51：ui_lock 儿童锁）----
        Command.UI_LOCK_START to CommandExecutor { command ->
            executeUiLockStart(context, command)
        },
        Command.UI_LOCK_STOP to CommandExecutor { command ->
            executeUiLockStop(context, command)
        },
        Command.UI_LOCK_STATUS to CommandExecutor { command ->
            executeUiLockStatus(command)
        },
        // ---- 界面自动化（v0.2.39：无障碍控件读取/操作，对标 autox）----
        Command.UI_DUMP to CommandExecutor { command ->
            executeUiDump(command)
        },
        Command.UI_FIND to CommandExecutor { command ->
            executeUiFind(command)
        },
        Command.UI_CLICK to CommandExecutor { command ->
            executeUiClick(command)
        },
        Command.UI_SET_TEXT to CommandExecutor { command ->
            executeUiSetText(context, command)
        },
        Command.UI_GESTURE to CommandExecutor { command ->
            executeUiGesture(command)
        },
        Command.UI_BACK to CommandExecutor { command ->
            executeUiBack(command)
        },
        Command.UI_HOME to CommandExecutor { command ->
            executeUiHome(command)
        },
        // ---- Python RPC（v0.2.39：手机内嵌 HTTP/REST 服务，供 Python 脚本驱动）----
        Command.RPC_START to CommandExecutor { command ->
            executeRpcStart(command)
        },
        Command.RPC_STOP to CommandExecutor { command ->
            executeRpcStop(command)
        },
        Command.RPC_STATUS to CommandExecutor { command ->
            executeRpcStatus(command)
        },
        // ---- 极空 BMS 保护板（v0.2.55）----
        Command.JIKONG_SCAN to CommandExecutor { command ->
            executeJikongScan(context, command)
        },
        Command.JIKONG_CONNECT to CommandExecutor { command ->
            executeJikongConnect(context, command)
        },
        Command.JIKONG_DISCONNECT to CommandExecutor { command ->
            executeJikongDisconnect(command)
        },
        Command.JIKONG_STATUS to CommandExecutor { command ->
            executeJikongStatus(command)
        },
        Command.JIKONG_MOS to CommandExecutor { command ->
            executeJikongMos(command)
        },
        Command.JIKONG_BALANCE to CommandExecutor { command ->
            executeJikongBalance(command)
        },
        Command.JIKONG_WRITE_PARAM to CommandExecutor { command ->
            executeJikongWriteParam(command)
        },
        // ---- v0.2.102：管理后台↔手机同步（远程播报/主题/播报设置）----
        Command.JIKONG_SPEAK to CommandExecutor { command ->
            executeJikongSpeak(context, command)
        },
        Command.JIKONG_SET_THEME to CommandExecutor { command ->
            executeJikongSetTheme(context, command)
        },
        Command.JIKONG_SET_SPEAK to CommandExecutor { command ->
            executeJikongSetSpeak(context, command)
        },
        Command.JIKONG_SET_CONFIG to CommandExecutor { command ->
            executeJikongSetConfig(context, command)
        },
        Command.JIKONG_SET_PROXIMITY to CommandExecutor { command ->
            executeJikongSetProximity(context, command)
        },
        Command.JIKONG_DEMO_REFRESH to CommandExecutor { command ->
            executeJikongDemoRefresh(command)
        },
        // ---- 界面自动化增强（v0.2.40：委托 automation/commands 执行器）----
        Command.UI_LONG_CLICK to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.longClick(command)
        },
        Command.UI_SCROLL_TO to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.scrollTo(command)
        },
        Command.UI_WAIT_IDLE to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.waitIdle(command)
        },
        Command.UI_WAIT_GONE to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.waitGone(command)
        },
        Command.UI_CLEAR to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.clear(context, command)
        },
        Command.UI_SENDKEY to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.sendKey(context, command)
        },
        Command.UI_EXISTS to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.exists(command)
        },
        Command.UI_GET_TEXT to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.getText(command)
        },
        Command.UI_CHECKED to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.flag(command) { it.checked() }
        },
        Command.UI_ENABLED to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.flag(command) { it.enabled() }
        },
        Command.UI_SELECTED to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.flag(command) { it.selected() }
        },
        Command.UI_COUNT to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.count(command)
        },
        Command.UI_WHICH to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.which(command)
        },
        Command.UI_IN_APP to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.inApp(command)
        },
        Command.UI_KEYBOARD_SHOWN to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.keyboardShown(command)
        },
        Command.UI_LAST_TOAST to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.lastToast(command)
        },
        Command.UI_COLOR to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.color(context, command)
        },
        Command.UI_RECENTS to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.global(command, "recents")
        },
        Command.UI_OPEN_NOTIFICATIONS to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.global(command, "notifications")
        },
        Command.UI_QUICK_SETTINGS to CommandExecutor { command ->
            my.bridge.com.automation.commands.UiCommandExecutors.global(command, "quick_settings")
        },
        // ---- 设备 + 应用命令（v0.2.40）----
        Command.UI_LAUNCH to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.launch(context, command)
        },
        Command.UI_OPEN_URL to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.openUrl(context, command)
        },
        Command.UI_INTENT to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.intent(context, command)
        },
        Command.UI_APPS to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.apps(context, command)
        },
        Command.UI_INSTALLED to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.installed(context, command)
        },
        Command.UI_SCREEN_ON to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.screenOn(context, command)
        },
        Command.UI_LOCKED to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.locked(context, command)
        },
        Command.UI_WAKE to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.wake(context, command)
        },
        Command.UI_ORIENTATION to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.orientation(context, command)
        },
        Command.UI_INFO to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.info(context, command)
        },
        Command.UI_LAUNCH_APP to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.launchApp(context, command)
        },
        Command.UI_PACKAGE_NAME to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.packageName(context, command)
        },
        Command.UI_APP_NAME to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.appName(context, command)
        },
        Command.UI_OPEN_APP_SETTING to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.openAppSetting(context, command)
        },
        Command.UI_SET_BRIGHTNESS to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.setBrightness(context, command)
        },
        Command.UI_GET_BRIGHTNESS to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.getBrightness(context, command)
        },
        Command.UI_GET_MEM to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.getMem(context, command)
        },
        Command.UI_IS_CHARGING to CommandExecutor { command ->
            my.bridge.com.automation.commands.DeviceCommandExecutors.isCharging(context, command)
        },
        // ---- 图像找色 + 传感器（v0.2.40）----
        Command.UI_FIND_COLOR to CommandExecutor { command ->
            my.bridge.com.automation.commands.InputCommandExecutors.findColor(context, command)
        },
        Command.UI_FIND_MULTI_COLORS to CommandExecutor { command ->
            my.bridge.com.automation.commands.InputCommandExecutors.findMultiColors(context, command)
        },
        Command.UI_SENSORS to CommandExecutor { command ->
            my.bridge.com.automation.commands.InputCommandExecutors.sensors(context, command)
        },
        // ---- 文件 + 媒体（v0.2.40）----
        Command.UI_FILE_READ to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileRead(context, command)
        },
        Command.UI_FILE_WRITE to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileWrite(context, command, false)
        },
        Command.UI_FILE_APPEND to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileWrite(context, command, true)
        },
        Command.UI_FILE_EXISTS to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileExists(context, command)
        },
        Command.UI_FILE_LIST to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileList(context, command)
        },
        Command.UI_FILE_COPY to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileCopyMove(context, command, false)
        },
        Command.UI_FILE_MOVE to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileCopyMove(context, command, true)
        },
        Command.UI_FILE_REMOVE to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.fileRemove(context, command)
        },
        Command.UI_MEDIA_SEEK to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.mediaControl(context, command, "seek")
        },
        Command.UI_MEDIA_VOLUME to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.mediaControl(context, command, "volume")
        },
        Command.UI_MEDIA_POSITION to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.mediaControl(context, command, "position")
        },
        Command.UI_MEDIA_DURATION to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.mediaControl(context, command, "duration")
        },
        Command.UI_MEDIA_STATE to CommandExecutor { command ->
            my.bridge.com.automation.commands.FileMediaCommandExecutors.mediaControl(context, command, "state")
        },
        // ---- 通知子域 + 剪贴板（v0.2.40）----
        Command.UI_NOTIFICATIONS to CommandExecutor { command ->
            my.bridge.com.automation.commands.NotificationCommandExecutors.notifications(command)
        },
        Command.UI_HAS_NOTIFICATION to CommandExecutor { command ->
            my.bridge.com.automation.commands.NotificationCommandExecutors.hasNotification(command)
        },
        Command.UI_NOTIFICATION_REPLY to CommandExecutor { command ->
            my.bridge.com.automation.commands.NotificationCommandExecutors.notificationReply(command)
        },
        Command.UI_NOTIFICATION_CLICK to CommandExecutor { command ->
            my.bridge.com.automation.commands.NotificationCommandExecutors.notificationClick(command)
        },
        Command.UI_NOTIFICATION_DISMISS to CommandExecutor { command ->
            my.bridge.com.automation.commands.NotificationCommandExecutors.notificationDismiss(command)
        },
        Command.UI_CLIPBOARD to CommandExecutor { command ->
            my.bridge.com.automation.commands.NotificationCommandExecutors.clipboard(context, command)
        },
        ).also { executors ->
            // 把命令执行器表注入 RPC 控制器，让 RPC 复用同一套执行器（动作层零重复）
            my.bridge.com.automation.rpc.RpcServerController.bind(executors, context)
        }
    }

    private suspend fun executeUploadLog(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        return try {
            val deviceId = deviceStore.deviceId()
            val logs = DiagLog.dumpLines().map {
                my.bridge.com.core.network.api.BridgeApi.LogLine(
                    ts = it.ts, level = it.level, tag = it.tag, message = it.message,
                )
            }
            val deviceName = deviceStore.deviceName() ?: deviceId
            val appVersion = DiagLog.appVersionName(context)
            val message = apiClient.uploadLog(
                deviceId = deviceId,
                logs = logs,
                deviceName = deviceName,
                appVersion = appVersion,
            )
            Timber.i("命令 upload_log 上传成功：$message")
            CommandResult(command.commandId, command.command, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 upload_log 上传失败")
            CommandResult(command.commandId, command.command, false, "上传失败：${e.message}")
        }
    }

    /** 执行 clear_log：清空设备端日志（内存缓冲 + 磁盘文件），配合管理后台「清除全部日志」。 */
    private fun executeClearLog(command: Command): CommandResult {
        return try {
            val removed = DiagLog.clearAll()
            CommandResult(
                command.commandId, command.command, true,
                "已清空设备端日志（内存缓冲 + $removed 个磁盘文件）",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 clear_log 执行失败")
            CommandResult(command.commandId, command.command, false, "清理失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // VNT 组网命令（v0.2.30 引入，v0.2.32 改为 1.x 语义）
    //
    // 平台限制（已在回执里如实说明）：
    // - VpnService.prepare() 授权框必须有 Activity 才能弹；
    // - Android 12+ 后台 startForegroundService 会抛 ForegroundServiceStartNotAllowedException；
    // ⇒ vnt_start 实际只在「App 在前台」或「用户此前已授权过 VPN」时可靠生效。
    // - vnt_status 刻意只读本地快照，不调 Vnt.list()（1.x 是本地内存读，但仍属 JNI，
    //   且详细设备列表只在页面可见时刷新，命令通道不该触发）。
    // - 1.x：组网编号叫 token（兼容旧 network_code 别名）；服务端是裸 host:port；
    //   UDP/TCP 由布尔 tcp 决定，不接受 udp:// / tcp:// 前缀。
    // ------------------------------------------------------------------

    /** vnt_start：可选覆盖服务端 / 组网编号 / 密码 / 协议后启动组网。 */
    private fun executeVntStart(context: Context, command: Command): CommandResult {
        return try {
            val server = command.strParam("server", "")
            // 1.x 叫 token；兼容 v0.2.30/31 管理后台沿用的 network_code 参数名
            val token = command.strParam("token", "").ifEmpty { command.strParam("network_code", "") }
            val password = command.strParam("password", "")
            val tcpText = command.strParam("tcp", "").lowercase()
            val tcp = when (tcpText) {
                "true", "1", "tcp" -> true
                "false", "0", "udp" -> false
                else -> null
            }
            // v0.2.33：状态守卫提到最前面。重复下发（含管理后台「强制下发」）绝不能
            // 改配置、也不能重写自动组网记忆——否则运行中的实例仍用启动时的旧配置，
            // 后台却以为新参数已生效；迟到的 start 还会把用户刚点「停止」清掉的记忆写回。
            val st = VntMeshController.state.value.status
            // v0.2.38：RECONNECTING = 断线自动重连中（监督循环存活），与 RUNNING 同等对待
            if (st == VntStatus.RUNNING || st == VntStatus.STARTING || st == VntStatus.RECONNECTING) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：组网已在运行/自动重连中（${VntMeshController.statusText()}）；" +
                        "本次参数未生效、未改动自动组网记忆。如需变更配置请先停止再开始",
                )
            }
            if (st == VntStatus.STOPPING) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：正在停止组网，请稍后用 vnt_status 确认状态后再试",
                )
            }
            // 确认要真正启动，才把覆盖参数写回单套配置 Prefs（与页面「组网配置」共用）
            if (server.isNotEmpty() || token.isNotEmpty() || password.isNotEmpty() || tcp != null) {
                val old = VntMeshPrefs.load(context)
                VntMeshPrefs.save(
                    context,
                    old.copy(
                        server = server.ifEmpty { old.server },
                        token = token.ifEmpty { old.token },
                        password = password.ifEmpty { old.password },
                        tcp = tcp ?: old.tcp,
                    ),
                )
            }
            val cfg = VntMeshPrefs.load(context)
            if (cfg.token.isBlank()) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "组网编号（Token）为空：请先在 APP「组网配置」里填写，或用本命令 token 参数指定",
                )
            }
            VntServerAddress.error(cfg.server)?.let {
                return CommandResult(command.commandId, command.command, false, "服务端地址非法：$it")
            }
            if (!VntMeshController.isPrepared(context)) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "尚未授予系统 VPN 权限：需 App 在前台由用户点一次「开始组网」完成授权后，远程启动才可用",
                )
            }
            val launched = VntMeshController.start(context)
            if (launched) VntMeshPrefs.setAutoStart(context, true)
            CommandResult(
                command.commandId, command.command, launched,
                if (launched) {
                    "已下发开始组网：${cfg.server} / Token ${cfg.token}（" +
                        "${if (cfg.tcp) "TCP" else "UDP"}）" +
                        "（首次需等待服务器注册，用 vnt_status 查状态；切 Tab/退后台不断连）"
                } else {
                    "启动失败：多为 Android 12+ 后台启动前台服务受限，详见设备日志；请让 App 回到前台再试"
                },
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 vnt_start 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** vnt_stop：停止组网（普通 startService，无前台义务，无平台限制）。 */
    private fun executeVntStop(context: Context, command: Command): CommandResult {
        return try {
            val before = VntMeshController.statusText()
            // v0.2.33：未组网时不下发停止。原实现无条件 startService(ACTION_STOP)：
            // 服务不存在时 Android 会新建一个 VntVpnService → onCreate 挂前台通知 →
            // shutdown() 里 worker 为 null ⇒ 没人调 onStopped()/stopSelf() ⇒ 空前台服务
            // 常驻、状态卡在 STOPPING；且无条件清 autoStart 会让下次冷启动不再自动组网，
            // 与「虚拟网必可达」硬约束冲突。
            val st = VntMeshController.state.value.status
            if (st == VntStatus.STOPPED || st == VntStatus.ERROR) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：当前未组网（$before）；未执行停止、开机自动组网记忆保持不变。" +
                        "若后台记录与实际不符，请点「刷新状态」或勾选「强制下发」重新确认",
                )
            }
            // v0.2.31：远程 stop 与页面「停止组网」一致，清自动组网记忆
            VntMeshPrefs.setAutoStart(context, false)
            VntMeshController.stop(context)
            CommandResult(
                command.commandId, command.command, true,
                "已下发停止组网（停止前：$before）；已清除开机自动组网记忆",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 vnt_stop 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** vnt_status：只读本地缓存快照，不触发 Vnt.list()。 */
    private fun executeVntStatus(command: Command): CommandResult {
        return try {
            CommandResult(
                command.commandId, command.command, true,
                VntMeshController.statusText(),
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 vnt_status 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** ssh_start：写意愿 + 启动内嵌 SSH 服务器。幂等守卫放最前（v0.2.33 约定）。 */
    private fun executeSshStart(context: Context, command: Command): CommandResult {
        return try {
            val st = SshServerController.state.value.status
            if (st == SshStatus.RUNNING || st == SshStatus.STARTING) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：SSH 服务已在运行（${SshServerController.statusText()}）；" +
                        "如需变更端口等配置请先 ssh_stop 再开始",
                )
            }
            if (st == SshStatus.STOPPING) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：正在停止 SSH 服务，请稍后用 ssh_status 确认状态后再试",
                )
            }
            // 意愿置真：开机自启 / 冷启动 / STICKY 重建据此自动恢复
            SshPrefs.setEnabled(context, true)
            val launched = SshServerController.start(context)
            CommandResult(
                command.commandId, command.command, launched,
                if (launched) {
                    "已下发启动 SSH 服务：端口 ${SshPrefs.port(context)} · 用户名 ${SshPrefs.USERNAME} · " +
                        "访问密码见 APP 状态页 · 存储暴露 ${SshPrefs.storageModeText()}；" +
                        "用 ssh_status 查状态，PC 连接示例 ssh -p ${SshPrefs.port(context)} ${SshPrefs.USERNAME}@<手机IP>"
                } else {
                    "启动失败：多为 Android 12+ 后台启动前台服务受限，详见设备日志；请让 App 回到前台再试"
                },
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 ssh_start 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** ssh_stop：写意愿 + 停止服务（断开全部会话）。未运行时幂等忽略，不假装成功。 */
    private fun executeSshStop(context: Context, command: Command): CommandResult {
        return try {
            val before = SshServerController.statusText()
            val st = SshServerController.state.value.status
            if (st == SshStatus.STOPPED || st == SshStatus.ERROR) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：当前 SSH 服务未运行（$before）；未执行停止、自恢复意愿保持不变。" +
                        "若后台记录与实际不符，请点「刷新状态」或勾选「强制下发」重新确认",
                )
            }
            SshPrefs.setEnabled(context, false)   // 远程 stop 清自恢复意愿
            SshServerController.stop(context)
            CommandResult(
                command.commandId, command.command, true,
                "已下发停止 SSH 服务（停止前：$before）；已断开全部活动会话、清除自恢复意愿",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 ssh_stop 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** ssh_status：只读本地快照，不触发网络操作。 */
    private fun executeSshStatus(context: Context, command: Command): CommandResult {
        return try {
            CommandResult(
                command.commandId, command.command, true,
                SshServerController.statusText(),
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 ssh_status 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 无障碍服务命令（v0.2.51：开启/关闭/检查状态）
    //
    // 复用 BridgeAccessibilityService 现有能力，补齐「能否程序内自动切换」的探测：
    // - 已绑定实例（isReady）→ 无障碍真正在跑；
    // - 已在设置开启（isEnabledInSettings）→ 系统配置层面已开（可能未绑定）；
    // - 持有 WRITE_SECURE_SETTINGS（canWriteSecureSettings）→ 可程序内直写开关。
    // 幂等守卫放最前（对齐 vnt/ssh 约定）：重复下发不假装成功。
    // ------------------------------------------------------------------

    /** accessibility_enable：程序内开启无障碍。幂等守卫放最前。 */
    private fun executeAccessibilityEnable(context: Context, command: Command): CommandResult {
        return try {
            if (BridgeAccessibilityService.isEnabledInSettings(context)) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：无障碍已在设置中开启" +
                        if (BridgeAccessibilityService.isReady()) "（服务已绑定，正在运行）" else "（但服务尚未绑定实例，请稍候或检查）",
                )
            }
            if (!BridgeAccessibilityService.canWriteSecureSettings(context)) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "无法程序内开启：未持有 WRITE_SECURE_SETTINGS 权限。" +
                        "请在管理后台「设备」页用 ADB 一键授权（或设备端 设置→权限管理）后重试",
                )
            }
            val ok = BridgeAccessibilityService.enableInSettings(context)
            CommandResult(
                command.commandId, command.command, ok,
                if (ok) {
                    "已程序内开启无障碍（写入系统设置，系统将自动绑定服务）"
                } else {
                    "开启失败：写入系统设置失败（可能权限被撤销或 ROM 限制），请改用 ADB 授权"
                },
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 accessibility_enable 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** accessibility_disable：程序内关闭无障碍。未开启时幂等忽略。 */
    private fun executeAccessibilityDisable(context: Context, command: Command): CommandResult {
        return try {
            if (!BridgeAccessibilityService.isEnabledInSettings(context)) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：无障碍当前未在设置中开启，无需关闭",
                )
            }
            if (!BridgeAccessibilityService.canWriteSecureSettings(context)) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "无法程序内关闭：未持有 WRITE_SECURE_SETTINGS 权限。" +
                        "请在管理后台「设备」页用 ADB 一键授权后重试",
                )
            }
            val ok = BridgeAccessibilityService.disableInSettings(context)
            CommandResult(
                command.commandId, command.command, ok,
                if (ok) {
                    "已程序内关闭无障碍（从系统设置移除本服务绑定；静默截屏/锁屏/UI 自动化能力将随之停用）"
                } else {
                    "关闭失败：写入系统设置失败（可能权限被撤销或 ROM 限制），请改用 ADB 或设备端手动关闭"
                },
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 accessibility_disable 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** accessibility_status：只读快照，返回三项关键状态。 */
    private fun executeAccessibilityStatus(context: Context, command: Command): CommandResult {
        return try {
            val enabled = BridgeAccessibilityService.isEnabledInSettings(context)
            val ready = BridgeAccessibilityService.isReady()
            val canWrite = BridgeAccessibilityService.canWriteSecureSettings(context)
            val sb = StringBuilder()
            sb.append("无障碍状态：").append(if (enabled) "已开启" else "未开启")
            sb.append("；实例绑定：").append(if (ready) "已绑定（运行中）" else "未绑定")
            sb.append("；WRITE_SECURE_SETTINGS 权限：").append(if (canWrite) "已授予（可程序内自动切换）" else "未授予（只能 ADB/手动切换）")
            CommandResult(command.commandId, command.command, true, sb.toString())
        } catch (e: Exception) {
            Timber.e(e, "命令 accessibility_status 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 微信离开即释放开关命令（v0.2.51）
    //
    // 开关存储于 WechatPrefs.releaseOnLeave（默认 false），设置页「保活设置」里也有
    // 同款开关。语义（用户原话对时）：
    // - 打开：只写 Prefs=true，无需额外动作（下次离开微信页才释放，符合语义）。
    // - 关闭：写 Prefs=false 后，若当前「正离开微信页」或「正停在微信页但已切后台/灭屏」
    //   → 立即 stopAll 释放资源（等同「离开」）；若正亮屏停在微信页 → 不释放，
    //   交给用户离开时 onPageLeave 自动关闭（用户正在使用，离开后自动关闭）。
    // ------------------------------------------------------------------

    /** 读取屏幕是否亮起（isInteractive）。 */
    private fun isScreenInteractive(context: Context): Boolean =
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isInteractive
        } catch (e: Exception) {
            true // 读不到按亮屏处理（保守：不误释放用户正在用的微信）
        }

    /** wechat_release_enable：打开「离开后立即结束」。 */
    private fun executeWechatReleaseEnable(context: Context, command: Command): CommandResult {
        return try {
            val wasOn = WechatController.releaseOnLeave(context)
            if (wasOn) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：「离开后立即结束」已是开启状态",
                )
            }
            WechatController.setReleaseOnLeave(context, true)
            CommandResult(
                command.commandId, command.command, true,
                "已开启「微信离开后立即结束」：离开微信页（切 Tab / 按 Home / 锁屏）时将自动释放微信资源；" +
                    "当前正在使用中不受影响",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 wechat_release_enable 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** wechat_release_disable：关闭开关，并按当前状态决定是否立即释放微信资源。 */
    private fun executeWechatReleaseDisable(context: Context, command: Command): CommandResult {
        return try {
            val wasOn = WechatController.releaseOnLeave(context)
            if (!wasOn) {
                return CommandResult(
                    command.commandId, command.command, false,
                    "重复下发被忽略：「离开后立即结束」已是关闭状态",
                )
            }
            // 记录关闭前状态，供回执说明「是否触发了立即释放」
            val wasPageActive = WechatRuntime.isPageActive()
            val wasStopped = WechatRuntime.isStopped()
            val screenOn = isScreenInteractive(context)

            // 写关闭开关
            WechatController.setReleaseOnLeave(context, false)

            // 判断是否需要立即释放：
            // ① 不在微信页（pageActive=false）→ 原本就是「离开即释放」状态下，开关开着时
            //    微信已被释放（isStopped=true），关闭开关无需额外动作；
            // ② 正停在微信页 + 亮屏 → 用户正在使用，不释放，交给离开时自动关闭（但现在
            //    开关已关，离开时也不会释放了——这正是用户要的「关闭开关」效果）；
            // ③ 正停在微信页 + 灭屏/后台 → 立即释放（等同「离开」），避免后台空挂连接。
            val releasedNow = wasPageActive && !screenOn && !wasStopped
            if (releasedNow) {
                WechatRuntime.stopAll("后台关闭「离开后立即释放」")
                WechatStateProxy.requestExitToHomeIfNeeded()
            }
            val note = when {
                !wasPageActive -> "当前不在微信页，无需立即释放（微信本就处于释放状态）"
                screenOn -> "当前正亮屏停在微信页，不释放（用户正在使用，关闭开关后离开也不会自动释放）"
                releasedNow -> "当前微信页已切后台/灭屏，已立即释放微信资源"
                else -> "微信资源此前已释放，无需重复释放"
            }
            CommandResult(
                command.commandId, command.command, true,
                "已关闭「微信离开后立即释放」：$note",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 wechat_release_disable 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** 关闭开关时若触发立即释放，通知 UI 切回主页（避免「回来还在微信页」）。 */
    private object WechatStateProxy {
        fun requestExitToHomeIfNeeded() {
            try {
                my.bridge.com.wechat.WechatState.requestExitToHome()
            } catch (e: Exception) {
                Timber.w(e, "切回主页令牌请求失败（忽略）")
            }
        }
    }

    /** wechat_release_status：只读快照，返回开关值 + 当前微信页/释放状态。 */
    private fun executeWechatReleaseStatus(context: Context, command: Command): CommandResult {
        return try {
            val on = WechatController.releaseOnLeave(context)
            val pageActive = WechatRuntime.isPageActive()
            val stopped = WechatRuntime.isStopped()
            val screenOn = isScreenInteractive(context)
            val sb = StringBuilder()
            sb.append("微信离开即释放：").append(if (on) "已开启" else "已关闭")
            sb.append("；当前微信页：").append(if (pageActive) "停留中" else "未停留")
            sb.append("；微信资源：").append(if (stopped) "已释放" else "常驻在线")
            sb.append("；屏幕：").append(if (screenOn) "亮屏" else "灭屏")
            CommandResult(command.commandId, command.command, true, sb.toString())
        } catch (e: Exception) {
            Timber.e(e, "命令 wechat_release_status 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 无界面模式命令（v0.2.51：ui_lock 儿童锁 / 防误触）
    //
    // UiLockController 管理锁定状态（Compose 可观察 + Prefs 持久化），
    // 锁定后 MainScreen 顶层渲染全屏遮挡层，不组合各页 → 页面级资源随组合销毁自动释放；
    // 微信按「离开即释放」开关决定；组网/主 WS/保活不动（硬约束）。
    // ------------------------------------------------------------------

    /** ui_lock_start：开启无界面模式。 */
    private fun executeUiLockStart(context: Context, command: Command): CommandResult {
        return try {
            val msg = my.bridge.com.ui.UiLockController.lock(context)
            CommandResult(command.commandId, command.command, true, msg)
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_lock_start 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** ui_lock_stop：关闭无界面模式。 */
    private fun executeUiLockStop(context: Context, command: Command): CommandResult {
        return try {
            val msg = my.bridge.com.ui.UiLockController.unlock(context)
            CommandResult(command.commandId, command.command, true, msg)
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_lock_stop 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /** ui_lock_status：只读快照。 */
    private fun executeUiLockStatus(command: Command): CommandResult {
        return try {
            val locked = my.bridge.com.ui.UiLockController.isLocked()
            CommandResult(
                command.commandId, command.command, true,
                if (locked) "无界面模式：已开启（界面已锁定，仅后台可解除）"
                else "无界面模式：未开启",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_lock_status 执行失败")
            CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
        }
    }

    /**
     * 执行 play_music：音源参数 url 按形态分三路。
     *
     * ① http(s):// 开头 → 远程 URL，MediaPlayer 直接流式播放（不缓存）；
     * ② / 开头 → 本地绝对路径，文件存在即直接播放（不存在则失败回执）；
     * ③ 其余（裸文件名，如 test.mp3）→ 客户端缓存目录优先：
     *    命中 → 直接播放；未命中 → 从服务器共享音乐目录下载（GET /api/music/{name}），
     *    持久化到缓存目录后播放，下次同名直接命中。
     *
     * 缓存目录 = App 私有目录 music/（getExternalFilesDir），无需存储权限，卸载自动清理。
     */
    private suspend fun executePlayMusic(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        val source = command.params.get("url")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
        if (source.isNullOrEmpty()) {
            return CommandResult(id, name, false, "缺少音源参数 url")
        }
        val loop = command.params.get("loop")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean ?: false

        return try {
            val resolved = resolveSource(context, deviceStore, apiClient, source)
            MusicPlayer.play(resolved, loop)
            Timber.i("命令 play_music 播放成功：$resolved（loop=$loop）")
            CommandResult(id, name, true, "播放中：$source")
        } catch (e: Exception) {
            Timber.e(e, "命令 play_music 播放失败：$source")
            CommandResult(id, name, false, "播放失败：${e.message}")
        }
    }

    /**
     * 音乐控制类命令（pause/resume/stop）统一包装：
     * 按当前 [MusicPlayer.state] 判定操作合法性，异常统一转失败回执。
     *
     * block 返回 (是否成功, 回执消息)；操作被状态约束拒绝时直接返回失败对，
     * 播放器抛出的 [IllegalStateException] 等异常也在此兜底。
     */
    private inline fun executeMusicControl(
        command: Command,
        block: () -> Pair<Boolean, String>,
    ): CommandResult = try {
        val (success, message) = block()
        CommandResult(command.commandId, command.command, success, message)
    } catch (e: Exception) {
        Timber.e(e, "命令 ${command.command} 执行失败")
        CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
    }

    /**
     * 执行 get_location：获取设备位置（真实实现，见 [LocationHelper]）。
     *
     * @param params.precision "coarse"（网络定位，默认）/ "fine"（GPS，超时回退网络）
     * @param params.timeout   超时秒数（默认 15，下限 5 上限 120）
     * @param params.refresh   强制刷新：跳过 5 分钟新鲜缓存短路，直接监听定位；
     *                         陈旧缓存兜底仍保留（回执来源字段标注缓存年龄）
     *
     * 权限：Manifest 已声明 COARSE/FINE/BACKGROUND（保活资源锚点），此处校验运行时授权；
     * fine 模式需 FINE 权限（仅有 COARSE 时自动降级为 coarse 并注明）。
     * v1 纯文本回执；[my.bridge.com.location.LocationHelper.LocationFix] 为结构化数据，
     * 后续版本直接序列化上报服务端持久化（SQL/MySQL）。
     */
    private suspend fun executeGetLocation(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command

        if (!LocationHelper.hasPermission(context)) {
            return CommandResult(id, name, false, "缺少定位权限，请打开 App 在系统设置中授予定位权限后重试")
        }
        var precision = command.params.get("precision")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim() ?: "coarse"
        var degradedNote = ""
        if (precision == "fine" && !LocationHelper.hasFinePermission(context)) {
            precision = "coarse"
            degradedNote = "（仅有粗略定位权限，已降级为网络定位）"
        }
        val timeoutSec = command.params.get("timeout")
            ?.takeIf { it.isJsonPrimitive }
            ?.asInt
            ?.coerceIn(5, 120) ?: 15
        val refresh = command.params.get("refresh")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean ?: false
        val refreshNote = if (refresh) "（强制刷新，已跳过缓存）" else ""

        return try {
            val fix = LocationHelper.getLocation(context, precision, timeoutSec * 1000L, refresh)
            Timber.i("命令 get_location 成功：${fix.toMessage()}（refresh=$refresh）")
            reportLocationFireAndForget(deviceStore, apiClient, fix)
            CommandResult(id, name, true, fix.toMessage() + degradedNote + refreshNote)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_location 失败")
            CommandResult(id, name, false, "定位失败：${e.message}")
        }
    }

    /**
     * 位置上报服务端持久化（fire-and-forget）：成功后异步 POST /api/locations，
     * 失败仅记日志，**绝不影响命令回执**——存档与回执解耦。
     */
    private fun reportLocationFireAndForget(
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        fix: my.bridge.com.location.LocationHelper.LocationFix,
    ) {
        reportScope.launch {
            try {
                val deviceId = deviceStore.deviceId()
                val token = runCatching { deviceStore.token() }.getOrNull()
                val action = apiClient.reportLocation(
                    deviceId = deviceId,
                    token = token,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracy = fix.accuracyMeters.toDouble(),
                    provider = fix.provider,
                    altitude = fix.altitudeMeters,
                    speed = fix.speedMps?.toDouble(),
                    fixTimeMs = fix.timestampMs,
                )
                Timber.i("位置上报成功：device=%s action=%s", deviceId, action)
            } catch (e: Exception) {
                Timber.w(e, "位置上报失败（不影响回执，忽略）")
            }
        }
    }

    private fun ok(message: String): Pair<Boolean, String> = true to message

    private fun fail(message: String): Pair<Boolean, String> = false to message

    // ------------------------------------------------------------------
    // 数据采集类命令（get_contacts / get_sms / get_call_log）
    //
    // 共同形态：权限校验 → 本地读取 → REST 上报服务端持久化 → 汇总回执。
    // 与 get_location 不同，这里**数据本身就是命令主产物**（后台页面消费），
    // 上报失败要在回执中注明而非静默忽略。
    // ------------------------------------------------------------------

    /** 参数里的开关（缺省 false）。 */
    private fun Command.boolParam(key: String): Boolean =
        params.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    /** 参数里的整数（缺省 default，收敛到 min–max）。 */
    private fun Command.intParam(key: String, default: Int, min: Int, max: Int): Int =
        params.get(key)?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(min, max) ?: default

    /** 参数里的字符串（缺省 default）。 */
    private fun Command.strParam(key: String, default: String): String =
        params.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: default

    /** 数据类命令执行骨架：校验权限 → 读取 → 上报 → 回执。 */
    private suspend fun <T> executeDataCollection(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
        permissionName: String,
        hasPermission: (Context) -> Boolean,
        read: (Context) -> List<T>,
        upload: suspend (deviceId: String, token: String?, items: List<T>) -> String,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!hasPermission(context)) {
            return CommandResult(id, name, false, "缺少${permissionName}权限，请打开 App 在系统设置中授予后重试")
        }
        val items = try {
            read(context)
        } catch (e: Exception) {
            Timber.e(e, "命令 $name 本地读取失败")
            return CommandResult(id, name, false, "读取失败：${e.message}")
        }
        if (items.isEmpty()) {
            return CommandResult(id, name, true, "未读取到数据（可能设备上确实没有，或被参数过滤）")
        }
        return try {
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            val summary = upload(deviceId, token, items)
            Timber.i("命令 %s 成功：%s", name, summary)
            CommandResult(id, name, true, summary)
        } catch (e: Exception) {
            Timber.e(e, "命令 $name 上报失败（已读取 ${items.size} 条）")
            CommandResult(
                id, name, false,
                "已读取 ${items.size} 条但上报失败：${e.message}（可重试下发）",
            )
        }
    }

    /**
     * 执行 get_contacts：读取通讯录并上报（服务端按设备覆盖快照）。
     *
     * @param params.only_with_phones 仅含有号码的联系人（默认开）
     * @param params.limit            数量上限（默认 2000，上限 5000）
     */
    private suspend fun executeGetContacts(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val onlyWithPhones =
            if (command.params.has("only_with_phones")) command.boolParam("only_with_phones") else true
        val limit = command.intParam("limit", 2000, 10, 5000)
        return executeDataCollection(
            context, deviceStore, apiClient, command,
            permissionName = "通讯录",
            hasPermission = { ContactsReader.hasPermission(it) },
            read = { ContactsReader.read(it, onlyWithPhones, limit) },
        ) { deviceId, token, items ->
            val r = apiClient.reportContacts(
                deviceId, token,
                items.map {
                    BridgeApi.ContactItem(
                        contact_key = it.key, name = it.name,
                        phones = it.phones, emails = it.emails,
                    )
                },
            )
            "已上报 ${items.size} 个联系人（服务端快照已更新）"
        }
    }

    /**
     * 执行 get_sms：读取短信并上报（服务端累积 + 去重）。
     *
     * @param params.sms_type all/inbox/sent/draft/outbox/failed（默认 all）
     * @param params.limit    条数上限（默认 100，上限 1000）
     */
    private suspend fun executeGetSms(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val smsType = command.strParam("sms_type", "all")
        val limit = command.intParam("limit", 100, 5, 1000)
        return executeDataCollection(
            context, deviceStore, apiClient, command,
            permissionName = "短信",
            hasPermission = { SmsReader.hasPermission(it) },
            read = { SmsReader.read(it, smsType, limit) },
        ) { deviceId, token, items ->
            val r = apiClient.reportSmsBatch(
                deviceId, token,
                items.map {
                    BridgeApi.SmsItem(
                        sms_key = it.key, address = it.address, body = it.body,
                        type = it.type, date_ms = it.dateMs, read = it.read,
                        sim_slot = it.simSlot, sim_number = it.simNumber,
                    )
                },
            )
            "已读取 ${items.size} 条短信并上报（新增 ${r.inserted}，更新 ${r.updated}；" +
                "含所属卡信息，重复上报可回填历史数据的卡字段）"
        }
    }

    /**
     * 执行 get_call_log：读取通话记录并上报（服务端按设备覆盖快照）。
     *
     * @param params.call_type all/incoming/outgoing/missed（默认 all）
     * @param params.limit     条数上限（默认 200，上限 1000）
     */
    private suspend fun executeGetCallLog(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val callType = command.strParam("call_type", "all")
        val limit = command.intParam("limit", 200, 5, 1000)
        return executeDataCollection(
            context, deviceStore, apiClient, command,
            permissionName = "通话记录",
            hasPermission = { CallLogReader.hasPermission(it) },
            read = { CallLogReader.read(it, callType, limit) },
        ) { deviceId, token, items ->
            val r = apiClient.reportCallLogs(
                deviceId, token,
                items.map {
                    BridgeApi.CallLogItem(
                        call_key = it.key, number = it.number, name = it.name,
                        type = it.type, duration = it.duration,
                        date_ms = it.dateMs, location = it.location,
                        sim_slot = it.simSlot, sim_number = it.simNumber,
                    )
                },
            )
            "已上报 ${items.size} 条通话记录（服务端快照已更新；含所属卡信息）"
        }
    }

    /**
     * 执行 get_sim_info：读取 SIM 卡列表并上报（服务端按设备覆盖快照）。
     *
     * 上报后后台「发送短信/拨打电话」的选卡下拉即可显示该设备实际卡
     * （卡序号/号码/运营商）。无 READ_PHONE_STATE 权限时回执明示（卡来源
     * 标记与选卡校验同样依赖该权限）。
     */
    private suspend fun executeGetSimInfo(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!SimHelper.hasPhoneStatePermission(context)) {
            return CommandResult(
                id, name, false,
                "缺少电话状态权限，请打开 App 在系统设置中授予后重试",
            )
        }
        val sims = SimHelper.listSims(context)
        if (sims.isEmpty()) {
            return CommandResult(id, name, true, "未读到可用 SIM 卡（设备可能未插卡）")
        }
        val summary = "共 ${sims.size} 张卡：" + sims.joinToString("；") {
            "${it.slotLabel}（${if (it.number.isNotBlank()) it.number else "号码未知"}" +
                if (it.carrier.isNotBlank()) " · ${it.carrier}）" else "）"
        }
        return try {
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            apiClient.reportSims(
                deviceId, token,
                sims.map {
                    BridgeApi.SimItem(
                        slot_index = it.slotIndex,
                        subscription_id = it.subscriptionId,
                        number = it.number,
                        carrier = it.carrier,
                    )
                },
            )
            Timber.i("命令 %s 成功：%s", name, summary)
            CommandResult(id, name, true, "$summary（已上报，选卡下拉已可显示具体卡信息）")
        } catch (e: Exception) {
            Timber.e(e, "命令 get_sim_info 上报失败")
            CommandResult(id, name, false, "已读取（$summary）但上报失败：${e.message}（可重试下发）")
        }
    }

    // ------------------------------------------------------------------
    // 写操作类命令（send_sms / dial_phone）
    //
    // 与数据采集类相反：这两个命令**改动设备/外界状态**（真实发短信会计费、
    // 直接拨打会产生通话），客户端只做「机械执行 + 如实回执」，
    // 下发前的确认责任在管理后台操作者（catalog 描述里已注明计费风险）。
    // ------------------------------------------------------------------

    /**
     * 执行 send_sms：用本机 SIM 卡发送短信。
     *
     * @param params.phones  收件人号码，中英文逗号/分号/竖线分隔可发多个
     * @param params.content 短信内容（超 70 字自动 multipart 切分）
     * @param params.sim     发送卡：auto（默认卡）/ sim1 / sim2 / sim3；
     *                       指定的卡不存在时回执失败（不静默退默认卡）
     *
     * 逐号码串行发送，等待系统 sent 回执（15 秒超时）后才计结果，
     * 回执汇总实际使用卡 + 每个号码的成功/失败明细（见 [SmsSender]）。
     */
    private suspend fun executeSendSms(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!SmsSender.hasPermission(context)) {
            return CommandResult(id, name, false, "缺少短信权限，请打开 App 在系统设置中授予后重试")
        }
        val phonesRaw = command.params.get("phones")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val content = command.params.get("content")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (phonesRaw.isEmpty()) return CommandResult(id, name, false, "缺少接收号码参数 phones")
        if (content.isEmpty()) return CommandResult(id, name, false, "缺少短信内容参数 content")

        // 统一中英文逗号/分号/竖线为分号后拆分，去空去重（保持顺序）
        val phones = phonesRaw.replace("，", ";").replace("；", ";")
            .replace(",", ";").replace("|", ";")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (phones.isEmpty()) return CommandResult(id, name, false, "未解析到有效号码")
        if (phones.size > 20) return CommandResult(id, name, false, "单次最多 20 个号码（当前 ${phones.size} 个）")

        return try {
            // 解析发送卡：auto=null（系统默认卡），否则按卡槽序号找激活卡
            val simParam = command.params.get("sim")
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "auto"
            val simSlot = SimHelper.slotParam(simParam)
            var subscriptionId: Int? = null
            var simNote = "默认卡"
            if (simSlot != null) {
                val sim = SimHelper.findBySlot(context, simSlot)
                if (sim == null) {
                    return CommandResult(
                        id, name, false,
                        "设备上没有卡${simSlot + 1}（当前：${SimHelper.summarize(context)}）",
                    )
                }
                subscriptionId = sim.subscriptionId
                simNote = sim.displayLabel
            }
            val details = SmsSender.send(context, phones, content, subscriptionId)
            val okList = details.filter { it.success }
            val failList = details.filterNot { it.success }
            val message = buildString {
                append("用[$simNote]发送完成：成功 ${okList.size}/${details.size}")
                if (failList.isNotEmpty()) {
                    append("；失败：")
                    append(failList.joinToString("；") { "${it.phone}（${it.message}）" })
                }
            }
            Timber.i("命令 %s：%s", name, message)
            // 只要有一个号码发出即算命令成功（失败明细已在消息里），全部失败才回执失败
            CommandResult(id, name, okList.isNotEmpty(), message)
        } catch (e: Exception) {
            Timber.e(e, "命令 send_sms 执行失败")
            CommandResult(id, name, false, "发送失败：${e.message}")
        }
    }

    /**
     * 执行 mark_sms_read：批量把短信标记为已读/未读（read 参数双向）。
     *
     * v0.1.57：已读状态由 Bridge 内部映射（SmsReadStore）承载——ColorOS 等定制
     * SmsProvider 对第三方 update 系统库整体拒绝（v0.1.56 三级回退实测全部
     * 0 行），不再要求「默认短信应用」身份；系统库写入仍尽力尝试（宽容 ROM
     * 下原生短信应用同步受益）。
     * restore=true 时标记完成后弹「切回原短信应用」确认框（预选原包名）。
     */
    private fun executeMarkSmsRead(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command

        if (!SmsReader.hasPermission(context)) {
            return CommandResult(id, name, false, "设备未授予短信权限（READ_SMS），请打开 App 授权")
        }

        val mode = command.strParam("mode", "all_unread")
        val smsId = command.strParam("sms_id", "")
        val address = command.strParam("address", "")
        // 标记方向：read（默认）=标记为已读；unread=标记为未读
        val targetRead = command.strParam("read", "read") != "unread"
        val targetName = if (targetRead) "已读" else "未读"
        // 标记未读只对收到的短信有意义（发出的短信无未读概念），type=1 过滤
        val readFilter = if (targetRead) "read = 0" else "read = 1 AND type = 1"
        val (selection, args) = when (mode) {
            "by_id" -> {
                if (smsId.isEmpty()) {
                    return CommandResult(id, name, false, "mode=by_id 需要参数 sms_id（短信 ID）")
                }
                "_id = ?" to arrayOf(smsId)
            }
            "by_address" -> {
                if (address.isEmpty()) {
                    return CommandResult(id, name, false, "mode=by_address 需要参数 address（号码）")
                }
                "address = ?" to arrayOf(address)
            }
            "before_date" -> {
                val raw = command.strParam("before_date", "")
                val epoch = parseBeforeDate(raw)
                if (epoch == null) {
                    return CommandResult(
                        id, name, false,
                        "mode=before_date 需要参数 before_date，格式：2026-10-26 或 2026-10-26 12:30",
                    )
                }
                "date < ? AND $readFilter" to arrayOf(epoch.toString())
            }
            else -> readFilter to emptyArray() // all_unread（默认）
        }

        return try {
            val affected = SmsRepository.setReadState(context, targetRead, selection, args)
            var message = when (mode) {
                "by_id" -> if (affected > 0) "短信 $smsId 已标记为$targetName"
                          else "未找到该短信（可能已删除或已是该状态）"
                "by_address" -> "已将号码 $address 的 $affected 条短信标记为$targetName"
                "before_date" -> "已将指定时间之前的 $affected 条短信标记为$targetName"
                else -> if (affected > 0) {
                    if (targetRead) "已将 $affected 条未读短信标记为已读"
                    else "已将 $affected 条已收短信标记为未读"
                } else "没有需要标记的短信"
            }
            if (mode == "by_id" && affected == 0) {
                return CommandResult(id, name, false, message)
            }

            // restore：完成后引导切回原短信应用（需设备上再点一次确认）
            val restore = command.boolParam("restore")
            if (restore) {
                message += if (SmsDefaultHelper.launchRestorePrevious(context)) {
                    "；已在设备上弹出「切回原短信应用」确认框，请确认"
                } else {
                    "；如需切回原短信应用，请在设备上打开 Bridge → 短信页操作"
                }
            }
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 mark_sms_read 执行失败")
            CommandResult(id, name, false, "标记失败：${e.message}")
        }
    }

    /** "yyyy-MM-dd[ HH:mm[:ss]]" → epoch 毫秒（本地时区）；非法返回 null。 */
    private fun parseBeforeDate(raw: String): Long? = try {
        val trimmed = raw.trim()
        val fmt = when (trimmed.length) {
            10 -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            16 -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            else -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        }
        fmt.isLenient = false
        fmt.parse(trimmed)?.time
    } catch (e: Exception) {
        null
    }

    /**
     * 执行 dial_phone：拨打电话。
     *
     * @param params.phone 电话号码
     * @param params.mode  direct（直接拨打，默认）/ dialer（打开拨号盘预填）
     *
     * direct 需 CALL_PHONE 运行时权限，缺失时**自动降级**为 dialer 并在回执注明；
     * 息屏/后台下系统可能拦截拨号界面弹出（Android 10+ 后台启动限制），
     * 回执里带提示，完整限制说明见 [PhoneDialer]。
     */
    private fun executeDialPhone(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val phone = command.params.get("phone")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (phone.isEmpty()) return CommandResult(id, name, false, "缺少电话号码参数 phone")
        val mode = command.params.get("mode")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "direct"
        // 使用卡：auto=null（系统默认卡），否则 0 基卡槽序号（仅 direct 模式生效）
        val simParam = command.params.get("sim")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "auto"
        val simSlot = SimHelper.slotParam(simParam)
        if (simSlot != null && mode != "dialer" && SimHelper.findBySlot(context, simSlot) == null) {
            // 指定的卡不存在：直接回执失败，避免「以为用卡2打了实际走默认卡」
            return CommandResult(
                id, name, false,
                "设备上没有卡${simSlot + 1}（当前：${SimHelper.summarize(context)}）",
            )
        }

        var direct = mode != "dialer"
        if (direct && !PhoneDialer.hasCallPermission(context)) {
            direct = false
            return try {
                val message = PhoneDialer.dial(context, phone, direct)
                CommandResult(id, name, true, "无直接拨打权限，已降级为拨号盘：$message")
            } catch (e: Exception) {
                Timber.e(e, "命令 dial_phone 降级拨号盘也失败")
                CommandResult(id, name, false, "缺少 CALL_PHONE 权限且拨号盘打开失败：${e.message}")
            }
        }
        return try {
            val message = PhoneDialer.dial(context, phone, direct, simSlot)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: IllegalArgumentException) {
            CommandResult(id, name, false, e.message ?: "号码格式不合法")
        } catch (e: Exception) {
            Timber.e(e, "命令 dial_phone 执行失败")
            CommandResult(id, name, false, "拨号失败：${e.message}")
        }
    }

    /**
     * 执行 set_volume：调节系统音量（媒体/铃声/闹钟）。
     *
     * @param params.level  音量百分比 0-100（换算到设备实际档位）
     * @param params.stream media（默认）/ ring / alarm
     *
     * 设置后回读验证 + 三段兜底（见 [VolumeController]），
     * 回执带设备实际档位（如「媒体 音量已设为 12/15（80%）」）。
     */
    private fun executeSetVolume(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val level = command.params.get("level")
            ?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
            ?: return CommandResult(id, name, false, "缺少音量参数 level")
        if (level < 0 || level > 100) {
            return CommandResult(id, name, false, "音量百分比须在 0-100，收到：$level")
        }
        val streamParam = command.params.get("stream")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "media"
        return try {
            val message = VolumeController.set(context, VolumeController.streamFor(streamParam), level.toInt())
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 set_volume 执行失败")
            CommandResult(id, name, false, e.message ?: "音量设置失败")
        }
    }

    /**
     * 执行 get_volume：读取全部音量状态（5 类音频流 + 铃声模式 + 免打扰）。
     * 纯只读；后台命令页会按回执后的快照展示音量卡片（滑条可反向下发调节）。
     */
    private fun executeGetVolume(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val snap = VolumeController.snapshot(context)
            val message = VolumeController.snapshotMessage(snap)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_volume 执行失败")
            CommandResult(id, name, false, e.message ?: "读取音量失败")
        }
    }

    /**
     * 执行 stream_stop：请求停止进行中的流式录像/录音。
     * 客户端置停止标志后，采集循环在下一个分片周期（≤500ms）退出并自动走
     * 「完整文件上传」流程；本命令立即回执（不等上传），上传回执由原
     * record_video / record_audio 命令携带。
     */
    private fun executeStreamStop(command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return if (MediaCaptureService.requestStreamStop()) {
            Timber.i("命令 stream_stop：已请求停止流式采集")
            CommandResult(id, name, true, "已请求停止流式采集（≤1 秒内生效），完整文件随后自动上传")
        } else {
            CommandResult(id, name, false, "当前没有进行中的流式采集（录像/录音未开流式模式，或已结束）")
        }
    }

    /**
     * 执行 lock_screen：锁屏（熄屏并上锁）。
     *
     * 两级策略（v0.1.40）：
     * 1. **首选无障碍模拟电源键**（GLOBAL_ACTION_LOCK_SCREEN，Android 9+）：与手动按
     *    电源键完全等价——正常锁屏、正常解锁，不会触发 lockNow 的「为安全起见请输入
     *    密码解锁」强制认证（设备管理员锁屏的固有副作用，实测确认）；
     * 2. **兜底设备管理员 lockNow**：无障碍未开启时走旧路（保留该方式，用户拍板）；
     *    未激活时发高优先级引导通知（v0.1.37：裸 startActivity 被 Android 10+ 后台
     *    启动限制静默拦截），并建议开启无障碍获得更好的锁屏体验。
     */
    private fun executeLockScreen(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command

        // ① 无障碍模拟电源键（与手动锁屏一致，无密码认证副作用）
        if (BridgeAccessibilityService.isReady()) {
            return if (BridgeAccessibilityService.lockScreen()) {
                Timber.i("命令 lock_screen：已模拟电源键锁屏（无障碍）")
                CommandResult(id, name, true, "已锁屏（模拟电源键）")
            } else {
                Timber.e("命令 lock_screen：无障碍锁屏动作执行失败")
                CommandResult(id, name, false, "锁屏失败：无障碍锁屏动作未生效")
            }
        }

        // ② 设备管理员兜底（lockNow：锁的是安全锁，重新点亮屏幕会要求输密码）
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, BridgeAdminReceiver::class.java)
        if (dpm == null || !dpm.isAdminActive(admin)) {
            my.bridge.com.admin.AdminActivationNotifier.post(context)
            return CommandResult(
                id, name, false,
                "无法锁屏：无障碍服务与设备管理员均未开启。推荐在设备上开启「Bridge 辅助服务」" +
                    "（设置 → 无障碍 → Bridge 辅助服务，锁屏体验与手动按电源键一致）；" +
                    "或手动激活设备管理员（设置 → 安全性 → 设备管理应用 → Bridge）后重新下发",
            )
        }
        return try {
            dpm.lockNow()
            Timber.i("命令 lock_screen：已锁屏（设备管理员 lockNow）")
            CommandResult(
                id, name, true,
                "已锁屏（设备管理员方式）。注意：重新点亮屏幕时系统会要求输入密码解锁；" +
                    "开启无障碍「Bridge 辅助服务」可改为与电源键一致的正常锁屏",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 lock_screen 执行失败")
            CommandResult(id, name, false, "锁屏失败：${e.message}")
        }
    }

    /**
     * 执行 restart_app：重启客户端应用进程。
     *
     * 顺序敏感：先预约「1 秒后由系统闹钟拉起启动页」（进程被杀仍生效），
     * 再回执，回执发出后延迟杀进程（给 WebSocket 发送留时间）——
     * 若先杀进程，回执就永远发不出去。
     */
    private fun executeRestartApp(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return CommandResult(id, name, false, "无法获取启动 Intent，重启取消")

        // ① 预约重启拉起：AlarmManager 1 秒后启动 MainActivity（进程被杀仍触发）
        val pi = PendingIntent.getActivity(
            context,
            RESTART_APP_REQUEST_CODE,
            launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + 1000
        try {
            am?.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }

        // ② 回执发出后（主线程延迟）杀进程；前台服务为 sticky，系统也会自动重拉
        Handler(Looper.getMainLooper()).postDelayed({
            Timber.i("restart_app：预约已就绪，杀进程完成重启")
            Runtime.getRuntime().exit(0)
        }, RESTART_APP_KILL_DELAY_MS)

        Timber.i("命令 restart_app：已预约 1 秒后重启")
        return CommandResult(
            id, name, true,
            "已预约重启：客户端进程即将退出并自动拉起（WebSocket 约 2-3 秒后重连）",
        )
    }

    /**
     * 执行 get_current_page：读取设备当前前台应用（应用级，见 [ForegroundAppReader]）。
     *
     * 需要「使用情况访问权」特殊授权（设备上开一次）：未授权时尝试弹出
     * 系统设置页（Android 10+ 后台可能被拦），回执失败并附开启步骤。
     */
    private fun executeGetCurrentPage(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!ForegroundAppReader.hasUsageAccess(context)) {
            val launched = ForegroundAppReader.launchUsageAccessSettings(context)
            return CommandResult(id, name, false, ForegroundAppReader.notGrantedMessage(launched))
        }
        return try {
            val message = ForegroundAppReader.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_current_page 执行失败")
            CommandResult(id, name, false, e.message ?: "读取前台应用失败")
        }
    }

    /**
     * 执行 get_network：读取当前网络状态（见 [NetworkReporter]）。
     * 只读查询，无需新权限（缺定位/电话权限时对应子项显示「未知」不失败）。
     */
    private fun executeGetNetwork(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val message = NetworkReporter.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_network 执行失败")
            CommandResult(id, name, false, e.message ?: "读取网络状态失败")
        }
    }

    /**
     * 执行 get_battery：读取电池状态（见 [BatteryReporter]）。
     * 粘性广播零权限，只读。
     */
    private fun executeGetBattery(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val message = BatteryReporter.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_battery 执行失败")
            CommandResult(id, name, false, e.message ?: "读取电池状态失败")
        }
    }

    /**
     * 执行 get_device_stats：聚合电量 + 活跃时长 + 流量（见 [DeviceStatsReporter]）。
     * 全部下发时读一次，零持续后台开销。
     */
    private fun executeGetDeviceStats(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val message = DeviceStatsReporter.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_device_stats 执行失败")
            CommandResult(id, name, false, e.message ?: "读取设备统计失败")
        }
    }

    // ------------------------------------------------------------------
    // 媒体采集类命令（screenshot / record_screen / take_photo）
    //
    // 共同形态：采集 → 产物落 App 私有 media/ 目录 → multipart 上传服务端 → 回执。
    // 截屏/录屏首次需设备端点一次「立即开始」（MediaProjection 系统限制）；
    // 之后投屏会话由 MediaCaptureService 常驻保持（借鉴 autox：一次授权进程
    // 不重启一直有效），后续命令免确认直接采集。Android 14+ 采集前必须
    // registerCallback（v0.1.36 每次报错的根因，v0.1.37 修复）。
    // ------------------------------------------------------------------

    /** 采集等待常量：授权最长等 90 秒，单帧采集等 20 秒。 */
    private const val CONSENT_TIMEOUT_MS = 90_000L
    private const val CAPTURE_TIMEOUT_MS = 20_000L

    /**
     * 执行 screenshot / record_screen：投屏授权 → 前台服务采集 → 上传。
     *
     * @param params.duration 录制秒数 3~60（仅 record_screen，默认 10）
     *
     * ① 会话保持中（projectionHeld）直接跳授权进采集；否则弹系统投屏授权框
     *    （透明 Activity），90 秒内未确认回执失败；
     * ② 授权后启动 [MediaCaptureService]（mediaProjection 类型前台服务）采集，
     *    完成后服务转「会话保持中」常驻（不释放授权）；
     * ③ 产物 multipart 上传 POST /api/media，上传失败在回执注明（文件保留）。
     */
    private suspend fun executeProjectionCapture(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
        mode: String,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        val isRecord = mode == MediaCaptureService.MODE_RECORD
        val isLive = mode == MediaCaptureService.MODE_SCREEN_LIVE
        // 录屏 3~60 秒；投屏直播 0=不限时（推荐，管理后台自动传 0）、10~3600 秒；
        // 不限时直到 stream_stop 手动停，24h 兜底防僵尸
        val durationSec =
            if (isRecord) command.intParam("duration", 10, 3, 60)
            else if (isLive) {
                val raw = command.params.get("duration")
                    ?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                if (raw <= 0) 0 else raw.coerceIn(10, 3600)
            } else 0
        val uploadArchive = command.boolParam("upload")
        // 投屏画质档位（v0.1.56）：smooth=流畅 720P/1.5Mbps/15fps，hd=高清（默认）
        val screenQuality = command.strParam("quality", "hd")
        val actionLabel = when {
            isRecord -> "录屏"
            isLive -> "投屏直播"
            else -> "截屏"
        }

        // 静默截屏优先（借鉴 autox，v0.1.40）：无障碍 takeScreenshot——无投屏授权框、
        // 无前台服务通知、无屏幕拉起，全程无感；录屏无法用无障碍，仍走 MediaProjection。
        // 静默路径失败才降级投屏路线（会弹系统授权框，属兜底而非常态）。
        if (mode == MediaCaptureService.MODE_SCREENSHOT && BridgeAccessibilityService.isReady()) {
            try {
                val path = BridgeAccessibilityService.takeScreenshotToFile(context)
                Timber.i("%s：无障碍静默截屏完成（%s）", name, path)
                return uploadCaptureFile(deviceStore, apiClient, id, name, kindOf(mode), File(path))
            } catch (e: Exception) {
                Timber.w(e, "%s：静默截屏失败，降级投屏路线（将弹系统授权框）", name)
            }
        }

        // 会话保持中：跳过系统授权确认（首次授权后 MediaProjection 由服务持有）
        val reuse = MediaCaptureService.projectionHeld
        var resultCode = 0
        var consentData: Intent? = null
        if (!reuse) {
            // ① 投屏授权：注册等待 → 弹透明授权页 → 阻塞等设备端确认
            val consentOutcome = ScreenCaptureHolder.Outcome<Pair<Int, Intent?>>()
            ScreenCaptureHolder.consent[command.commandId] = consentOutcome
            val launched = try {
                context.startActivity(ProjectionConsentActivity.intentOf(context, command.commandId))
                true
            } catch (e: Exception) {
                Timber.w(e, "%s：弹出投屏授权框失败（后台启动被拦截）", name)
                false
            }
            if (!launched) {
                ScreenCaptureHolder.consent.remove(command.commandId)
                return CommandResult(
                    id, name, false,
                    "无法在设备上弹出投屏授权框（后台启动被系统拦截），请打开 App 后重试下发",
                )
            }
            Timber.i("%s：等待设备端投屏授权（最长 %d 秒）…", name, CONSENT_TIMEOUT_MS / 1000)
            val consentReady = consentOutcome.latch.await(CONSENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val consent = consentOutcome.value
            if (!consentReady || consent == null || consent.second == null) {
                ScreenCaptureHolder.consent.remove(command.commandId)
                return CommandResult(
                    id, name, false,
                    "设备端未确认投屏授权（${CONSENT_TIMEOUT_MS / 1000} 秒内未点「立即开始」或被拒绝）",
                )
            }
            resultCode = consent.first
            consentData = consent.second
        }

        // ② 采集：注册结果等待 → 启动采集服务（mediaProjection 类型前台；
        //    复用模式不带授权 extras，服务直接用已持有的会话）
        val captureOutcome = ScreenCaptureHolder.Outcome<ScreenCaptureHolder.CaptureFile>()
        ScreenCaptureHolder.capture[command.commandId] = captureOutcome
        val serviceIntent = Intent(context, MediaCaptureService::class.java).apply {
            putExtra(MediaCaptureService.EXTRA_COMMAND_ID, command.commandId)
            putExtra(MediaCaptureService.EXTRA_MODE, mode)
            if (isRecord || isLive) {
                putExtra(MediaCaptureService.EXTRA_DURATION_SEC, durationSec)
                putExtra(MediaCaptureService.EXTRA_UPLOAD, uploadArchive)
            }
            if (isLive) putExtra(MediaCaptureService.EXTRA_QUALITY, screenQuality)
            if (!reuse) {
                putExtra(MediaCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(
                    MediaCaptureService.EXTRA_CONSENT_DATA,
                    Bundle().apply { putParcelable("data", consentData) },
                )
            }
        }
        val started = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (e: Exception) {
            Timber.e(e, "%s：采集服务启动失败", name)
            false
        }
        if (!started) {
            ScreenCaptureHolder.capture.remove(command.commandId)
            return CommandResult(id, name, false, "$actionLabel 采集服务启动失败")
        }
        // 不限时（duration=0）：等待上限 24h 兜底，真实结束靠 stream_stop
        val waitMs = when {
            isLive && durationSec == 0 -> 86_400_000L + CAPTURE_TIMEOUT_MS
            isRecord || isLive -> durationSec * 1000L + CAPTURE_TIMEOUT_MS
            else -> CAPTURE_TIMEOUT_MS
        }
        val done = captureOutcome.latch.await(waitMs, TimeUnit.MILLISECONDS)
        val capture = captureOutcome.value
        if (!done || capture == null) {
            return CommandResult(id, name, false, "$actionLabel 采集超时")
        }
        if (!capture.success) {
            return CommandResult(id, name, false, capture.error ?: "$actionLabel 失败")
        }
        // 流式「回执上传」未开启：删除本地存档，直接成功回执（只实时观看过，不留档）
        if (capture.skipUpload) {
            runCatching { File(capture.path).delete() }
            return CommandResult(id, name, true, "$actionLabel 已停止（回执上传未开启，存档未保留）")
        }

        // ③ 上传
        return uploadCaptureFile(deviceStore, apiClient, id, name, kindOf(mode), File(capture.path))
    }

    /**
     * 执行 take_photo：Camera1 静默拍照（无需投屏授权，需相机运行时权限）。
     *
     * @param params.camera back（后置，默认）/ front（前置）
     *
     * 后台访问相机要求 CAMERA 类型前台服务（Android 11+ while-in-use 规则），
     * 静音策略：enableShutterSound(false) + 临时静音系统流，见 [MediaCaptureService]。
     */
    private suspend fun executeTakePhoto(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CommandResult(
                id, name, false,
                "缺少相机权限，请打开 App 在系统设置中授予相机权限后重试",
            )
        }
        val facing = command.strParam("camera", "back")
        if (facing !in setOf("back", "front")) {
            return CommandResult(id, name, false, "camera 参数只支持 back/front，收到：$facing")
        }

        val captureOutcome = ScreenCaptureHolder.Outcome<ScreenCaptureHolder.CaptureFile>()
        ScreenCaptureHolder.capture[command.commandId] = captureOutcome
        val serviceIntent = Intent(context, MediaCaptureService::class.java).apply {
            putExtra(MediaCaptureService.EXTRA_COMMAND_ID, command.commandId)
            putExtra(MediaCaptureService.EXTRA_MODE, MediaCaptureService.MODE_PHOTO)
            putExtra(MediaCaptureService.EXTRA_CAMERA_FACING, facing)
        }
        val started = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (e: Exception) {
            Timber.e(e, "take_photo：采集服务启动失败")
            false
        }
        if (!started) {
            ScreenCaptureHolder.capture.remove(command.commandId)
            return CommandResult(id, name, false, "相机服务启动失败")
        }
        val done = captureOutcome.latch.await(30_000, TimeUnit.MILLISECONDS)
        val capture = captureOutcome.value
        if (!done || capture == null) {
            return CommandResult(id, name, false, "拍照超时（相机无响应）")
        }
        if (!capture.success) {
            return CommandResult(id, name, false, capture.error ?: "拍照失败")
        }
        return uploadCaptureFile(deviceStore, apiClient, id, name, "photo", File(capture.path))
    }

    /**
     * 执行 record_video：Camera1 + MediaRecorder 摄像头录像（借鉴 autox）。
     *
     * @param params.camera back（后置，默认）/ front（前置）
     * @param params.quality low / medium（默认，720p）/ high（1080p）
     * @param params.duration 录制秒数 3~60（默认 10）
     * @param params.enable_audio 是否录制麦克风伴音（默认 true）
     *
     * 同 take_photo 走 CAMERA 类型前台服务（Android 11+ while-in-use 规则）；
     * 启动顺序坑（unlock 前必须起虚拟预览）见 [MediaCaptureService.captureVideo]。
     * 摄像头独占：录像/拍照互斥由采集服务单工作线程天然串行，无需额外锁。
     */
    private suspend fun executeRecordVideo(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CommandResult(
                id, name, false,
                "缺少相机权限，请打开 App 在系统设置中授予相机权限后重试",
            )
        }
        val enableAudio = command.params.get("enable_audio")
            ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        if (enableAudio &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CommandResult(
                id, name, false,
                "enable_audio=true 但缺少麦克风权限，请打开 App 授权麦克风，或下发时关闭「录制声音」",
            )
        }
        val facing = command.strParam("camera", "back")
        if (facing !in setOf("back", "front")) {
            return CommandResult(id, name, false, "camera 参数只支持 back/front，收到：$facing")
        }
        val quality = command.strParam("quality", "medium")
        if (quality !in setOf("low", "medium", "high")) {
            return CommandResult(id, name, false, "quality 参数只支持 low/medium/high，收到：$quality")
        }
        val stream = command.boolParam("stream")
        // 流式录像需要 MPEG_2_TS 容器（API 26+，Android 8.0）
        if (stream && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return CommandResult(
                id, name, false,
                "流式录像需要 Android 8.0+（MPEG-2 TS 容器），当前系统 ${Build.VERSION.SDK_INT}，请关闭流式模式",
            )
        }
        // 流式模式：duration 0=不限时（管理后台自动传 0），直到 stream_stop；非流式按原范围
        val uploadArchive = command.boolParam("upload")
        val durationSec =
            if (stream) {
                val raw = command.params.get("duration")
                    ?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                if (raw <= 0) 0 else raw.coerceIn(10, 600)
            } else command.intParam("duration", 10, 3, 60)

        val captureOutcome = ScreenCaptureHolder.Outcome<ScreenCaptureHolder.CaptureFile>()
        ScreenCaptureHolder.capture[command.commandId] = captureOutcome
        val serviceIntent = Intent(context, MediaCaptureService::class.java).apply {
            putExtra(MediaCaptureService.EXTRA_COMMAND_ID, command.commandId)
            putExtra(MediaCaptureService.EXTRA_MODE, MediaCaptureService.MODE_VIDEO)
            putExtra(MediaCaptureService.EXTRA_CAMERA_FACING, facing)
            putExtra(MediaCaptureService.EXTRA_QUALITY, quality)
            putExtra(MediaCaptureService.EXTRA_DURATION_SEC, durationSec)
            putExtra(MediaCaptureService.EXTRA_ENABLE_AUDIO, enableAudio)
            putExtra(MediaCaptureService.EXTRA_STREAM, stream)
            putExtra(MediaCaptureService.EXTRA_UPLOAD, uploadArchive)
        }
        val started = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (e: Exception) {
            Timber.e(e, "record_video：采集服务启动失败")
            false
        }
        if (!started) {
            ScreenCaptureHolder.capture.remove(command.commandId)
            return CommandResult(id, name, false, "录像服务启动失败")
        }
        // 录制时长 + 授权/启动/编码缓冲；不限时（duration=0）等待上限 24h 兜底
        val awaitMs = if (durationSec == 0) 86_400_000L + 30_000 else durationSec * 1000L + 30_000
        val done = captureOutcome.latch.await(awaitMs, TimeUnit.MILLISECONDS)
        val capture = captureOutcome.value
        if (!done || capture == null) {
            return CommandResult(id, name, false, "录像超时（相机无响应）")
        }
        if (!capture.success) {
            return CommandResult(id, name, false, capture.error ?: "录像失败")
        }
        // 流式「回执上传」未开启：删除本地存档，直接成功回执
        if (capture.skipUpload) {
            runCatching { File(capture.path).delete() }
            return CommandResult(id, name, true, "录像已停止（回执上传未开启，存档未保留）")
        }
        return uploadCaptureFile(deviceStore, apiClient, id, name, "video", File(capture.path))
    }

    /**
     * 执行 record_audio：MediaRecorder 麦克风录音（M4A/AAC）。
     *
     * @param params.quality low（16kHz 语音档）/ medium（默认，44.1kHz）/ high（48kHz 立体声）
     * @param params.duration 录制秒数 5~300（默认 30）
     *
     * Android 11+ 后台访问麦克风要求 MICROPHONE 类型前台服务（Manifest 已声明）。
     */
    private suspend fun executeRecordAudio(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CommandResult(
                id, name, false,
                "缺少麦克风权限，请打开 App 在系统设置中授予麦克风权限后重试",
            )
        }
        val quality = command.strParam("quality", "medium")
        if (quality !in setOf("low", "medium", "high")) {
            return CommandResult(id, name, false, "quality 参数只支持 low/medium/high，收到：$quality")
        }
        val stream = command.boolParam("stream")
        // 流式模式：duration 0=不限时（管理后台自动传 0），直到 stream_stop；非流式按原范围
        val uploadArchive = command.boolParam("upload")
        val durationSec =
            if (stream) {
                val raw = command.params.get("duration")
                    ?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                if (raw <= 0) 0 else raw.coerceIn(10, 600)
            } else command.intParam("duration", 30, 5, 300)

        val captureOutcome = ScreenCaptureHolder.Outcome<ScreenCaptureHolder.CaptureFile>()
        ScreenCaptureHolder.capture[command.commandId] = captureOutcome
        val serviceIntent = Intent(context, MediaCaptureService::class.java).apply {
            putExtra(MediaCaptureService.EXTRA_COMMAND_ID, command.commandId)
            putExtra(MediaCaptureService.EXTRA_MODE, MediaCaptureService.MODE_AUDIO)
            putExtra(MediaCaptureService.EXTRA_QUALITY, quality)
            putExtra(MediaCaptureService.EXTRA_DURATION_SEC, durationSec)
            putExtra(MediaCaptureService.EXTRA_STREAM, stream)
            putExtra(MediaCaptureService.EXTRA_UPLOAD, uploadArchive)
        }
        val started = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (e: Exception) {
            Timber.e(e, "record_audio：采集服务启动失败")
            false
        }
        if (!started) {
            ScreenCaptureHolder.capture.remove(command.commandId)
            return CommandResult(id, name, false, "录音服务启动失败")
        }
        val awaitMs = if (durationSec == 0) 86_400_000L + 20_000 else durationSec * 1000L + 20_000
        val done = captureOutcome.latch.await(awaitMs, TimeUnit.MILLISECONDS)
        val capture = captureOutcome.value
        if (!done || capture == null) {
            return CommandResult(id, name, false, "录音超时（麦克风无响应）")
        }
        if (!capture.success) {
            return CommandResult(id, name, false, capture.error ?: "录音失败")
        }
        // 流式「回执上传」未开启：删除本地存档，直接成功回执
        if (capture.skipUpload) {
            runCatching { File(capture.path).delete() }
            return CommandResult(id, name, true, "录音已停止（回执上传未开启，存档未保留）")
        }
        return uploadCaptureFile(deviceStore, apiClient, id, name, "audio", File(capture.path))
    }

    /** 执行 clear_media：清空客户端本地 media/ 目录残留（采集进行中拒绝执行）。 */
    private fun executeClearMedia(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        if (MediaCaptureService.captureBusy) {
            return CommandResult(id, name, false, "正在采集媒体（截屏/录像/录音等），请等采集结束后再清理")
        }
        return try {
            val (count, bytes) = MediaCaptureService.clearMediaFiles(context)
            val sizeText = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0)
            else "%.0f KB".format(bytes / 1024.0)
            CommandResult(id, name, true, "已清理 $count 个本地媒体文件（共 $sizeText）；服务器侧媒体可在「媒体文件」页单独删除")
        } catch (e: Exception) {
            Timber.e(e, "clear_media 执行失败")
            CommandResult(id, name, false, "清理失败：${e.message}")
        }
    }

    /** 采集模式 → 服务端 kind 字段。 */
    private fun kindOf(mode: String): String = when (mode) {
        MediaCaptureService.MODE_RECORD -> "screen_record"
        MediaCaptureService.MODE_SCREEN_LIVE -> "screen_live"
        MediaCaptureService.MODE_PHOTO -> "photo"
        MediaCaptureService.MODE_VIDEO -> "video"
        MediaCaptureService.MODE_AUDIO -> "audio"
        else -> "screenshot"
    }

    // ------------------------------------------------------------------
    // 交互类命令（show_toast / speak / vibrate / show_dialog）
    // ------------------------------------------------------------------

    /** 执行 show_toast：设备上显示一条 Toast（见 [ToastHelper]）。 */
    private fun executeShowToast(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val text = command.params.get("text")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (text.isEmpty()) return CommandResult(id, name, false, "缺少提示内容参数 text")
        if (text.length > 500) return CommandResult(id, name, false, "提示内容过长（${text.length} 字，上限 500）")
        return try {
            val message = ToastHelper.show(context, text)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 show_toast 执行失败")
            CommandResult(id, name, false, "显示失败：${e.message}")
        }
    }

    /**
     * 执行 speak：TTS 朗读（见 [TtsSpeaker]）。
     * 播完（或失败）才回执；依赖设备装有 TTS 引擎。
     */
    private fun executeSpeak(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val text = command.params.get("text")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (text.isEmpty()) return CommandResult(id, name, false, "缺少播报内容参数 text")
        if (text.length > 1000) return CommandResult(id, name, false, "播报内容过长（${text.length} 字，上限 1000）")
        return try {
            val message = TtsSpeaker.speak(context, text, timeoutMs = 120_000)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 speak 执行失败")
            CommandResult(id, name, false, e.message ?: "语音播报失败")
        }
    }

    /** 执行 vibrate：单次震动（见 [DeviceVibrator]）。 */
    private fun executeVibrate(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val duration = command.intParam("duration", 500, 50, 10_000)
        return try {
            val message = DeviceVibrator.vibrate(context, duration.toLong())
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 vibrate 执行失败")
            CommandResult(id, name, false, e.message ?: "震动失败")
        }
    }

    /**
     * 执行 show_dialog：全局悬浮窗对话框（见 [OverlayDialog]）。
     *
     * 需要「显示在其他应用上层」特殊权限：未授权时自动弹系统设置页引导，
     * 开一次永久有效。回执「已弹出」后即完成；用户点「知道了」会触发
     * 二次上报（WS event=dialog_click），管理后台回执区追加显示点击结果。
     */
    private fun executeShowDialog(
        context: Context,
        webSocketClient: () -> BridgeWebSocketClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!OverlayDialog.hasPermission(context)) {
            val launched = OverlayDialog.launchOverlaySettings(context)
            val guide = if (launched) {
                "已在设备上打开设置页，请开启「显示在其他应用上层」后重新下发本命令"
            } else {
                "请在设备上：设置 → 应用 → Bridge → 显示在其他应用上层，开启后重新下发本命令"
            }
            return CommandResult(id, name, false, "未授予悬浮窗权限。$guide")
        }
        val title = command.params.get("title")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val content = command.params.get("content")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (content.isEmpty()) return CommandResult(id, name, false, "缺少弹窗内容参数 content")

        return try {
            val error = OverlayDialog.show(context, title, content) {
                // 用户点击「知道了」→ 二次上报（点击结果追加到后台命令回执区）
                val data = com.google.gson.JsonObject().apply {
                    addProperty("event", "dialog_click")
                    addProperty("command_id", command.commandId)
                    addProperty("command", command.command)
                }
                val sent = try {
                    webSocketClient().sendBusinessMessage(data)
                } catch (e: Exception) {
                    Timber.w(e, "弹窗点击上报失败（连接未就绪）")
                    false
                }
                Timber.i("show_dialog 用户点击确认（二次上报 sent=%s）", sent)
            }
            if (error != null) {
                CommandResult(id, name, false, error)
            } else {
                CommandResult(id, name, true, "已弹出对话框，等待用户点击「知道了」（点击后回传结果）")
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 show_dialog 执行失败")
            CommandResult(id, name, false, e.message ?: "弹窗失败")
        }
    }

    /**
     * 执行 send_notification：发送系统通知（桌面图标显示角标）。
     *
     * 角标机制 = 通知驱动：Android 无公开角标 API，通知进通知栏后各 ROM 的
     * launcher 自动把 [Notification.number]（或红点）画到桌面图标上；
     * 「清除通知」模式撤回远程通知即归零角标。保活常驻通知不计入角标。
     * 通知权限未开启时自动打开系统通知设置页引导（同弹窗/锁屏的引导模式）。
     */
    private fun executeSendNotification(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val mode = command.strParam("mode", "send")

        // 清除模式：撤回远程通知 + 角标归零
        if (mode == "clear") {
            NotificationPoster.clear(context)
            return CommandResult(id, name, true, "已清除通知，桌面角标已归零")
        }

        // 通知总开关检查：未开启则引导（开一次永久有效）
        if (!NotificationPoster.areEnabled(context)) {
            val launched = NotificationPoster.launchNotificationSettings(context)
            val guide = if (launched) {
                "已在设备上打开通知设置页，请开启 Bridge 的通知权限后重新下发本命令"
            } else {
                "请在设备上：设置 → 应用 → Bridge → 通知，开启后重新下发本命令"
            }
            return CommandResult(id, name, false, "设备通知权限未开启。$guide")
        }

        val title = command.strParam("title", "服务器消息")
        val content = command.params.get("content")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (content.isEmpty()) return CommandResult(id, name, false, "缺少通知内容参数 content")
        val sound = command.params.get("sound")
            ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val badge = command.intParam("badge", 1, 1, 99)

        return try {
            val message = NotificationPoster.post(context, title, content, sound, badge)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 send_notification 执行失败")
            CommandResult(id, name, false, e.message ?: "通知发送失败")
        }
    }

    /**
     * 执行 check_update：检查并自动更新（服务器目录版，不依赖 GitHub）。
     *
     * 完整闭环（v0.1.37 起）：检查 → 自动下载 APK（进度通知）→ 拉起系统安装页
     * （Android 不允许静默安装，用户点一次「安装」）。防重复触发：下载/安装中
     * 再次下发直接拒绝并提示，见 [my.bridge.com.update.UpdateManager]。
     * mode=check_only 时只检查不下载，回执告知是否有新版本（v0.1.44）。
     * force_download=true 时忽略本地缓存强制重下（v0.1.45：本地缓存被污染自救）。
     * 关于页「检查更新」按钮走同一入口，行为一致（自动安装）。
     */
    private suspend fun executeCheckUpdate(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        val mode = command.strParam("mode", "auto_install")
        val autoInstall = mode != "check_only"
        val assistInstall = mode == "assist_install"
        val forceDownload = command.boolParam("force_download")
        if (forceDownload && !autoInstall) {
            return CommandResult(id, name, false, "「强制重新下载」仅在自动安装模式下有意义，请把检查模式改为自动安装")
        }
        return try {
            val message = my.bridge.com.update.UpdateManager.checkAndInstall(
                context, autoInstall, forceDownload, assistInstall,
            )
            Timber.i("命令 check_update（mode=%s force=%s）：%s", mode, forceDownload, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 check_update 执行失败")
            CommandResult(id, name, false, "检查更新失败：${e.message}")
        }
    }

    private const val RESTART_APP_REQUEST_CODE = 2001
    private const val RESTART_APP_KILL_DELAY_MS = 1200L

    /** 按音源形态分三路解析（见 [executePlayMusic] 注释），返回可直接交给 MediaPlayer 的地址。 */
    private suspend fun resolveSource(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        source: String,
    ): String = when {
        source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) -> source

        source.startsWith("/") -> {
            if (!File(source).isFile) {
                throw IllegalArgumentException("本地文件不存在：$source")
            }
            source
        }

        else -> resolveByFilename(context, deviceStore, apiClient, source)
    }

    /** 裸文件名：客户端缓存目录优先，未命中从服务器共享目录下载并持久化。 */
    private suspend fun resolveByFilename(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        filename: String,
    ): String {
        if (filename.contains('/') || filename.contains('\\') || filename.contains("..")) {
            throw IllegalArgumentException("仅支持单级文件名：$filename")
        }
        val cacheDir = musicCacheDir(context)
        val cached = File(cacheDir, filename)
        if (cached.isFile) {
            Timber.i("音乐缓存命中：${cached.absolutePath}")
            return cached.absolutePath
        }

        // 缓存未命中 → 请求服务器共享音乐目录
        val deviceId = deviceStore.deviceId()
        val token = deviceStore.token()
        Timber.i("音乐缓存未命中，向服务器请求：$filename")
        val body = try {
            apiClient.downloadMusic(filename, deviceId, token)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                throw IllegalArgumentException("服务器与本地缓存均无此文件：$filename")
            }
            throw e
        }

        // 先写临时文件再原子重命名，避免半截文件被当成有效缓存
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$filename.tmp")
        try {
            body.use { resp ->
                resp.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val target = File(cacheDir, filename)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("缓存写入失败：$filename")
            }
        } finally {
            tmp.delete()
        }
        Timber.i("音乐已下载并缓存：${File(cacheDir, filename).absolutePath}")
        return File(cacheDir, filename).absolutePath
    }

    /** 客户端音乐缓存目录：App 私有 music/（无外置存储时回退内部存储）。 */
    private fun musicCacheDir(context: Context): File =
        context.getExternalFilesDir("music") ?: File(context.filesDir, "music")

    // ------------------------------------------------------------------
    // 界面自动化命令（v0.2.39：无障碍控件读取/操作，对标 autox）
    //
    // 依赖 BridgeAccessibilityService（无障碍）开启，且 XML 已声明
    // canRetrieveWindowContent + canPerformGestures。
    // 耗电红线：全部「按命令拉取、零常驻轮询」，选择器查找仅在命令触发时遍历一次控件树。
    // ------------------------------------------------------------------

    /** 无障碍服务未开启时的统一回执。 */
    private fun uiNotReady(command: Command): CommandResult =
        CommandResult(
            command.commandId, command.command, false,
            "无障碍服务未开启：请在设备「设置 → 无障碍 → Bridge 辅助服务」开启后重试",
        )

    /**
     * 极简选择器 DSL 解析：把 autox 风格的链式表达式解析为 UiGlobalSelector。
     * 支持 text/textContains/textStartsWith/textEndsWith/textMatches、
     * id/idContains/desc/descContains/className/classNameContains/packageName、
     * clickable/enabled/scrollable/editable（布尔，可带 true/false）等。
     * 例：text("登录")、textContains("确定").clickable()、className("Button")
     */
    private fun parseSelector(expr: String): my.bridge.com.automation.automator.UiGlobalSelector {
        val s = my.bridge.com.automation.automator.UiGlobalSelector()
        // 按 .methodName(args) 逐段匹配；忽略空白与末尾多余字符
        val regex = Regex("""(\w+)\s*\(([^)]*)\)""")
        var applied = false
        for (m in regex.findAll(expr)) {
            val name = m.groupValues[1]
            val raw = m.groupValues[2].trim()
            val arg = raw.trim('"', '\'', ' ')
            fun boolArg(): Boolean = arg.isEmpty() || arg.equals("true", true)
            when (name) {
                "text" -> s.text(arg)
                "textContains" -> s.textContains(arg)
                "textStartsWith" -> s.textStartsWith(arg)
                "textEndsWith" -> s.textEndsWith(arg)
                "textMatches" -> s.textMatches(arg)
                "id" -> s.id(arg)
                "idContains" -> s.idContains(arg)
                "desc" -> s.desc(arg)
                "descContains" -> s.descContains(arg)
                "className" -> s.className(arg)
                "classNameContains" -> s.classNameContains(arg)
                "packageName" -> s.packageName(arg)
                "clickable" -> s.clickable(boolArg())
                "enabled" -> s.enabled(boolArg())
                "scrollable" -> s.scrollable(boolArg())
                "editable" -> s.editable(boolArg())
                "longClickable" -> s.longClickable(boolArg())
                "checkable" -> s.checkable(boolArg())
                "checked" -> s.checked(boolArg())
                "focusable" -> s.focusable(boolArg())
                "focused" -> s.focused(boolArg())
                "selected" -> s.selected(boolArg())
                "visibleToUser" -> s.visibleToUser(boolArg())
                "password" -> s.password(boolArg())
                "depth" -> s.depth(arg.toIntOrNull() ?: 0)
                else -> throw IllegalArgumentException("不支持的选择器方法：$name")
            }
            applied = true
        }
        if (!applied) throw IllegalArgumentException("选择器解析失败：$expr")
        return s
    }

    /** 取根节点；未就绪返回 null（调用方回执）。 */
    private fun uiRootOrNull(): my.bridge.com.automation.automator.UiObject? =
        BridgeAccessibilityService.currentRoot()

    /** 把 UiObject 转成 JSON 描述（供回执展示）。 */
    private fun nodeToJson(n: my.bridge.com.automation.automator.UiObject, depth: Int = -1): String {
        val b = n.bounds()
        val bp = n.boundsInParent()
        return buildString {
            append("{")
            // 基础标识
            append("\"text\":").append(jsonStr(n.text()))
            append(",\"desc\":").append(jsonStr(n.desc() ?: ""))
            append(",\"id\":").append(jsonStr(n.id() ?: ""))
            append(",\"className\":").append(jsonStr(n.className() ?: ""))
            append(",\"package\":").append(jsonStr(n.packageName() ?: ""))
            // 坐标（屏幕 / 父容器）
            append(",\"bounds\":[").append(b.left).append(',').append(b.top)
                .append(',').append(b.right).append(',').append(b.bottom).append("]")
            append(",\"boundsInParent\":[").append(bp.left).append(',').append(bp.top)
                .append(',').append(bp.right).append(',').append(bp.bottom).append("]")
            // 布尔状态
            append(",\"clickable\":").append(n.clickable())
            append(",\"longClickable\":").append(n.longClickable())
            append(",\"checkable\":").append(n.checkable())
            append(",\"checked\":").append(n.checked())
            append(",\"focusable\":").append(n.focusable())
            append(",\"focused\":").append(n.focused())
            append(",\"selected\":").append(n.selected())
            append(",\"enabled\":").append(n.enabled())
            append(",\"editable\":").append(n.editable())
            append(",\"scrollable\":").append(n.scrollable())
            append(",\"visibleToUser\":").append(n.visibleToUser())
            append(",\"accessibilityFocused\":").append(n.accessibilityFocused())
            append(",\"password\":").append(n.password())
            append(",\"contentInvalid\":").append(n.isContentInvalid)
            append(",\"screenReaderFocusable\":").append(n.isScreenReaderFocusable)
            append(",\"dismissable\":").append(n.isDismissable)
            append(",\"multiLine\":").append(n.isMultiLine)
            append(",\"importantForAccessibility\":").append(n.isImportantForAccessibility)
            // 层级
            append(",\"childCount\":").append(n.childCount())
            append(",\"depth\":").append(depth)
            append(",\"drawingOrder\":").append(n.drawingOrder())
            append(",\"windowId\":").append(n.windowId)
            // 集合信息（列表/网格）
            append(",\"row\":").append(n.row())
            append(",\"column\":").append(n.column())
            append(",\"rowSpan\":").append(n.rowSpan())
            append(",\"columnSpan\":").append(n.columnSpan())
            append(",\"rowCount\":").append(n.rowCount())
            append(",\"columnCount\":").append(n.columnCount())
            // 输入相关
            append(",\"maxTextLength\":").append(n.maxTextLength)
            append(",\"textSelectionStart\":").append(n.textSelectionStart)
            append(",\"textSelectionEnd\":").append(n.textSelectionEnd)
            append(",\"inputType\":").append(n.inputType)
            append(",\"error\":").append(jsonStr(n.error?.toString() ?: ""))
            append(",\"hintText\":").append(jsonStr(n.hintText?.toString() ?: ""))
            append(",\"tooltipText\":").append(jsonStr(n.tooltipText?.toString() ?: ""))
            append(",\"paneTitle\":").append(jsonStr(n.paneTitle?.toString() ?: ""))
            // 其它
            append(",\"liveRegion\":").append(n.liveRegion)
            append(",\"movementGranularities\":").append(n.movementGranularities)
            // 可执行动作列表（id + 名称，便于后台/脚本判断能否执行某动作）
            append(",\"actions\":[")
            var firstAction = true
            for (a in n.actionList) {
                if (!firstAction) append(',')
                append("{\"id\":").append(a.id).append(",\"label\":").append(jsonStr(a.label?.toString() ?: "")).append("}")
                firstAction = false
            }
            append("]")
            append("}")
        }
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /** ui_dump：导出控件树（DFS，限制节点数与深度）。
     *
     * - all_windows=false（默认）：只导出当前活动窗口（rootInActiveWindow）。
     * - all_windows=true：遍历所有可见窗口（含状态栏/导航栏/输入法等系统窗口），
     *   每个节点带 windowId + windowType，供调试台显示信号/电池/时间等状态栏控件。
     */
    private fun executeUiDump(command: Command): CommandResult {
        // all_windows 缺省 true（调试台要看到状态栏控件）；传 0 才只导出活动窗口
        val allWindows = command.intParam("all_windows", 1, 0, 1) == 1
        val maxNodes = command.intParam("max_nodes", 500, 1, 3000)
        val maxDepth = command.intParam("max_depth", 24, 1, 60)
        return try {
            val sb = StringBuilder("[")
            var count = 0
            fun walk(n: my.bridge.com.automation.automator.UiObject, depth: Int, windowType: Int) {
                if (count >= maxNodes || depth > maxDepth) return
                if (count > 0) sb.append(',')
                sb.append(nodeToJson(n, depth))
                // 在 depth 之后追加窗口类型（nodeToJson 已输出 windowId，这里补 type）
                sb.setLength(sb.length - 1) // 去掉结尾的 }
                sb.append(",\"windowType\":").append(windowType).append("}")
                count++
                for (i in 0 until n.childCount()) {
                    if (count >= maxNodes) break
                    val c = n.child(i) ?: continue
                    walk(c, depth + 1, windowType)
                }
            }
            if (allWindows) {
                val roots = BridgeAccessibilityService.allWindowRootsWithType()
                for ((type, root) in roots) {
                    walk(root, 0, type)
                    root.recycle()
                }
                if (count == 0) {
                    // all_windows 取不到窗口时回退单窗口
                    val root = uiRootOrNull()
                    if (root != null) { walk(root, 0, 0); root.recycle() }
                }
            } else {
                val root = uiRootOrNull() ?: return uiNotReady(command)
                walk(root, 0, 0)
                root.recycle()
            }
            sb.append("]")
            CommandResult(command.commandId, command.command, true, sb.toString())
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_dump 执行失败")
            CommandResult(command.commandId, command.command, false, "导出界面结构失败：${e.message}")
        }
    }

    /** ui_find：按选择器查找控件，返回匹配节点 JSON 列表。支持 timeout 等待出现。 */
    private fun executeUiFind(command: Command): CommandResult {
        val max = command.intParam("max_results", 20, 1, 200)
        val timeoutMs = command.intParam("timeout_ms", 0, 0, 60000)
        val selectorText = command.strParam("selector", "")
        return try {
            val selector = parseSelector(selectorText)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var result: List<my.bridge.com.automation.automator.UiObject> = emptyList()
            // 等待出现：timeout>0 时轮询查找（间隔 300ms，零常驻，仅命令期间轮询）
            while (true) {
                val root = uiRootOrNull()
                if (root != null) {
                    result = selector.findAndReturnList(root, max)
                    root.recycle()
                    if (result.isNotEmpty()) break
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
                Thread.sleep(300)
            }
            val sb = StringBuilder("[")
            result.forEachIndexed { i, n ->
                if (i > 0) sb.append(',')
                sb.append(nodeToJson(n))
            }
            sb.append("]")
            if (result.isEmpty()) {
                CommandResult(command.commandId, command.command, false, "未找到匹配控件（selector=$selectorText）")
            } else {
                CommandResult(
                    command.commandId, command.command, true,
                    "匹配 ${result.size} 个控件：$sb",
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_find 执行失败")
            CommandResult(command.commandId, command.command, false, "查找失败：${e.message}")
        }
    }

    /** ui_click：优先按选择器点击，否则按坐标。带三级降级（节点→祖先→坐标手势）。 */
    private fun executeUiClick(command: Command): CommandResult {
        val selectorText = command.strParam("selector", "")
        val index = command.intParam("index", 0, 0, 200)
        return try {
            if (selectorText.isNotEmpty()) {
                val root = uiRootOrNull() ?: return uiNotReady(command)
                val selector = parseSelector(selectorText)
                val list = selector.findAndReturnList(root, index + 1)
                val target = list.getOrNull(index)
                root.recycle()
                if (target == null) {
                    CommandResult(command.commandId, command.command, false, "未找到匹配控件（index=$index）")
                } else {
                    // 三级降级：节点 ACTION_CLICK → 最近可点击祖先 → bounds 中心坐标手势
                    val (ok, strategy) = target.clickWithFallback()
                    if (ok) {
                        CommandResult(command.commandId, command.command, true, "已点击控件（$strategy）：${nodeToJson(target)}")
                    } else {
                        val b = target.bounds()
                        val cx = b.centerX()
                        val cy = b.centerY()
                        if (BridgeAccessibilityService.click(cx, cy)) {
                            CommandResult(command.commandId, command.command, true, "已点击控件（坐标手势 ($cx,$cy)）：${nodeToJson(target)}")
                        } else {
                            CommandResult(command.commandId, command.command, false, "点击失败：控件及其祖先均不可点击，坐标手势也未成功")
                        }
                    }
                }
            } else {
                val x = command.params.get("x")?.takeIf { it.isJsonPrimitive }?.asInt
                val y = command.params.get("y")?.takeIf { it.isJsonPrimitive }?.asInt
                if (x == null || y == null) {
                    CommandResult(command.commandId, command.command, false, "请提供 selector 或 x/y 坐标")
                } else if (BridgeAccessibilityService.click(x, y)) {
                    CommandResult(command.commandId, command.command, true, "已点击坐标 ($x,$y)")
                } else {
                    CommandResult(command.commandId, command.command, false, "坐标点击失败（无障碍未就绪或手势被取消）")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_click 执行失败")
            CommandResult(command.commandId, command.command, false, "点击失败：${e.message}")
        }
    }

    /** ui_set_text：向可编辑控件写入文本。带 IME 兜底（ACTION_SET_TEXT → IME commitText）。 */
    private fun executeUiSetText(context: Context, command: Command): CommandResult {
        val root = uiRootOrNull() ?: return uiNotReady(command)
        val index = command.intParam("index", 0, 0, 200)
        return try {
            val selector = parseSelector(command.strParam("selector", ""))
            val text = command.strParam("text", "")
            if (text.isEmpty()) {
                root.recycle()
                return CommandResult(command.commandId, command.command, false, "文本内容不能为空")
            }
            val list = selector.findAndReturnList(root, index + 1)
            val target = list.getOrNull(index)
            root.recycle()
            if (target == null) {
                CommandResult(command.commandId, command.command, false, "未找到匹配输入框（index=$index）")
            } else {
                // 第一级：直接 ACTION_SET_TEXT（要求控件 editable）
                if (target.editable() && target.setText(text)) {
                    CommandResult(command.commandId, command.command, true, "已输入文本（ACTION_SET_TEXT）：${nodeToJson(target)}")
                } else {
                    // 第二级：IME 兜底（先点击聚焦，再切 BridgeKeyboard commitText）
                    val b = target.bounds()
                    val cx = b.centerX()
                    val cy = b.centerY()
                    BridgeAccessibilityService.click(cx, cy) // 聚焦目标输入框
                    Thread.sleep(150)
                    if (BridgeAccessibilityService.imeCommitText(context, text)) {
                        CommandResult(command.commandId, command.command, true, "已输入文本（IME 兜底）：${nodeToJson(target)}")
                    } else {
                        CommandResult(
                            command.commandId, command.command, false,
                            "输入失败：ACTION_SET_TEXT 无效且 IME 兜底未成功（请在「设置→语言与输入法」启用 Bridge 输入辅助）",
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_set_text 执行失败")
            runCatching { root.recycle() }
            CommandResult(command.commandId, command.command, false, "输入失败：${e.message}")
        }
    }

    /** ui_gesture：坐标手势（滑动/点击/长按）。 */
    private fun executeUiGesture(command: Command): CommandResult {
        val action = command.strParam("action", "swipe")
        val duration = command.intParam("duration_ms", 300, 1, 60000).toLong()
        return try {
            val x1 = command.params.get("x1")?.takeIf { it.isJsonPrimitive }?.asInt
            val y1 = command.params.get("y1")?.takeIf { it.isJsonPrimitive }?.asInt
            if (x1 == null || y1 == null) {
                return CommandResult(command.commandId, command.command, false, "缺少起点坐标 x1/y1")
            }
            val ok = when (action) {
                "click" -> BridgeAccessibilityService.click(x1, y1)
                "long_click" -> BridgeAccessibilityService.longClick(x1, y1, duration)
                else -> {
                    val x2 = command.params.get("x2")?.takeIf { it.isJsonPrimitive }?.asInt
                    val y2 = command.params.get("y2")?.takeIf { it.isJsonPrimitive }?.asInt
                    if (x2 == null || y2 == null) {
                        return CommandResult(command.commandId, command.command, false, "滑动手势缺少终点坐标 x2/y2")
                    }
                    BridgeAccessibilityService.swipe(x1, y1, x2, y2, duration)
                }
            }
            CommandResult(
                command.commandId, command.command, ok,
                if (ok) "手势执行成功（$action）" else "手势执行失败（无障碍未就绪或手势被取消）",
            )
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_gesture 执行失败")
            CommandResult(command.commandId, command.command, false, "手势失败：${e.message}")
        }
    }

    /** ui_back：模拟返回键。 */
    private fun executeUiBack(command: Command): CommandResult =
        if (BridgeAccessibilityService.back()) {
            CommandResult(command.commandId, command.command, true, "已触发返回键")
        } else {
            uiNotReady(command)
        }

    /** ui_home：模拟主页键回桌面。 */
    private fun executeUiHome(command: Command): CommandResult =
        if (BridgeAccessibilityService.home()) {
            CommandResult(command.commandId, command.command, true, "已触发主页键")
        } else {
            uiNotReady(command)
        }

    // ------------------------------------------------------------------
    // Python RPC（v0.2.39：手机内嵌 HTTP/REST 服务，供 Python 脚本驱动）
    // 状态联动 + 幂等守卫：rpc_start 重复下发时若已在运行则回执「已在运行」；
    // rpc_stop 未运行时直接收状态。token 鉴权是安全红线。
    // ------------------------------------------------------------------

    /** rpc_start：拉起手机内嵌 HTTP/REST 服务（监听 0.0.0.0，默认端口 15161），生成 token。 */
    private fun executeRpcStart(command: Command): CommandResult {
        val port = command.intParam("port", 15161, 0, 65535)
        return try {
            if (my.bridge.com.automation.rpc.RpcServerController.isRunning()) {
                val s = my.bridge.com.automation.rpc.RpcServerController.state
                CommandResult(
                    command.commandId, command.command, true,
                    "RPC 服务已在运行 · 端口 ${s.port} · token ${s.token}（重复下发被忽略）",
                )
            } else if (my.bridge.com.automation.rpc.RpcServerController.start(port)) {
                val s = my.bridge.com.automation.rpc.RpcServerController.state
                CommandResult(
                    command.commandId, command.command, true,
                    "RPC 服务已启动 · 端口 ${s.port} · token ${s.token}（可经 127.0.0.1 / 局域网 / VNT 虚拟网连入）",
                )
            } else {
                CommandResult(command.commandId, command.command, false, "RPC 服务启动失败")
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 rpc_start 执行失败")
            CommandResult(command.commandId, command.command, false, "RPC 启动失败：${e.message}")
        }
    }

    /** rpc_stop：停止 RPC 服务，释放端口与线程池。 */
    private fun executeRpcStop(command: Command): CommandResult =
        try {
            if (!my.bridge.com.automation.rpc.RpcServerController.isRunning()) {
                CommandResult(command.commandId, command.command, true, "RPC 服务未运行（无需停止）")
            } else {
                my.bridge.com.automation.rpc.RpcServerController.stop()
                CommandResult(command.commandId, command.command, true, "RPC 服务已停止")
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 rpc_stop 执行失败")
            CommandResult(command.commandId, command.command, false, "RPC 停止失败：${e.message}")
        }

    /** rpc_status：只读本地快照，不触发网络操作。 */
    private fun executeRpcStatus(command: Command): CommandResult =
        CommandResult(command.commandId, command.command, true, my.bridge.com.automation.rpc.RpcServerController.statusText())

    // ------------------------------------------------------------------
    // 极空 BMS 保护板（v0.2.55：BLE 连接 + 状态上报 + 远程控制）
    // 管理后台「命令下发」页可下发 jikong_* 命令，客户端经 BLE 转成帧发给保护板。
    // ------------------------------------------------------------------

    /** jikong_scan：扫描周边极空 BLE 设备。 */
    private suspend fun executeJikongScan(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val devices = my.bridge.com.jikong.JikongBleClient.scan(context, durationMs = 8000)
            if (devices.isEmpty()) {
                CommandResult(id, name, true, "未扫描到极空设备（请确认已开蓝牙并靠近设备）")
            } else {
                val sb = StringBuilder("扫描到 ${devices.size} 个设备：\n")
                devices.forEach { d ->
                    sb.append("· ${d.name}  ${d.address}  信号 ${d.rssi} dBm\n")
                }
                CommandResult(id, name, true, sb.toString())
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_scan 执行失败")
            CommandResult(id, name, false, "扫描失败：${e.message}")
        }
    }

    /** jikong_connect：连接指定地址的极空保护板。 */
    private suspend fun executeJikongConnect(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val address = command.params.get("address")?.asString
            ?: return CommandResult(id, name, false, "缺少参数 address（极空设备 BLE 地址）")
        return try {
            val ok = my.bridge.com.jikong.JikongController.instance.connect(context, address)
            if (ok) {
                CommandResult(id, name, true, "已连接极空设备 $address，开始周期读取并上报状态")
            } else {
                CommandResult(id, name, false, "连接失败（请确认设备地址正确、已开蓝牙、设备在附近且未被他端占用）")
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_connect 执行失败")
            CommandResult(id, name, false, "连接失败：${e.message}")
        }
    }

    /** jikong_disconnect：断开极空保护板。 */
    private fun executeJikongDisconnect(command: Command): CommandResult {
        return try {
            my.bridge.com.jikong.JikongController.instance.disconnect()
            CommandResult(command.commandId, command.command, true, "已断开极空设备连接")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_disconnect 执行失败")
            CommandResult(command.commandId, command.command, false, "断开失败：${e.message}")
        }
    }

    /** jikong_status：只读本地状态快照。 */
    private fun executeJikongStatus(command: Command): CommandResult {
        val s = my.bridge.com.jikong.JikongController.instance.status
        val msg = if (!s.connected) {
            "极空设备未连接"
        } else {
            buildString {
                append("已连接 ").append(s.deviceName).append("（").append(s.deviceAddress).append("）\n")
                append("总压 ").append(fmt2(s.totalVoltage)).append("V｜电流 ").append(fmt2(s.current))
                    .append("A｜SOC ").append(s.soc).append("%\n")
                append("压差 ").append(fmt2(s.diffVoltage)).append("V｜温差 ").append(fmt2(s.tempDiff)).append("℃\n")
                append("充电MOS ").append(if (s.chargeMosOn) "开" else "关")
                    .append("｜放电MOS ").append(if (s.dischargeMosOn) "开" else "关")
                    .append("｜均衡 ").append(if (s.balanceOn) "开" else "关")
                if (s.cellVoltages.isNotEmpty()) {
                    append("\n单体：").append(s.cellVoltages.joinToString(" / ") { fmt2(it) }).append("V")
                }
                if (s.temperatures.isNotEmpty()) {
                    append("\n温度：").append(s.temperatures.joinToString(" / ") { fmt2(it) }).append("℃")
                }
            }
        }
        return CommandResult(command.commandId, command.command, true, msg)
    }

    /** jikong_mos：控制充放电 MOS。 */
    private suspend fun executeJikongMos(command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val charge = command.params.get("charge")?.asBoolean ?: true
        val discharge = command.params.get("discharge")?.asBoolean ?: true
        val on = command.params.get("on")?.asString != "off"
        return try {
            val ok = my.bridge.com.jikong.JikongController.instance.setMos(charge, discharge, on)
            CommandResult(id, name, ok, if (ok) "MOS 已${if (on) "开启" else "关闭"}（充=$charge 放=$discharge）" else "MOS 控制失败（未连接或写帧失败）")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_mos 执行失败")
            CommandResult(id, name, false, "MOS 控制失败：${e.message}")
        }
    }

    /** jikong_balance：控制主动均衡。 */
    private suspend fun executeJikongBalance(command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val on = command.params.get("on")?.asString != "off"
        return try {
            val ok = my.bridge.com.jikong.JikongController.instance.setBalance(on)
            CommandResult(id, name, ok, if (ok) "均衡已${if (on) "开启" else "关闭"}" else "均衡控制失败（未连接或写帧失败）")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_balance 执行失败")
            CommandResult(id, name, false, "均衡控制失败：${e.message}")
        }
    }

    /** jikong_write_param：写保护参数。 */
    private suspend fun executeJikongWriteParam(command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val paramId = command.params.get("param_id")?.asInt
            ?: return CommandResult(id, name, false, "缺少参数 param_id")
        val value = command.params.get("value")?.asInt
            ?: return CommandResult(id, name, false, "缺少参数 value")
        return try {
            val ok = my.bridge.com.jikong.JikongController.instance.writeParam(paramId, value)
            CommandResult(id, name, ok, if (ok) "参数 $paramId 已写入 $value" else "写参数失败（未连接或写帧失败）")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_write_param 执行失败")
            CommandResult(id, name, false, "写参数失败：${e.message}")
        }
    }

    private fun fmt2(v: Float): String = if (kotlin.math.abs(v) >= 100) "%.1f".format(v) else "%.2f".format(v)

    /** jikong_speak（v0.2.102）：远程触发仪表盘播报（与手机双击表盘同一组合逻辑，绕过静音）。 */
    private fun executeJikongSpeak(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val s = my.bridge.com.jikong.JikongController.instance.status
            if (s.soc !in 0..100) {
                CommandResult(id, name, false, "未读到有效电量（SOC=${s.soc}），无法播报")
            } else {
                my.bridge.com.jikong.JikongController.instance.speakDashboardNow(context)
                CommandResult(id, name, true, "已触发手机播报仪表盘数值（电量${s.soc}%）")
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_speak 执行失败")
            CommandResult(id, name, false, "播报失败：${e.message}")
        }
    }

    /** jikong_set_theme（v0.2.102）：后台切换深/浅色同步到手机（持久化 + 页面即时生效）。 */
    private fun executeJikongSetTheme(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val mode = command.params.get("mode")?.asInt
            ?: return CommandResult(id, name, false, "缺少参数 mode（0=自动 1=浅色 2=深色）")
        return try {
            my.bridge.com.jikong.JikongPrefs.setTheme(context, mode)
            my.bridge.com.ui.JkUiState.remoteThemeTick++   // 页面订阅 tick 即时重读生效
            my.bridge.com.jikong.JikongController.instance.forceReport()
            CommandResult(id, name, true, "主题已切换（mode=$mode），手机端即时生效")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_set_theme 执行失败")
            CommandResult(id, name, false, "切主题失败：${e.message}")
        }
    }

    /** jikong_set_speak（v0.2.102）：后台「语音播报」卡同步小喇叭/播报设置（字段可选，仅改传了的）。 */
    private fun executeJikongSetSpeak(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val p = command.params
        return try {
            val applied = mutableListOf<String>()
            p.get("muted")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asBoolean
                my.bridge.com.jikong.JikongPrefs.setMuted(context, v)
                applied.add(if (v) "已静音" else "已恢复播报")
            }
            p.get("speak_enabled")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asBoolean
                my.bridge.com.jikong.JikongPrefs.setSpeakEnabled(context, v)
                applied.add(if (v) "语音播报开" else "语音播报关")
            }
            p.get("speak_interval_min")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asInt.coerceIn(1, 120)
                my.bridge.com.jikong.JikongPrefs.setSpeakIntervalMin(context, v)
                applied.add("播报间隔 ${v} 分钟")
            }
            p.get("speak_low_soc")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asInt.coerceIn(1, 50)
                my.bridge.com.jikong.JikongPrefs.setSpeakLowSoc(context, v)
                applied.add("低电阈值 ${v}%")
            }
            p.get("speak_warn_repeat_min")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asInt.coerceIn(1, 60)
                my.bridge.com.jikong.JikongPrefs.setSpeakWarnRepeatMin(context, v)
                applied.add("提醒间隔 ${v} 分钟")
            }
            if (applied.isEmpty()) {
                CommandResult(id, name, false, "无有效参数（muted/speak_enabled/speak_interval_min/speak_low_soc/speak_warn_repeat_min）")
            } else {
                my.bridge.com.ui.JkUiState.remoteSpeakTick++
                my.bridge.com.jikong.JikongController.instance.forceReport()
                CommandResult(id, name, true, "播报设置已同步：" + applied.joinToString("；"))
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_set_speak 执行失败")
            CommandResult(id, name, false, "同步播报设置失败：${e.message}")
        }
    }

    /**
     * jikong_set_config（v0.2.104）：后台「设置页」镜像可编辑部分同步到手机——
     * 时速仪表盘开关 / 电耗单位 / 电费单价 / 里程折扣。字段可选仅改传了的；
     * 应用后 remoteSpeakTick 自增（设置页/统计页订阅即时生效）+ forceReport 回报。
     */
    private fun executeJikongSetConfig(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val p = command.params
        return try {
            val applied = mutableListOf<String>()
            p.get("dash_enabled")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asBoolean
                my.bridge.com.jikong.JikongPrefs.setDashEnabled(context, v)
                // 与手机设置页 toggleDash 同语义：立即启停（SpeedDash.start 幂等，
                // 页面不可见时由 ON_RESUME 门控兜底）；关则无条件停。
                if (v) my.bridge.com.jikong.SpeedDash.start(context)
                else my.bridge.com.jikong.SpeedDash.stop()
                applied.add(if (v) "时速仪表盘开" else "时速仪表盘关")
            }
            p.get("unit_kwh100")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asBoolean
                my.bridge.com.jikong.JikongPrefs.setUnitKwh100(context, v)
                applied.add(if (v) "电耗单位 kWh/100km" else "电耗单位 Wh/km")
            }
            p.get("elec_price")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asDouble.toFloat().coerceIn(0.05f, 10f)
                my.bridge.com.jikong.JikongPrefs.setElecPrice(context, v)
                applied.add("电费单价 %.2f 元/度".format(v))
            }
            p.get("odo_discount")?.takeIf { it.isJsonPrimitive }?.let {
                val v = it.asDouble.toFloat().coerceIn(0.1f, 1f)
                my.bridge.com.jikong.JikongPrefs.setOdoDiscount(context, v)
                applied.add("里程折扣 %.1f".format(v))
            }
            if (applied.isEmpty()) {
                CommandResult(id, name, false, "无有效参数（dash_enabled/unit_kwh100/elec_price/odo_discount）")
            } else {
                my.bridge.com.ui.JkUiState.remoteSpeakTick++
                my.bridge.com.jikong.JikongController.instance.forceReport()
                CommandResult(id, name, true, "设置已同步：" + applied.joinToString("；"))
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_set_config 执行失败")
            CommandResult(id, name, false, "同步设置失败：${e.message}")
        }
    }

    /**
     * jikong_set_proximity（v0.2.107）：后台「靠近自动连接」卡开关同步到手机——
     * auto_connect 开/关，联动前台服务启停。交由 JikongController.setProximityAutoConnect
     * 统一处理（写 Prefs + evaluate/stop + forceReport），与手机设置页开关同语义。
     */
    private fun executeJikongSetProximity(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val v = command.params.get("auto_connect")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: return CommandResult(id, name, false, "缺少参数 auto_connect（开/关）")
            my.bridge.com.jikong.JikongController.instance.setProximityAutoConnect(context, v)
            CommandResult(id, name, true, "靠近自动连接已${if (v) "开启" else "关闭"}")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_set_proximity 执行失败")
            CommandResult(id, name, false, "同步靠近自动连接失败：${e.message}")
        }
    }

    /**
     * jikong_demo_refresh（v0.2.105）：管理后台「刷新模拟数据」远程触发手机端
     * 演示数据随机重生成（BMS 总览+仪表盘联动注入，与手机横幅按钮同一逻辑）。
     * refreshDemoData 内部自带 statusVersion 自增 + reportStatus（demo 数据变化
     * 必过 JSON 去重），此处再 forceReport 双保险，后台刷新即刻同步新值。
     */
    private fun executeJikongDemoRefresh(command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val controller = my.bridge.com.jikong.JikongController.instance
        if (!controller.demoMode) {
            return CommandResult(id, name, false, "手机端不在演示模式，无法刷新模拟数据")
        }
        return try {
            controller.refreshDemoData()
            controller.forceReport()
            CommandResult(id, name, true, "演示模拟数据已刷新并回报（SOC=${controller.status.soc}%）")
        } catch (e: Exception) {
            Timber.e(e, "命令 jikong_demo_refresh 执行失败")
            CommandResult(id, name, false, "刷新演示数据失败：${e.message}")
        }
    }
}
