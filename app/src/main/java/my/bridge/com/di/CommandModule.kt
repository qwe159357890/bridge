package my.bridge.com.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandExecutor
import my.bridge.com.core.network.ws.CommandResult
import my.bridge.com.core.storage.DeviceStore
import android.os.Build
import timber.log.Timber
import javax.inject.Singleton

/**
 * 命令执行器装配。
 *
 * 注册服务端可下发的命令（与服务端 ALLOWED_COMMANDS 白名单一致）：
 * - upload_log：采集诊断日志并上传到后端。
 */
@Module
@InstallIn(SingletonComponent::class)
object CommandModule {

    @Provides
    @Singleton
    fun provideCommandExecutors(
        @ApplicationContext context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
    ): Map<String, CommandExecutor> = mapOf(
        Command.UPLOAD_LOG to CommandExecutor { command ->
            executeUploadLog(context, deviceStore, apiClient, command)
        },
    )

    private suspend fun executeUploadLog(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        return try {
            val deviceId = deviceStore.deviceId()
            val content = DiagLog.dump(context)
            val deviceName = deviceStore.deviceName() ?: deviceId
            val appVersion = DiagLog.appVersionName(context)
            val message = apiClient.uploadLog(
                deviceId = deviceId,
                content = content,
                deviceName = deviceName,
                appVersion = appVersion,
            )
            Timber.i("命令 upload_log 上传成功：$message")
            CommandResult(command.commandId, command.command, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 upload_log 上传失败")
            CommandResult(command.commandId, command.command, false, "上传失败：${e.message}")
        }
    }
}
