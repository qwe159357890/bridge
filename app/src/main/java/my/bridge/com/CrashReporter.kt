package my.bridge.com

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.storage.DeviceStore
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 崩溃自动上报。
 *
 * 通过 [Thread.setDefaultUncaughtExceptionHandler] 安装全局异常处理器。
 *
 * v0.1.98 修复（重大）：旧实现在崩溃线程里 `scope.launch`(异步) 后立即交回系统
 * 默认处理器终止进程——**协程根本来不及执行，崩溃日志从未上传成功**（服务端日志库
 * 长期查无 Crash 记录的根因）。现改为崩溃线程内同步执行：
 * 1. 先把完整堆栈**同步写入本地文件**（filesDir/crash/last_crash.txt，保底必达）；
 * 2. 再在崩溃线程内用 `runBlocking`+3.5s 预算尝试上报（进程本就要终止，短暂阻塞可接受）；
 * 3. 下次启动时若发现未送达的崩溃文件，自动补传后删除。
 */
class CrashReporter(
    private val context: Context,
    private val deviceStore: DeviceStore,
    private val apiClient: BridgeApiClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var installed = false

    private fun crashFile(): File = File(context.applicationContext.filesDir, "crash/last_crash.txt")

    /** 安装崩溃处理器。幂等。 */
    fun install(enabled: Boolean = true) {
        if (installed || !enabled) return
        // 启动即补传上次崩溃（断网/被杀导致的未送达堆栈，下次开机必达）
        uploadPendingCrash()
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

    /** 组装完整崩溃文本（线程 + 版本 + 全堆栈含 cause 链）。 */
    private fun buildCrashText(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return buildString {
            append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append("\n线程：").append(thread.name)
            append("\n版本：").append(runCatching { DiagLog.appVersionName(context) }.getOrDefault("?"))
            append("\n设备：").append(runCatching { deviceStore.deviceId() }.getOrDefault("?"))
            append("\n堆栈：\n").append(sw.toString())
        }
    }

    /** 采集崩溃日志：同步落盘 + 同步尽力上报（崩溃线程内执行）。 */
    private fun report(thread: Thread, throwable: Throwable) {
        try {
            DiagLog.e("Crash", "未捕获异常（线程 ${thread.name}）", throwable)
            DiagLog.flushToFile()

            val text = buildCrashText(thread, throwable)

            // 1) 同步落盘（崩溃现场保底，之后启动时补传）
            runCatching {
                val f = crashFile()
                f.parentFile?.mkdirs()
                f.writeText(text)
            }

            // 2) 同步尽力上报（3.5s 预算；进程即将终止，阻塞可接受）
            runCatching {
                val deviceId = deviceStore.deviceId()
                val logs = DiagLog.dumpLines().map {
                    my.bridge.com.core.network.api.BridgeApi.LogLine(
                        ts = it.ts, level = it.level, tag = it.tag, message = it.message,
                    )
                } + my.bridge.com.core.network.api.BridgeApi.LogLine(
                    ts = System.currentTimeMillis(),
                    level = "E",
                    tag = "Crash",
                    message = text.take(4000),
                )
                runBlocking {
                    withTimeout(3500) {
                        apiClient.uploadLog(
                            deviceId = deviceId,
                            logs = logs,
                            deviceName = deviceStore.deviceName() ?: deviceId,
                            appVersion = DiagLog.appVersionName(context),
                        )
                    }
                }
                // 上报成功即删本地保底文件
                runCatching { crashFile().delete() }
                Timber.i("崩溃日志已上报")
            }.onFailure { Timber.w(it, "崩溃日志同步上报失败（已有本地保底）") }
        } catch (e: Exception) {
            Timber.w(e, "崩溃上报流程异常")
        }
    }

    /** 启动时补传上次未送达的崩溃（独立协程，失败保留文件下次再试）。 */
    private fun uploadPendingCrash() {
        val f = crashFile()
        if (!f.exists()) return
        scope.launch {
            try {
                val text = f.readText()
                val deviceId = deviceStore.deviceId()
                apiClient.uploadLog(
                    deviceId = deviceId,
                    logs = listOf(
                        my.bridge.com.core.network.api.BridgeApi.LogLine(
                            ts = System.currentTimeMillis(),
                            level = "E",
                            tag = "Crash",
                            message = text.take(4000),
                        )
                    ),
                    deviceName = deviceStore.deviceName() ?: deviceId,
                    appVersion = DiagLog.appVersionName(context),
                )
                f.delete()
                Timber.i("上次崩溃日志补传成功")
            } catch (e: Exception) {
                Timber.w(e, "崩溃日志补传失败（保留文件下次再试）")
            }
        }
    }
}
