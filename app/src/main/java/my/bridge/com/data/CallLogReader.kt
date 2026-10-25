package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

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
     * @param typeParam catalog 的 call_type：all/incoming/outgoing/missed
     * @param limit 最多返回条数（5–1000）
     */
    fun read(context: Context, typeParam: String, limit: Int): List<CallLogEntry> {
        val cr = context.contentResolver
        val typeFilter = typeCode(typeParam)
        val selection = if (typeFilter != null) "type = ?" else null
        val selectionArgs = if (typeFilter != null) arrayOf(typeFilter.toString()) else null

        val result = mutableListOf<CallLogEntry>()
        cr.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
                CallLog.Calls.GEOCODED_LOCATION,
            ),
            selection, selectionArgs,
            CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            while (c.moveToNext() && result.size < limit) {
                result.add(
                    CallLogEntry(
                        key = c.getString(0) ?: "",
                        number = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        type = c.getInt(3),
                        duration = c.getInt(4),
                        dateMs = c.getLong(5),
                        location = c.getString(6) ?: "",
                    )
                )
            }
        }
        return result
    }
}
