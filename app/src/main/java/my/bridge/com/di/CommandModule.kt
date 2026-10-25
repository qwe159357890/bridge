package my.bridge.com.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandExecutor
import my.bridge.com.core.network.ws.CommandResult
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.media.MusicPlayer
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Singleton

/**
 * 命令执行器装配。
 *
 * 注册服务端可下发的命令（与服务端 ALLOWED_COMMANDS 白名单一致）：
 * - upload_log：采集诊断日志并上传到后端。
 * - play_music：播放音乐（音源三形态，见 [executePlayMusic]）。
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
        Command.PLAY_MUSIC to CommandExecutor { command ->
            executePlayMusic(context, deviceStore, apiClient, command)
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

    /**
     * 执行 play_music：音源参数 url 按形态分三路。
     *
     * ① http(s):// 开头 → 远程 URL，MediaPlayer 直接流式播放（不缓存）；
     * ② / 开头 → 本地绝对路径，文件存在即直接播放（不存在则失败回执）；
     * ③ 其余（裸文件名，如 test.mp3）→ 客户端缓存目录优先：
     *    命中 → 直接播放；未命中 → 从服务器共享音乐目录下载（GET /api/music/{name}），
     *    持久化到缓存目录后播放，下次同名直接命中。
     *
     * 缓存目录 = App 私有目录 music/（getExternalFilesDir），无需存储权限，卸载自动清理。
     */
    private suspend fun executePlayMusic(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        val source = command.params.get("url")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
        if (source.isNullOrEmpty()) {
            return CommandResult(id, name, false, "缺少音源参数 url")
        }
        val loop = command.params.get("loop")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean ?: false

        return try {
            val resolved = resolveSource(context, deviceStore, apiClient, source)
            MusicPlayer.play(resolved, loop)
            Timber.i("命令 play_music 播放成功：$resolved（loop=$loop）")
            CommandResult(id, name, true, "播放中：$resolved")
        } catch (e: Exception) {
            Timber.e(e, "命令 play_music 播放失败：$source")
            CommandResult(id, name, false, "播放失败：${e.message}")
        }
    }

    /** 按音源形态分三路解析（见 [executePlayMusic] 注释），返回可直接交给 MediaPlayer 的地址。 */
    private suspend fun resolveSource(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        source: String,
    ): String = when {
        source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) -> source

        source.startsWith("/") -> {
            if (!File(source).isFile) {
                throw IllegalArgumentException("本地文件不存在：$source")
            }
            source
        }

        else -> resolveByFilename(context, deviceStore, apiClient, source)
    }

    /** 裸文件名：客户端缓存目录优先，未命中从服务器共享目录下载并持久化。 */
    private suspend fun resolveByFilename(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        filename: String,
    ): String {
        if (filename.contains('/') || filename.contains('\\') || filename.contains("..")) {
            throw IllegalArgumentException("仅支持单级文件名：$filename")
        }
        val cacheDir = musicCacheDir(context)
        val cached = File(cacheDir, filename)
        if (cached.isFile) {
            Timber.i("音乐缓存命中：${cached.absolutePath}")
            return cached.absolutePath
        }

        // 缓存未命中 → 请求服务器共享音乐目录
        val deviceId = deviceStore.deviceId()
        val token = deviceStore.token()
        Timber.i("音乐缓存未命中，向服务器请求：$filename")
        val body = try {
            apiClient.downloadMusic(filename, deviceId, token)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                throw IllegalArgumentException("服务器与本地缓存均无此文件：$filename")
            }
            throw e
        }

        // 先写临时文件再原子重命名，避免半截文件被当成有效缓存
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$filename.tmp")
        try {
            body.use { resp ->
                resp.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val target = File(cacheDir, filename)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("缓存写入失败：$filename")
            }
        } finally {
            tmp.delete()
        }
        Timber.i("音乐已下载并缓存：${File(cacheDir, filename).absolutePath}")
        return File(cacheDir, filename).absolutePath
    }

    /** 客户端音乐缓存目录：App 私有 music/（无外置存储时回退内部存储）。 */
    private fun musicCacheDir(context: Context): File =
        context.getExternalFilesDir("music") ?: File(context.filesDir, "music")
}
