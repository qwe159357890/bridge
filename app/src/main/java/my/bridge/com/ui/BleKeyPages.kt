package my.bridge.com.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.bridge.com.blekey.BleKeyBroadcaster
import my.bridge.com.blekey.BleKeyBroadcastService
import my.bridge.com.blekey.BleKeyDeviceName
import my.bridge.com.blekey.BleKeyHealth
import my.bridge.com.blekey.BleKeyMode
import my.bridge.com.blekey.BleKeyPrefs
import my.bridge.com.blekey.BleKeyProtocol
import my.bridge.com.blekey.BleKeyRuntime
import my.bridge.com.blekey.BleKeySelfCheck
import my.bridge.com.blekey.BleKeyState
import kotlinx.coroutines.delay

/** 蓝牙钥匙详情页是否展示（设置分区点进 / 点通知直达）。 */
object BleKeyUiState {

    /**
     * 通知点击带的 extra：由 MainActivity 读取后置 [showPage]。
     *
     * ★ 不能在服务构建通知时直接置 `showPage = true`——构建通知每次状态刷新都会跑，
     *   那样用户下次打开 App 会莫名落到蓝牙钥匙页（v0.3.002 的真实 bug，P2 修正）。
     */
    const val EXTRA_OPEN = "my.bridge.com.blekey.OPEN_PAGE"

    var showPage by mutableStateOf(false)
}

/**
 * 设置 → 连接 →「蓝牙钥匙」分区卡片。
 *
 * 只放「一眼要看的」：总开关、编号、当前档位与原因、详情入口。
 * 其余（自检 / 功率 / 静默时段 / 安全提示）收进 [BleKeyPage]，避免设置页过长。
 */
@Composable
fun BleKeySettingsSection() {
    val context = LocalContext.current
    val runtime by BleKeyRuntime.state.collectAsStateWithLifecycle()
    val advState by BleKeyBroadcaster.state.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(BleKeyPrefs.enabled(context)) }
    var numberInput by remember { mutableStateOf(BleKeyPrefs.number(context)?.toString() ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔑", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("蓝牙钥匙（BLE 广播）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        modeText(runtime.mode, advState),
                        style = MaterialTheme.typography.bodySmall,
                        color = modeColor(runtime.mode, advState),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        val n = numberInput.toIntOrNull()
                        if (on && n == null) {
                            Toast.makeText(context, "请先填写钥匙编号（00~99）", Toast.LENGTH_SHORT).show()
                        } else {
                            enabled = on
                            BleKeyPrefs.setEnabled(context, on)
                            if (on) {
                                n?.let { BleKeyPrefs.setNumber(context, it) }
                                BleKeyBroadcastService.evaluate(context)
                            } else {
                                BleKeyBroadcastService.stop(context)
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = numberInput,
                    onValueChange = { input ->
                        numberInput = input.filter { it.isDigit() }.take(2)
                        val n = numberInput.toIntOrNull()
                        if (n != null && n in 0..99) {
                            BleKeyPrefs.setNumber(context, n)
                            if (enabled) BleKeyBroadcastService.evaluate(context)
                        }
                    },
                    label = { Text("编号") },
                    placeholder = { Text("00~99") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        runtime.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    runtime.error?.takeIf { runtime.mode == BleKeyMode.BLOCKED }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { BleKeyUiState.showPage = true }) {
                    Text("详情与自检", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    BleKeyRuntime.state.value.advertisingSince.let {
                        if (it > 0) "已播 ${fmtDuration(System.currentTimeMillis() - it)}" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 蓝牙钥匙详情页（覆盖层，照 SSH 页范式：无 NavHost，BackHandler 接管返回）。
 *
 * 覆盖设计文档 §11 的 10 个 UI 元素：开关 / 编号 / UUID 两种格式复制 / 状态与原因 /
 * 自检 / 已播时长 / 电池白名单 / 安全提示 / 功率档位 / 静默时段。
 */
@Composable
fun BleKeyPage() {
    BackHandler { BleKeyUiState.showPage = false }

    val context = LocalContext.current
    val runtime by BleKeyRuntime.state.collectAsStateWithLifecycle()
    val advState by BleKeyBroadcaster.state.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(BleKeyPrefs.enabled(context)) }
    var numberInput by remember { mutableStateOf(BleKeyPrefs.number(context)?.toString() ?: "") }
    var prefixInput by remember { mutableStateOf(BleKeyPrefs.prefix(context)) }
    var txPower by remember { mutableStateOf(BleKeyPrefs.txPower(context)) }
    var quietEnabled by remember { mutableStateOf(BleKeyPrefs.quietEnabled(context)) }
    var quietStart by remember { mutableStateOf(BleKeyPrefs.quietStart(context)) }
    var quietEnd by remember { mutableStateOf(BleKeyPrefs.quietEnd(context)) }
    var quietNoticeOn by remember { mutableStateOf(BleKeyPrefs.quietNoticeOn(context)) }

    // 设备名：系统蓝牙名（无权限/蓝牙关时为 null）
    var systemName by remember { mutableStateOf(BleKeyDeviceName.current(context)) }
    var pendingRename by remember { mutableStateOf<String?>(null) }

    var selfCheckRunning by remember { mutableStateOf(false) }
    var selfCheckResult by remember { mutableStateOf<BleKeySelfCheck.Result?>(null) }
    var selfCheckError by remember { mutableStateOf<String?>(null) }

    // 已播时长每秒刷新
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    // 从系统设置返回后刷新电池白名单状态
    var batteryTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val batteryOptimized = remember(batteryTick) { isIgnoringBatteryOptimizations(context) }

    val uuid = numberInput.toIntOrNull()
        ?.takeIf { it in BleKeyProtocol.NUMBER_MIN..BleKeyProtocol.NUMBER_MAX }
        ?.let { BleKeyProtocol.fullUuid(BleKeyPrefs.prefix(context), it) }

    fun refreshService() {
        if (enabled) BleKeyBroadcastService.evaluate(context) else BleKeyBroadcastService.stop(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                fontSize = 22.sp,
                modifier = Modifier
                    .clickable { BleKeyUiState.showPage = false }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text("蓝牙钥匙", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                modeText(runtime.mode, advState),
                fontSize = 12.sp,
                color = modeColor(runtime.mode, advState),
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BleKeyHint(
                "手机持续广播 iBeacon，车上 ESP32 扫到即当钥匙。编号不同的手机 = 不同的钥匙；" +
                    "夜间若到车边不通电，打开 App 即可恢复广播（时段静默）。",
            )

            // ---- 状态 ----
            BleKeyCard("当前状态") {
                // tick 每秒变一次 → 触发重组，让「已播时长」走字
                val nowMs = remember(tick) { System.currentTimeMillis() }
                BleKeyInfoRow("档位", modeText(runtime.mode, advState))
                BleKeyInfoRow("生效原因", runtime.reason)
                BleKeyInfoRow(
                    "已播时长",
                    runtime.advertisingSince.takeIf { it > 0 }
                        ?.let { fmtDuration(nowMs - it) }
                        ?: "—",
                )
                BleKeyInfoRow("保活", "每 60 秒强制重建一次（防 ROM 静默停播）")
                runtime.error?.let { BleKeyInfoRow("原因", it) }
            }

            // ---- 运行概况（v0.3.008）----
            // 用途：50 项验收不靠手点。挂着跑一段时间，看这里的累计数字就能判断有没有在稳定广播；
            // 要更细的证据就去「设置 → 连接 → 上传诊断日志」，上传流里有一行 [BleKeySummary]。
            BleKeyCard("运行概况") {
                // 每 10 秒读一次 SharedPreferences（健康台账），不必每秒读盘刷新
                val health = remember(tick / 10) { BleKeyHealth.snapshot(context) }
                val nowMs = remember(tick / 10) { System.currentTimeMillis() }
                BleKeyInfoRow("服务启动", "${health.serviceCreates} 次（越多说明被系统回收越频繁）")
                BleKeyInfoRow("巡检轮数", "${health.watchdogRuns} 次 · 每 60 秒一轮")
                BleKeyInfoRow(
                    "最后巡检",
                    health.lastWatchdogAt.takeIf { it > 0 }
                        ?.let { fmtDuration(nowMs - it) + "前" }
                        ?: "—",
                )
                BleKeyInfoRow("累计广播", BleKeyHealth.humanDuration(health.broadcastMs))
                BleKeyInfoRow(
                    "广播失败",
                    if (health.advFails == 0) "0 次" else "${health.advFails} 次（重点看，上传日志可查原因）",
                )
                BleKeyInfoRow(
                    "上次断档",
                    health.lastGapMs.takeIf { it > 0 }
                        ?.let { BleKeyHealth.humanDuration(it) + "（重建时算出的中断时长）" }
                        ?: "—",
                )
                health.modeCounts.entries.sortedByDescending { it.value }.take(4).forEach { (m, n) ->
                    BleKeyInfoRow("档位 ${m.name}", "$n 轮")
                }
                BleKeyInfoRow("导出", "设置 → 连接 → 上传诊断日志")
            }

            // ---- 开关 + 编号 ----
            BleKeyCard("开关与编号") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用广播", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            val n = numberInput.toIntOrNull()
                            if (on && n == null) {
                                Toast.makeText(context, "请先填写钥匙编号（00~99）", Toast.LENGTH_SHORT).show()
                            } else {
                                enabled = on
                                BleKeyPrefs.setEnabled(context, on)
                                refreshService()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = {
                            numberInput = it.filter { c -> c.isDigit() }.take(2)
                            numberInput.toIntOrNull()?.takeIf { n -> n in 0..99 }?.let { n ->
                                BleKeyPrefs.setNumber(context, n)
                                refreshService()
                            }
                        },
                        label = { Text("编号") },
                        placeholder = { Text("00~99") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "编号决定 UUID 末两位，改了等于换一把钥匙，对端需重新登记。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 身份 ----
            BleKeyCard("标识（对端登记用）") {
                if (uuid == null) {
                    Text(
                        "先填编号，才能生成完整 UUID。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        uuid,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        BleKeyProtocol.toDashed(uuid),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { copyText(context, uuid, "32 位 UUID") }) {
                            Text("复制 32 位", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { copyText(context, BleKeyProtocol.toDashed(uuid), "带连字符 UUID") }) {
                            Text("复制带连字符", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---- 设备名 ----
            BleKeyCard("设备名（对端 SCAN 列表显示）") {
                Text(
                    "对端列表的「名称」列就是这个。Android 没有「把自定义字符串写进广播」的接口，" +
                        "广播出去的就是系统蓝牙名——所以想带编号只能改系统名。默认不改动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                BleKeyInfoRow("当前系统名", systemName ?: "读取失败（缺蓝牙权限或蓝牙未开）")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val suggested = numberInput.toIntOrNull()?.let { BleKeyDeviceName.suggest(context, it) }
                    TextButton(
                        enabled = suggested != null,
                        onClick = { pendingRename = suggested },
                    ) { Text("按编号改名", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { systemName = BleKeyDeviceName.current(context) }) {
                        Text("刷新", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "建议 ≤20 字符（中文约 8 字）：超长只会在广播里被截断，不影响钥匙识别（对端按 UUID 认）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 自检 ----
            BleKeyCard("自检（手动，仅点一次）") {
                Text(
                    "边播边扫 5 秒。多数手机不会把自己发出的包报给扫描器，所以「扫不到自己」很正常，" +
                        "不代表广播有问题——ESP32 侧 SCAN 才是最终依据。此功能用来区分「本机硬件限制」和「扫描链路异常」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val r = selfCheckResult
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = uuid != null && !selfCheckRunning,
                        onClick = {
                            val target = uuid ?: return@OutlinedButton
                            selfCheckRunning = true
                            selfCheckResult = null
                            selfCheckError = null
                            BleKeySelfCheck.start(context, target) { result ->
                                selfCheckRunning = false
                                selfCheckResult = result
                                selfCheckError = result.error
                            }
                        },
                    ) { Text(if (selfCheckRunning) "扫描中…" else "开始自检") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            selfCheckRunning -> "扫描中…"
                            r == null -> "未自检"
                            else -> selfCheckShort(r)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r?.outcome == BleKeySelfCheck.Outcome.SELF_FOUND)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val detail = selfCheckDetail(r, selfCheckError)
                if (detail != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (r?.outcome) {
                            // 只有「根本没扫成」（权限/蓝牙关/异常）才用错误色；
                            // 其余无结论档一律中性——手机侧本就无法自证广播，红色只会造成误判
                            BleKeySelfCheck.Outcome.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // ---- 功率与静默 ----
            BleKeyCard("发射功率") {
                Text(
                    "判近的门槛由对端 RSSI 决定，手机功率只影响余量。裤兜/人体遮挡大时选「高」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_LOW to "低",
                        android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM to "中",
                        android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH to "高",
                    ).forEach { (level, label) ->
                        val selected = txPower == level
                        TextButton(
                            onClick = {
                                txPower = level
                                BleKeyPrefs.setTxPower(context, level)
                                refreshService()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(if (selected) "● $label" else "○ $label") }
                    }
                }
                Text(
                    BleKeyPrefs.txPowerText(txPower),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BleKeyCard("时段静默（只关后台慢速档）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用静默时段", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "静默期内：App 在前台或极空已连接照常快速广播；两者都没有则停播（省电）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = quietEnabled,
                        onCheckedChange = {
                            quietEnabled = it
                            BleKeyPrefs.setQuietEnabled(context, it)
                            refreshService()
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quietStart,
                        onValueChange = { quietStart = it.take(5) },
                        label = { Text("开始") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = quietEnd,
                        onValueChange = { quietEnd = it.take(5) },
                        label = { Text("结束") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (BleKeyPrefs.parseHm(quietStart) == null || BleKeyPrefs.parseHm(quietEnd) == null) {
                            Toast.makeText(context, "时间格式应为 HH:MM", Toast.LENGTH_SHORT).show()
                        } else {
                            BleKeyPrefs.setQuietWindow(context, quietStart, quietEnd)
                            refreshService()
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("保存", style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("静默前 5 分钟提醒", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "每天最多一次，提前告诉你今晚后台会停播。静默期内直接沉睡是既定行为，" +
                                "这条提醒是唯一的告知手段。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = quietNoticeOn,
                        onCheckedChange = {
                            quietNoticeOn = it
                            BleKeyPrefs.setQuietNoticeOn(context, it)
                        },
                    )
                }
            }

            // ---- 系统限制 ----
            BleKeyCard("系统限制") {
                BleKeyInfoRow(
                    "电池优化白名单",
                    if (batteryOptimized) "未加入（后台可能被限制）" else "已加入",
                )
                if (batteryOptimized) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("去系统设置加入白名单", style = MaterialTheme.typography.bodySmall) }
                }
            }

            // ---- 高级 ----
            BleKeyCard("高级：UUID 前缀") {
                Text(
                    "30 字符 hex。默认 627269646765000000000000000000（\"bridge\" 的 ASCII hex + 补零）。" +
                        "改前缀 = 换整套钥匙，所有对端都要重新登记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = { prefixInput = it.filter { c -> c.isLetterOrDigit() }.take(32) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = {
                        val clean = prefixInput.replace("-", "").lowercase()
                        if (clean.length != BleKeyProtocol.PREFIX_LEN) {
                            Toast.makeText(context, "前缀必须是 30 个 hex 字符（当前 ${clean.length}）", Toast.LENGTH_SHORT).show()
                        } else {
                            BleKeyPrefs.setPrefix(context, clean)
                            Toast.makeText(context, "已保存，请重新核对 UUID", Toast.LENGTH_SHORT).show()
                            refreshService()
                        }
                    }) { Text("保存前缀", style = MaterialTheme.typography.bodySmall) }
                }
            }

            // ---- 安全提示 ----
            BleKeyCard("安全边界（务必知悉）") {
                Text(
                    "① UUID 就是钥匙本体：iBeacon 广播是公开的，任何人拿到这 32 位字符都能播出一个同样的信号，" +
                        "没有任何防伪。② 所以本功能防的是「误启动 / 忘记拔钥匙」，不防「有人故意复制」。\n" +
                        "③ 真正防盗需要 BLE 连接 + 挑战应答，对端要大改，当前不做。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // 改名二次确认：这是全局副作用，绝不静默执行
    val renameTarget = pendingRename
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("把蓝牙名改成「$renameTarget」？") },
            text = {
                Text(
                    "这会修改手机的整体蓝牙名称——已配对的耳机、车载、其他 App 都会看到新名字。\n\n" +
                        "它只影响对端列表的显示，不影响钥匙识别（对端按 UUID 认钥匙）。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRename = null
                    if (BleKeyDeviceName.apply(context, renameTarget)) {
                        systemName = BleKeyDeviceName.current(context)
                        // 重建广播会话，让对端下次 SCAN 就能看到新名字
                        refreshService()
                        Toast.makeText(context, "已改名，下次广播生效", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "改名失败（缺蓝牙权限或蓝牙未开）", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("改名") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 小组件

@Composable
private fun BleKeyCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BleKeyInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BleKeyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

private fun copyText(context: Context, text: String, label: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun modeText(mode: BleKeyMode, adv: BleKeyState): String = when {
    mode == BleKeyMode.BOOST && adv == BleKeyState.ADVERTISING -> "广播中 · 亮屏提速（100ms）"
    mode == BleKeyMode.FAST && adv == BleKeyState.ADVERTISING -> "广播中 · 快速（250ms）"
    mode == BleKeyMode.SLOW && adv == BleKeyState.ADVERTISING -> "广播中 · 慢速保底（1000ms）"
    mode == BleKeyMode.QUIET -> "已暂停 · 时段静默"
    mode == BleKeyMode.BLOCKED -> "未广播"
    mode == BleKeyMode.OFF -> "已停止"
    adv == BleKeyState.ADVERTISING -> "广播中"
    else -> "已停止"
}

@Composable
private fun modeColor(mode: BleKeyMode, adv: BleKeyState) = when {
    adv == BleKeyState.ADVERTISING &&
        (mode == BleKeyMode.BOOST || mode == BleKeyMode.FAST || mode == BleKeyMode.SLOW) ->
        MaterialTheme.colorScheme.primary
    mode == BleKeyMode.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
    mode == BleKeyMode.BLOCKED || adv == BleKeyState.FAILED ||
        adv == BleKeyState.BT_OFF || adv == BleKeyState.NO_PERMISSION ||
        adv == BleKeyState.UNSUPPORTED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun fmtDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "%d 小时 %d 分".format(h, m)
        m > 0 -> "%d 分 %d 秒".format(m, s)
        else -> "%d 秒".format(s)
    }
}

/**
 * 自检短结论。
 *
 * 关键语义：只有 [BleKeySelfCheck.Outcome.SELF_FOUND] 才算"通过"；其余都不是"失败"，
 * 而是"本机扫不到自己"这一正常现象的不同成因。**不得写成"失败"或提距离/遮挡**——
 * 自己扫自己没有距离概念（用户实测：ESP32 能扫到且正在通电，本机自检仍扫不到）。
 */
private fun selfCheckShort(r: BleKeySelfCheck.Result): String = when (r.outcome) {
    BleKeySelfCheck.Outcome.SELF_FOUND -> "已确认在播（${r.rssi ?: "?"} dBm，${r.hits} 帧）"
    BleKeySelfCheck.Outcome.NO_SELF_SCAN_OK -> "无结论 · 扫描正常"
    BleKeySelfCheck.Outcome.NO_SELF_NO_BEACON -> "无结论 · 附近无信标"
    BleKeySelfCheck.Outcome.NOTHING_SEEN -> "无结论 · 未收到任何包"
    BleKeySelfCheck.Outcome.FAILED -> "无法自检"
}

/** 自检详细说明（短结论不够时补充；[BleKeySelfCheck.Outcome.SELF_FOUND] 无需补充）。 */
private fun selfCheckDetail(r: BleKeySelfCheck.Result?, error: String?): String? {
    if (r == null) return null
    return when (r.outcome) {
        BleKeySelfCheck.Outcome.SELF_FOUND -> null
        BleKeySelfCheck.Outcome.NO_SELF_SCAN_OK ->
            "5 秒内扫到 ${r.beaconCount} 个 iBeacon（共 ${r.deviceCount} 台设备），但没收到自己。" +
                "这说明扫描和 iBeacon 解析都正常，只是本机不上报自发广播——多数机型如此，" +
                "**不代表钥匙异常**。请用 ESP32 侧 SCAN 确认。"
        BleKeySelfCheck.Outcome.NO_SELF_NO_BEACON ->
            "5 秒内扫到 ${r.deviceCount} 台蓝牙设备，但都不是 iBeacon，没有可比对的目标。" +
                "无法据此判断，请用 ESP32 侧 SCAN 确认。"
        BleKeySelfCheck.Outcome.NOTHING_SEEN ->
            "5 秒内一个蓝牙包都没收到。周围通常至少有耳机、手环这类 BLE 设备；" +
                "若确实一台都没有，那就无法判断扫描是否正常。" +
                "若你确定附近有蓝牙设备、这里仍为 0，才说明扫描被占用（如开着极空扫描页）或受系统限制。"
        BleKeySelfCheck.Outcome.FAILED -> error ?: "自检未能执行"
    }
}
