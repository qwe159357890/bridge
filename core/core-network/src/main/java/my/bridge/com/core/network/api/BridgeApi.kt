package my.bridge.com.core.network.api

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Bridge 后端 REST 接口（Retrofit）。
 *
 * 客户端对接的 REST 能力（管理侧接口不在此列）：
 *   - 设备注册（POST /api/devices/register）→ device_id + token
 *   - 日志上传（POST /api/logs）→ code=0
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

    /** 注册设备，返回 device_id + token。 */
    @POST("api/devices/register")
    suspend fun registerDevice(@Body body: RegisterRequest): RegisterResponse

    /** 上传诊断日志。 */
    @POST("api/logs")
    suspend fun uploadLog(@Body body: LogUploadRequest): LogUploadResponse
}
