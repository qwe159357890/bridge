package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * 通讯录读取器（get_contacts 命令的数据源，只读不写）。
 *
 * 借鉴 references/autoxClient autoxTcpServer/modules/contacts.js 的两级查询
 * 优化：一次性把「全部电话」和「全部邮箱」按 contact_id 分组为 Map，再与
 * 联系人主表合并——避免逐联系人子查询的 N+1 问题（500 人通讯录从数千次
 * 查询降为 3 次）。
 *
 * 权限：Manifest 已声明 READ_CONTACTS，应用启动时随批量权限申请授予；
 * 此处校验运行时授权，未授权时执行器直接回执失败。
 */
object ContactsReader {

    /** 一个联系人（号码/邮箱为原始字符串列表）。 */
    data class ContactEntry(
        val key: String,
        val name: String,
        val phones: List<String>,
        val emails: List<String>,
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 读取全部联系人。
     *
     * @param onlyWithPhones true 时过滤掉没有任何号码的联系人
     * @param limit 最多返回条数
     */
    fun read(context: Context, onlyWithPhones: Boolean, limit: Int): List<ContactEntry> {
        val cr = context.contentResolver

        // 1. 联系人主表（按名字排序）
        data class Base(val id: String, val name: String, val hasPhone: Boolean)
        val bases = mutableListOf<Base>()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ),
            null, null,
            "display_name ASC",
        )?.use { c ->
            while (c.moveToNext() && bases.size < limit) {
                bases.add(
                    Base(
                        id = c.getString(0),
                        name = c.getString(1) ?: "",
                        hasPhone = c.getInt(2) > 0,
                    )
                )
            }
        }

        // 2. 全部电话，按 contact_id 分组
        val phonesById = mutableMapOf<String, MutableList<String>>()
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val number = c.getString(1)?.trim().orEmpty()
                if (number.isNotEmpty()) {
                    phonesById.getOrPut(id) { mutableListOf() }.add(number)
                }
            }
        }

        // 3. 全部邮箱，按 contact_id 分组
        val emailsById = mutableMapOf<String, MutableList<String>>()
        cr.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val address = c.getString(1)?.trim().orEmpty()
                if (address.isNotEmpty()) {
                    emailsById.getOrPut(id) { mutableListOf() }.add(address)
                }
            }
        }

        // 4. 合并
        val result = mutableListOf<ContactEntry>()
        for (b in bases) {
            val phones = phonesById[b.id].orEmpty()
            if (onlyWithPhones && phones.isEmpty()) continue
            result.add(ContactEntry(key = b.id, name = b.name, phones = phones, emails = emailsById[b.id].orEmpty()))
            if (result.size >= limit) break
        }
        return result
    }
}
