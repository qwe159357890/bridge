package my.bridge.com.core.network.api

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
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
}
