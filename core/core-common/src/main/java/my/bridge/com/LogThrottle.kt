package my.bridge.com.core.common

/**
 * 重复事件日志节流器（v0.2.130 日志治理）。
 *
 * 背景：网络抖动时 VNT / 微信 WS 会在几分钟内反复「连上→断开→重连」十几轮，
 * 每轮产生 4~5 条状态日志，同一个根因（如 Disconnect）被重复写十几次，把服务端
 * 日志窗口（20000 条）与排障视野占满。用户 2026-11-10 实测：42 分钟 299 条里
 * 组网 95 条 + 微信 61 条，大半是同一根因的反复重写。
 *
 * 策略（用户拍板「状态翻转时详记 1 条，重复轮次静默计数，恢复时打一条汇总」）：
 * - 相同 [key] 的**归一化签名**（通常取失败原因/状态名）连续重复 → 静默计数，不产生日志行；
 * - 签名变化（状态翻转 / 失败原因变化）→ 先打一条上一段的汇总，再详记新状态；
 * - [summaryIfAny] 在「恢复成功」时主动打汇总，让风暴结束有明确收尾；
 * - **兜底心跳**：风暴持续且状态一直不变时，每 [BURST_REPORT_EVERY] 轮或每
 *   [BURST_REPORT_MAX_MS] 打一条「仍在进行中」汇总，避免长时间零日志被误判为服务已死。
 *
 * ★ 为什么不是「只记错误」：v0.2.129 的假恢复问题靠 I 级「回读=8」破案——
 * `setStreamVolume` 静默忽略不抛异常，只记 E/W 会让这类「达不到目标但不报错」的问题
 * 永远无解。本节流器只砍「过程性重复」，**状态变更骨架一条不删**。
 *
 * ★ 为什么不是「降为 D 级」：[DiagLogTree.isLoggable] 恒 true，D 级日志仍进
 * 环形缓冲 + 落盘 + 上传，只是不刷 Logcat——降级不减少服务端日志量。必须真正不产生行。
 *
 * 线程安全：所有公开方法 [Synchronized]，native 回调/主线程/协程可并发调用。
 * 纯日志侧工具，**不触碰任何业务状态**。
 */
object LogThrottle {

    /** 风暴持续时，最多静默多少轮就打一条「仍在进行中」汇总。 */
    private const val BURST_REPORT_EVERY = 8

    /** 风暴持续时，最长静默多久（毫秒）就打一条汇总。 */
    private const val BURST_REPORT_MAX_MS = 60_000L

    private class State(
        /** null = 尚未收到任何事件，保证首条永远详记（避免空签名被误判为重复）。 */
        var signature: String? = null,
        var suppressed: Int = 0,
        var since: Long = 0L,
        var lastReportedCount: Int = 0,
        // 状态翻转瞬间保留的旧值，供「上一段汇总」使用
        var previousSignature: String = "",
        var sinceBeforeChange: Long = 0L,
    )

    private val states = HashMap<String, State>()

    /** 单条日志动作，交给 [DiagLog] 按级别输出。 */
    private fun emit(level: String, tag: String, message: String) {
        when (level) {
            "E" -> DiagLog.e(tag, message)
            "W" -> DiagLog.w(tag, message)
            else -> DiagLog.i(tag, message)
        }
    }

    /**
     * 上报一次事件。
     *
     * @param key 组件内节流键（如 "vnt-connect"），不同事件流分开计数
     * @param signature 归一化状态签名（如失败原因 "Disconnect"），**不含次数/时间戳等易变内容**
     * @param level "I"/"W"/"E"
     * @param message 首条/状态翻转时要详记的完整日志文本
     * @return true=本次已输出日志；false=被静默（重复轮次）
     */
    @Synchronized
    fun event(key: String, signature: String, level: String, tag: String, message: String): Boolean {
        val s = states.getOrPut(key) { State() }
        val now = System.currentTimeMillis()
        val (changed, suppressedBefore, heartbeat) = advance(s, signature, now)

        if (heartbeat) {
            // 长时间同一状态：打一条「仍在进行中」心跳汇总，不打断节流计数。
            val sec = (now - s.since).coerceAtLeast(0) / 1000
            DiagLog.i(
                tag,
                "[节流汇总] 状态「$signature」仍持续（已静默 ${s.suppressed + 1} 轮，历时 ${sec}s）",
            )
            s.lastReportedCount = s.suppressed
            return false
        }
        if (!changed) return false

        // 状态翻转：先吐出上一段被静默的汇总（若有），再详记新状态
        if (suppressedBefore > 0) {
            val sec = (now - s.sinceBeforeChange).coerceAtLeast(0) / 1000
            DiagLog.i(
                tag,
                "[节流汇总] 上一状态「${s.previousSignature}」连续重复 ${suppressedBefore + 1} 次（历时 ${sec}s），状态已变化",
            )
        }
        emit(level, tag, message)
        return true
    }

    /**
     * 只做节流判定、**不产生任何日志行**，供调用方自行输出（例如需要带 throwable 的
     * [DiagLog.e]，或需要按自己格式拼装文本）。语义与 [event] 完全一致。
     */
    @Synchronized
    fun shouldEmit(key: String, signature: String, tag: String? = null): Boolean {
        val s = states.getOrPut(key) { State() }
        val now = System.currentTimeMillis()
        val (changed, suppressedBefore, heartbeat) = advance(s, signature, now)

        if (heartbeat) {
            if (tag != null) {
                val sec = (now - s.since).coerceAtLeast(0) / 1000
                DiagLog.i(
                    tag,
                    "[节流汇总] 状态「$signature」仍持续（已静默 ${s.suppressed + 1} 轮，历时 ${sec}s）",
                )
            }
            s.lastReportedCount = s.suppressed
            return false
        }
        if (!changed) return false
        if (suppressedBefore > 0 && tag != null) {
            val sec = (now - s.sinceBeforeChange).coerceAtLeast(0) / 1000
            DiagLog.i(
                tag,
                "[节流汇总] 上一状态「${s.previousSignature}」连续重复 ${suppressedBefore + 1} 次（历时 ${sec}s），状态已变化",
            )
        }
        return true
    }

    /**
     * 推进状态机。返回三元组 (是否状态翻转, 翻转前被静默次数, 是否应打心跳汇总)。
     * 重置时把「旧签名/旧起始时间」留在 [previousSignature]/[sinceBeforeChange] 供汇总使用。
     */
    private fun advance(s: State, signature: String, now: Long): Triple<Boolean, Int, Boolean> {
        if (s.signature == signature) {
            // 重复轮次：静默计数，不产生日志行
            s.suppressed++
            // 兜底：静默满 N 轮或超过时间阈值，打一条「仍在进行中」，避免长时间零日志
            val byCount = s.suppressed - s.lastReportedCount >= BURST_REPORT_EVERY
            val byTime = now - s.since >= BURST_REPORT_MAX_MS
            return Triple(false, 0, byCount || byTime)
        }
        val suppressedBefore = s.suppressed
        s.previousSignature = s.signature ?: ""
        s.sinceBeforeChange = s.since
        s.signature = signature
        s.suppressed = 0
        s.lastReportedCount = 0
        s.since = now
        return Triple(true, suppressedBefore, false)
    }

    /**
     * 「恢复成功」时主动收尾：若该键有被静默的重复轮次，打一条汇总并**清空该键状态**。
     * 用于连接成功后明确告诉排障者「刚才那串重连结束了」，避免风暴无声无息。
     *
     * ★ 清空（而非只把计数归零）是刻意的：若保留签名，下一轮新风暴的**首条**失败会因为
     * 签名相同被直接静默，违背「首条永远详记」原则，排障者看不到新故障的起点。
     * 注意只清「失败原因」键；过程日志键（连接服务端/握手/注册等）保持长期节流，
     * 不因每次重连成功就重新打印一遍。
     *
     * @return true=打了汇总（说明此前确实存在风暴）
     */
    @Synchronized
    fun summaryIfAny(key: String, tag: String, label: String = "重连"): Boolean {
        val s = states[key] ?: return false
        if (s.suppressed <= 0) return false
        val sec = (System.currentTimeMillis() - s.since).coerceAtLeast(0) / 1000
        DiagLog.i(
            tag,
            "[节流汇总] ${label}结束：连续 ${s.suppressed + 1} 轮（末次状态「${s.signature}」，历时 ${sec}s）",
        )
        states.remove(key)
        return true
    }

    /** 重置某个键（进程重启/组件停止时调用，避免跨会话累计）。 */
    @Synchronized
    fun reset(key: String) {
        states.remove(key)
    }

    /** 重置某个前缀下的所有键（如 "vnt-"），用于组件整体停止。 */
    @Synchronized
    fun resetPrefix(prefix: String) {
        states.keys.removeAll { it.startsWith(prefix) }
    }
}
