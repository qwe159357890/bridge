package my.bridge.com.core.network.api

import com.google.gson.Gson
import my.bridge.com.core.network.ws.ServerAddress
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Bridge 后端 REST 客户端封装。
 *
 * 基于 Retrofit 暴露 [BridgeApi]，供设备注册、日志上传等调用。
 * 实例经 Hilt 注入（见 app 层 NetworkModule）。
 *
 * 服务地址可配：每次调用按当前 [ServerAddress.restBaseUrl] 动态构建 Retrofit，
 * 因此用户修改服务地址后无需重建本对象即可生效（Retrofit 构建轻量，
 * 重资源 OkHttpClient 复用）。
 */
class BridgeApiClient(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) {

    /** 按当前服务地址构建 Retrofit 并创建 API 接口。 */
    private fun api(): BridgeApi =
        Retrofit.Builder()
            .baseUrl(ServerAddress.restBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(BridgeApi::class.java)

    /** 注册设备，返回 token（失败抛异常，由调用方处理）。 */
    suspend fun registerDevice(deviceId: String, deviceName: String? = null): String =
        api().registerDevice(BridgeApi.RegisterRequest(device_id = deviceId, device_name = deviceName)).token

    /** 上传诊断日志，返回服务端 message（失败抛异常，由调用方处理）。 */
    suspend fun uploadLog(
        deviceId: String,
        content: String,
        deviceName: String = "",
        appVersion: String = "",
    ): String = api().uploadLog(
        BridgeApi.LogUploadRequest(
            device_id = deviceId,
            content = content,
            device_name = deviceName,
            app_version = appVersion,
        )
    ).message

    /**
     * 下载服务器共享目录的音乐文件（play_music 裸文件名音源）。
     * 404 等失败抛异常（HttpException，由调用方区分处理），调用方负责关闭响应体。
     */
    suspend fun downloadMusic(
        filename: String,
        deviceId: String,
        token: String?,
    ): ResponseBody = api().downloadMusic(filename, deviceId, token)

    /**
     * 上报一次位置（get_location 成功后 fire-and-forget）。
     * 失败抛异常，调用方应静默处理（存档不影响命令回执）。
     */
    suspend fun reportLocation(
        deviceId: String,
        token: String?,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        provider: String,
        altitude: Double?,
        speed: Double?,
        fixTimeMs: Long,
    ): String = api().reportLocation(
        deviceId,
        token,
        BridgeApi.LocationReportRequest(
            device_id = deviceId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            provider = provider,
            altitude = altitude,
            speed = speed,
            fix_time_ms = fixTimeMs,
        ),
    ).action

    /**
     * 上报通讯录快照（get_contacts）。失败抛异常，由调用方决定回执语义
     * （通讯录是命令主产物，上报失败应在回执中注明）。
     */
    suspend fun reportContacts(
        deviceId: String,
        token: String?,
        contacts: List<BridgeApi.ContactItem>,
    ): Int = api().reportContacts(
        deviceId,
        token,
        BridgeApi.ContactsReportRequest(contacts = contacts),
    ).total

    /**
     * 上报一批短信（get_sms，服务端去重）。失败抛异常，由调用方处理。
     */
    suspend fun reportSmsBatch(
        deviceId: String,
        token: String?,
        messages: List<BridgeApi.SmsItem>,
    ): BridgeApi.SmsReportResponse = api().reportSms(
        deviceId,
        token,
        BridgeApi.SmsReportRequest(messages = messages),
    )

    /**
     * 上报通话记录快照（get_call_log）。失败抛异常，由调用方处理。
     */
    suspend fun reportCallLogs(
        deviceId: String,
        token: String?,
        records: List<BridgeApi.CallLogItem>,
    ): Int = api().reportCallLogs(
        deviceId,
        token,
        BridgeApi.CallLogReportRequest(records = records),
    ).total
}
