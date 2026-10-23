package my.bridge.com

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import my.bridge.com.keepalive.KeepAliveService
import timber.log.Timber

/**
 * Bridge 应用入口。
 *
 * 职责：
 * 1. 初始化 Hilt DI 容器；
 * 2. 初始化日志（Timber）；
 * 3. 无条件拉起常驻保活服务（保活与业务解耦，借鉴 Aurelay）。
 */
@HiltAndroidApp
class BridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 日志：release 只保留 warn 以上
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 无条件拉起常驻保活服务
        KeepAliveService.start(this)

        Timber.i("BridgeApplication 初始化完成")
    }
}
