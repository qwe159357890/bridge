package my.bridge.com.jikong

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条里程台账记录（统计页 v0.2.85）。
 *
 * 录入语义：用户输入三轮车当前里程（km），App 快照当下保护板的循环容量（Ah）
 * 与总压（V）。两条相邻记录即可算出区间电耗：Wh/km = ΔAh × 平均电压 ÷ Δkm。
 *
 * @param id      唯一 id（录入时刻毫秒 + 随机后缀），同步幂等键，录制后不变
 * @param ts      录入时刻（epoch 毫秒）
 * @param odoKm   里程表读数 km
 * @param cycleAh 录入时保护板循环容量 Ah（0x02 帧已解析）
 * @param volt    录入时总压 V
 * @param synced  是否已同步到服务端（false=待同步；服务端/MySQL 离线时保持 false 可重试）
 */
data class JkMileageRecord(
    val id: String,
    val ts: Long,
    val odoKm: Float,
    val cycleAh: Float,
    val volt: Float,
    val synced: Boolean,
)

/**
 * 里程台账本地存储（SharedPreferences + org.json 数组，SalesPrefs 范式）。
 *
 * 设计要点：
 * - **本地为准**：录入永远成功（标 synced=false），服务端/MySQL 离线不丢数；
 * - **删除联动**：删「待同步」行仅删本地；删「已同步」行 → 本地删除 + record_id
 *   进待删除队列（墓碑），随下次同步上行（服务端软删 deleted=1）；
 * - 存量上限 [MAX_RECORDS]（约 5 年手动录入量），超出丢最旧（台账有服务端留档兜底）。
 */
object JikongStatsStore {

    private const val SP_NAME = "jikong_config"          // 与 JikongPrefs 同文件
    private const val KEY_RECORDS = "jikong_mileage_records"
    private const val KEY_PENDING_DELETES = "jikong_mileage_pending_deletes"

    /** 台账存量上限（超出丢最旧；服务端留档兜底）。 */
    const val MAX_RECORDS = 2000

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 全部记录（按录入顺序，最旧在前）。解析失败静默清空重来（防脏数据卡死页面）。 */
    fun records(context: Context): List<JkMileageRecord> {
        val raw = sp(context).getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                JkMileageRecord(
                    id = o.getString("id"),
                    ts = o.getLong("ts"),
                    odoKm = (o.getDouble("odo_km")).toFloat(),
                    cycleAh = (o.getDouble("cycle_ah")).toFloat(),
                    volt = (o.optDouble("volt", 0.0)).toFloat(),
                    synced = o.getBoolean("synced"),
                )
            }
        }.getOrElse { emptyList() }
    }

    /** 待同步记录（synced=false）。 */
    fun pendingSync(context: Context): List<JkMileageRecord> =
        records(context).filter { !it.synced }

    /** 待删除墓碑（已同步后被本地删除的 record_id，等待上行）。 */
    fun pendingDeletes(context: Context): List<String> {
        val raw = sp(context).getString(KEY_PENDING_DELETES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrElse { emptyList() }
    }

    /** 未同步条数（记录 + 墓碑），驱动同步按钮可用性。 */
    fun unsyncedCount(context: Context): Int =
        pendingSync(context).size + pendingDeletes(context).size

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 追加一条记录（synced=false）。返回新记录。 */
    fun add(context: Context, odoKm: Float, cycleAh: Float, volt: Float): JkMileageRecord {
        val list = records(context).toMutableList()
        val rec = JkMileageRecord(
            id = "${System.currentTimeMillis()}_${(100..999).random()}",
            ts = System.currentTimeMillis(),
            odoKm = odoKm,
            cycleAh = cycleAh,
            volt = volt,
            synced = false,
        )
        list.add(rec)
        if (list.size > MAX_RECORDS) list.subList(0, list.size - MAX_RECORDS).clear()
        saveRecords(context, list)
        return rec
    }

    /**
     * 删除一条记录。
     * @return true = 该记录已同步过、需上行删除墓碑（调用方把它加入待删除队列）；
     *         false = 仅删本地即可。
     */
    fun delete(context: Context, id: String): Boolean {
        val list = records(context).toMutableList()
        val target = list.firstOrNull { it.id == id } ?: return false
        list.removeAll { it.id == id }
        saveRecords(context, list)
        return target.synced
    }

    /** 同步成功后翻转标记（仅置 synced=true，不动其它字段）。 */
    fun markSynced(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        saveRecords(context, records(context).map {
            if (it.id in idSet) it.copy(synced = true) else it
        })
    }

    /** 墓碑上行成功后清出待删除队列。 */
    fun markDeletesSynced(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val remain = JSONArray()
        pendingDeletes(context).forEach { if (it !in idSet) remain.put(it) }
        sp(context).edit().putString(KEY_PENDING_DELETES, remain.toString()).apply()
    }

    /** 删除墓碑入队（删「已同步」行时调用）。 */
    fun enqueueDelete(context: Context, id: String) {
        val arr = JSONArray()
        pendingDeletes(context).forEach { arr.put(it) }
        arr.put(id)
        sp(context).edit().putString(KEY_PENDING_DELETES, arr.toString()).apply()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun saveRecords(context: Context, list: List<JkMileageRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("ts", r.ts)
                    .put("odo_km", r.odoKm.toDouble())
                    .put("cycle_ah", r.cycleAh.toDouble())
                    .put("volt", r.volt.toDouble())
                    .put("synced", r.synced)
            )
        }
        sp(context).edit().putString(KEY_RECORDS, arr.toString()).apply()
    }
}
