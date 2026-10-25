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

    override fun onCreate() {
        super.onCreate()

        // 日志：release 只保留 warn 以上
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

        Timber.i("BridgeApplication 初始化完成")
    }
}
