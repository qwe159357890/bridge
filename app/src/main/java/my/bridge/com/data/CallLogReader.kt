package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 通话记录读取器（get_call_log 命令的数据源，只读）。
 *
 * 查询 [CallLog.Calls]（content://call_log/calls），带联系人名缓存列与
 * 归属地列（设备端已解析好的直接复用）。借鉴 references/autoxClient
 * modules/phone.js 的类型映射与字段选择。
 *
 * 权限：READ_CALL_LOG 运行时授权（启动批量申请）。
 */
object CallLogReader {

    /** 一条通话记录。 */
    data class CallLogEntry(
        val key: String,
        val number: String,
        val name: String,     // 设备端缓存的联系人名（未匹配为空）
        val type: Int,        // 1呼入 2呼出 3未接 4语音信箱 5拒接 6已拦截
        val duration: Int,    // 通话时长（秒）
        val dateMs: Long,
        val location: String, // 归属地（设备端 geocoded）
        val simSlot: Int,     // 经手的卡槽序号（0 基）；-1 = 未知
        val simNumber: String, // 该卡本机号码（运营商未写入时为空）
    )

    /** catalog 的 call_type 参数 → Android type 值（all 返回 null = 不过滤）。 */
    fun typeCode(param: String): Int? = when (param) {
        "incoming" -> CallLog.Calls.INCOMING_TYPE   // 1
        "outgoing" -> CallLog.Calls.OUTGOING_TYPE   // 2
        "missed" -> CallLog.Calls.MISSED_TYPE       // 3
        else -> null // all
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 读取通话记录（时间倒序）。
     *
     * 卡来源四层解析（都拿不到时按未知 -1/""）：
     * ① 通话库 ``sub_id`` 列（AOSP 5.1+ 隐藏列，多数 ROM 存在）→ 映射激活卡；
     * ② ``PHONE_ACCOUNT_COMPONENT_NAME`` + ``PHONE_ACCOUNT_ID`` 两列拼装
     *    「组件名/id」与 [android.telecom.TelecomManager.callCapablePhoneAccounts]
     *    逐个比对——注意 ACCOUNT_ID 列只存 handle 的 **id 部分**，必须与
     *    COMPONENT_NAME 组合才是完整键（v0.1.29 修正：此前拿完整 toString()
     *    对 id 部分，永远匹配不上）；
     * ③ ``PHONE_ACCOUNT_ADDRESS``（该通话使用的线路地址 ≈ 本机号码）与
     *    SIM 卡号码做尾号比对（兼容 +86 国码前缀差异）；
     * ④ 单卡兜底：设备只有一张激活卡时，通话必然都经它（无权限/读不到卡
     *    时不猜测）。
     *
     * ROM 兼容：个别 ROM（实测一加 9 Plus / ColorOS）对未知列投影**直接抛
     * IllegalArgumentException**（「Invalid column sub_id」）而不是返回 -1 索引，
     * 因此投影分三级尝试：全量（含 sub_id）→ 去 sub_id → 最小基础列。
     *
     * @param typeParam catalog 的 call_type：all/incoming/outgoing/missed
     * @param limit 最多返回条数（5–1000）
     */
    fun read(context: Context, typeParam: String, limit: Int): List<CallLogEntry> {
        val cr = context.contentResolver
        val typeFilter = typeCode(typeParam)
        val selection = if (typeFilter != null) "type = ?" else null
        val selectionArgs = if (typeFilter != null) arrayOf(typeFilter.toString()) else null
        val order = CallLog.Calls.DATE + " DESC"

        val sims = SimHelper.listSims(context)
        val subToSim = sims.associateBy { it.subscriptionId }

        // 「组件名/id」→ 卡槽映射（与通话库 COMPONENT_NAME + ACCOUNT_ID 对齐）
        val handleToSlot = buildMap {
            try {
                val tm = context.getSystemService(android.telecom.TelecomManager::class.java)
                val accounts = tm?.callCapablePhoneAccounts
                if (accounts != null) {
                    for (i in accounts.indices) {
                        val h = accounts[i]
                        put("${h.componentName.flattenToString()}/${h.id}", i)
                    }
                }
            } catch (_: Exception) {
                // 无权限/ROM 异常时走后续兜底层
            }
        }

        val baseProjection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
            CallLog.Calls.GEOCODED_LOCATION,
        )
        // 三级投影尝试：ROM 不认某列时逐级降级（见方法注释）
        val fullProjection = baseProjection + arrayOf(
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ADDRESS,
            "sub_id", // AOSP 隐藏列，部分 ROM 无
        )
        val noSubProjection = baseProjection + arrayOf(
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ADDRESS,
        )

        val cursor: Cursor? = run {
            var lastErr: Exception? = null
            for ((label, proj) in listOf("全量(含sub_id)" to fullProjection, "去sub_id" to noSubProjection, "最小基础列" to baseProjection)) {
                try {
                    return@run cr.query(CallLog.Calls.CONTENT_URI, proj, selection, selectionArgs, order)
                } catch (e: IllegalArgumentException) {
                    Timber.w("通话库投影（%s）不被支持，降级：%s", label, e.message)
                    lastErr = e
                }
            }
            Timber.e(lastErr ?: IllegalStateException("unknown"), "通话记录查询全部投影均失败")
            null
        }

        val result = mutableListOf<CallLogEntry>()
        cursor?.use { c ->
            val subIdx = c.getColumnIndex("sub_id")
            val acctIdIdx = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            val compIdx = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)
            val addrIdx = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ADDRESS)
            while (c.moveToNext() && result.size < limit) {
                var slot: Int? = null
                // ① sub_id → subscriptionId 映射
                if (subIdx >= 0 && !c.isNull(subIdx)) {
                    slot = subToSim[c.getInt(subIdx)]?.slotIndex
                }
                // ② 组件名 + id 精确匹配（完整键）
                if (slot == null && compIdx >= 0) {
                    val comp = c.getString(compIdx)
                    val acctId = if (acctIdIdx >= 0) c.getString(acctIdIdx) else null
                    if (!comp.isNullOrBlank() && !acctId.isNullOrBlank()) {
                        slot = handleToSlot["$comp/$acctId"]
                    }
                }
                // ③ 线路地址（本机号码）尾号比对（兼容 +86 前缀）
                if (slot == null && addrIdx >= 0) {
                    val addr = normalizeForMatch(c.getString(addrIdx))
                    if (addr.length >= 5) {
                        slot = sims.firstOrNull { s ->
                            val n = normalizeForMatch(s.number)
                            n.length >= 5 && (n.endsWith(addr) || addr.endsWith(n))
                        }?.slotIndex
                    }
                }
                // ④ 单卡兜底：只有一张激活卡时通话必然经它
                if (slot == null && sims.size == 1) {
                    slot = sims[0].slotIndex
                }
                result.add(
                    CallLogEntry(
                        key = c.getString(0) ?: "",
                        number = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        type = c.getInt(3),
                        duration = c.getInt(4),
                        dateMs = c.getLong(5),
                        location = c.getString(6) ?: "",
                        simSlot = slot ?: -1,
                        simNumber = slot?.let { s ->
                            sims.firstOrNull { it.slotIndex == s }?.number
                        } ?: "",
                    )
                )
            }
        }
        return result
    }

    /** 号码比对清洗：只留数字（去掉 +/空格/连字符），使「+86138…」与「138…」可尾号互配。 */
    private fun normalizeForMatch(raw: String?): String =
        raw?.filter { it.isDigit() } ?: ""
}
