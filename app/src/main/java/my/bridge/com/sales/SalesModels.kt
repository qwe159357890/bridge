package my.bridge.com.sales

import org.json.JSONObject

/** 销售页产品类型，key 与参考服务端数据库表/接口参数一致。 */
enum class SalesProductType(val key: String, val label: String) {
    SPONGE("sponge", "海绵"),
    CRANK("crank", "手摇器"),
    PEACH_BRANCH("peach_branch", "桃树枝");

    companion object {
        fun fromKey(key: String): SalesProductType = entries.firstOrNull { it.key == key } ?: SPONGE
    }
}

data class SalesUser(
    val username: String = "",
    val display_name: String = "",
    val role: String = "operator",
    val editable_columns: List<String>? = null,
    val allowed_product_types: List<String>? = null,
    val visible_columns: List<String>? = null,
) {
    val displayName: String get() = display_name.ifBlank { username }
    val isManager: Boolean get() = role == "admin" || role == "manager"
    val canCreate: Boolean get() = isManager
    val canDelete: Boolean get() = isManager
    val canEditAll: Boolean get() = isManager
    val roleLabel: String get() = when (role) {
        "admin" -> "管理员"
        "manager" -> "经理"
        else -> "操作员"
    }
    val roleLevel: Int get() = when (role) {
        "admin" -> 3
        "manager" -> 2
        else -> 1
    }

    fun allowedTypes(): List<SalesProductType> = if (isManager) {
        SalesProductType.entries
    } else {
        SalesProductType.entries.filter { allowed_product_types?.contains(it.key) == true }
    }

    fun canSeeColumn(prop: String): Boolean = isManager || visible_columns?.contains(prop) == true
    fun canEditColumn(prop: String): Boolean = isManager || editable_columns?.contains(prop) == true
}

/** 15 列售后记录。字段名保持服务端契约，避免 Gson/JSON 双向映射时改名。 */
data class SalesRecord(
    val id: Int = 0,
    val serial: String = "",
    val shop_serial: String = "",
    val date: String = "",
    val receiver: String = "",
    val phone: String = "",
    val goods: String = "",
    val order_no: String = "",
    val send_express: String = "",
    val return_express: String = "",
    val return_express_company: String = "",
    val return_freight: Double = 0.0,
    val signed: String = "未签收",
    val picked_up: Int = 0,
    val remarks: String = "",
    val created_at: String = "",
    val row_color: String = "",
) {
    fun value(prop: String): String = when (prop) {
        "serial" -> serial
        "shop_serial" -> shop_serial
        "date" -> date
        "receiver" -> receiver
        "phone" -> phone
        "goods" -> goods
        "order_no" -> order_no
        "send_express" -> send_express
        "return_express" -> return_express
        "return_express_company" -> return_express_company
        "return_freight" -> if (return_freight == 0.0) "" else return_freight.toString().removeSuffix(".0")
        "signed" -> signed
        "picked_up" -> if (picked_up == 1) "已取件" else "未取件"
        "remarks" -> remarks
        "created_at" -> created_at
        "row_color" -> row_color
        else -> ""
    }

    /** PATCH cell 的 value 永远是字符串；picked_up/return_freight 的显示与存储在此分开。 */
    fun withCell(prop: String, value: String): SalesRecord = when (prop) {
        "serial" -> copy(serial = value)
        "shop_serial" -> copy(shop_serial = value)
        "date" -> copy(date = value)
        "receiver" -> copy(receiver = value)
        "phone" -> copy(phone = value)
        "goods" -> copy(goods = value)
        "order_no" -> copy(order_no = value)
        "send_express" -> copy(send_express = value)
        "return_express" -> copy(return_express = value)
        "return_express_company" -> copy(return_express_company = value)
        "return_freight" -> copy(return_freight = value.toDoubleOrNull() ?: 0.0)
        "signed" -> copy(signed = value)
        "picked_up" -> copy(picked_up = if (value == "1" || value == "已取件") 1 else 0)
        "remarks" -> copy(remarks = value)
        "row_color" -> copy(row_color = value)
        else -> this
    }

    fun toRequestJson(productType: SalesProductType, includeProduct: Boolean): JSONObject = JSONObject().apply {
        if (includeProduct) put("product_type", productType.key)
        put("serial", serial)
        put("shop_serial", shop_serial)
        put("date", date)
        put("receiver", receiver)
        put("phone", phone)
        put("goods", goods)
        put("order_no", order_no)
        put("send_express", send_express)
        put("return_express", return_express)
        put("return_express_company", return_express_company)
        put("return_freight", return_freight)
        put("signed", signed)
        put("picked_up", picked_up)
        put("remarks", remarks)
        put("row_color", row_color)
    }
}

data class SalesColumn(
    val prop: String,
    val label: String,
    val width: Int,
    val mobileDefault: Boolean = false,
    val readingDefault: Boolean = false,
)

/** 列顺序/默认宽度照 AfterSalesPage.vue DEFAULT_COLUMNS；移动/阅读默认列照 APK 适配常量。 */
val SALES_COLUMNS = listOf(
    SalesColumn("serial", "序号", 100),
    SalesColumn("shop_serial", "店铺序号", 110),
    SalesColumn("date", "日期", 120),
    SalesColumn("receiver", "收货人", 110, mobileDefault = true, readingDefault = true),
    SalesColumn("phone", "电话", 120),
    SalesColumn("goods", "货物", 180, mobileDefault = true, readingDefault = true),
    SalesColumn("order_no", "订单号", 200),
    SalesColumn("send_express", "发货快递", 150),
    SalesColumn("return_express", "退货快递", 150, mobileDefault = true, readingDefault = true),
    SalesColumn("return_express_company", "退货快递公司", 110),
    SalesColumn("return_freight", "运费", 60),
    SalesColumn("signed", "签收", 80, readingDefault = true),
    SalesColumn("picked_up", "取件", 80, mobileDefault = true, readingDefault = true),
    SalesColumn("created_at", "创建时间", 160),
    SalesColumn("remarks", "备注", 150),
)

data class SalesListResult(
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 100,
    val records: List<SalesRecord> = emptyList(),
)

data class SalesFilters(
    val signed: String = "",
    val dateStart: String = "",
    val dateEnd: String = "",
    val hasReturnExpress: Boolean? = null,
    val shopSerial: String = "",
    val sortColumn: String = "",
    val sortOrder: String = "",
    val columnFilters: Map<String, String> = emptyMap(),
)

data class SalesOnlineDevice(
    val client_type: String = "",
    val os: String = "",
    val browser: String = "",
    val connected_at: String = "",
)

data class SalesOnlineUser(
    val username: String = "",
    val display_name: String = "",
    val role: String = "",
    val connected_at: String = "",
    val device_count: Int = 1,
    val devices: List<SalesOnlineDevice> = emptyList(),
)

data class SalesEditLock(
    val username: String = "",
    val display_name: String = "",
    val role: String = "",
    val locked_at: String = "",
)

data class SalesLockConflict(
    val lock_key: String = "",
    val locked_by: String = "",
    val locked_by_display_name: String = "",
    val role: String = "",
    val message: String = "",
)

data class SalesNotification(
    val id: Long = 0L,
    val username: String = "",
    val display_name: String = "",
    val action: String = "",
    val product_type: String = "",
    val record_id: Int? = null,
    val detail: String = "",
) {
    fun message(): String {
        val actionText = when (action) {
            "create" -> "新增了一条记录"
            "update" -> "更新了一条记录"
            "delete" -> "删除了一条记录"
            "sign_change" -> "切换了签收状态"
            "picked_up_change" -> "切换了取件状态"
            "color_mark" -> "标记了行颜色"
            else -> detail.ifBlank { "执行了操作" }
        }
        return "${display_name.ifBlank { username }} $actionText"
    }
}

data class ExpressTrace(val time: String = "", val desc: String = "")
data class ExpressResult(
    val success: Boolean = false,
    val message: String = "",
    val traces: List<ExpressTrace> = emptyList(),
    val company: String = "",
    val state: String = "",
)

data class CookieStatus(
    val has_cookie: Boolean = false,
    val has_bduss: Boolean = false,
    val cookie_count: Int = 0,
    val cookie_keys: List<String> = emptyList(),
)

data class SalesNotice(val id: Long, val text: String, val isError: Boolean = false)

data class SalesSearchAllResult(
    val product_type: String,
    val product_name: String,
    val total: Int,
)

data class SalesExportFile(
    val bytes: ByteArray,
    val filename: String,
) {
    override fun equals(other: Any?): Boolean = other is SalesExportFile && filename == other.filename && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * filename.hashCode() + bytes.contentHashCode()
}
