package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
     * 卡来源两层解析（都拿不到时按未知 -1/""）：
     * ① 通话库的 ``sub_id`` 列（AOSP 5.1+ 隐藏列，多数 ROM 存在）→ 映射激活卡；
     * ② [CallLog.Calls.PHONE_ACCOUNT_ID]（PhoneAccountHandle 扁平化字符串）
     *    与 [android.telecom.TelecomManager.callCapablePhoneAccounts] 逐个比对。
     *
     * ROM 兼容：个别 ROM（实测一加 9 Plus / ColorOS）对未知列投影**直接抛
     * IllegalArgumentException**（「Invalid column sub_id」）而不是返回 -1 索引，
     * 因此先带 sub_id 查询，失败后去掉该列降级重查（卡来源仅剩 ② 路径）。
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

        // ① sub_id → 卡信息映射
        val subToSim = SimHelper.listSims(context).associateBy { it.subscriptionId }
        // ② PhoneAccountHandle → 卡槽序号映射（顺序≈卡槽，autox 同款假设）。
        //    只用 toString() 作键（flattenToString 非公开 API）；个别 ROM 对不上
        //    时按未知处理，主路径是 sub_id 列。
        val accountToSlot = buildMap {
            try {
                val tm = context.getSystemService(android.telecom.TelecomManager::class.java)
                val accounts = tm?.callCapablePhoneAccounts
                if (accounts != null) {
                    for (i in 0 until accounts.size) {
                        put(accounts[i].toString(), i)
                    }
                }
            } catch (_: Exception) {
                // 无权限/ROM 异常时仅靠 sub_id 路径
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
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        // 带 sub_id 查询；ROM 不认该列时降级重查（见方法注释）
        val cursor = try {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                baseProjection + "sub_id",
                selection, selectionArgs, order,
            )
        } catch (e: IllegalArgumentException) {
            Timber.w("通话库无 sub_id 列，降级为不含卡来源查询：%s", e.message)
            try {
                cr.query(CallLog.Calls.CONTENT_URI, baseProjection, selection, selectionArgs, order)
            } catch (e2: Exception) {
                Timber.e(e2, "通话记录查询失败（降级路径）")
                null
            }
        }

        val result = mutableListOf<CallLogEntry>()
        cursor?.use { c ->
            val subIdx = c.getColumnIndex("sub_id")
            val accountIdx = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            while (c.moveToNext() && result.size < limit) {
                // 卡槽：sub_id 映射优先，phone_account_id 比对兜底
                var sim: SimHelper.SimInfo? = null
                if (subIdx >= 0 && !c.isNull(subIdx)) {
                    sim = subToSim[c.getInt(subIdx)]
                }
                var slot = sim?.slotIndex
                if (slot == null && accountIdx >= 0) {
                    val acct = c.getString(accountIdx)
                    if (!acct.isNullOrBlank()) slot = accountToSlot[acct]
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
                            subToSim.values.firstOrNull { it.slotIndex == s }?.number
                        } ?: "",
                    )
                )
            }
        }
        return result
    }
}
