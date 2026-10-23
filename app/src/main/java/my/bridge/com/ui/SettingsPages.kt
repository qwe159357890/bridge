package my.bridge.com.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import my.bridge.com.BuildConfig
import my.bridge.com.keepalive.KeepAlivePrefs
import my.bridge.com.keepalive.KeepAliveService
import my.bridge.com.keepalive.KeepAliveUtils
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

    // 细化弹窗状态
    var showResourceSheet by remember { mutableStateOf(false) }
    // 核心项二次确认：非空时表示「用户正试图关闭某核心项」
    var pendingCoreOff by remember { mutableStateOf<ResourceOption?>(null) }

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
) {
    val context = LocalContext.current
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
                val checked = KeepAlivePrefs.getBoolean(context, opt.key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(opt) }
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
                    Switch(checked = checked, onCheckedChange = { onToggle(opt) })
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
