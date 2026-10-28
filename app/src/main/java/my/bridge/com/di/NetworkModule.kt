package my.bridge.com.di

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.bridge.com.core.network.NetworkClientFactory
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeConnectionManager
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.core.network.ws.CommandExecutor
import my.bridge.com.core.network.ws.ServerAddress
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.keepalive.KeepAliveService
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Singleton

/**
 * 网络层依赖装配。
 *
 * 提供 OkHttp、Gson、REST 客户端、WebSocket 客户端、连接编排器单例。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = NetworkClientFactory.create(debug = false)

    @Provides
    @Singleton
    fun provideBridgeApiClient(
        client: OkHttpClient,
        gson: Gson,
    ): BridgeApiClient = BridgeApiClient(okHttpClient = client, gson = gson)

    @Provides
    @Singleton
    fun provideBridgeWebSocketClient(
        client: OkHttpClient,
        gson: Gson,
        identity: DeviceIdentityProvider,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        commandExecutors: Map<String, @JvmSuppressWildcards CommandExecutor>,
    ): BridgeWebSocketClient =
        BridgeWebSocketClient(
            okHttpClient = client,
            gson = gson,
            deviceIdProvider = { identity.deviceId },
            tokenProvider = { identity.token },
            deviceNameProvider = { identity.deviceName },
            // 上报「保活服务运行时长」，与 App「关于」页的保活运行时长口径一致
            uptimeSecondsProvider = {
                val startedAt = KeepAliveService.startedAtMillis()
                if (startedAt > 0L) {
                    (System.currentTimeMillis() - startedAt) / 1000
                } else {
                    0L
                }
            },
            // 上报本机全部 IPv4（供服务端 adb 匹配任一命中 + 后台展示供用户交叉确认）
            localIpsProvider = ::collectLocalIpv4Addresses,
            // 上报客户端版本号：服务端日志/后台可直接确认设备运行版本，避免版本怀疑
            appVersionProvider = { my.bridge.com.BuildConfig.VERSION_NAME },
            // 每次连接成功、发 hello 前先注册设备（幂等）：服务端重启后注册表清空，
            // 重连时借此自动补注册，保证「在线」与「已注册」一致。
            // 直接读 deviceStore（而非 identity），避免 provider 惰性初始化时序问题。
            onBeforeHello = {
                try {
                    val deviceId = deviceStore.deviceId().also { identity.deviceId = it }
                    val deviceName = deviceStore.deviceName().also { identity.deviceName = it }
                    val token = apiClient.registerDevice(deviceId, deviceName)
                    deviceStore.saveToken(token)
                    identity.token = token
                } catch (e: Exception) {
                    // 注册失败不阻断连接（auth 关闭时允许无 token；重连会再次尝试）
                    android.util.Log.w("NetworkModule", "注册设备失败（继续握手）：${e.message}")
                }
            },
            listener = null,
            commandExecutors = commandExecutors,
        )

    @Provides
    @Singleton
    fun provideBridgeConnectionManager(
        webSocketClient: BridgeWebSocketClient,
        deviceStore: DeviceStore,
        identity: DeviceIdentityProvider,
    ): BridgeConnectionManager =
        BridgeConnectionManager(
            webSocketClient = webSocketClient,
            deviceIdProvider = {
                deviceStore.deviceId().also { identity.deviceId = it }
            },
            loadServerAddress = {
                val host = deviceStore.serverHost()
                val port = deviceStore.serverPort()
                if (host != null || port != null) {
                    ServerAddress.set(
                        host ?: ServerAddress.host(),
                        port ?: ServerAddress.port(),
                    )
                }
            },
        )
}

/**
 * 收集本机全部 IPv4 地址（去环回、去重，按接口名排序保证稳定）。
 *
 * 一台手机可能同时有多个地址（WiFi、蜂窝、热点、虚拟接口等），全部上报供
 * 服务端 adb 匹配「任一命中」与后台展示「用户交叉确认」。任何异常只记日志返回空表，
 * 绝不阻断握手。
 */
private fun collectLocalIpv4Addresses(): List<String> = try {
    val entries = mutableListOf<Pair<String, String>>() // iface -> ip
    for (nif in NetworkInterface.getNetworkInterfaces()) {
        for (addr in nif.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                addr.hostAddress?.let { entries += nif.name to it }
            }
        }
    }
    // WiFi 接口优先展示，其余按接口名稳定排序
    entries.sortedWith(
        compareBy({ if (it.first.startsWith("wlan")) 0 else 1 }, { it.first }),
    ).map { it.second }.distinct()
} catch (e: Exception) {
    android.util.Log.w("NetworkModule", "收集本机 IP 失败（忽略）：${e.message}")
    emptyList()
}
