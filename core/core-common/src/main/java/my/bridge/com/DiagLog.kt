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

    /** 按行上传时最多带出的磁盘历史行数（防止日志量极大时构造过多对象）。 */
    private const val MAX_DUMP_LINES = 5000

    /** 单日志文件上限（字节），写满即滚动。默认 1MB。 */
    private const val MAX_FILE_BYTES = 1024 * 1024

    /** 日志文件保留份数（含主文件），超出淘汰最旧。默认 5 份 ≈ 5MB 上限。 */
    private const val MAX_FILES = 5

    /** 日志文件名（滚动后缀 .1 ~ .N）。 */
    private const val FILE_NAME = "bridge.log"

    private val ring = ArrayDeque<String>()

    private val lock = Any()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 结构化日志行缓冲（与 ring 同步维护，供 upload_log 按行上传）。 */
    private val lineRing = ArrayDeque<LogLine>()

    /** 落盘目录（惰性初始化，需先 [init]）。 */
    private var logDir: File? = null

    /** 单线程文件写执行器（串行化磁盘 IO）。 */
    private var writer: ExecutorService? = null

    /**
     * 一行结构化日志（供 [dumpLines] 按行上传，服务端按行存储/去重/过滤）。
     *
     * @param ts 日志产生时间（epoch 毫秒）
     * @param level 级别：I/W/E/D/V
     * @param tag 模块标签，如 中继/音轨/解码/Bridge
     * @param message 日志正文
     */
    data class LogLine(
        val ts: Long,
        val level: String,
        val tag: String,
        val message: String,
    )

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
     *
     * ★ 关键：Timber 的 prepareLog 会把 Throwable 的**完整堆栈**用 ``\n`` 拼进 message
     * （``原始消息 + '\n' + Log.getStackTraceString(t)``），所以这里收到的 message
     * 往往已经含多行堆栈。若不剥离，这条含 ``\n`` 的 message 会进入内存 lineRing
     * 原样上传（message 字段含真实换行），或落盘后被拆散——都是脏数据。
     * 因此当 throwable 非空时，把 message 里 Timber 追加的堆栈段（首个 ``\n`` 之后）
     * 剥掉，只保留原始文本，再用 [throwableSuffix] 生成简洁摘要（异常类 + 第一帧）。
     */
    fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        // 剥离 Timber 拼入的完整堆栈：首个 \n 之后即堆栈段（\tat / Caused by / ... N more）。
        // 正常日志正文几乎不含 \n；即使极少数多行正文，异常摘要也已由 throwableSuffix 补齐，
        // 信息不丢失，且换来「一行一条、无脏数据」的确定性。
        val clean = if (throwable != null) {
            val nl = message.indexOf('\n')
            if (nl >= 0) message.substring(0, nl).trimEnd() else message
        } else {
            message
        }
        val (level, msg) = when (priority) {
            Log.ERROR, Log.ASSERT -> "E" to (clean + throwableSuffix(throwable))
            Log.WARN -> "W" to clean
            Log.INFO -> "I" to clean
            else -> "D" to clean // DEBUG / VERBOSE
        }
        append(level, tag, msg)
    }

    private fun throwableSuffix(throwable: Throwable?): String = throwable?.let {
        " | ${it.javaClass.simpleName}: ${it.message} @ " +
            (it.stackTrace.firstOrNull()?.toString() ?: "无堆栈")
    } ?: ""

    /**
     * 把 message 中的换行符（\r/\n/\r\n）转义为可见占位符，保证一条日志在磁盘文件里
     * 物理上只占一行。否则多行堆栈会被落盘拆散，readRecentFileLines() 读回时误拆成多条。
     */
    private fun flattenNewlines(message: String): String =
        message.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎')

    private fun append(level: String, tag: String, message: String) {
        // D 级（debug/verbose）不刷 Logcat，避免高频日志拖慢应用；仅 I/W/E 输出到 Logcat。
        when (level) {
            "W" -> Log.w(TAG, "[$tag] $message")
            "E" -> Log.e(TAG, "[$tag] $message")
            "I" -> Log.i(TAG, "[$tag] $message")
            else -> Unit
        }
        val now = Date()
        val ts = now.time
        // message 可能含多行（如异常堆栈被整体作为一条日志写入时含 \n/\r），
        // 落盘前必须把换行转义为可见占位符，否则一条日志在磁盘文件里被物理拆成多行，
        // readRecentFileLines() 用 readLines() 读回时会误拆成多条独立日志（tag 空、level I 的脏数据）。
        val flat = flattenNewlines(message)
        val line = "${timeFormat.format(now)} [$level] [$tag] $flat"
        synchronized(lock) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
            if (lineRing.size >= RING_CAPACITY) lineRing.removeFirst()
            // 内存缓冲与磁盘统一存「转义后」的 flat：既保证上传的 message 不含真实换行
            // （避免服务端 message 字段出现 \n 脏数据），又保证 dumpLines 合并去重时
            // 内存/磁盘两路 message 一致、去重键 (ts,level,tag,message) 能正确命中。
            lineRing.addLast(LogLine(ts, level, tag, flat))
        }
        // 磁盘行带完整 epoch 毫秒前缀（| 分隔），保证重启后能精确还原 ts、按行上传且去重精确。
        writeToFile("$ts|$line")
    }

    /** 取内存缓冲全部行（由旧到新）。 */
    fun ringLines(): List<String> = synchronized(lock) { ArrayList(ring) }

    /** 取结构化日志行（由旧到新），供 upload_log 按行上传。
     *
     * 合并「磁盘历史」与「内存缓冲」两部分：
     * - 磁盘历史（[readRecentFileLines]）：跨进程/重启前落盘的行，带 epoch 毫秒前缀可精确还原 ts；
     *   只取「早于内存最早日志」的部分，避免与内存重复；
     * - 内存 [lineRing]：本次进程产生的最新日志（精确 ts）。
     * 重启后 lineRing 为空，此时返回磁盘全部历史（保证历史不丢）。
     * 去重交给服务端（按 device_id + ts + line_hash 唯一索引），重复上传自动跳过。
     */
    fun dumpLines(): List<LogLine> {
        val mem = synchronized(lock) { ArrayList(lineRing) }
        val disk = readRecentFileLines()
        if (mem.isEmpty()) return disk
        // 磁盘历史与内存缓冲存在重叠（磁盘是内存落盘的持久化副本），
        // 用 (ts, level, tag, message) 元组去重，而非按 ts 阈值过滤——
        // 后者在同毫秒边界会误删「与内存最早一条同 ts」的磁盘行导致漏报。
        val seen = HashSet<String>()
        val result = ArrayList<LogLine>(disk.size + mem.size)
        fun key(l: LogLine) = "${l.ts}\u0001${l.level}\u0001${l.tag}\u0001${l.message}"
        for (l in disk) if (seen.add(key(l))) result.add(l)
        for (l in mem) if (seen.add(key(l))) result.add(l)
        result.sortBy { it.ts }
        return result
    }

    /** 清空内存缓冲。 */
    fun clear() = synchronized(lock) {
        ring.clear()
        lineRing.clear()
    }

    /**
     * 彻底清空日志：内存环形缓冲 + 磁盘日志文件（主文件与全部滚动文件）。
     *
     * 供服务端下发 `clear_log` 命令时调用，配合管理后台「清除全部日志」按钮
     * 实现「三端同步清除」：MySQL 已删，若设备端不清，下次 upload_log 会把
     * dumpLines() 里的内存 + 磁盘历史原样重新上传，导致删库后日志又回来。
     * 返回清理的磁盘文件数（供回执提示）。
     */
    fun clearAll(): Int = synchronized(lock) {
        ring.clear()
        lineRing.clear()
        val dir = logDir ?: return@synchronized 0
        var removed = 0
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(FILE_NAME)) {
                    if (f.delete()) removed++
                }
            }
        } catch (_: Exception) {
            // 磁盘清理失败忽略，不影响内存清空
        }
        removed
    }

    /**
     * 强制把待写日志刷到磁盘（同步，阻塞至队列排空）。
     * 供崩溃上报前调用，尽量保住崩溃瞬间的历史；失败仅忽略。
     */
    fun flushToFile() {
        val w = writer ?: return
        try {
            // submit + get 短超时：真正等待已排队的写完成（单线程执行器 FIFO），
            // 确保崩溃上报前历史已落盘；超时则不阻塞崩溃路径。
            w.submit { }.get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // 执行器可能已关闭或等待超时，忽略（崩溃路径不能卡太久）
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
     * 给内存缓冲/环境信息留空间）。仅读主文件 + 滚动文件，去掉 ts 前缀保持可读。
     */
    private fun readRecentFileText(): String {
        val dir = logDir ?: return ""
        return try {
            val main = File(dir, FILE_NAME)
            if (!main.exists()) return ""
            val bytes = main.readBytes()
            val cap = MAX_DUMP_CHARS / 2
            val raw = if (bytes.size <= cap) {
                String(bytes, Charsets.UTF_8)
            } else {
                String(bytes, bytes.size - cap, cap, Charsets.UTF_8)
            }
            // 去掉每行开头的 "ts|" 前缀（若有），恢复可读格式
            raw.lines().joinToString("\n") { stripTsPrefix(it) }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 读取磁盘历史的结构化日志行（含滚动文件，按时间由旧到新）。
     * 磁盘行格式为 ``epoch毫秒|MM-dd HH:mm:ss.SSS [L] [tag] msg``，可精确还原 ts；
     * 兼容旧格式（无 ts 前缀的行用「当前年份 + 解析时间」估算 ts）。
     * 最多保留最近 [MAX_DUMP_LINES] 行，避免日志量极大时一次性构造过多对象。
     */
    private fun readRecentFileLines(): List<LogLine> {
        val dir = logDir ?: return emptyList()
        return try {
            val files = mutableListOf<File>()
            val main = File(dir, FILE_NAME)
            if (main.exists()) files.add(main)
            for (i in 1..MAX_FILES) {
                val f = File(dir, "$FILE_NAME.$i")
                if (f.exists()) files.add(f)
            }
            val result = ArrayList<LogLine>()
            // 从旧到新读（.N 最旧在前，main 最新在后），超限时从最旧淘汰
            for (f in files.asReversed()) {
                for (raw in f.readLines()) {
                    val line = raw.trimEnd()
                    if (line.isEmpty()) continue
                    val parsed = parseFileLine(line)
                    if (parsed == null) {
                        // 防御性兜底：任何「无法解析成 [L] [tag] msg」的行，都视为
                        // 上一条日志的物理续行（历史版本把含 \n 的 message 拆散落盘，
                        // 或异常堆栈被拆散后的碎片：\tat / Caused by / ... N more 等），
                        // 统一归并到上一条 message，既不丢信息，也不降级成 tag 空脏行。
                        // 不针对具体异常类型——只要解析失败就归并，对新出现的任何
                        // 多行内容（含未来未知形态）都同样兜底。
                        if (result.isNotEmpty()) {
                            val last = result[result.size - 1]
                            result[result.size - 1] = last.copy(
                                // 续行用 ⏎ 连接（与 append 的 flattenNewlines 语义一致），
                                // 保证 message 里只有 ⏎ 占位符、绝无真实 \n，避免再次污染。
                                message = last.message + "⏎" + line.trimStart()
                            )
                        }
                        continue
                    }
                    result.add(parsed)
                    if (result.size > MAX_DUMP_LINES) result.removeAt(0)
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 解析一行磁盘日志 → LogLine（带 ts 前缀精确还原，旧格式估算）。
     * 无法解析成 ``[L] [tag] msg`` 结构（无级别标记）时返回 null，交由调用方归并。 */
    private fun parseFileLine(line: String): LogLine? {
        val idx = line.indexOf('|')
        val (ts, rest) = if (idx > 0) {
            val t = line.substring(0, idx).toLongOrNull()
            if (t != null) t to line.substring(idx + 1) else null to line
        } else null to line
        // rest 形如 "MM-dd HH:mm:ss.SSS [L] [tag] msg"
        // 无级别标记（不含 "[X]"）的行不是合法日志行（如堆栈碎片），返回 null 归并
        val level = extractLevel(rest) ?: return null
        val tag = extractTag(rest) ?: ""
        val message = extractMessage(rest) ?: rest
        // message 保持落盘时的转义形态（⏎ 占位符），不做反转义——
        // 与内存 lineRing 统一（append 里 lineRing 也存转义后的 flat），
        // 保证 dumpLines 合并去重时内存/磁盘两路 message 一致、去重键稳定。
        val finalTs = ts ?: estimateTs(rest)
        return LogLine(finalTs, level, tag, message)
    }

    private fun extractLevel(rest: String): String? {
        val b = rest.indexOf('[')
        if (b < 0) return null
        val e = rest.indexOf(']', b)
        if (e < 0) return null
        return rest.substring(b + 1, e).trim().ifEmpty { null }
    }

    private fun extractTag(rest: String): String? {
        // 第一个 [..] 是级别，第二个 [..] 是 tag
        val first = rest.indexOf(']')
        if (first < 0) return null
        val after = rest.substring(first + 1).trimStart()
        if (!after.startsWith("[")) return null
        val e = after.indexOf(']')
        if (e < 0) return null
        return after.substring(1, e).trim().ifEmpty { null }
    }

    private fun extractMessage(rest: String): String? {
        val first = rest.indexOf(']')
        if (first < 0) return rest
        val after = rest.substring(first + 1).trimStart()
        if (!after.startsWith("[")) return rest.substring(first + 1).trimStart().ifEmpty { rest }
        val e = after.indexOf(']')
        if (e < 0) return rest
        return after.substring(e + 1).trimStart().ifEmpty { rest }
    }

    /** 旧格式（无 ts 前缀）估算 ts：当前年份 + 解析出的 MM-dd HH:mm:ss.SSS。 */
    private fun estimateTs(rest: String): Long {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
            fmt.parse("$year-${rest.take(18)}")?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    /** 去掉行首 "ts|" 前缀（供全文本 dump 保持可读）。 */
    private fun stripTsPrefix(line: String): String {
        val idx = line.indexOf('|')
        return if (idx > 0 && line.substring(0, idx).toLongOrNull() != null) {
            line.substring(idx + 1)
        } else line
    }
}
