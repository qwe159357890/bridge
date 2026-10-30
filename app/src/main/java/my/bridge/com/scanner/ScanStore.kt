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
 * 设计（照调研文档 §5.5 / §6.6，v0.1.95 修订）：
 * - 会话一等键 = UUID（id），支持自由增删改/重命名；date 仅作创建日期分组与「今日会话」判定；
 * - 扫码自动落「今日最新会话」，不存在则自动创建（默认名 defaultName(date)）；
 * - 跨天自动切会话：0 点分界后 date 变化，自动建新会话不会串单；
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
            // 旧档迁移：v0.1.94 会话无 id，用 date 补齐为一等键
            sessions = sessions.map { s -> if (s.id.isNullOrBlank()) s.copy(id = s.date) else s }
            nextId = (sessions.maxOfOrNull { s -> s.records.maxOfOrNull { it.id } ?: 0L } ?: 0L) + 1
            loaded = true
            publish()
        }
    }

    // ------------------------------------------------------------------
    // 日期与会话（v0.1.95：会话自由增删改，id 为一等键）
    // ------------------------------------------------------------------

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 今天的一等键（yyyy-MM-dd）。 */
    fun todayKey(): String = dateFmt.format(Date())

    /** 会话默认名：「10月30日」（v0.1.95 起：创建弹窗默认文本，可编辑，不带「出库单」后缀）。 */
    fun defaultName(date: String): String = runCatching {
        val parts = date.split("-")
        "${parts[1].toInt()}月${parts[2].toInt()}日"
    }.getOrDefault(date)

    /** 今日会话列表（可能多个，按创建时间倒序，最新在前）。 */
    fun todaySessions(): List<ScanSession> = synchronized(lock) {
        sessions.filter { it.date == todayKey() }.sortedByDescending { it.createdAt }
    }

    /** 今日最新会话（不存在返回 null，不自动创建；自动创建只在落库时刻发生）。 */
    fun todaySession(): ScanSession? = todaySessions().firstOrNull()

    fun sessionById(id: String): ScanSession? = synchronized(lock) {
        sessions.find { it.key == id }
    }

    /**
     * 创建会话（跨天提示 / 会话管理页「新建」入口）。
     * @param date 创建日期，默认今天（历史补录场景可传过去日期）。
     */
    fun createSession(context: Context, name: String, date: String = todayKey()): ScanSession = synchronized(lock) {
        ensureLoaded(context)
        val created = ScanSession(
            id = java.util.UUID.randomUUID().toString(),
            date = date,
            name = name.ifBlank { defaultName(date) },
            createdAt = System.currentTimeMillis(),
            records = emptyList(),
        )
        sessions = sessions + created
        Timber.tag(TAG).i("创建会话 %s（%s）", created.name, date)
        persistAsync(context)
        publish()
        created
    }

    /** 重命名会话。 */
    fun renameSession(context: Context, id: String, newName: String) = synchronized(lock) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@synchronized
        sessions = sessions.map { if (it.key == id) it.copy(name = trimmed) else it }
        persistAsync(context)
        publish()
    }

    /** 删除会话（按 id），并按剩余记录重建全局已见集合。 */
    fun deleteSessionById(context: Context, id: String) = synchronized(lock) {
        sessions = sessions.filter { it.key != id }
        rebuildSeen()
        persistAsync(context)
        publish()
        Timber.tag(TAG).i("删除会话 %s", id)
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
     * 入库：自动落「今日最新会话」（跨天 0 点分界自动切单），写全局已见集合，异步持久化。
     * 今日无会话时自动创建（默认名「10月30日」）。
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
        val session = sessions.filter { it.date == today }.maxByOrNull { it.createdAt } ?: run {
            val created = ScanSession(
                id = java.util.UUID.randomUUID().toString(),
                date = today,
                name = defaultName(today),
                createdAt = System.currentTimeMillis(),
                records = emptyList(),
            )
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
            if (it.key == session.key) it.copy(records = it.records + record) else it
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
    fun clearSession(context: Context, id: String) = synchronized(lock) {
        sessions = sessions.map { if (it.key == id) it.copy(records = emptyList()) else it }
        rebuildSeen()
        persistAsync(context)
        publish()
        Timber.tag(TAG).i("清空会话 %s", id)
    }

    /** 修改单条记录的单号（记录级编辑），同步全局已见集合。 */
    fun updateRecord(context: Context, sessionId: String, recordId: Long, newValue: String) = synchronized(lock) {
        val trimmed = newValue.trim()
        if (trimmed.isEmpty()) return@synchronized
        sessions = sessions.map { s ->
            if (s.key != sessionId) s
            else s.copy(records = s.records.map { if (it.id == recordId) it.copy(value = trimmed) else it })
        }
        rebuildSeen()
        persistAsync(context)
        publish()
        Timber.tag(TAG).i("编辑记录 %d → %s", recordId, trimmed)
    }

    /** 删除单条记录，并按剩余记录重建全局已见集合。 */
    fun deleteRecord(context: Context, sessionId: String, recordId: Long) = synchronized(lock) {
        sessions = sessions.map { s ->
            if (s.key != sessionId) s
            else s.copy(records = s.records.filter { it.id != recordId })
        }
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

    fun exportCsv(context: Context, id: String): String? = synchronized(lock) {
        ensureLoaded(context)
        val session = sessions.find { it.key == id } ?: return null
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
