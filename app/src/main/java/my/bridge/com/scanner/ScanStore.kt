package my.bridge.com.scanner

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import my.bridge.com.scanner.ScanFormats.MANUAL
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 快递扫码本地存储（v1 纯本地闭环，服务端通道下一版接入）。
 *
 * 设计（照调研文档 §5.5 / §6.6）：
 * - 会话以「自然日」为一等键（yyyy-MM-dd），每天一个，自动命名「10月30日 出库单」；
 * - 跨天自动切会话：addRecord 时发现今天没有会话就自动创建（0 点分界不会串单）；
 * - 全局已见值集合（value → 首见日期）支撑「全局去重」策略，O(1) 判重；
 * - 持久化 = filesDir/scan/scan_sessions.json（Gson），保存走单线程后台执行器。
 */
object ScanStore {

    private const val TAG = "快递扫码"
    private const val MAX_SEEN = 50_000

    /** 判重结果。 */
    sealed class Decision {
        /** 通过，可入库。 */
        data object Accept : Decision()

        /** 重复且被忽略（附提示文案）。 */
        data class Duplicate(val reason: String) : Decision()

        /** 重复且策略要求询问用户（由 UI 弹窗，用户接受后 forceAdd）。 */
        data object Ask : Decision()
    }

    /** Gson 持久化根对象。 */
    private data class PersistedData(
        @SerializedName("sessions") val sessions: List<ScanSession> = emptyList(),
        /** 全局已见值：value → 首见日期（yyyy-MM-dd）。 */
        @SerializedName("seen") val seen: Map<String, String> = emptyMap(),
    )

    private val gson = Gson()
    private val saveExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "scan-store") }
    private val lock = Any()

    private var loaded = false
    private var sessions: List<ScanSession> = emptyList()
    private var seen: Map<String, String> = emptyMap()
    private var nextId: Long = 1L

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, "scan/scan_sessions.json")

    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loaded) return
            runCatching {
                val f = file(context)
                if (f.exists()) {
                    val data = gson.fromJson(f.readText(), PersistedData::class.java)
                    sessions = data?.sessions ?: emptyList()
                    seen = data?.seen ?: emptyMap()
                }
            }.onFailure { Timber.tag(TAG).e(it, "读取扫码存档失败") }
            nextId = (sessions.maxOfOrNull { s -> s.records.maxOfOrNull { it.id } ?: 0L } ?: 0L) + 1
            loaded = true
            publish()
        }
    }

    // ------------------------------------------------------------------
    // 日期与会话
    // ------------------------------------------------------------------

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 今天的一等键（yyyy-MM-dd）。 */
    fun todayKey(): String = dateFmt.format(Date())

    /** 会话显示名：「10月30日 出库单」。 */
    fun displayName(date: String): String = runCatching {
        val parts = date.split("-")
        "${parts[1].toInt()}月${parts[2].toInt()}日 出库单"
    }.getOrDefault("$date 出库单")

    /** 今日会话（不存在返回 null，不自动创建；自动创建只在落库时刻发生）。 */
    fun todaySession(): ScanSession? = synchronized(lock) {
        sessions.find { it.date == todayKey() }
    }

    /** 手动创建今日会话（跨天提示卡的「创建今日出库单」按钮）。已存在则原样返回。 */
    fun createTodaySession(context: Context): ScanSession = synchronized(lock) {
        ensureLoaded(context)
        val today = todayKey()
        sessions.find { it.date == today } ?: run {
            val created = ScanSession(
                date = today,
                name = displayName(today),
                createdAt = System.currentTimeMillis(),
                records = emptyList(),
            )
            sessions = sessions + created
            Timber.tag(TAG).i("创建今日会话 %s", today)
            persistAsync(context)
            publish()
            created
        }
    }

    // ------------------------------------------------------------------
    // 判重与入库
    // ------------------------------------------------------------------

    /**
     * 判重评估（按当前去重策略）。
     * @param lastValue 今日会话最后一条记录的值（相邻去重用），无记录传 null。
     */
    fun evaluate(context: Context, value: String, lastValue: String?): Decision {
        val strategy = ScanPrefs.dedup(context)
        val today = todaySession()
        val inToday = today?.records?.any { it.value == value } ?: false
        val seenDate = seen[value]
        return when (strategy) {
            DedupStrategy.ADJACENT ->
                if (value == lastValue) Decision.Duplicate("重复单号已忽略（相邻去重）") else Decision.Accept
            DedupStrategy.ASK ->
                if (inToday || seenDate != null) Decision.Ask else Decision.Accept
            DedupStrategy.ALWAYS_ACCEPT -> Decision.Accept
            DedupStrategy.SESSION_ALL ->
                if (inToday) Decision.Duplicate("重复单号已忽略（今日会话内已存在）") else Decision.Accept
            DedupStrategy.GLOBAL_ALL ->
                when {
                    inToday -> Decision.Duplicate("重复单号已忽略（今日会话内已存在）")
                    seenDate != null -> Decision.Duplicate("该单号 $seenDate 已扫过（全局去重）")
                    else -> Decision.Accept
                }
        }
    }

    /**
     * 入库：自动落今天的会话（跨天 0 点分界自动切单），写全局已见集合，异步持久化。
     * @param force true = 用户在「询问」弹窗点了接受。
     */
    fun addRecord(
        context: Context,
        value: String,
        format: String,
        manual: Boolean = false,
        force: Boolean = false,
    ): ScanRecord? = synchronized(lock) {
        ensureLoaded(context)
        val today = todayKey()
        val session = sessions.find { it.date == today } ?: run {
            val created = ScanSession(today, displayName(today), System.currentTimeMillis(), emptyList())
            sessions = sessions + created
            created
        }
        // 库内并发/重复防御：非 force 时同会话内不允许完全相同的值重复入库
        if (!force && session.records.any { it.value == value }) {
            Timber.tag(TAG).i("入库防御：重复值 %s 已存在，跳过", value)
            return@synchronized null
        }
        val record = ScanRecord(
            id = nextId++,
            value = value,
            format = format,
            timestamp = System.currentTimeMillis(),
            manual = manual,
        )
        sessions = sessions.map {
            if (it.date == today) it.copy(records = it.records + record) else it
        }
        seen = seen + (value to today)
        if (seen.size > MAX_SEEN) {
            // 按首见日期裁掉最旧的一部分
            val drop = seen.entries.sortedBy { it.value }.take(seen.size - MAX_SEEN).map { it.key }.toSet()
            seen = seen.filterKeys { it !in drop }
        }
        persistAsync(context)
        publish()
        Timber.tag(TAG).i("入库 %s (%s) 今日第 %d 条", value, format, session.records.size + 1)
        record
    }

    /** 清空指定会话的记录（保留会话本身），并按剩余记录重建全局已见集合。 */
    fun clearSession(context: Context, date: String) = synchronized(lock) {
        sessions = sessions.map { if (it.date == date) it.copy(records = emptyList()) else it }
        rebuildSeen()
        persistAsync(context)
        publish()
        Timber.tag(TAG).i("清空会话 %s", date)
    }

    /** 删除整个会话（历史清理用）。 */
    fun deleteSession(context: Context, date: String) = synchronized(lock) {
        sessions = sessions.filter { it.date != date }
        rebuildSeen()
        persistAsync(context)
        publish()
    }

    /** 按剩余会话记录重建全局已见集合（清空/删除后保证一致）。 */
    private fun rebuildSeen() {
        seen = sessions.flatMap { s -> s.records.map { it.value to s.date } }.toMap()
    }

    // ------------------------------------------------------------------
    // CSV 导出（照参考项目 ScanModel.ToCSV 语义：单号,码制,时间,来源）
    // ------------------------------------------------------------------

    fun exportCsv(context: Context, date: String): String? = synchronized(lock) {
        ensureLoaded(context)
        val session = sessions.find { it.date == date } ?: return null
        if (session.records.isEmpty()) return null
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        buildString {
            append("单号,码制,时间,来源\n")
            session.records.forEach { r ->
                val value = csvEscape(r.value)
                val source = if (r.manual) "手动输入" else "扫码"
                append("$value,${r.format},${timeFmt.format(Date(r.timestamp))},$source\n")
            }
        }
    }

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n'))
            "\"${v.replace("\"", "\"\"")}\"" else v

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun persistAsync(context: Context) {
        val data = PersistedData(sessions, seen)
        val target = file(context)
        saveExecutor.execute {
            runCatching {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(gson.toJson(data))
                if (!tmp.renameTo(target)) {
                    target.writeText(gson.toJson(data))
                    tmp.delete()
                }
            }.onFailure { Timber.tag(TAG).e(it, "扫码存档写入失败") }
        }
    }

    private fun publish() {
        ScanState.replace(sessions, seen.size)
    }
}
