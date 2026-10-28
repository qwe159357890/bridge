package my.bridge.com.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import timber.log.Timber
import java.io.File

/**
 * Bridge 内部短信已读映射（v0.1.57，2026-10-28 拍板）。
 *
 * 背景：ColorOS 等定制 SmsProvider 对第三方 update 系统短信库普遍拒绝
 * （批量 selection update 返回 0 行、逐条单行 URI 也无效，v0.1.56 三级回退
 * 实测仍失败）——写系统库这条路在部分 ROM 上就是堵死的。
 *
 * 方案：**不依赖系统库的 read 列**。Bridge 自己维护一份
 * 「_id → 已读覆盖」映射（本地 JSON 持久化），展示层把系统 read 值与映射
 * 叠加（映射优先），使标记已读/未读在 Bridge 界面内完全生效，且**不再要求
 * 默认短信应用身份**。原生短信应用的已读状态不受影响（各管各的）。
 *
 * - 映射按短信 _id 记录（系统库主键，稳定）；
 * - 双集合：readIds（强制已读）/ unreadIds（强制未读），标记时互斥迁移；
 * - 容量上限 [MAX_IDS] 条（超出按最旧淘汰），防止无限膨胀；
 * - 全部调用方（内置短信页 UI / 管理端 sms 命令）统一经
 *   [SmsRepository.setReadState] 落到本存储。
 */
object SmsReadStore {

    private const val MAX_IDS = 20000
    private const val FILE_NAME = "sms_read_overrides.json"

    private val readIds = LinkedHashSet<String>()
    private val unreadIds = LinkedHashSet<String>()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (!f.exists()) return
            val obj = JsonParser.parseString(f.readText()).asJsonObject
            obj.getAsJsonArray("read")?.forEach { readIds.add(it.asString) }
            obj.getAsJsonArray("unread")?.forEach { unreadIds.add(it.asString) }
            Timber.i("已读映射加载：已读 %d 条 / 强制未读 %d 条", readIds.size, unreadIds.size)
        }.onFailure { Timber.w(it, "已读映射加载失败（按空映射）") }
    }

    @Synchronized
    private fun persist(context: Context) {
        runCatching {
            val obj = JsonObject().apply {
                add("read", JsonArray().apply { readIds.forEach { add(it) } })
                add("unread", JsonArray().apply { unreadIds.forEach { add(it) } })
            }
            file(context).writeText(obj.toString())
        }.onFailure { Timber.w(it, "已读映射持久化失败") }
    }

    private fun trim(set: MutableSet<String>) {
        val it = set.iterator()
        while (set.size > MAX_IDS && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    /**
     * 记录覆盖标记：把 [ids] 标为已读（read=true）或未读（false）。
     * @return 实际记录的条数（幂等去重后仍按入参数计）
     */
    @Synchronized
    fun mark(context: Context, ids: Collection<String>, read: Boolean): Int {
        if (ids.isEmpty()) return 0
        ensureLoaded(context)
        for (id in ids) {
            if (id.isBlank()) continue
            if (read) {
                unreadIds.remove(id)
                readIds.add(id)
            } else {
                readIds.remove(id)
                unreadIds.add(id)
            }
        }
        trim(readIds)
        trim(unreadIds)
        persist(context)
        return ids.size
    }

    /** 叠加映射后的生效已读状态（映射优先于系统库 read 列）。 */
    @Synchronized
    fun isRead(systemRead: Boolean, id: String): Boolean =
        if (id in unreadIds) false else (systemRead || id in readIds)

    /** 展示前预热（确保映射已从磁盘加载）。 */
    @Synchronized
    fun warmUp(context: Context) {
        ensureLoaded(context)
    }
}
