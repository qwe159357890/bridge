package my.bridge.com

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.storage.DeviceStore
import timber.log.Timber
import kotlin.system.exitProcess

/**
 * 崩溃自动上报。
 *
 * 通过 [Thread.setDefaultUncaughtExceptionHandler] 安装全局异常处理器：
 * 崩溃时先采集诊断日志（崩溃堆栈 + 内存缓冲），在独立线程异步上报到后端，
 * 最后交回系统默认处理器（不阻塞、不改变崩溃行为）。
 *
 * 设计要点：
 * - 上报为「尽力而为」：失败仅记日志，绝不影响崩溃流程；
 * - 用独立线程 + 短阻塞等待，尽量在进程退出前发出请求；
 * - 默认开启，可在 [init] 时通过 [enabled] 关闭（隐私考虑）。
 */
class CrashReporter(
    private val context: Context,
    private val deviceStore: DeviceStore,
    private val apiClient: BridgeApiClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var installed = false

    /** 安装崩溃处理器。幂等。 */
    fun install(enabled: Boolean = true) {
        if (installed || !enabled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(thread, throwable)
            // 交回系统默认处理器（打印堆栈并终止进程）
            previous?.uncaughtException(thread, throwable) ?: run {
                Timber.e(throwable, "未捕获异常（无默认处理器）")
                exitProcess(2)
            }
        }
        installed = true
        Timber.i("崩溃自动上报已安装")
    }

    /** 采集崩溃日志并异步上报。 */
    private fun report(thread: Thread, throwable: Throwable) {
        try {
            // 记录崩溃到诊断日志缓冲，并尽力把内存日志刷到磁盘（崩溃瞬间历史不丢）
            DiagLog.e("Crash", "未捕获异常（线程 ${thread.name}）", throwable)
            DiagLog.flushToFile()

            // 异步上报（独立线程，短阻塞等待尽量发出）
            scope.launch {
                try {
                    val deviceId = deviceStore.deviceId()
                    val content = buildString {
                        append("===== 崩溃信息 =====\n")
                        append("线程：").append(thread.name).append("\n")
                        append("异常：").append(throwable.javaClass.name).append(": ")
                            .append(throwable.message).append("\n")
                        throwable.stackTrace.forEach {
                            append("\tat ").append(it.toString()).append("\n")
                        }
                        append("\n")
                        // dump 内已含环境信息 + 内存缓冲 + 磁盘日志（跨进程历史）
                        append(DiagLog.dump(context))
                    }
                    val deviceName = deviceStore.deviceName() ?: deviceId
                    val appVersion = DiagLog.appVersionName(context)
                    apiClient.uploadLog(
                        deviceId = deviceId,
                        content = content,
                        deviceName = deviceName,
                        appVersion = appVersion,
                    )
                    Timber.i("崩溃日志已上报")
                } catch (e: Exception) {
                    Timber.w(e, "崩溃日志上报失败")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "崩溃上报流程异常")
        }
    }
}
