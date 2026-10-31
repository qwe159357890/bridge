package my.bridge.com.sales

import android.content.Context
import android.os.Build
import my.bridge.com.BuildConfig
import my.bridge.com.core.common.DiagLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** 销售页 REST 客户端：严格按 references/SalesRecordRemote/server/routers 契约实现。 */
class SalesApiClient(context: Context) {

    class ApiException(
        val status: Int,
        message: String,
        val conflict: Boolean = false,
    ) : Exception(message)

    data class LoginResult(val token: String, val user: SalesUser)

    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 释放底层 HTTP 连接池与线程池（退出销售页省电）。
     *
     * 对照智能控制/快递扫码：它们的 client 在 stop() 里置 null 彻底释放；
     * 销售页此前只断开 WS，OkHttp 的 keep-alive 连接与 Dispatcher 线程一直常驻。
     * 退出页面时调用，空闲连接被立刻回收、线程池停止，进入页面时再懒重建。
     */
    fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    // ------------------------------------------------------------------
    // 认证
    // ------------------------------------------------------------------

    fun login(username: String, password: String, force: Boolean): LoginResult {
        val deviceInfo = JSONObject().apply {
            put("client_type", "apk")
            put("os", "Android " + Build.VERSION.RELEASE)
            put("browser", "Bridge")
            put("app_version", BuildConfig.VERSION_NAME)
        }
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("force", force)
            put("device_info", deviceInfo)
        }
        val text = execute("POST", "/api/auth/login", body = body, auth = false)
        val obj = JSONObject(text)
        val token = obj.optString("access_token")
        val userObj = obj.optJSONObject("user") ?: JSONObject()
        if (token.isBlank()) throw ApiException(0, "登录响应缺少 access_token")
        return LoginResult(token, parseUser(userObj))
    }

    fun logout() {
        execute("POST", "/api/auth/logout", body = JSONObject())
    }

    fun me(): SalesUser = parseUser(JSONObject(execute("GET", "/api/auth/me")))

    // ------------------------------------------------------------------
    // 售后记录
    // ------------------------------------------------------------------

    fun list(
        type: SalesProductType,
        page: Int,
        size: Int,
        keyword: String,
        filters: SalesFilters,
        columnFilters: Map<String, String>,
    ): SalesListResult {
        val params = linkedMapOf(
            "product_type" to type.key,
            "page" to page.toString(),
            "size" to size.toString(),
            "keyword" to keyword,
            "sort_column" to filters.sortColumn.ifBlank { "date" },
            "sort_order" to filters.sortOrder.ifBlank { "desc" },
            "signed" to filters.signed,
            "date_start" to filters.dateStart,
            "date_end" to filters.dateEnd,
            "shop_serial" to filters.shopSerial,
        )
        filters.hasReturnExpress?.let { params["has_return_express"] = it.toString() }
        val activeColumnFilters = columnFilters.filterValues { it.isNotBlank() }
        if (activeColumnFilters.isNotEmpty()) {
            params["column_filters"] = JSONObject(activeColumnFilters).toString()
        }
        val obj = JSONObject(execute("GET", "/api/after-sales", params = params))
        val records = obj.optJSONArray("records")?.let(::parseRecords) ?: emptyList()
        return SalesListResult(
            total = obj.optInt("total"),
            page = obj.optInt("page", page),
            size = obj.optInt("size", size),
            records = records,
        )
    }

    fun create(record: SalesRecord, type: SalesProductType): Int {
        val obj = JSONObject(execute("POST", "/api/after-sales", body = record.toRequestJson(type, includeProduct = true)))
        return obj.optInt("id")
    }

    fun update(record: SalesRecord, type: SalesProductType) {
        val body = record.toRequestJson(type, includeProduct = false)
        execute("PUT", "/api/after-sales/${record.id}", params = mapOf("product_type" to type.key), body = body)
    }

    fun updateCell(recordId: Int, type: SalesProductType, column: String, value: String) {
        val body = JSONObject().apply {
            put("product_type", type.key)
            put("column_name", column)
            put("value", value)
        }
        execute("PATCH", "/api/after-sales/$recordId/cell", body = body)
    }

    fun delete(recordId: Int, type: SalesProductType) {
        execute("DELETE", "/api/after-sales/$recordId", params = mapOf("product_type" to type.key))
    }

    fun shops(type: SalesProductType): List<String> {
        val obj = JSONObject(execute("GET", "/api/after-sales/shops/list", params = mapOf("product_type" to type.key)))
        return obj.optJSONArray("shops")?.toStringList() ?: emptyList()
    }

    fun searchAll(keyword: String, excludeType: SalesProductType): List<SalesSearchAllResult> {
        val obj = JSONObject(
            execute(
                "GET",
                "/api/after-sales/search/all",
                params = mapOf("keyword" to keyword, "exclude_type" to excludeType.key),
            ),
        )
        val arr = obj.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            SalesSearchAllResult(
                product_type = item.optString("product_type"),
                product_name = item.optString("product_name"),
                total = item.optInt("total"),
            )
        }
    }

    /** 新增表单智能填充：唯一命中返回销售记录字段表，否则 null。 */
    fun matchSales(type: SalesProductType, field: String, value: String): Map<String, String>? {
        if (value.isBlank()) return null
        val obj = JSONObject(
            execute(
                "GET",
                "/api/after-sales/match/sales",
                params = mapOf("product_type" to type.key, "field" to field, "value" to value.trim()),
            ),
        )
        if (!obj.optBoolean("success")) return null
        val record = obj.optJSONObject("record") ?: return null
        val result = mutableMapOf<String, String>()
        record.keys().forEach { key ->
            val v = record.opt(key)
            if (v != null && v != JSONObject.NULL) result[key] = v.toString()
        }
        return result
    }

    fun queryExpress(expressNo: String): ExpressResult {
        val obj = JSONObject(
            execute("GET", "/api/after-sales/express/query", params = mapOf("express_no" to expressNo.trim())),
        )
        val traces = obj.optJSONArray("data")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                ExpressTrace(time = item.optString("time"), desc = item.optString("desc"))
            }
        } ?: emptyList()
        return ExpressResult(
            success = obj.optBoolean("success"),
            message = obj.optString("message"),
            traces = traces,
            company = obj.optString("company"),
            state = obj.optString("state"),
        )
    }

    fun exportExcel(
        type: SalesProductType,
        keyword: String,
        filters: SalesFilters,
        visibleColumns: List<String>,
        recordsForColor: List<SalesRecord>,
        selectedIds: Set<Int>?,
        dateStart: String? = null,
        dateEnd: String? = null,
    ): SalesExportFile {
        val colorMap = JSONObject()
        recordsForColor.forEach { if (it.row_color.isNotBlank()) colorMap.put(it.id.toString(), it.row_color) }
        val params = linkedMapOf(
            "product_type" to type.key,
            "keyword" to keyword,
            "signed" to filters.signed,
            "date_start" to (dateStart ?: filters.dateStart),
            "date_end" to (dateEnd ?: filters.dateEnd),
            "shop_serial" to filters.shopSerial,
            "columns" to visibleColumns.filter { it != "row_color" }.joinToString(","),
            "color_map" to if (colorMap.length() > 0) colorMap.toString() else "",
            "record_ids" to selectedIds?.takeIf { it.isNotEmpty() }?.joinToString(",").orEmpty(),
        )
        filters.hasReturnExpress?.let { params["has_return_express"] = it.toString() }
        return executeBytes("GET", "/api/after-sales/export/excel", params = params)
    }

    // ------------------------------------------------------------------
    // 系统设置（百度 Cookie）
    // ------------------------------------------------------------------

    fun bdCookieStatus(): CookieStatus {
        val obj = JSONObject(execute("GET", "/api/settings/bd-cookie"))
        return CookieStatus(
            has_cookie = obj.optBoolean("has_cookie"),
            has_bduss = obj.optBoolean("has_bduss"),
            cookie_count = obj.optInt("cookie_count"),
            cookie_keys = obj.optJSONArray("cookie_keys")?.toStringList() ?: emptyList(),
        )
    }

    fun saveBdCookie(cookie: String) {
        execute("POST", "/api/settings/bd-cookie", body = JSONObject().put("cookie_str", cookie.trim()))
    }

    // ------------------------------------------------------------------
    // 底层请求
    // ------------------------------------------------------------------

    private fun buildUrl(path: String, params: Map<String, String?>): okhttp3.HttpUrl {
        val base = SalesPrefs.serverUrl(appContext)
        if (!SalesPrefs.isValidServerUrl(base)) {
            throw ApiException(0, "请先配置销售服务器地址（http:// 或 https:// 开头）")
        }
        val full = base.trimEnd('/') + path
        val httpUrl = full.toHttpUrlOrNull() ?: throw ApiException(0, "销售服务器地址无效")
        val builder = httpUrl.newBuilder()
        params.forEach { (key, value) ->
            if (!value.isNullOrBlank()) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    private fun execute(
        method: String,
        path: String,
        params: Map<String, String?> = emptyMap(),
        body: JSONObject? = null,
        auth: Boolean = true,
    ): String {
        val request = buildRequest(method, path, params, body, auth)
        DiagLog.i("销售页", "REST $method $path -> ${request.url}")
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            DiagLog.i("销售页", "REST $method $path 响应：code=${response.code} len=${text.length}")
            if (!response.isSuccessful) {
                throw toApiException(response.code, text)
            }
            return text
        }
    }

    private fun executeBytes(method: String, path: String, params: Map<String, String?>): SalesExportFile {
        val request = buildRequest(method, path, params, body = null, auth = true)
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                throw toApiException(response.code, text)
            }
            val disposition = response.header("Content-Disposition").orEmpty()
            return SalesExportFile(bytes = bytes, filename = parseFilename(disposition))
        }
    }

    private fun buildRequest(
        method: String,
        path: String,
        params: Map<String, String?>,
        body: JSONObject?,
        auth: Boolean,
    ): Request {
        val builder = Request.Builder().url(buildUrl(path, params))
        if (auth) {
            val token = SalesPrefs.token(appContext)
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            builder.header("X-Client-Id", SalesPrefs.clientId(appContext))
        }
        val requestBody = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: JSONObject().toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            "PUT" -> builder.put(requestBody ?: throw ApiException(0, "PUT 请求缺少 body"))
            "PATCH" -> builder.patch(requestBody ?: throw ApiException(0, "PATCH 请求缺少 body"))
            "DELETE" -> builder.delete()
            else -> throw ApiException(0, "不支持的请求方法：$method")
        }
        return builder.build()
    }

    private fun toApiException(status: Int, body: String): ApiException {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
        val message = when {
            detail.isNotBlank() -> detail
            body.isNotBlank() -> body.take(160)
            status == 401 -> "登录已过期，请重新登录"
            status == 403 -> "权限不足"
            status == 404 -> "资源不存在"
            status == 409 -> "该账号已在其他设备登录"
            status == 0 -> "网络连接失败"
            else -> "请求失败（HTTP $status）"
        }
        return ApiException(status, message, conflict = status == 409)
    }

    private fun parseFilename(disposition: String): String {
        // Content-Disposition: attachment; filename*=UTF-8''%E5%94%AE%E5%90%8E...
        val encoded = disposition.substringAfter("filename*=", "")
            .substringAfter("''", "")
            .trim().trim('"')
        return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }
            .getOrDefault("")
            .ifBlank { "售后记录.xlsx" }
    }

    private fun parseUser(obj: JSONObject): SalesUser = SalesUser(
        username = obj.optString("username"),
        display_name = obj.optString("display_name"),
        role = obj.optString("role", "operator"),
        editable_columns = obj.optJSONArray("editable_columns")?.toStringList(),
        allowed_product_types = obj.optJSONArray("allowed_product_types")?.toStringList(),
        visible_columns = obj.optJSONArray("visible_columns")?.toStringList(),
    )

    private fun parseRecords(arr: JSONArray): List<SalesRecord> =
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(::parseRecord)
        }

    private fun parseRecord(obj: JSONObject): SalesRecord = SalesRecord(
        id = obj.optInt("id"),
        serial = obj.optString("serial"),
        shop_serial = obj.optString("shop_serial"),
        date = obj.optString("date"),
        receiver = obj.optString("receiver"),
        phone = obj.optString("phone"),
        goods = obj.optString("goods"),
        order_no = obj.optString("order_no"),
        send_express = obj.optString("send_express"),
        return_express = obj.optString("return_express"),
        return_express_company = obj.optString("return_express_company"),
        return_freight = obj.optDouble("return_freight"),
        signed = obj.optString("signed", "未签收"),
        picked_up = obj.optInt("picked_up"),
        remarks = obj.optString("remarks"),
        created_at = obj.optString("created_at"),
        row_color = obj.optString("row_color"),
    )

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
