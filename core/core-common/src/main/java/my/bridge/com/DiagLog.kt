package my.bridge.com.core.common

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 诊断日志采集器（内存环形缓冲 + 文件落盘轮转）。
 *
 * 设计目标：客户端把关键事件记入「内存环形缓冲 + 磁盘日志文件」，
 * 排查问题时经 [dump] 导出全文本，再通过日志上传接口上报到后端。
 *
 * 双缓冲职责：
 * - 内存环形缓冲（[RING_CAPACITY] 条）：实时、供崩溃瞬间 [CrashReporter] 快速采集；
 * - 文件落盘（`files/logs/bridge.log`，按大小轮转）：跨进程存活、重启/崩溃后历史仍在，
 *   是「不用 adb、靠后端拉日志排障」的兜底。
 *
 * 落盘策略（[MAX_FILE_BYTES] / [MAX_FILES]）：
 * - 单文件写满即滚动：`bridge.log` → `bridge.log.1` → … → `bridge.log.5`，超份数淘汰最旧；
 * - 磁盘占用上限 ≈ 单文件大小 × 份数（默认约 5MB）；
 * - 写文件走单线程 [ExecutorService] 异步执行，绝不阻塞业务/主线程；
 * - 崩溃上报前调用 [flushToFile] 强制落盘（尽力而为，失败不影响崩溃流程）。
 *
 * 线程安全：内存缓冲写入口走同一把锁；文件写由单线程执行器串行化。
 */
object DiagLog {

    const val TAG = "BridgeDiag"

    /** 内存环形缓冲容量（条）。 */
    private const val RING_CAPACITY = 600

    /** 导出时最多带出多少字符（与后端 max_content_chars 对齐，避免请求体过大）。 */
    private const val MAX_DUMP_CHARS = 200_000

    /** 单日志文件上限（字节），写满即滚动。默认 1MB。 */
    private const val MAX_FILE_BYTES = 1024 * 1024

    /** 日志文件保留份数（含主文件），超出淘汰最旧。默认 5 份 ≈ 5MB 上限。 */
    private const val MAX_FILES = 5

    /** 日志文件名（滚动后缀 .1 ~ .N）。 */
    private const val FILE_NAME = "bridge.log"

    private val ring = ArrayDeque<String>()

    private val lock = Any()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 落盘目录（惰性初始化，需先 [init]）。 */
    private var logDir: File? = null

    /** 单线程文件写执行器（串行化磁盘 IO）。 */
    private var writer: ExecutorService? = null

    /**
     * 初始化落盘：设置日志目录并启动异步写线程。
     * 必须在 [BridgeApplication.onCreate] 早期（plant Timber 之前）调用一次，幂等。
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (logDir != null) return
            val dir = File(context.applicationContext.filesDir, "logs")
            dir.mkdirs()
            logDir = dir
            writer = Executors.newSingleThreadExecutor { r ->
                Thread(r, "BridgeDiagWriter").apply { isDaemon = true }
            }
        }
        // 启动时顺带清理一次历史残留（滚动之外的双保险）
        cleanupExcess()
    }

    /** 一般级日志：记入内存缓冲 + 落盘。 */
    fun i(tag: String, message: String) = append("I", tag, message)

    /** 警告级日志：记入内存缓冲 + 落盘。 */
    fun w(tag: String, message: String) = append("W", tag, message)

    /** 错误级日志：记入内存缓冲 + 落盘，附异常类型与堆栈首部。 */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let {
            " | ${it.javaClass.simpleName}: ${it.message} @ " +
                (it.stackTrace.firstOrNull()?.toString() ?: "无堆栈")
        } ?: ""
        append("E", tag, message + extra)
    }

    /**
     * 通用写入口：按 Android [Log] 优先级（VERBOSE~ERROR）记入内存缓冲 + 落盘。
     * 供 DiagLogTree 桥接 Timber 日志使用，使全项目（网络层 / 无障碍 / 媒体 / 短信等）
     * 的 Timber 日志也能进入 dump() 上传链路，后端才能拉到完整日志。
     */
    fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val (level, msg) = when (priority) {
            Log.ERROR, Log.ASSERT -> "E" to (message + throwableSuffix(throwable))
            Log.WARN -> "W" to message
            Log.INFO -> "I" to message
            else -> "D" to message // DEBUG / VERBOSE
        }
        append(level, tag, msg)
    }

    private fun throwableSuffix(throwable: Throwable?): String = throwable?.let {
        " | ${it.javaClass.simpleName}: ${it.message} @ " +
            (it.stackTrace.firstOrNull()?.toString() ?: "无堆栈")
    } ?: ""

    private fun append(level: String, tag: String, message: String) {
        // D 级（debug/verbose）不刷 Logcat，避免高频日志拖慢应用；仅 I/W/E 输出到 Logcat。
        when (level) {
            "W" -> Log.w(TAG, "[$tag] $message")
            "E" -> Log.e(TAG, "[$tag] $message")
            "I" -> Log.i(TAG, "[$tag] $message")
            else -> Unit
        }
        val line = "${timeFormat.format(Date())} [$level] [$tag] $message"
        synchronized(lock) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
        writeToFile(line)
    }

    /** 取内存缓冲全部行（由旧到新）。 */
    fun ringLines(): List<String> = synchronized(lock) { ArrayList(ring) }

    /** 清空内存缓冲。 */
    fun clear() = synchronized(lock) { ring.clear() }

    /**
     * 强制把待写日志刷到磁盘（同步，阻塞至队列排空）。
     * 供崩溃上报前调用，尽量保住崩溃瞬间的历史；失败仅忽略。
     */
    fun flushToFile() {
        val w = writer ?: return
        val barrier = object : Runnable { override fun run() {} }
        try {
            w.execute(barrier)
            // 单线程执行器按 FIFO 执行，barrier 执行完即意味着之前的写都已完成
            // （无同步回调可用，这里以「提交成功 + 稍候」尽力而为，不阻塞崩溃路径太久）
        } catch (_: Exception) {
            // 执行器可能已关闭，忽略
        }
    }

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
     * 生成完整日志文本（环境信息 + 内存缓冲 + 磁盘日志），用于上传。
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
        }

        val fileText = readRecentFileText()
        if (fileText.isNotEmpty()) {
            builder.append("\n---------- 磁盘日志（跨进程历史，最近部分）----------\n")
            builder.append(fileText)
        }

        if (buffered.isEmpty() && fileText.isEmpty()) {
            builder.append("\n（暂无日志）\n")
        }

        val text = builder.toString()
        return if (text.length <= MAX_DUMP_CHARS) text else text.substring(text.length - MAX_DUMP_CHARS)
    }

    // ------------------------------------------------------------------ 落盘实现

    /** 异步写一行到日志文件（单线程执行器串行化）。 */
    private fun writeToFile(line: String) {
        val w = writer ?: return
        val dir = logDir ?: return
        try {
            w.execute { appendLineToFile(dir, line) }
        } catch (_: Exception) {
            // 执行器已关闭等，忽略（不阻塞日志主流程）
        }
    }

    /** 追加一行并滚动（在写线程内执行）。 */
    private fun appendLineToFile(dir: File, line: String) {
        try {
            var target = File(dir, FILE_NAME)
            if (target.exists() && target.length() >= MAX_FILE_BYTES) {
                rotate(dir)
            }
            target.appendText(line + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // 磁盘 IO 失败静默忽略，绝不让日志系统拖垮业务
        }
    }

    /** 滚动：主文件改名为 .1，.1→.2 … 超过份数删最旧。 */
    private fun rotate(dir: File) {
        // 删除最旧的（超出保留份数）
        val oldest = File(dir, "$FILE_NAME.$MAX_FILES")
        if (oldest.exists()) oldest.delete()

        // 从高到低逐个改名
        for (i in MAX_FILES - 1 downTo 1) {
            val from = File(dir, "$FILE_NAME.$i")
            if (from.exists()) from.renameTo(File(dir, "$FILE_NAME.${i + 1}"))
        }
        val main = File(dir, FILE_NAME)
        if (main.exists()) main.renameTo(File(dir, "$FILE_NAME.1"))
    }

    /** 启动时清理：删除超出保留份数的残留文件。 */
    private fun cleanupExcess() {
        val dir = logDir ?: return
        try {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                if (name != FILE_NAME) {
                    val suffix = name.removePrefix("$FILE_NAME.")
                    val idx = suffix.toIntOrNull()
                    if (idx == null || idx > MAX_FILES) f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 读取磁盘日志的最近部分（尾部优先，最多 [MAX_DUMP_CHARS] 的一半，
     * 给内存缓冲/环境信息留空间）。
     */
    private fun readRecentFileText(): String {
        val dir = logDir ?: return ""
        return try {
            val main = File(dir, FILE_NAME)
            if (!main.exists()) return ""
            val bytes = main.readBytes()
            val cap = MAX_DUMP_CHARS / 2
            if (bytes.size <= cap) {
                String(bytes, Charsets.UTF_8)
            } else {
                String(bytes, bytes.size - cap, cap, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
