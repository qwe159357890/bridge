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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
@Composable
fun KeepAliveSettings() {
    val context = LocalContext.current

    // 从 KeepAlivePrefs 读取各层开关当前值
    var always by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_ALWAYS)) }
    var boot by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_BOOT)) }
    var fallback by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_FALLBACK)) }
    var resource by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_RESOURCE)) }
    var silentPlay by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_SILENT_PLAY)) }
    var onePixel by remember { mutableStateOf(KeepAlivePrefs.getBoolean(context, KeepAlivePrefs.KEY_KEEP_ALIVE_ONE_PIXEL)) }

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
                SettingSwitchRow(
                    icon = "📡",
                    title = "资源锚点",
                    checked = resource,
                    onCheckedChange = {
                        resource = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_RESOURCE, it)
                        Timber.d("切换：资源锚点 -> ${if (it) "开" else "关"}")
                    },
                )
                SettingSwitchRow(
                    icon = "🔇",
                    title = "无声播放",
                    checked = silentPlay,
                    onCheckedChange = {
                        silentPlay = it
                        update(KeepAlivePrefs.KEY_KEEP_ALIVE_SILENT_PLAY, it)
                        Timber.d("切换：无声播放 -> ${if (it) "开" else "关"}")
                    },
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
                text = "保活策略借鉴 Aurelay + SmsForwarder，详见 docs/keepalive/design.md。\n默认已开启最有效的几项；一像素因需悬浮窗权限默认关闭。",
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
