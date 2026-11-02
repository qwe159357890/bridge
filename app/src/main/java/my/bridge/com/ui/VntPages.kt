package my.bridge.com.ui

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import my.bridge.com.vnt.VntMeshConfig
import my.bridge.com.vnt.VntMeshController
import my.bridge.com.vnt.VntMeshPrefs
import my.bridge.com.vnt.VntMeshState
import my.bridge.com.vnt.VntPeer
import my.bridge.com.vnt.VntServerAddress
import my.bridge.com.vnt.VntStatus

// 与交互原型 ui-mockup-vnt-mesh.html 对齐的配色
private val VntBlue = Color(0xFF1A6EE8)
private val VntGreen = Color(0xFF2E9E5B)
private val VntRed = Color(0xFFE5484D)
private val VntInk = Color(0xFF1F2329)
private val VntGray = Color(0xFF6B7280)
private val VntBg = Color(0xFFF5F6F8)
private val VntCard = Color(0xFFFFFFFF)

/**
 * 组网页面（v0.2.32，1.x / VntApp v1.2.9，主页第 5 个 Tab）。
 *
 * 生命周期：连接由 [my.bridge.com.vnt.VntVpnService] 前台常驻，页面**只订阅状态、
 * 不启停连接**（照 `AudioRelayPage` 做法）。切 Tab / 退后台不断网。
 *
 * 刷新：仅「App 在前台 + 处于本页面」时才刷新；进页面立即刷一次，之后 5 秒一次；
 * 离开页面 / ON_PAUSE 取消定时任务（细节见 [VntMeshController.startRefresh]）。
 *
 * ★ 1.x 能力边界：底层只有 `Vnt.list() -> PeerRouteInfo[]`，**没有速率 / NAT / 真实 RTT /
 * 服务端列表**，也不存在 2.x 的 `udp://` 协议前缀；配置改为裸 `host:port` + UDP/TCP 开关。
 */
@Composable
fun VntPage() {
    val context = LocalContext.current
    val state by VntMeshController.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 配置弹窗（单套配置）
    var showConfig by remember { mutableStateOf(false) }

    // 系统 VPN 授权：prepare() 返回非 null 时必须经 Activity 弹框
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // v0.2.31：用户主动点「开始组网」→ 记忆，下次冷启动自动开始。
            // 授权被拒时不写记忆（用户并未真正开启）。
            VntMeshPrefs.setAutoStart(context, true)
            VntMeshController.start(context)
        } else {
            Toast.makeText(context, "需要系统 VPN 授权才能组网", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestStart() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            consentLauncher.launch(prepareIntent)
        } else {
            VntMeshPrefs.setAutoStart(context, true)
            VntMeshController.start(context)
        }
    }

    // 前台可见性守卫：ON_RESUME 才允许刷新，ON_PAUSE 立即停（一次都不再拉）
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (VntMeshController.state.value.isRunning) VntMeshController.startRefresh()
                }
                Lifecycle.Event.ON_PAUSE -> VntMeshController.stopRefresh()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            VntMeshController.stopRefresh()
        }
    }

    // 连接刚建立（页面已可见）时立即开始刷新；断连时停止
    LaunchedEffect(state.status) {
        if (state.status == VntStatus.RUNNING &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            VntMeshController.startRefresh()
        } else if (state.status != VntStatus.RUNNING) {
            VntMeshController.stopRefresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VntBg)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ① 状态卡
        VntStatusCard(state = state)

        // ② 本机信息卡（连接后显示）
        if (state.isRunning) {
            VntInfoCard(state = state)
        } else {
            // 未连接时展示当前配置摘要，方便点开始之前核对
            val cfg = remember { VntMeshPrefs.load(context) }
            VntConfigSummaryCard(cfg = cfg, onEdit = { showConfig = true })
        }

        // ③ 设备列表
        VntPeerCard(peers = state.peers, lastUpdatedAt = state.lastUpdatedAt, running = state.isRunning)

        // ④ 主按钮 + 配置入口
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { showConfig = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8EDF5)),
            ) {
                Text("组网配置", color = VntInk, fontSize = 14.sp)
            }
            if (state.status == VntStatus.RUNNING || state.status == VntStatus.STARTING) {
                Button(
                    onClick = {
                        // v0.2.31：用户主动点「停止组网」→ 清记忆，下次启动需手动开启。
                        VntMeshPrefs.setAutoStart(context, false)
                        VntMeshController.stop(context)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = VntRed),
                ) {
                    Text("停止组网", color = Color.White, fontSize = 14.sp)
                }
            } else {
                Button(
                    onClick = { requestStart() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = VntBlue),
                ) {
                    Text("开始组网", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        Text(
            "组网是基础设施：开始后切 Tab / 退后台不中断，只有点「停止组网」或退出应用才结束。" +
                " Android 同时只允许一个 VPN，启动会断开其它 VPN。" +
                " ★ 1.x 互通要求：Token、密码、加密模式、服务端加密、指纹校验必须与存量 1.x 客户端逐项一致。",
            color = VntGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
    }

    if (showConfig) {
        VntConfigDialog(
            initial = VntMeshPrefs.load(context),
            onDismiss = { showConfig = false },
            onSave = { cfg ->
                VntMeshPrefs.save(context, cfg)
                showConfig = false
                if (VntMeshController.state.value.isRunning) {
                    VntMeshController.restart(context)
                    Toast.makeText(context, "配置已保存，正在重连", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

// ============ 卡片组件 ============

@Composable
private fun VntCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = VntCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun VntStatusCard(state: VntMeshState) {
    val (bg, title, subtitle) = when (state.status) {
        VntStatus.RUNNING -> Triple(
            VntGreen,
            "已连接 · ${state.virtualIp ?: "—"}",
            "虚拟局域网已建立，可与同组网编号（Token）下的设备互访",
        )
        VntStatus.STARTING -> Triple(VntBlue, "正在建立虚拟网络…", state.message)
        VntStatus.STOPPING -> Triple(VntGray, "正在停止…", state.message)
        VntStatus.ERROR -> Triple(VntRed, "组网异常", state.message)
        VntStatus.STOPPED -> Triple(VntBlue, "组网未连接", "点击下方「开始组网」建立虚拟局域网")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.status == VntStatus.STARTING) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
            }
        }
        if (state.isRunning) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VntChip(if (state.tcp) "TCP" else "UDP")
                VntChip("MTU ${state.mtu}")
                VntChip("在线 ${state.peers.count { it.online }}")
            }
        }
    }
}

@Composable
private fun VntChip(text: String) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun VntInfoCard(state: VntMeshState) {
    VntCard {
        Text("本机信息", color = VntInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        VntInfoRow("虚拟 IPv4", state.virtualIp ?: "—")
        VntInfoRow("组网编号 Token", state.token.ifBlank { "—" })
        VntInfoRow("设备名", state.deviceName)
        VntInfoRow("服务端", state.server)
        VntInfoRow("协议", if (state.tcp) "TCP" else "UDP")
        VntInfoRow("服务端版本", state.serverVersion ?: "—")
        VntInfoRow("MTU", state.mtu.toString())
    }
}

@Composable
private fun VntInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = VntGray, fontSize = 12.sp)
        Text(value, color = VntInk, fontSize = 12.sp)
    }
}

@Composable
private fun VntConfigSummaryCard(cfg: VntMeshConfig, onEdit: () -> Unit) {
    VntCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "连接信息",
                color = VntInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEdit) { Text("修改", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        VntInfoRow("服务端", cfg.server)
        VntInfoRow("组网编号", cfg.token.ifBlank { "未填写（必填）" })
        VntInfoRow("协议", if (cfg.tcp) "TCP" else "UDP")
        VntInfoRow("加密模式", cfg.cipherModel)
        VntInfoRow("设备名", cfg.deviceName)
    }
}

@Composable
private fun VntPeerCard(
    peers: List<VntPeer>,
    lastUpdatedAt: Long,
    running: Boolean,
) {
    VntCard {
        val online = peers.count { it.online }
        Text(
            if (running) "在线设备（$online）" else "设备列表（未连接）",
            color = VntInk,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        if (!running) {
            Text("开始组网后，仅在停留本页期间每 5 秒刷新一次", color = VntGray, fontSize = 11.sp)
            return@VntCard
        }
        if (peers.isEmpty()) {
            Text("正在获取设备列表…", color = VntGray, fontSize = 12.sp)
            return@VntCard
        }
        peers.forEach { peer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (peer.online) VntGreen else VntGray,
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(peer.ip, color = VntInk, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(peer.name, color = VntGray, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (peer.online) (if (peer.direct) "P2P 直连" else "服务端中继") else "离线",
                        color = VntGray,
                        fontSize = 11.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        peer.rt?.let { "RT $it" } ?: "—",
                        color = VntGray,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(peer.status, color = VntGray, fontSize = 11.sp)
                }
            }
        }
        if (lastUpdatedAt > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                "设备上下线无 Java 层事件，UI 最长滞后一个刷新周期（5 秒）；" +
                    "退到后台不刷新，回前台首次刷新补齐。P2P / 中继由底层路由 metric 判定。",
                color = VntGray,
                fontSize = 10.sp,
            )
        }
    }
}

// ============ 配置弹窗（单套配置，1.x 字段） ============

@Composable
private fun VntConfigDialog(
    initial: VntMeshConfig,
    onDismiss: () -> Unit,
    onSave: (VntMeshConfig) -> Unit,
) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(initial.server) }
    var token by remember { mutableStateOf(initial.token) }
    var tcp by remember { mutableStateOf(initial.tcp) }
    var password by remember { mutableStateOf(initial.password) }
    var cipherModel by remember { mutableStateOf(initial.cipherModel) }
    var deviceName by remember { mutableStateOf(initial.deviceName) }
    var ip by remember { mutableStateOf(initial.ip) }
    var mtuText by remember { mutableStateOf(initial.mtu.toString()) }
    var serverEncrypt by remember { mutableStateOf(initial.serverEncrypt) }
    var finger by remember { mutableStateOf(initial.finger) }
    var firstLatency by remember { mutableStateOf(initial.firstLatency) }
    var stunText by remember { mutableStateOf(initial.stunServers.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("组网配置（1.x）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VntField("服务端地址", server, { server = it }, "039039.xyz:31007")
                Text(
                    "1.x 只接受裸 host:port，**不要带 udp:// / tcp:// 等协议前缀**；" +
                        "UDP / TCP 用下方开关选择。默认指向你的自建 VNTS。",
                    color = VntGray,
                    fontSize = 10.sp,
                )
                VntField("组网编号 Token", token, { token = it }, "必填，与存量 1.x 客户端一致")
                VntToggle("使用 TCP（关闭=UDP）", tcp) { tcp = it }
                Text(
                    "你真机 1.2.13 原生 APK 选的是「协议 UDP」，对应此处开关**关闭**。",
                    color = VntGray,
                    fontSize = 10.sp,
                )
                VntField("客户端加密密码", password, { password = it }, "留空不加密；须与其它客户端一致")
                VntField("加密模式", cipherModel, { cipherModel = it }, "aes_gcm")
                Text(
                    "官方 v1.2.9 可选：aes_gcm / aes_cbc / aes_ecb（默认 aes_gcm）。" +
                        "不同加密状态的客户端不能通信。",
                    color = VntGray,
                    fontSize = 10.sp,
                )
                VntField("设备名", deviceName, { deviceName = it }, "Bridge")
                VntField("固定虚拟 IP", ip, { ip = it }, "留空由服务端动态分配")
                VntField("MTU", mtuText, { mtuText = it }, "1410")
                VntToggle("服务端加密", serverEncrypt) { serverEncrypt = it }
                VntToggle("指纹校验", finger) { finger = it }
                VntToggle("延迟优先（关闭=P2P 优先）", firstLatency) { firstLatency = it }
                VntField(
                    "STUN 服务器（每行一个）",
                    stunText,
                    { stunText = it },
                    "stun1.l.google.com:19302",
                    multiLine = true,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "高级（子网转发 / 端口映射）：本期不做，后续版本开放。" +
                        " ★ 与存量 1.x 客户端互通的前提：Token / 密码 / 加密模式 / 服务端加密 / 指纹校验逐项一致。",
                    color = VntGray,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 保存前按 1.x 规则校验，避免存进去后 native 抛晦涩异常
                VntServerAddress.error(server)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    return@TextButton
                }
                if (token.isBlank()) {
                    Toast.makeText(context, "组网编号（Token）不能为空", Toast.LENGTH_LONG).show()
                    return@TextButton
                }
                val mtu = mtuText.trim().toIntOrNull()
                    ?.coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU)
                    ?: VntMeshConfig.DEFAULT_MTU
                val stuns = stunText.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { VntMeshConfig.DEFAULT_STUN }
                onSave(
                    initial.copy(
                        server = server.trim(),
                        token = token.trim(),
                        tcp = tcp,
                        password = password,
                        cipherModel = cipherModel.trim().ifBlank { VntMeshConfig.DEFAULT_CIPHER },
                        deviceName = deviceName.trim(),
                        ip = ip.trim(),
                        mtu = mtu,
                        serverEncrypt = serverEncrypt,
                        finger = finger,
                        firstLatency = firstLatency,
                        stunServers = stuns,
                    )
                )
            }) { Text("保存并重连", color = VntBlue) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = VntGray) }
        },
    )
}

@Composable
private fun VntField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    multiLine: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(placeholder, fontSize = 11.sp) },
        singleLine = !multiLine,
        maxLines = if (multiLine) 4 else 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VntToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = VntInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
