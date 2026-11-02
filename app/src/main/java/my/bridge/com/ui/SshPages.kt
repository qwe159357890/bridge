package my.bridge.com.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.bridge.com.ssh.SshPrefs
import my.bridge.com.ssh.SshServerController
import my.bridge.com.ssh.SshStatus
import my.bridge.com.vnt.VntMeshController
import my.bridge.com.vnt.VntStatus
import timber.log.Timber

/**
 * SSH 服务 UI（v0.2.34）——设置→连接页分区 + 独立状态页。
 *
 * ★ 生命周期约定（调研报告 §6.3 已拍板，与「智能控制」相反）：SSH 是**常驻服务**，
 * 页面 onDispose **绝不**停服务；开关与页面只改「意愿」（SshPrefs.enabled）并调
 * Controller 启停，启停后果由自愈链兜底。
 *
 * 导航：项目无 NavHost（全状态驱动），状态页经 [SshUiState.showPage] 由 MainScreen
 * 以覆盖层方式组合；页内 BackHandler 后组合、优先接管返回键。
 */
object SshUiState {
    var showPage by mutableStateOf(false)
}

// ============ 设置 → 连接页「SSH 服务」分区 ============

@Composable
fun SshSettingsSection() {
    val context = LocalContext.current
    val state by SshServerController.state.collectAsStateWithLifecycle()
    val active = state.status == SshStatus.RUNNING || state.status == SshStatus.STARTING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🖥️", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SSH 服务器（SFTP · 终端）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "端口 ${SshPrefs.port(context)} · 局域网与 VNT 虚拟网可达",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = active,
                    onCheckedChange = { on ->
                        if (on) {
                            SshPrefs.setEnabled(context, true)
                            SshServerController.start(context)
                        } else {
                            SshPrefs.setEnabled(context, false)
                            SshServerController.stop(context)
                        }
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { SshUiState.showPage = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sshStatusText(state.status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = sshStatusColor(state.status),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "详情 ›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun sshStatusText(status: SshStatus): String = when (status) {
    SshStatus.RUNNING -> "运行中 · 会话 ${SshServerController.state.value.sessions}"
    SshStatus.STARTING -> "启动中…"
    SshStatus.STOPPING -> "停止中…"
    SshStatus.STOPPED -> "已停止"
    SshStatus.ERROR -> "异常"
}

fun sshStatusColor(status: SshStatus): Color = when (status) {
    SshStatus.RUNNING -> Color(0xFF2E9E5B)
    SshStatus.STOPPED -> Color(0xFF8A919F)
    SshStatus.ERROR -> Color(0xFFE5484D)
    else -> Color(0xFFE8862E)
}

// ============ SSH 服务状态页（独立覆盖层） ============

@Composable
fun SshServerPage() {
    BackHandler { SshUiState.showPage = false }

    val context = LocalContext.current
    val state by SshServerController.state.collectAsStateWithLifecycle()
    val vntState by VntMeshController.state.collectAsStateWithLifecycle()

    var showPwdDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }

    // 密码 / 指纹：进页面读一次（密码明文存应用私有 SP，状态页拍板要求可见）
    var password by remember { mutableStateOf("") }
    var pwdVisible by remember { mutableStateOf(true) }
    var fingerprint by remember { mutableStateOf("读取中…") }
    LaunchedEffect(Unit) {
        password = SshPrefs.password(context)
        fingerprint = my.bridge.com.ssh.SshHostKeyStore.fingerprint(context)
            ?: "不可用（服务首次启动时生成）"
    }
    val localIps = remember { my.bridge.com.ssh.SshServerService.localIpAddresses() }

    // 从系统设置授权页返回后刷新存储档位（ON_RESUME 重新计算）
    var storageTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val storageGranted = remember(storageTick) { SshPrefs.allFilesAccessGranted() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏
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
                    .clickable { SshUiState.showPage = false }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text("SSH 服务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                sshStatusText(state.status),
                fontSize = 12.sp,
                color = sshStatusColor(state.status),
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
            HintText(
                "PC 连接：ssh -p ${SshPrefs.port(context)} ${SshPrefs.USERNAME}@<手机IP>，" +
                    "或用 WinSCP（协议 SFTP）。同一服务同时服务 WiFi 局域网与 VNT 虚拟网。"
            )

            // ---- 连接信息 ----
            SectionCard("连接信息") {
                InfoRow("地址（WiFi）", localIps.firstOrNull() ?: "未连接 WiFi")
                val vntIp = vntState.virtualIp
                InfoRow("地址（VNT 虚拟网）", if (vntState.status == VntStatus.RUNNING && vntIp != null) vntIp else "组网未运行")
                InfoRow("端口", "${state.port.takeIf { it != 0 } ?: SshPrefs.port(context)}")
                InfoRow("用户名", SshPrefs.USERNAME)
                InfoRow("活动会话", "${state.sessions}")
            }

            // ---- 访问密码 ----
            SectionCard("访问密码") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pwdVisible) password else "•••• •••• ••••",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { pwdVisible = !pwdVisible },
                    )
                    Text(
                        "修改",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { showPwdDialog = true }
                            .padding(8.dp),
                    )
                }
                Text(
                    "首次启动随机生成 · 修改后持久化，重启服务/重启 APP 均沿用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 主机密钥 ----
            SectionCard("主机密钥指纹（首次连接时客户端应与此一致）") {
                Text(
                    fingerprint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    "首次启动生成并持久化；更换会使所有客户端重新确认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 存储访问 ----
            SectionCard("存储访问") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (storageGranted) "所有文件访问 · 已授权" else "所有文件访问 · 未授权",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (storageGranted) "SFTP 根目录 = /sdcard（全盘档）"
                            else "当前仅可访问应用目录（基础档）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!storageGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Text(
                            "去授权",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    runCatching {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    }.onFailure {
                                        Timber.w(it, "跳转「所有文件访问」设置失败")
                                    }
                                }
                                .padding(8.dp),
                        )
                    }
                }
            }

            // ---- 高级 ----
            SectionCard("高级") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPortDialog = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("端口", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${SshPrefs.port(context)}",
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var wakeOn by remember(storageTick) { mutableStateOf(SshPrefs.wakeLockEnabled(context)) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("灭屏保持连接（WakeLock）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "运行期间持锁防灭屏断连；关闭更省电",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = wakeOn,
                        onCheckedChange = { on ->
                            wakeOn = on
                            SshPrefs.setWakeLockEnabled(context, on)
                        },
                    )
                }
            }

            // ---- 启停按钮（颜色 = 点击后动作，与智能控制语义一致）----
            val busy = state.status == SshStatus.STARTING || state.status == SshStatus.STOPPING
            Button(
                onClick = {
                    if (state.status == SshStatus.RUNNING) {
                        SshPrefs.setEnabled(context, false)
                        SshServerController.stop(context)
                    } else if (state.status == SshStatus.STOPPED || state.status == SshStatus.ERROR) {
                        SshPrefs.setEnabled(context, true)
                        SshServerController.start(context)
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.status == SshStatus.RUNNING) Color(0xFFE5484D)
                    else Color(0xFF2E9E5B),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    when (state.status) {
                        SshStatus.RUNNING -> "停止服务"
                        SshStatus.STARTING -> "启动中…"
                        SshStatus.STOPPING -> "停止中…"
                        SshStatus.ERROR -> "重试启动"
                        SshStatus.STOPPED -> "开启服务"
                    }
                )
            }
            state.message.takeIf { it.isNotBlank() && state.status != SshStatus.RUNNING }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }

    if (showPwdDialog) {
        PasswordEditDialog(
            current = password,
            onCancel = { showPwdDialog = false },
            onConfirm = { newPwd ->
                if (SshPrefs.setPassword(context, newPwd)) {
                    password = newPwd.trim()
                    Timber.i("SSH 访问密码已修改并持久化")
                }
                showPwdDialog = false
            },
        )
    }

    if (showPortDialog) {
        PortEditDialog(
            current = SshPrefs.port(context),
            onCancel = { showPortDialog = false },
            onConfirm = { newPort ->
                SshPrefs.setPort(context, newPort)
                showPortDialog = false
            },
        )
    }
}

// ============ 小组件 ============

@Composable
private fun HintText(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PasswordEditDialog(
    current: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("修改访问密码") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("至少 8 位") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { text = SshPrefs.generatePassword() }) {
                    Text("随机生成")
                }
                Text(
                    "保存后立即生效并持久化；已登录的会话不受影响",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.trim().length >= 8) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

@Composable
private fun PortEditDialog(
    current: Int,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val port = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("修改端口") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    label = { Text("1024 - 65535") },
                )
                Text(
                    "非 root 不能绑定 1024 以下端口；修改后需重启服务生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { port?.let(onConfirm) },
                enabled = port != null && port in 1024..65535,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}
