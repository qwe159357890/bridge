package my.bridge.com

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeConnectionManager
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.keepalive.KeepAliveService
import timber.log.Timber
import javax.inject.Inject

/**
 * Bridge 应用入口。
 *
 * 职责：
 * 1. 初始化 Hilt DI 容器；
 * 2. 初始化日志（Timber）；
 * 3. 无条件拉起常驻保活服务（保活与业务解耦，借鉴 Aurelay）；
 * 4. 启动后端连接（设备标识初始化 → 注册 → WebSocket 长连接）；
 * 5. 安装崩溃自动上报。
 */
@HiltAndroidApp
class BridgeApplication : Application() {

    @Inject
    lateinit var connectionManager: BridgeConnectionManager

    @Inject
    lateinit var deviceStore: DeviceStore

    @Inject
    lateinit var apiClient: BridgeApiClient

    @Inject
    lateinit var musicStateReporter: my.bridge.com.media.MusicStateReporter

    @Inject
    lateinit var bluetoothMediaButtonListener: my.bridge.com.media.BluetoothMediaButtonListener

    @Inject
    lateinit var reportManager: my.bridge.com.report.ReportManager

    override fun onCreate() {
        super.onCreate()

        // 日志：先初始化落盘目录（files/logs/），再桥接 Timber → DiagLog 内存缓冲
        // （release 也必须 plant，否则后端拉日志看不到网络层/无障碍/媒体等关键模块的日志）；
        // debug 额外 plant DebugTree 刷 Logcat，便于本地开发。
        my.bridge.com.core.common.DiagLog.init(this)
        Timber.plant(DiagLogTree())
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 安装崩溃自动上报（在连接/保活之前，尽早覆盖）
        CrashReporter(this, deviceStore, apiClient).install(enabled = true)

        // 无条件拉起常驻保活服务
        KeepAliveService.start(this)

        // 启动后端长连接（设备注册 + WebSocket）
        connectionManager.start()

        // 音乐播放状态上报（状态变化 → WS message 通道 → 管理后台实时展示/按钮联动）
        musicStateReporter.start()

        // 蓝牙耳机媒体按键监听（复用常驻保活语境，零额外功耗：只挂 MediaSession + 一次焦点，
        // 无定时巡检）。按键 → Toast + WS 上报后台。
        my.bridge.com.media.BridgeApplicationContextHolder.init(this)
        bluetoothMediaButtonListener.start(this)

        // 主动上报总管：周期位置上报（服务定时+WorkManager 兜底）+ 新短信即推 +
        // 接收服务端 config_update 推送（后台远程开关，默认双开）
        reportManager.start()

        Timber.i("BridgeApplication 初始化完成")
    }
}
