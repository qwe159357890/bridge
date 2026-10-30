package my.bridge.com.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.bridge.com.BuildConfig
import my.bridge.com.access.BridgeAccessibilityService
import my.bridge.com.keepalive.KeepAlivePrefs
import my.bridge.com.keepalive.KeepAliveService
import my.bridge.com.keepalive.KeepAliveUtils
import my.bridge.com.relay.RelayPrefs
import timber.log.Timber

// ============ 通用设置 ============
@Composable
fun GeneralSettings() {
    var darkMode by remember { mutableStateOf(false) }
    var dnd by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle("通用设置") }
        item {
            SettingCard {
                SettingSwitchRow(
                    icon = "🌙",
                    title = "深色模式",
                    checked = darkMode,
                    onCheckedChange = { darkMode = it; Timber.d("切换：深色模式 -> ${if (it) "开" else "关"}") },
                )
                SettingSwitchRow(
                    icon = "🔕",
                    title = "消息免打扰",
                    checked = dnd,
                    onCheckedChange = { dnd = it; Timber.d("切换：消息免打扰 -> ${if (it) "开" else "关"}") },
                )
                SettingValueRow(icon = "🌐", title = "语言", value = "简体中文") { Timber.d("选择语言") }
                SettingValueRow(icon = "🧹", title = "清理缓存", value = "12.4 MB") { Timber.d("清理缓存") }
            }
        }
    }
}

// ============ 保活设置 ============

/** 「后台资源占用」的一类细分资源。core=true 表示核心保活手段，关闭需二次确认。 */
private data class ResourceOption(
    val key: String,
    val icon: String,
    val name: String,
    val desc: String,
    val core: Boolean = false,
)

/** 8 类可细化管理的后台资源，与 KeepAlivePrefs 的 KEY_RES_* 一一对应。 */
private val resourceOptions = listOf(
    ResourceOption(KeepAlivePrefs.KEY_RES_WAKELOCK, "⚡", "CPU 唤醒锁", "WakeLock，阻止 CPU 进入深睡", core = true),
    ResourceOption(KeepAlivePrefs.KEY_RES_WIFILOCK, "📡", "WiFi 高性能锁", "WifiLock，保持 WiFi 高吞吐"),
    ResourceOption(KeepAlivePrefs.KEY_RES_NETWORK, "🌐", "网络监听", "NetworkCallback，注册网络状态回调"),
    ResourceOption(KeepAlivePrefs.KEY_RES_LOCATION, "📍", "定位", "持续监听 GPS/网络定位"),
    ResourceOption(KeepAlivePrefs.KEY_RES_SENSOR, "🧭", "传感器", "监听加速度计 / 陀螺仪"),
    ResourceOption(KeepAlivePrefs.KEY_RES_BLUETOOTH, "📶", "蓝牙扫描", "低功耗 BLE 扫描，需蓝牙扫描权限"),
    ResourceOption(KeepAlivePrefs.KEY_RES_CAMERA, "📷", "相机", "后台短暂持有相机设备，需相机权限"),
    ResourceOption(KeepAlivePrefs.KEY_RES_AUDIO, "🔇", "无声播放", "独立音轨循环播放近静音 PCM"),
)

@Composable
fun KeepAliveSettings() {
    val context = LocalContext.current

    // 从 KeepAlivePrefs 读取各层开关当前值
    var always by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_ALWAYS)) }
    var boot by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_BOOT)) }
    var fallback by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_FALLBACK)) }
    var resource by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_RESOURCE)) }
    var onePixel by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_ONE_PIXEL)) }
    var hideRecents by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_HIDE_RECENTS)) }

    // 细化弹窗状态
    var showResourceSheet by remember { mutableStateOf(false) }
    // 核心项二次确认：非空时表示「用户正试图关闭某核心项」
    var pendingCoreOff by remember { mutableStateOf<ResourceOption?>(null) }
    // 弹窗刷新键：核心项经二次确认写盘不经过弹窗的 toggle 路径，需主动触发同步
    var sheetRefresh by remember { mutableIntStateOf(0) }

    fun update(key: String, value: Boolean) {
        KeepAlivePrefs.setBoolean(context, key, value)
        // 让改动即时生效：刷新正在运行的服务，重新应用各层开关
        KeepAliveService.refresh(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle("保活设置") }
        item {
            SettingCard {
                SettingSwitchRow(
                    icon = "🛡️",
                    title = "保活总开关",
                    checked = always,
                    onCheckedChange = {
                        always = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_ALWAYS, it)
                        Timber.d("切换：保活总开关 -> ${if (it) "开" else "关"}")
                    },
                )
                SettingSwitchRow(
                    icon = "🔌",
                    title = "开机自启",
                    checked = boot,
                    onCheckedChange = {
                        boot = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_BOOT, it)
                        Timber.d("切换：开机自启 -> ${if (it) "开" else "关"}")
                    },
                )
                SettingSwitchRow(
                    icon = "🔄",
                    title = "双兜底唤醒",
                    checked = fallback,
                    onCheckedChange = {
                        fallback = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_FALLBACK, it)
                        Timber.d("切换：双兜底唤醒 -> ${if (it) "开" else "关"}")
                    },
                )
                // 后台资源占用：总开关 + 「细化」按钮弹出子选项
                ResourceRow(
                    checked = resource,
                    onCheckedChange = {
                        resource = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_RESOURCE, it)
                        Timber.d("切换：后台资源占用 -> ${if (it) "开" else "关"}")
                    },
                    onRefine = { showResourceSheet = true },
                )
                SettingSwitchRow(
                    icon = "👁️",
                    title = "一像素锚点",
                    checked = onePixel,
                    onCheckedChange = {
                        onePixel = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_ONE_PIXEL, it)
                        Timber.d("切换：一像素锚点 -> ${if (it) "开" else "关"}")
                    },
                )
                // 在最近任务中隐藏（借鉴 SmsForwarder enableExcludeFromRecents）：
                // 开=最近任务不再出现 Bridge 卡片，上滑/一键清理碰不到，防误杀；
                // 关=即时恢复显示（修复 SmsForwarder 原版只存偏好、需重启才恢复的缺陷）
                SettingSwitchRow(
                    icon = "🕵️",
                    title = "在最近任务中隐藏",
                    checked = hideRecents,
                    onCheckedChange = {
                        hideRecents = it
                        KeepAlivePrefs.setBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_HIDE_RECENTS, it)
                        (context as? Activity)?.let { act ->
                            KeepAliveUtils.setExcludeFromRecents(act, it)
                        }
                        Toast.makeText(
                            context,
                            if (it) "已隐藏：最近任务不再显示 Bridge，可从桌面图标进入" else "已恢复：Bridge 重新出现在最近任务",
                            Toast.LENGTH_SHORT,
                        ).show()
                        Timber.d("切换：在最近任务中隐藏 -> ${if (it) "开" else "关"}")
                    },
                )
            }
        }
        item {
            SettingCard {
                SettingValueRow(
                    icon = "🔋",
                    title = "电池优化白名单",
                    value = if (KeepAliveUtils.isIgnoreBatteryOptimization(context)) "已加入" else "去设置",
                    onClick = {
                        Timber.d("引导加入电池优化白名单")
                        (context as? Activity)?.let { KeepAliveUtils.requestIgnoreBatteryOptimization(it) }
                    },
                )
            }
        }
        item {
            SectionTitle("权限管理")
        }
        item {
            SettingCard {
                // 无障碍服务（v0.1.40）：模拟电源键锁屏 + 静默截屏的前置开关
                val accReady = BridgeAccessibilityService.isEnabledInSettings(context)
                SettingValueRow(
                    icon = if (accReady) "✅" else "♿",
                    title = "无障碍服务（锁屏/静默截屏）",
                    value = if (accReady) "已开启" else "去开启",
                    onClick = {
                        Timber.d("引导开启无障碍服务（Bridge 辅助服务）")
                        try {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "跳转无障碍设置失败")
                        }
                    },
                )
                val activity = context as? Activity
                KeepAliveUtils.runtimePermissions().forEach { permission ->
                    val granted = KeepAliveUtils.hasPermission(context, permission)
                    SettingValueRow(
                        icon = if (granted) "✅" else "⚠️",
                        title = permissionLabel(permission),
                        value = if (granted) "已授权" else "去授权",
                        onClick = {
                            Timber.d("手动申请权限：$permission")
                            activity?.let { KeepAliveUtils.requestRuntimePermissions(it) }
                        },
                    )
                }
            }
        }
        item {
            Text(
                text = "保活策略借鉴 Aurelay + SmsForwarder，详见 docs/keepalive/design.md。\n「后台资源占用」= 持续持有各类系统资源以抬高进程优先级；CPU 唤醒锁为核心手段，关闭会明显降低保活效果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    // 资源细化弹窗
    if (showResourceSheet) {
        ResourceSheet(
            options = resourceOptions,
            refreshKey = sheetRefresh,
            onToggle = { opt ->
                if (opt.core && KeepAlivePrefs.getBoolean(context, opt.key)) {
                    // 核心项当前为开，本次是「关闭」→ 需要二次确认
                    pendingCoreOff = opt
                } else {
                    val next = !KeepAlivePrefs.getBoolean(context, opt.key)
                    update(opt.key, next)
                    Timber.d("后台资源占用 · ${opt.name} -> ${if (next) "开" else "关"}")
                }
            },
            onDismiss = { showResourceSheet = false },
        )
    }

    // 核心项关闭二次确认
    pendingCoreOff?.let { coreOpt ->
        CoreConfirmDialog(
            option = coreOpt,
            onCancel = { pendingCoreOff = null },
            onConfirm = {
                update(coreOpt.key, false)
                Timber.d("核心项「${coreOpt.name}」已二次确认关闭")
                pendingCoreOff = null
                sheetRefresh++ // 弹窗开关状态同步（确认写盘不走 toggle 路径）
            },
        )
    }
}

/** 「后台资源占用」行：总开关 + 「细化」按钮。 */
@Composable
private fun ResourceRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRefine: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📡", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Text("后台资源占用", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "细化",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                .clickable { onRefine() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 资源细化底部弹窗：列出 8 类资源，逐项勾选。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ResourceSheet(
    options: List<ResourceOption>,
    onToggle: (ResourceOption) -> Unit,
    onDismiss: () -> Unit,
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    // 开关状态提升为 Compose 可观察状态（mutableStateMapOf）。此前在组合期直接
    // 读 SharedPreferences 是普通读取（无重组订阅）：点开关只写盘不触发重组，
    // Switch UI 不动，要重开弹窗（重新组合）才显示新值（2026-10-28 用户反馈）。
    // refreshKey 变化（核心项二次确认写盘）时重建状态映射完成同步。
    val states = remember(options, refreshKey) {
        mutableStateMapOf<String, Boolean>().apply {
            options.forEach { put(it.key, KeepAlivePrefs.getBoolean(context, it.key)) }
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("后台资源占用 · 细化", style = MaterialTheme.typography.titleMedium)
            Text(
                "勾选要持续占用的资源。占用越少越省电，但保活效果相应减弱。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            options.forEach { opt ->
                val checked = states[opt.key] ?: false
                // 切换后立即回读落盘值刷新状态（含核心项二次确认路径：确认关闭
                // 后 onToggle 才真正写盘，这里回读保证两种路径都同步 UI）
                fun toggleAndSync() {
                    onToggle(opt)
                    states[opt.key] = KeepAlivePrefs.getBoolean(context, opt.key)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleAndSync() }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(opt.icon, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(opt.name, style = MaterialTheme.typography.bodyLarge)
                            if (opt.core) {
                                Text(
                                    "核心",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color(0xFFE5484D),
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(androidx.compose.ui.graphics.Color(0xFFFDECEC).copy(alpha = 1f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(
                            opt.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = checked, onCheckedChange = { toggleAndSync() })
                }
            }
        }
    }
}

/** 核心项关闭二次确认对话框。 */
@Composable
private fun CoreConfirmDialog(
    option: ResourceOption,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        icon = { Text("⚠️", style = MaterialTheme.typography.headlineMedium) },
        title = { Text("关闭「${option.name}」？") },
        text = {
            Text("${option.name} 是核心保活手段，关闭后将明显降低后台存活率，进程可能被系统更频繁回收。确定要关闭吗？")
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("确认关闭", color = androidx.compose.ui.graphics.Color(0xFFE5484D))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

// ============ 主动上报设置 ============

/**
 * 主动上报设置页（周期位置上报 + 新短信即推，双开关默认开）。
 *
 * 与服务端配置是**同一份**：这里改动立即写入本地（ReportManager 配置流即时生效）；
 * 管理后台「上报设置」改动经 WS config_update 推送覆盖本地——两端一致，
 * 服务端配置为准。间隔 <15 分钟时仅主级（保活协程）生效，WorkManager 兜底
 * 仍按系统下限 15 分钟跑（UI 有说明）。
 */
@Composable
fun ReportSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            my.bridge.com.report.ReportPrefsEntryPoint::class.java,
        ).reportPrefs()
    }

    var locationEnabled by remember { mutableStateOf(true) }
    var intervalMin by remember { mutableIntStateOf(10) }
    var smsEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val cfg = prefs.current()
        locationEnabled = cfg.locationEnabled
        intervalMin = cfg.locationIntervalMin
        smsEnabled = cfg.smsEnabled
    }

    fun save(loc: Boolean = locationEnabled, min: Int = intervalMin, sms: Boolean = smsEnabled) {
        scope.launch {
            prefs.save(my.bridge.com.report.ReportPrefs.Config(loc, min, sms))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle("主动上报") }
        item {
            SettingCard {
                SettingSwitchRow(
                    icon = "📍",
                    title = "周期位置上报",
                    checked = locationEnabled,
                    onCheckedChange = {
                        locationEnabled = it
                        save(loc = it)
                        Timber.d("切换：周期位置上报 -> ${if (it) "开" else "关"}")
                    },
                )
                // 间隔滑条（1~120 分钟；保活协程按此值跑，WorkManager 兜底按 ≥15 分钟）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⏱️", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "上报间隔：$intervalMin 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        androidx.compose.material3.Slider(
                            value = intervalMin.toFloat(),
                            onValueChange = { intervalMin = it.toInt().coerceIn(1, 120) },
                            onValueChangeFinished = { save() },
                            valueRange = 1f..120f,
                            enabled = locationEnabled,
                        )
                        Text(
                            "小于 15 分钟时仅保活服务存活期间生效；进程被杀后的系统级兜底按 15 分钟跑",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SettingCard {
                val smsPerm = my.bridge.com.data.SmsReader.hasPermission(context)
                SettingSwitchRow(
                    icon = "💬",
                    title = "新短信即推",
                    checked = smsEnabled,
                    onCheckedChange = {
                        smsEnabled = it
                        save(sms = it)
                        Timber.d("切换：新短信即推 -> ${if (it) "开" else "关"}")
                    },
                )
                val smsValue = when {
                    smsEnabled && smsPerm -> "已开启"
                    smsEnabled -> "缺短信权限"
                    else -> "已关闭"
                }
                SettingValueRow(
                    icon = if (smsEnabled && smsPerm) "✅" else if (smsEnabled) "⚠️" else "🔇",
                    title = "短信读取权限",
                    value = smsValue,
                    onClick = {
                        if (!smsPerm) {
                            (context as? Activity)?.let { act ->
                                KeepAliveUtils.requestRuntimePermissions(act)
                            }
                        }
                    },
                )
            }
        }
        item {
            Text(
                text = "开关与间隔可在管理后台「设备 → 上报设置」远程修改（服务端配置为准，本页改动也立即生效）。" +
                    "位置上报复用既有轨迹存储；新短信即推通过监听系统短信库实现，无需把 Bridge 设为默认短信应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

// ============ 关于 ============
@Composable
fun AboutPage() {
    val context = LocalContext.current

    // 运行时长：自保活服务启动起累计，每秒刷新一次，用于确认保活是否持续。
    // 保活失效时这个数字会停住或随进程重启归零，正是最直接的证据。
    val startedAt = remember { KeepAliveService.startedAtMillis() }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(16.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("B", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.padding(top = 12.dp))
                Text("Bridge", style = MaterialTheme.typography.titleLarge)
                Text(
                    "企业级多模块 Android 应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "版本 v${BuildConfig.VERSION_NAME} · 内部测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        // 运行时长卡片
        Spacer(Modifier.padding(top = 12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "保活运行时长",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (startedAt > 0L) {
                        formatUptime(nowTick - startedAt)
                    } else {
                        "服务未启动"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "数字持续增长即代表保活成功；若停住或归零，说明进程被系统回收",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 检查更新卡片：与 check_update 命令走同一入口（UpdateManager，
        // 自动下载 APK + 进度通知 + 弹系统安装页）；busy 时按钮禁用防重复触发。
        Spacer(Modifier.padding(top = 12.dp))
        val scope = rememberCoroutineScope()
        var updateMessage by remember { mutableStateOf("检查服务器是否有新版本，自动下载并弹出安装页") }
        var updating by remember { mutableStateOf(false) }
        // 阶段文案与下载进度（轮询 UpdateManager 的 volatile 状态，150ms 一次足够顺滑）
        var updateStage by remember { mutableStateOf("") }
        var updatePct by remember { mutableStateOf(-2) } // -2=不显示进度条 -1=不确定式 0..100=确定式
        // 缓存管理弹窗状态
        var cacheVisible by remember { mutableStateOf(false) }
        var cacheList by remember {
            mutableStateOf(emptyList<my.bridge.com.update.UpdateManager.CacheEntry>())
        }
        var cacheMsg by remember { mutableStateOf("") }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Button(
                    onClick = {
                        if (updating) return@Button
                        updating = true
                        scope.launch {
                            // 阶段轮询：检查中 / 下载中（带真实百分比）/ 拉安装页
                            val poll = launch {
                                while (isActive) {
                                    // 阶段文案同时刷新按钮与下方说明文字（下方文字不能
                                    // 停在静态提示上，用户要求分阶段变化）
                                    when (my.bridge.com.update.UpdateManager.phase) {
                                        my.bridge.com.update.UpdateManager.Phase.CHECKING -> {
                                            updateStage = "正在检查服务器版本…"
                                            updatePct = -2
                                        }
                                        my.bridge.com.update.UpdateManager.Phase.DOWNLOADING -> {
                                            val m = my.bridge.com.update.UpdateManager
                                            val pct = m.downloadPct
                                            updateStage = if (pct >= 0) {
                                                "正在下载更新… $pct%%（%.1f / %.1f MB）".format(
                                                    m.downloadReadBytes / 1048576.0,
                                                    m.downloadTotalBytes / 1048576.0,
                                                )
                                            } else {
                                                "正在下载更新… %.1f MB".format(m.downloadReadBytes / 1048576.0)
                                            }
                                            updatePct = pct
                                        }
                                        my.bridge.com.update.UpdateManager.Phase.WAITING_INSTALL -> {
                                            updateStage = "更新包就绪，正在拉起安装页…"
                                            updatePct = -2
                                        }
                                        else -> {}
                                    }
                                    if (updateStage.isNotBlank()) updateMessage = updateStage
                                    delay(150)
                                }
                            }
                            updateMessage = try {
                                my.bridge.com.update.UpdateManager.checkAndInstall(context)
                            } catch (e: Exception) {
                                "检查更新失败：${e.message}"
                            }
                            poll.cancel()
                            updating = false
                        }
                    },
                    enabled = !updating,
                ) {
                    Text(if (updating) "更新处理中…" else "检查更新")
                }
                if (updating && updatePct != -2) {
                    if (updatePct in 0..100) {
                        LinearProgressIndicator(
                            progress = { updatePct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                }
                Text(
                    text = updateMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
                // 缓存管理入口：列出已下载的更新包（含实际版本号），有选择地手动删除——
                // 万一下载过「假 44 实为 43」的污染缓存，从这里删掉即可恢复正常更新
                androidx.compose.material3.TextButton(
                    onClick = {
                        cacheList = my.bridge.com.update.UpdateManager.listCache(context)
                        cacheMsg = if (cacheList.isEmpty()) "暂无缓存（检查到新版本并下载后才有）" else ""
                        cacheVisible = true
                    },
                ) {
                    Text("管理更新缓存", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 更新缓存管理弹窗：列表展示（文件名/大小/日期/APK 实际版本号），
        // 单个删除 + 全部清除——自动清理误遗留时的手动兜底
        if (cacheVisible) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { cacheVisible = false },
                title = { Text("更新缓存管理") },
                text = {
                    Column {
                        if (cacheMsg.isNotBlank()) {
                            Text(
                                cacheMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                        ) {
                            items(cacheList.size) { i ->
                                val e = cacheList[i]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            e.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                        )
                                        val ver = e.versionName?.let { " · 实际 v$it" } ?: " · 版本未知"
                                        Text(
                                            "%.1f MB%s%s".format(
                                                e.sizeBytes / 1048576.0,
                                                ver,
                                                " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                                    .format(java.util.Date(e.lastModified)),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    androidx.compose.material3.TextButton(onClick = {
                                        my.bridge.com.update.UpdateManager.deleteCache(context, listOf(e.name))
                                        cacheList = my.bridge.com.update.UpdateManager.listCache(context)
                                        if (cacheList.isEmpty()) cacheMsg = "已清空"
                                    }) { Text("删除") }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        my.bridge.com.update.UpdateManager.deleteCache(context, cacheList.map { it.name })
                        cacheList = emptyList()
                        cacheMsg = "已全部清除"
                    }) { Text("全部清除") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { cacheVisible = false }) { Text("关闭") }
                },
            )
        }
    }
}

/** 把运行时长毫秒数格式化成「X 小时 Y 分 Z 秒」（不足 1 小时只显示分与秒）。 */
private fun formatUptime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d 小时 %d 分 %d 秒".format(hours, minutes, seconds)
    } else {
        "%d 分 %d 秒".format(minutes, seconds)
    }
}

// ============ 设置通用组件 ============
@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: String,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingValueRow(
    icon: String,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 把权限名转成友好中文标签（未知权限显示原名）。 */
private fun permissionLabel(permission: String): String = when (permission) {
    "android.permission.ACCESS_FINE_LOCATION" -> "精确定位"
    "android.permission.ACCESS_COARSE_LOCATION" -> "大致定位"
    "android.permission.CAMERA" -> "相机"
    "android.permission.BLUETOOTH_SCAN" -> "蓝牙扫描"
    "android.permission.BLUETOOTH_CONNECT" -> "蓝牙连接"
    "android.permission.POST_NOTIFICATIONS" -> "通知"
    else -> permission.substringAfterLast('.')
}

// ============ 后端连接设置 ============

/** 连接状态的中文文案 + 颜色。 */
private fun connectionStateText(state: my.bridge.com.core.network.ws.WsConnectionState): String =
    when (state) {
        my.bridge.com.core.network.ws.WsConnectionState.DISCONNECTED -> "未连接"
        my.bridge.com.core.network.ws.WsConnectionState.CONNECTING -> "连接中…"
        my.bridge.com.core.network.ws.WsConnectionState.CONNECTED -> "已连接"
        my.bridge.com.core.network.ws.WsConnectionState.RECONNECTING -> "重连中…"
        my.bridge.com.core.network.ws.WsConnectionState.STOPPED -> "已停止"
    }

private fun connectionStateColor(state: my.bridge.com.core.network.ws.WsConnectionState): androidx.compose.ui.graphics.Color =
    when (state) {
        my.bridge.com.core.network.ws.WsConnectionState.CONNECTED -> androidx.compose.ui.graphics.Color(0xFF2E9E5B)
        my.bridge.com.core.network.ws.WsConnectionState.STOPPED -> androidx.compose.ui.graphics.Color(0xFFE5484D)
        else -> androidx.compose.ui.graphics.Color(0xFFE8862E)
    }

@Composable
fun ConnectionSettings() {
    val viewModel: ConnectionViewModel = androidx.hilt.navigation.compose.hiltViewModel()

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val deviceId by viewModel.deviceId.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val serverHost by viewModel.serverHost.collectAsStateWithLifecycle()
    val serverPort by viewModel.serverPort.collectAsStateWithLifecycle()
    val uploadResult by viewModel.uploadResult.collectAsStateWithLifecycle()

    var showNameDialog by remember { mutableStateOf(false) }
    var showAddressDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionTitle("后端连接") }
        item {
            SettingCard {
                // 连接状态（实时）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔌", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    Text("连接状态", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        connectionStateText(connectionState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = connectionStateColor(connectionState),
                    )
                }
                // 设备 ID（只读）
                SettingValueRow(icon = "🆔", title = "设备标识", value = deviceId.ifBlank { "读取中…" }) {
                    Timber.d("设备标识：$deviceId")
                }
                // 设备昵称（可编辑）
                SettingValueRow(icon = "🏷️", title = "设备昵称", value = deviceName.ifBlank { "未设置" }) {
                    showNameDialog = true
                }
                // 服务地址（可编辑，改后重连）
                SettingValueRow(
                    icon = "🌐",
                    title = "服务地址",
                    value = "${serverHost.ifBlank { "读取中…" }}:${serverPort}",
                ) {
                    showAddressDialog = true
                }
                // 手动上传日志
                SettingValueRow(icon = "📤", title = "上传诊断日志", value = "上传") {
                    viewModel.uploadLog()
                }
            }
        }
        // 上传结果提示
        uploadResult?.let { result ->
            item {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        item {
            Text(
                text = "设备标识来自系统 ANDROID_ID，重装应用不变。昵称仅作展示名，用于服务端识别设备；不设置时默认显示设备标识。服务地址修改后会自动重连；心跳/重连参数由客户端与服务端硬编码，无需配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }

        // ===== 音频中继分区 =====
        item { SectionTitle("音频中继") }
        item {
            AudioRelaySettings()
        }

        // ===== 智能控制分区 =====
        item { SectionTitle("智能控制") }
        item {
            RelaySwitchSettings()
        }
    }

    // 昵称编辑弹窗
    if (showNameDialog) {
        NameEditDialog(
            initial = deviceName,
            onCancel = { showNameDialog = false },
            onConfirm = { newName ->
                viewModel.saveDeviceName(newName)
                showNameDialog = false
            },
        )
    }

    // 服务地址编辑弹窗
    if (showAddressDialog) {
        AddressEditDialog(
            initialHost = serverHost,
            initialPort = serverPort,
            onCancel = { showAddressDialog = false },
            onConfirm = { host, port ->
                viewModel.saveServerAddress(host, port)
                showAddressDialog = false
            },
        )
    }
}

@Composable
private fun AddressEditDialog(
    initialHost: String,
    initialPort: String,
    onCancel: () -> Unit,
    onConfirm: (host: String, port: String) -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("设置服务地址") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        error = null
                    },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text("如 10.0.2.2 或 example.com") },
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        error = null
                    },
                    label = { Text("端口") },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text("如 15160") },
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val err = my.bridge.com.core.network.ws.ServerAddress.validate(host, port)
                    if (err != null) {
                        error = err
                    } else {
                        onConfirm(host.trim(), port.trim())
                    }
                }
            ) { Text("保存并重连") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

@Composable
private fun NameEditDialog(
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("设置设备昵称") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("昵称") },
                singleLine = true,
                placeholder = { Text("留空则使用设备标识") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

// ============ 音频中继设置 ============

/**
 * 音频中继设置分区（设置 → 连接页）。
 *
 * 中转服务器可编辑、自动重连开关（默认开，关闭需二次确认，复用核心项确认样式）。
 * 后端零改动：中转服务器默认 039039.xyz:15151，与 Aurelay 原版共用同一后端。
 */
@Composable
private fun AudioRelaySettings() {
    val context = LocalContext.current

    var server by remember { mutableStateOf(RelayPrefs.relayServer(context)) }
    var token by remember { mutableStateOf(RelayPrefs.reportToken(context)) }
    var reportUrl by remember { mutableStateOf(RelayPrefs.reportUrl(context)) }
    var autoReconnect by remember { mutableStateOf(RelayPrefs.autoReconnect(context)) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showReportUrlDialog by remember { mutableStateOf(false) }
    // 自动重连关闭二次确认
    var pendingAutoReconnectOff by remember { mutableStateOf(false) }

    SettingCard {
        SettingValueRow(
            icon = "🌐",
            title = "中转服务器",
            value = server,
        ) {
            showServerDialog = true
        }
        SettingValueRow(
            icon = "🔑",
            title = "访问令牌",
            value = token,
        ) {
            showTokenDialog = true
        }
        SettingValueRow(
            icon = "📡",
            title = "上报地址",
            value = reportUrl,
        ) {
            showReportUrlDialog = true
        }
        SettingSwitchRow(
            icon = "🔁",
            title = "自动重连",
            checked = autoReconnect,
            onCheckedChange = { newValue ->
                if (!newValue) {
                    // 关闭自动重连需二次确认
                    pendingAutoReconnectOff = true
                } else {
                    autoReconnect = true
                    RelayPrefs.setBoolean(context, RelayPrefs.KEY_AUTO_RECONNECT, true)
                    Timber.d("切换：音频中继自动重连 -> 开")
                }
            },
        )
    }

    // 中转服务器编辑弹窗
    if (showServerDialog) {
        RelayServerDialog(
            initial = server,
            onCancel = { showServerDialog = false },
            onConfirm = { newServer ->
                RelayPrefs.setString(context, RelayPrefs.KEY_RELAY_SERVER, newServer)
                server = newServer
                showServerDialog = false
            },
        )
    }

    // 访问令牌编辑弹窗
    if (showTokenDialog) {
        RelayTextDialog(
            title = "设置访问令牌",
            label = "访问令牌",
            placeholder = "如 qwe951753",
            initial = token,
            onCancel = { showTokenDialog = false },
            onConfirm = { newToken ->
                RelayPrefs.setString(context, RelayPrefs.KEY_REPORT_TOKEN, newToken)
                token = newToken
                showTokenDialog = false
            },
        )
    }

    // 上报地址编辑弹窗
    if (showReportUrlDialog) {
        RelayTextDialog(
            title = "设置上报地址",
            label = "上报地址",
            placeholder = "如 http://039039.xyz:15151/api/aurelay/report",
            initial = reportUrl,
            onCancel = { showReportUrlDialog = false },
            onConfirm = { newUrl ->
                RelayPrefs.setString(context, RelayPrefs.KEY_REPORT_URL, newUrl)
                reportUrl = newUrl
                showReportUrlDialog = false
            },
        )
    }

    // 自动重连关闭二次确认
    if (pendingAutoReconnectOff) {
        AutoReconnectConfirmDialog(
            onCancel = { pendingAutoReconnectOff = false },
            onConfirm = {
                autoReconnect = false
                RelayPrefs.setBoolean(context, RelayPrefs.KEY_AUTO_RECONNECT, false)
                Timber.d("音频中继自动重连已二次确认关闭")
                pendingAutoReconnectOff = false
            },
        )
    }
}

/** 中转服务器地址编辑弹窗。 */
@Composable
private fun RelayServerDialog(
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("设置中转服务器") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("中转服务器") },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text("如 039039.xyz:15151") },
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val t = text.trim()
                    if (t.isEmpty()) {
                        error = "服务器地址不能为空"
                    } else {
                        onConfirm(t)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/** 通用文本编辑弹窗（访问令牌 / 上报地址等共用）。 */
@Composable
private fun RelayTextDialog(
    title: String,
    label: String,
    placeholder: String,
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                placeholder = { Text(placeholder) },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/** 自动重连关闭二次确认对话框（复用核心项确认样式）。 */
@Composable
private fun AutoReconnectConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        icon = { Text("⚠️", style = MaterialTheme.typography.headlineMedium) },
        title = { Text("关闭「自动重连」？") },
        text = {
            Text("自动重连是音频中继保持链路稳定的关键机制。关闭后，一旦网络波动或服务器断开连接，中继将不会自动恢复，需要手动重新开始接收。确定要关闭吗？")
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("确认关闭", color = androidx.compose.ui.graphics.Color(0xFFE5484D))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

// ============ 智能控制设置 ============

/**
 * 智能控制（ESP32 继电器）设置分区（设置 → 连接页）。
 *
 * 继电器服务器地址、API Key、当前设备、继电器端口均可编辑。
 * 后端零改动：客户端主动直连参考项目中继服务器（方案 A）。
 */
@Composable
private fun RelaySwitchSettings() {
    val context = LocalContext.current

    var server by remember { mutableStateOf(my.bridge.com.switch.RelaySwitchPrefs.server(context)) }
    var apiKey by remember { mutableStateOf(my.bridge.com.switch.RelaySwitchPrefs.apiKey(context)) }
    var deviceId by remember { mutableStateOf(my.bridge.com.switch.RelaySwitchPrefs.deviceId(context)) }
    var pio by remember {
        mutableStateOf(my.bridge.com.switch.RelaySwitchPrefs.pioForDevice(context, my.bridge.com.switch.RelaySwitchPrefs.deviceId(context)))
    }
    var showServerDialog by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var showPioDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    SettingCard {
        SettingValueRow(
            icon = "🌐",
            title = "继电器服务器",
            value = server,
        ) {
            showServerDialog = true
        }
        SettingValueRow(
            icon = "🆔",
            title = "当前设备",
            value = deviceId,
        ) {
            showDeviceDialog = true
        }
        SettingValueRow(
            icon = "🔌",
            title = "继电器端口",
            value = pio.toString(),
        ) {
            showPioDialog = true
        }
        SettingValueRow(
            icon = "🔑",
            title = "API Key",
            value = apiKey,
        ) {
            showApiKeyDialog = true
        }
    }

    if (showServerDialog) {
        RelayServerDialog(
            initial = server,
            onCancel = { showServerDialog = false },
            onConfirm = { v ->
                my.bridge.com.switch.RelaySwitchPrefs.setString(context, my.bridge.com.switch.RelaySwitchPrefs.KEY_SERVER, v)
                server = v
                showServerDialog = false
            },
        )
    }
    if (showDeviceDialog) {
        RelayTextDialog(
            title = "设置当前设备",
            label = "设备 ID",
            placeholder = "如 esp32_001",
            initial = deviceId,
            onCancel = { showDeviceDialog = false },
            onConfirm = { v ->
                my.bridge.com.switch.RelaySwitchPrefs.setString(context, my.bridge.com.switch.RelaySwitchPrefs.KEY_DEVICE_ID, v)
                deviceId = v
                showDeviceDialog = false
            },
        )
    }
    if (showPioDialog) {
        RelayTextDialog(
            title = "设置继电器端口",
            label = "端口 (1-9)",
            placeholder = "如 6",
            initial = pio.toString(),
            onCancel = { showPioDialog = false },
            onConfirm = { v ->
                val n = v.toIntOrNull()
                if (n == null || n !in 1..9) {
                    android.widget.Toast.makeText(context, "端口必须在 1-9 之间", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    my.bridge.com.switch.RelaySwitchPrefs.setPioForDevice(context, deviceId, n)
                    pio = n
                    showPioDialog = false
                }
            },
        )
    }
    if (showApiKeyDialog) {
        RelayTextDialog(
            title = "设置 API Key",
            label = "API Key",
            placeholder = "留空则不启用认证",
            initial = apiKey,
            onCancel = { showApiKeyDialog = false },
            onConfirm = { v ->
                my.bridge.com.switch.RelaySwitchPrefs.setString(context, my.bridge.com.switch.RelaySwitchPrefs.KEY_API_KEY, v)
                apiKey = v
                showApiKeyDialog = false
            },
        )
    }
}
