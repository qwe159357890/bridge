package my.bridge.com.di

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内设备标识缓存。
 *
 * 由 [NetworkModule] 中的连接编排器在启动时写入，
 * 供 WebSocket 客户端同步 provider 读取，避免「挂起函数初始化时序」与
 * 「WS 连接需同步取 device_id」之间的耦合。
 */
@Singleton
class DeviceIdentityProvider @Inject constructor() {
    @Volatile
    var deviceId: String = "pending"

    @Volatile
    var token: String? = null

    @Volatile
    var deviceName: String? = null
}
