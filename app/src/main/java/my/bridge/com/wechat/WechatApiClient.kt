package my.bridge.com.wechat

import android.content.Context
import my.bridge.com.core.common.DiagLog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 微信（center-server 中心服务器）REST 客户端。
 *
 * 契约照 references/center-server/web-frontend/src/api：
 * - Base：`{server}/api`，鉴权 `Authorization: Bearer <api_key>`；
 * - 响应 `{code,message,data}`，`code != 0` 视为失败；
 * - 附件：`{server}/api/files/{objectName}?token={api_key}`。
 *
 * 与 Bridge 服务端（15160）完全无关：这是第五套连接体系，中心服务器零改动。
 */
class WechatApiClient(
    private val context: Context,
    /** 临时覆盖服务器地址（测试连接时用输入值，而不是已保存的）。 */
    private val urlOverride: String = "",
    /** 临时覆盖 API Key。 */
    private val keyOverride: String = "",
) {

    class ApiException(val status: Int, message: String) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var baseUrl: String = ""
    private var token: String = ""

    /** 每次请求前同步一次地址/密钥（用户可能在设置页改）。 */
    private fun sync() {
        baseUrl = urlOverride.ifBlank { WechatPrefs.serverUrl(context) }
        token = keyOverride.ifBlank { WechatPrefs.apiKey(context) }
        if (baseUrl.isBlank()) throw ApiException(0, "未配置中心服务器地址")
    }

    private fun url(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = (baseUrl + "/api/" + path.trimStart('/')).toHttpUrlOrNull()
            ?: throw ApiException(0, "服务器地址非法：$baseUrl")
        val b = builder.newBuilder()
        params.forEach { (k, v) -> if (v.isNotBlank()) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun request(url: HttpUrl, method: String, body: okhttp3.RequestBody? = null): Request {
        val b = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
        if (body == null) b.method(method, null) else b.method(method, body)
        return b.build()
    }

    /** 统一执行并拆信封；失败抛 [ApiException]。 */
    private fun execute(url: HttpUrl, method: String, body: okhttp3.RequestBody? = null): JSONObject {
        val resp = client.newCall(request(url, method, body)).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiException(it.code, "HTTP ${it.code}")
            val obj = runCatching { JSONObject(text) }.getOrElse { e ->
                throw ApiException(it.code, "响应不是 JSON：${text.take(80)}")
            }
            val code = obj.optInt("code", 0)
            if (code != 0) throw ApiException(code, obj.optString("message", "请求失败"))
            return obj
        }
    }

    private fun dataOf(obj: JSONObject): Any? =
        if (obj.has("data")) obj.get("data") else null

    // ------------------------------------------------------------------
    // 连接测试 / 代理 / 实例
    // ------------------------------------------------------------------

    /** 测试连接：GET /api/agents。 */
    fun testConnection(): Boolean = runCatching {
        sync()
        execute(url("agents"), "GET")
        true
    }.onFailure { DiagLog.w(TAG, "测试连接失败：${it.message}") }.getOrDefault(false)

    /** GET /api/agents → 代理与实例列表。 */
    fun agents(): List<WechatAgent> {
        sync()
        val arr = dataOf(execute(url("agents"), "GET")) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val instArr = o.optJSONArray("instances")
            val instances = (0 until (instArr?.length() ?: 0)).mapNotNull { j ->
                val io = instArr!!.optJSONObject(j) ?: return@mapNotNull null
                WechatInstance(
                    wxid = io.optString("wxid"),
                    accountNumber = io.optInt("account_number", 0),
                    nickname = io.optString("nickname"),
                    pid = io.optInt("pid", 0),
                    port = io.optInt("port", 0),
                )
            }
            WechatAgent(
                agentId = o.optString("agent_id"),
                agentName = o.optString("agent_name"),
                displayName = o.optString("display_name"),
                isConnected = o.optBoolean("is_connected", false),
                connectedAt = o.optString("connected_at"),
                lastPing = o.optString("last_ping"),
                instances = instances,
            )
        }
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /**
     * GET /api/messages/sessions —— **全局会话，无账号维度**（服务端 SQL 不查账号）。
     */
    fun sessions(limit: Int = 100, offset: Int = 0): List<WechatSession> {
        sync()
        val d = dataOf(execute(url("messages/sessions", mapOf(
            "limit" to limit.toString(), "offset" to offset.toString())), "GET"))
        val arr = (d as? JSONObject)?.optJSONArray("sessions")
            ?: d as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WechatSession(
                userWxid = o.optString("user_wxid"),
                userNickname = o.optString("user_nickname").ifBlank { o.optString("user_wxid") },
                lastMsg = o.optString("last_msg"),
                lastTime = o.optString("last_time"),
                updateTimestamp = o.optLong("update_timestamp", 0L),
                unreadCount = o.optInt("unread_count", 0),
                isPinned = o.optInt("is_pinned", 0) == 1,
                isMuted = o.optInt("is_muted", 0) == 1,
            )
        }
    }

    /** GET /api/messages/sessions/unread_total —— 非免打扰未读总和（服务端口径）。 */
    fun unreadTotal(): Int {
        sync()
        val d = dataOf(execute(url("messages/sessions/unread_total"), "GET"))
        return when (d) {
            is JSONObject -> d.optInt("total_unread", d.optInt("unread_total", 0))
            is Number -> d.toInt()
            else -> 0
        }
    }

    // ------------------------------------------------------------------
    // 消息
    // ------------------------------------------------------------------

    /** GET /api/messages?session_id=&account_wxid=&limit=&offset= */
    fun messages(sessionId: String, accountWxid: String = "", limit: Int = 50, offset: Int = 0): List<WechatMessage> {
        sync()
        val params = linkedMapOf("session_id" to sessionId, "limit" to limit.toString(), "offset" to offset.toString())
        if (accountWxid.isNotBlank()) params["account_wxid"] = accountWxid
        val d = dataOf(execute(url("messages", params), "GET"))
        val arr = (d as? JSONObject)?.optJSONArray("messages")
            ?: (d as? JSONObject)?.optJSONArray("list")
            ?: d as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WechatMessage(
                id = o.optLong("id", 0L),
                sessionId = o.optString("session_id", sessionId),
                accountWxid = o.optString("account_wxid"),
                msgId = o.optString("msg_id"),
                msgType = o.optInt("msg_type", WechatMsgType.TEXT),
                content = o.optString("content"),
                attachmentJson = o.opt("attachment")?.toString().orEmpty(),
                createTime = o.optString("create_time"),
                timestamp = o.optLong("timestamp", 0L),
                isSelf = o.optInt("is_self", 0) == 1,
                senderWxid = o.optString("sender_wxid"),
                senderNickname = o.optString("sender_nickname"),
            )
        }
    }

    // ------------------------------------------------------------------
    // 联系人
    // ------------------------------------------------------------------

    /** GET /api/contacts?account_wxid=&contact_type=&has_session=&limit=&offset= */
    fun contacts(accountWxid: String = "", filter: String = "all", limit: Int = 100, offset: Int = 0): List<WechatContact> {
        sync()
        val params = linkedMapOf("limit" to limit.toString(), "offset" to offset.toString())
        if (accountWxid.isNotBlank()) params["account_wxid"] = accountWxid
        when (filter) {
            "has_session" -> params["has_session"] = "true"
            "person" -> params["contact_type"] = "0"
            "group" -> params["contact_type"] = "1"
            "subscription" -> params["contact_type"] = "2"
        }
        val d = dataOf(execute(url("contacts", params), "GET"))
        val arr = (d as? JSONObject)?.optJSONArray("contacts")
            ?: (d as? JSONObject)?.optJSONArray("list")
            ?: d as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val wxid = o.optString("wxid")
            WechatContact(
                wxid = wxid,
                accountWxid = o.optString("account_wxid"),
                nickname = o.optString("nickname").ifBlank { wxid },
                contactType = o.optInt("contact_type", if (wxid.startsWith("gh_")) 2 else 0),
            )
        }
    }

    // ------------------------------------------------------------------
    // 会话状态变更
    // ------------------------------------------------------------------

    fun setPinned(sessionId: String, pinned: Boolean) {
        sync()
        post("messages/sessions/pin", JSONObject().put("session_id", sessionId).put("is_pinned", if (pinned) 1 else 0))
    }

    fun setMuted(sessionId: String, muted: Boolean) {
        sync()
        post("messages/sessions/mute", JSONObject().put("session_id", sessionId).put("is_muted", if (muted) 1 else 0))
    }

    /** DELETE /api/messages/{session_id}/{msg_id} —— 删除单条消息（服务端会广播给所有客户端）。 */
    fun deleteMessage(sessionId: String, msgId: String) {
        sync()
        execute(url("messages/${encode(sessionId)}/${encode(msgId)}"), "DELETE")
    }

    /** 已读：未读数归零（进入会话/点通知时调用）。 */
    fun markRead(sessionId: String) {
        sync()
        post("messages/sessions/unread", JSONObject().put("session_id", sessionId).put("unread_count", 0))
    }

    fun clearSessionMessages(sessionId: String) {
        sync()
        val u = url("messages/sessions/${encode(sessionId)}/messages")
        execute(u, "DELETE")
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** POST /api/wechat/send_text?wxid=&target_wxid=&content= */
    fun sendText(wxid: String, targetWxid: String, content: String) {
        sync()
        execute(url("wechat/send_text", mapOf(
            "wxid" to wxid, "target_wxid" to targetWxid, "content" to content)), "POST",
            "".toRequestBody(null))
    }

    /**
     * POST /api/wechat/upload_and_send（multipart：file / wxid / to_wxid / file_type）。
     * 手机端**只能**走这个接口——`send_image/send_file/send_video` 的参数是服务器上的文件路径。
     */
    fun uploadAndSend(wxid: String, targetWxid: String, fileName: String, mime: String, bytes: ByteArray) {
        sync()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("wxid", wxid)
            .addFormDataPart("to_wxid", targetWxid)
            .addFormDataPart("file_type", "auto")
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        // 上传接口超时更长（参考前端 timeout=120000）
        val call = client.newCall(request(url("wechat/upload_and_send"), "POST", body))
        call.execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "上传失败 HTTP ${r.code}")
            val obj = JSONObject(r.body?.string().orEmpty())
            val code = obj.optInt("code", 0)
            if (code != 0) throw ApiException(code, obj.optString("message", "上传失败"))
        }
    }

    /** 附件 URL：`{server}/api/files/{object}?token={api_key}`。 */
    fun attachmentUrl(objectName: String): String {
        val b = (WechatPrefs.serverUrl(context) + "/api/files/" + objectName.trimStart('/'))
            .toHttpUrlOrNull() ?: return ""
        return b.newBuilder().addQueryParameter("token", WechatPrefs.apiKey(context)).build().toString()
    }

    /**
     * 头像 URL：`GET {server}/api/contacts/avatar?wxid=`
     * （`web-frontend/src/utils/avatarManager.ts` 第 84 行；**用 Bearer 头鉴权，不带 token 参数**）。
     */
    fun avatarUrl(wxid: String): String {
        if (wxid.isBlank()) return ""
        val b = (WechatPrefs.serverUrl(context) + "/api/contacts/avatar")
            .toHttpUrlOrNull() ?: return ""
        return b.newBuilder().addQueryParameter("wxid", wxid).build().toString()
    }

    /** 当前 API Key（头像请求要带 Bearer 头）。 */
    fun apiKeyValue(): String = WechatPrefs.apiKey(context)

    private fun post(path: String, body: JSONObject) {
        execute(url(path), "POST", body.toString().toRequestBody(JSON_MEDIA))
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "微信"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
