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
}
