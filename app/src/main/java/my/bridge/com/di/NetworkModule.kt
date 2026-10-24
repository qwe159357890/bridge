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
            listener = null,
            commandExecutors = commandExecutors,
        )

    @Provides
    @Singleton
    fun provideBridgeConnectionManager(
        webSocketClient: BridgeWebSocketClient,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        identity: DeviceIdentityProvider,
    ): BridgeConnectionManager =
        BridgeConnectionManager(
            webSocketClient = webSocketClient,
            deviceIdProvider = {
                deviceStore.deviceId().also { identity.deviceId = it }
            },
            tokenProvider = {
                deviceStore.token().also { identity.token = it }
            },
            deviceNameProvider = {
                deviceStore.deviceName().also { identity.deviceName = it }
            },
            register = { deviceId, deviceName ->
                val token = apiClient.registerDevice(deviceId, deviceName)
                deviceStore.saveToken(token)
                identity.token = token
                token
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
