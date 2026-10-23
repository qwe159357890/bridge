package my.bridge.com.core.common

import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 诊断日志采集器（借鉴 aurelay DiagLog，精简版）。
 *
 * 设计目标：客户端把关键事件记入内存环形缓冲，排查问题时经
 * `dump()` 导出全文本，再通过日志上传接口上报到后端（docs/server/design.md 第十七节）。
 *
 * 精简说明：
 * - 保留「内存环形缓冲 + 环境信息 + dump()」核心能力；
 * - 省略文件落盘/轮转（后续需要导出文件时再补）；
 * - 始终输出 Logcat，便于本地开发调试。
 *
 * 线程安全：所有写入口走同一把锁。
 */
object DiagLog {

    const val TAG = "BridgeDiag"

    /** 内存环形缓冲容量（条）。 */
    private const val RING_CAPACITY = 600

    /** 导出时最多带出多少字符（与后端 max_content_chars 对齐，避免请求体过大）。 */
    private const val MAX_DUMP_CHARS = 200_000

    private val ring = ArrayDeque<String>()

    private val lock = Any()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 一般级日志：始终记入内存缓冲。 */
    fun i(tag: String, message: String) = append("I", tag, message)

    /** 警告级日志：始终记入内存缓冲。 */
    fun w(tag: String, message: String) = append("W", tag, message)

    /** 错误级日志：始终记入内存缓冲，附异常类型与堆栈首部。 */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let {
            " | ${it.javaClass.simpleName}: ${it.message} @ " +
                (it.stackTrace.firstOrNull()?.toString() ?: "无堆栈")
        } ?: ""
        append("E", tag, message + extra)
    }

    private fun append(level: String, tag: String, message: String) {
        when (level) {
            "W" -> Log.w(TAG, "[$tag] $message")
            "E" -> Log.e(TAG, "[$tag] $message")
            else -> Log.i(TAG, "[$tag] $message")
        }
        synchronized(lock) {
            val line = "${timeFormat.format(Date())} [$level] [$tag] $message"
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
    }

    /** 取内存缓冲全部行（由旧到新）。 */
    fun ringLines(): List<String> = synchronized(lock) { ArrayList(ring) }

    /** 清空内存缓冲。 */
    fun clear() = synchronized(lock) { ring.clear() }

    /** 取当前安装包版本名（读取失败返回「未知」）。 */
    fun appVersionName(context: Context): String = try {
        val app = context.applicationContext
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "未知"
    } catch (e: Exception) {
        "未知"
    }

    /** 采集环境信息（机型 / 系统 / 版本）。 */
    fun environment(context: Context): String {
        val app = context.applicationContext
        return buildString {
            append("App 版本：").append(appVersionName(app)).append("\n")
            append("安装包名：").append(app.packageName).append("\n")
            append("机型：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append("（").append(Build.BRAND).append("）\n")
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append("，SDK ").append(Build.VERSION.SDK_INT)
                .append("，ABI ").append(Build.SUPPORTED_ABIS.joinToString("/")).append("\n")
        }
    }

    /**
     * 生成完整日志文本（环境信息 + 内存缓冲），用于上传。
     * 超过上限时保留尾部（最新内容）。
     */
    fun dump(context: Context): String {
        val builder = StringBuilder()
        builder.append("========== Bridge 手机端诊断日志 ==========\n")
        builder.append(environment(context))
        builder.append("生成时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append("\n")

        val buffered = ringLines()
        if (buffered.isNotEmpty()) {
            builder.append("\n---------- 内存缓冲（最近 ").append(buffered.size).append(" 条）----------\n")
            for (line in buffered) builder.append(line).append("\n")
        } else {
            builder.append("\n（暂无日志）\n")
        }

        val text = builder.toString()
        return if (text.length <= MAX_DUMP_CHARS) text else text.substring(text.length - MAX_DUMP_CHARS)
    }
}
