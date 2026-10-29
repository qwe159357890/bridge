package my.bridge.com

import my.bridge.com.core.common.DiagLog
import timber.log.Timber

/**
 * Timber → DiagLog 桥接树。
 *
 * 背景：项目里大部分关键模块（WS 连接、命令执行、无障碍、媒体采集、短信、位置、
 * 数据读取等）用 [Timber] 记日志，而日志上传（upload_log / 崩溃上报）只导出
 * [DiagLog] 的内存环形缓冲。二者割裂会导致后端拉日志时看不到这些关键日志，
 * 使「日志上传 + 后台查看」功能几乎失效。
 *
 * 本树把 Timber 全部级别的日志桥接进 DiagLog 环形缓冲，一次桥接即让全项目日志
 * 都能经 dump() 进入上传链路。同时保留 Timber 原树（如 DebugTree）负责刷 Logcat。
 *
 * 用法（在 BridgeApplication.onCreate）：
 * ```
 * Timber.plant(DiagLogTree())          // 桥接进内存缓冲（release 也必须 plant）
 * if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())  // debug 才刷 Logcat
 * ```
 */
class DiagLogTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        DiagLog.log(priority, tag ?: "Bridge", message, t)
    }

    // 始终放行：debug/verbose 级日志也要进内存缓冲，排查问题时需要完整上下文。
    override fun isLoggable(tag: String?, priority: Int): Boolean = true
}
