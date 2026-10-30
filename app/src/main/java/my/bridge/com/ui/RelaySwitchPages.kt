package my.bridge.com.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import my.bridge.com.switch.RelaySwitchClient
import my.bridge.com.switch.RelaySwitchPrefs
import my.bridge.com.switch.RelaySwitchProtocol
import my.bridge.com.switch.RelaySwitchState

// 智能控制页配色（严格照 style.css：badge-on=绿，badge-off=红，badge-wait=灰；
// btn-turn-on=红（当前关闭、点击开启），btn-turn-off=绿（当前开启、点击关闭），btn-disabled=灰）
private val SwitchGreen = Color(0xFF2E9E5B)
private val SwitchRed = Color(0xFFE5484D)
private val SwitchGray = Color(0xFF8A919F)

/**
 * 智能控制页（主页「发现」Tab 改为「智能控制」）。
 *
 * 交互流程严格照 references/relay-control/src/main.ts 移植：
 * - 真实开关状态仍完全由 connected + device.online + statusCache 推导；
 * - 点击开关只发 set + Toast，UI 等设备上报 status 驱动刷新；另加命令确认层防止重复点击，
 *   15 秒未确认自动查询，查询 10 秒仍无回应则显示「重试开启/关闭」；
 * - 窗格模式点击卡片 = 选中该设备并切回单卡模式；
 * - 设置保存后整体重连。
 */
@Composable
fun RelaySwitchPage() {
    val context = LocalContext.current
    val client = remember { SwitchClientHolder.get() }

    // ★ 唯一状态来源：不可变快照。读取即自动订阅重组
    val ui = RelaySwitchState.state
    var paneMode by remember { mutableStateOf(RelaySwitchPrefs.paneMode(context)) }

    // 二次确认弹窗状态
    var pendingCloseDevice by remember { mutableStateOf<RelaySwitchState.Device?>(null) }
    // 设置弹窗状态
    var showSettings by remember { mutableStateOf(false) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        // 对应 showToast：客户端/服务端消息统一走 Toast
        RelaySwitchState.setToastListener { message ->
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        // 页面进入即连接
        client.start(context)
        onDispose {
            // 离开页面即释放连接（省电优先，页面少用，不追求重连低延迟）
            client.stop()
            RelaySwitchState.setToastListener(null)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        // 对应 updateNavTitle：窗格=「设备列表」，单卡=当前设备名
        SectionTitle(
            if (paneMode) "设备列表"
            else RelaySwitchState.deviceName(ui.devices, RelaySwitchPrefs.deviceId(context))
        )

        // 顶部工具条：窗格开关 + 连接状态 + 设置入口
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        // 对应 togglePaneMode：先持久化，再同步订阅范围
                        val enabled = !paneMode
                        RelaySwitchPrefs.setBoolean(context, RelaySwitchPrefs.KEY_PANE_MODE, enabled)
                        paneMode = enabled
                        client.onPaneModeChanged(context, enabled)
                    }
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("窗格模式", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = paneMode,
                    onCheckedChange = null,
                )
            }
            Spacer(Modifier.width(8.dp))
            ConnectionPill(ui.connected)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { showSettings = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙️", style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 内容区：窗格模式用每行两列卡片网格，单设备模式用单卡。
        // 卡片所需的 value / connected 均由上层从快照计算后**作为参数传入**，
        // Compose 可可靠检测变化并重组。
        if (paneMode) {
            val sorted = RelaySwitchState.sortedDevices(ui.devices, ui.deviceOrder)
            if (sorted.isEmpty()) {
                EmptyState("暂无设备")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                ) {
                    gridItems(sorted, key = { it.id }) { device ->
                        val pio = RelaySwitchPrefs.pioForDevice(context, device.id)
                        val value = RelaySwitchState.pioValue(ui.statusCache[device.id], pio)
                        val command = ui.pendingCommands[RelaySwitchState.commandKey(device.id, pio)]
                        PaneCard(
                            device = device,
                            value = value,
                            connected = ui.connected,
                            pendingCommand = command,
                            onToggle = { isOn ->
                                if (command?.awaitingRetry == true) {
                                    client.setRelay(device.id, pio, command.targetValue)
                                } else if (isOn) {
                                    pendingCloseDevice = device
                                } else {
                                    client.setRelay(device.id, pio, 0)
                                }
                            },
                            // 对应 pane-card click：选中该设备并切回单卡模式
                            onSelect = {
                                RelaySwitchPrefs.setString(context, RelaySwitchPrefs.KEY_DEVICE_ID, device.id)
                                RelaySwitchPrefs.setBoolean(context, RelaySwitchPrefs.KEY_PANE_MODE, false)
                                paneMode = false
                                client.onDeviceSelected(context, device.id)
                            },
                        )
                    }
                }
            }
        } else {
            val currentId = RelaySwitchPrefs.deviceId(context)
            val device = ui.devices.find { it.id == currentId }
                ?: RelaySwitchState.Device(currentId, currentId, false)
            val pio = RelaySwitchPrefs.pioForDevice(context, currentId)
            val value = RelaySwitchState.pioValue(ui.statusCache[currentId], pio)
            val command = ui.pendingCommands[RelaySwitchState.commandKey(currentId, pio)]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceCard(
                    device = device,
                    value = value,
                    connected = ui.connected,
                    pendingCommand = command,
                    onToggle = { isOn ->
                        if (command?.awaitingRetry == true) {
                            client.setRelay(device.id, pio, command.targetValue)
                        } else if (isOn) {
                            pendingCloseDevice = device
                        } else {
                            client.setRelay(device.id, pio, 0)
                        }
                    },
                )
                Text(
                    text = "控制 ESP32 继电器设备（智能开关）。开启后 Bridge 主动连接继电器服务器，" +
                        "服务器地址与 API Key 可在设置中配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }

    // 关闭二次确认（对应 showConfirm）
    pendingCloseDevice?.let { device ->
        CloseConfirmDialog(
            device = device,
            onCancel = { pendingCloseDevice = null },
            onConfirm = {
                client.setRelay(device.id, RelaySwitchPrefs.pioForDevice(context, device.id), 1)
                pendingCloseDevice = null
            },
        )
    }

    // 服务器设置弹窗（保存后整体重连，对应 save-settings 的处理）
    if (showSettings) {
        RelaySwitchSettingsDialog(
            client = client,
            onDismiss = { showSettings = false },
            onSaved = { client.restart(context) },
        )
    }
}

/** 客户端单例持有器：保证页面重组间复用同一 WebSocket 客户端实例。 */
private object SwitchClientHolder {
    @Volatile
    private var client: RelaySwitchClient? = null

    @Synchronized
    fun get(): RelaySwitchClient = client ?: RelaySwitchClient().also { client = it }
}

/**
 * 卡片显示态推导（严格照参考 renderPaneCard / updateUI 的三态分支）。
 *
 * - WAITING：WS 未连接或设备离线 → 「等待连接」灰，按钮禁用；
 * - FETCHING：已连接且在线但尚无状态值 → 「获取中…」灰，按钮禁用；
 * - ON / OFF：有真实电平值 → 已开启（绿徽章 + 绿「关闭设备」）/ 已关闭（红徽章 + 红「开启设备」）。
 */
private enum class CardPhase { WAITING, FETCHING, ON, OFF }

private fun cardPhase(connected: Boolean, online: Boolean, value: Int?): CardPhase = when {
    !connected || !online -> CardPhase.WAITING
    value == null -> CardPhase.FETCHING
    value == 0 -> CardPhase.ON
    else -> CardPhase.OFF
}

/** 连接状态胶囊（对应 connection-pill，两态：已连接/未连接）。 */
@Composable
private fun ConnectionPill(connected: Boolean) {
    val (text, color) = if (connected) "已连接" to SwitchGreen else "未连接" to SwitchGray
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 单设备卡片（含电源环光晕 + SVG 图标 + 粒子特效，浅色化还原原项目视觉）。 */
@Composable
private fun DeviceCard(
    device: RelaySwitchState.Device,
    /** 该设备当前引脚的电平值：0=开，1=关，null=尚未取得。 */
    value: Int?,
    /** WS 是否已连接。 */
    connected: Boolean,
    /** 当前端口的待确认命令；只影响按钮，不篡改真实状态徽章。 */
    pendingCommand: RelaySwitchState.PendingCommand?,
    onToggle: (isOn: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val pio = RelaySwitchPrefs.pioForDevice(context, device.id)
    val phase = cardPhase(connected, device.online, value)
    val isOn = phase == CardPhase.ON
    val hasValue = phase == CardPhase.ON || phase == CardPhase.OFF
    val awaitingConfirmation = pendingCommand != null && !pendingCommand.awaitingRetry
    val retryCommand = pendingCommand?.takeIf { it.awaitingRetry }

    // 状态配色（与原项目一致：开启=绿，关闭=红，等待=灰）
    val ringColor = when (phase) {
        CardPhase.ON -> SwitchGreen
        CardPhase.OFF -> SwitchRed
        else -> SwitchGray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 背景粒子特效
            ParticleBackground(
                online = device.online && connected,
                isOn = isOn,
                hasValue = hasValue,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 电源环（外圈旋转光环 + 内圈脉冲光晕 + 中心图标）
                PowerRing(
                    online = device.online && connected,
                    isOn = isOn,
                    hasValue = hasValue,
                    ringColor = ringColor,
                )
                Spacer(Modifier.height(14.dp))
                Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    RelaySwitchProtocol.pioName(pio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                // 状态徽章（含 SVG 图标；严格照参考：已开启=绿，已关闭=红，等待/获取中=灰）
                val (badgeText, badgeColor) = when (phase) {
                    CardPhase.WAITING -> "等待连接" to SwitchGray
                    CardPhase.FETCHING -> "获取中…" to SwitchGray
                    CardPhase.ON -> "已开启" to SwitchGreen
                    CardPhase.OFF -> "已关闭" to SwitchRed
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    BadgeIcon(
                        isOnline = device.online && connected,
                        hasValue = hasValue,
                        isOn = isOn,
                        tint = badgeColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(badgeText, style = MaterialTheme.typography.labelMedium, color = badgeColor)
                }
                Spacer(Modifier.height(14.dp))

                // 等待确认只禁用当前按钮；超时后按原目标显示「重试开启/关闭」。
                Button(
                    onClick = { onToggle(isOn) },
                    enabled = retryCommand != null || (hasValue && !awaitingConfirmation),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            awaitingConfirmation -> SwitchGray
                            retryCommand != null -> if (retryCommand.targetValue == 0) SwitchRed else SwitchGreen
                            phase == CardPhase.ON -> SwitchGreen
                            phase == CardPhase.OFF -> SwitchRed
                            else -> SwitchGray
                        },
                    ),
                ) {
                    if (awaitingConfirmation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        ActionBtnIcon(
                            isOnline = device.online && connected,
                            hasValue = hasValue,
                            isOn = isOn,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            awaitingConfirmation -> "等待确认…"
                            retryCommand != null -> if (retryCommand.targetValue == 0) "重试开启" else "重试关闭"
                            phase == CardPhase.WAITING -> "等待连接"
                            phase == CardPhase.FETCHING -> "获取中…"
                            phase == CardPhase.ON -> "关闭设备"
                            else -> "开启设备"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 窗格模式紧凑卡片（每行两列，结构与单设备卡同构、更紧凑；点击卡片=选中该设备切单卡）。 */
@Composable
private fun PaneCard(
    device: RelaySwitchState.Device,
    /** 该设备当前引脚的电平值：0=开，1=关，null=尚未取得。 */
    value: Int?,
    /** WS 是否已连接。 */
    connected: Boolean,
    /** 当前端口的待确认命令；只影响按钮，不篡改真实状态徽章。 */
    pendingCommand: RelaySwitchState.PendingCommand?,
    onToggle: (isOn: Boolean) -> Unit,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val pio = RelaySwitchPrefs.pioForDevice(context, device.id)
    val phase = cardPhase(connected, device.online, value)
    val isOn = phase == CardPhase.ON
    val hasValue = phase == CardPhase.ON || phase == CardPhase.OFF
    val awaitingConfirmation = pendingCommand != null && !pendingCommand.awaitingRetry
    val retryCommand = pendingCommand?.takeIf { it.awaitingRetry }

    val ringColor = when (phase) {
        CardPhase.ON -> SwitchGreen
        CardPhase.OFF -> SwitchRed
        else -> SwitchGray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 背景粒子特效（与单设备卡一致）
            ParticleBackground(
                online = device.online && connected,
                isOn = isOn,
                hasValue = hasValue,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 紧凑电源环（小图标环）
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer { alpha = if (device.online && connected) 1f else 0.5f }
                            .background(
                                ringColor.copy(alpha = if (device.online && connected) 0.12f else 0.08f),
                                CircleShape,
                            ),
                    )
                    PowerRingIcon(
                        online = device.online && connected,
                        isOn = isOn,
                        tint = if (device.online && connected) ringColor else SwitchGray,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    RelaySwitchProtocol.pioName(pio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // 状态徽章（含 SVG 图标；严格照参考：已开启=绿，已关闭=红，等待/获取中=灰）
                val (badgeText, badgeColor) = when (phase) {
                    CardPhase.WAITING -> "等待连接" to SwitchGray
                    CardPhase.FETCHING -> "获取中…" to SwitchGray
                    CardPhase.ON -> "已开启" to SwitchGreen
                    CardPhase.OFF -> "已关闭" to SwitchRed
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BadgeIcon(
                        isOnline = device.online && connected,
                        hasValue = hasValue,
                        isOn = isOn,
                        tint = badgeColor,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor)
                }
                Spacer(Modifier.height(10.dp))

                // 等待确认只禁用当前按钮；超时后按原目标重试，不经过状态反转。
                Button(
                    onClick = { onToggle(isOn) },
                    enabled = retryCommand != null || (hasValue && !awaitingConfirmation),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            awaitingConfirmation -> SwitchGray
                            retryCommand != null -> if (retryCommand.targetValue == 0) SwitchRed else SwitchGreen
                            phase == CardPhase.ON -> SwitchGreen
                            phase == CardPhase.OFF -> SwitchRed
                            else -> SwitchGray
                        },
                    ),
                ) {
                    if (awaitingConfirmation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        ActionBtnIcon(
                            isOnline = device.online && connected,
                            hasValue = hasValue,
                            isOn = isOn,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            awaitingConfirmation -> "确认中…"
                            retryCommand != null -> if (retryCommand.targetValue == 0) "重试开启" else "重试关闭"
                            phase == CardPhase.WAITING -> "等待"
                            phase == CardPhase.FETCHING -> "获取中"
                            phase == CardPhase.ON -> "关闭"
                            else -> "开启"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 电源环：外圈旋转光环 + 内圈脉冲光晕 + 中心 SVG 图标（浅色化）。 */
@Composable
private fun PowerRing(
    online: Boolean,
    isOn: Boolean,
    hasValue: Boolean,
    ringColor: Color,
) {
    val transition = rememberInfiniteTransition(label = "ring")

    // 外圈光环旋转
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isOn) 3000 else 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    // 内圈脉冲光晕
    val pulse = transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isOn) 2500 else 3000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 内圈填充（状态色淡底）
        Box(
            modifier = Modifier
                .size(68.dp)
                .graphicsLayer { alpha = if (online) 1f else 0.5f }
                .background(
                    ringColor.copy(alpha = if (online) 0.12f else 0.08f),
                    CircleShape,
                ),
        )
        // 内圈脉冲光晕（仅在线时有）
        if (online && hasValue) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .graphicsLayer {
                        val glow = pulse.value
                        scaleX = 1f + (glow - 0.5f) * 0.15f
                        scaleY = 1f + (glow - 0.5f) * 0.15f
                        alpha = glow
                    }
                    .background(ringColor.copy(alpha = 0.15f), CircleShape),
            )
        }
        // 外圈旋转光环（conic 渐变简化为旋转的弧线）
        Canvas(modifier = Modifier.size(80.dp)) {
            val arcColor = if (online) ringColor.copy(alpha = 0.6f) else ringColor.copy(alpha = 0.2f)
            drawArc(
                color = arcColor,
                startAngle = spin.value,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = 2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        // 中心 SVG 图标
        PowerRingIcon(
            online = online,
            isOn = isOn,
            tint = if (online) ringColor else SwitchGray,
            modifier = Modifier.size(34.dp),
        )
    }
}

/** 背景粒子特效（浅色化：开启=绿色上升方块，关闭=红色下降圆点，等待=灰色漂浮）。 */
@Composable
private fun ParticleBackground(online: Boolean, isOn: Boolean, hasValue: Boolean) {
    val transition = rememberInfiniteTransition(label = "particle")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        val particles = 8
        for (i in 0 until particles) {
            val seed = (i * 37.7f) % 1f
            val phase = (progress.value + seed) % 1f
            val x = size.width * (0.08f + 0.84f * seed)
            // 开启态：从底部上升；关闭态：从顶部下降；等待态：原地漂浮
            val y = when {
                !online -> size.height * (0.3f + 0.3f * sin(phase * 2 * PI).toFloat())
                isOn -> size.height * (1f - phase)
                else -> size.height * phase
            }
            val alpha = when {
                !online -> 0.3f
                phase < 0.15f || phase > 0.85f -> 0.15f
                else -> 0.5f
            }
            val color = when {
                !online -> SwitchGray
                isOn -> SwitchGreen
                else -> SwitchRed
            }
            val size = 3.dp.toPx() + 3.dp.toPx() * seed
            if (isOn) {
                // 绿色方块（上升）
                drawRect(color = color.copy(alpha = alpha), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(size, size))
            } else {
                // 红色圆点（下降）/ 灰色圆点（漂浮）
                drawCircle(color = color.copy(alpha = alpha), radius = size / 2f, center = Offset(x, y))
            }
        }
    }
}

/** 关闭设备二次确认对话框（对应 showConfirm）。 */
@Composable
private fun CloseConfirmDialog(
    device: RelaySwitchState.Device,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Text("⚠️", style = MaterialTheme.typography.headlineMedium) },
        title = { Text("确认关闭") },
        text = { Text("关闭「${device.name}」将停止供电，确定要执行此操作吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认关闭", color = SwitchRed) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/** 服务器设置弹窗（对应 modal-sheet；含「保存」「刷新」两个动作，照 modal-actions）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySwitchSettingsDialog(
    client: RelaySwitchClient,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(RelaySwitchPrefs.server(context)) }
    var apiKey by remember { mutableStateOf(RelaySwitchPrefs.apiKey(context)) }
    var deviceId by remember { mutableStateOf(RelaySwitchPrefs.deviceId(context)) }
    var pio by remember {
        mutableStateOf(RelaySwitchPrefs.pioForDevice(context, RelaySwitchPrefs.deviceId(context)).toString())
    }
    // 设备下拉框展开状态
    var deviceDropdownExpanded by remember { mutableStateOf(false) }
    // 设备列表（来自 RelaySwitchState 快照，已由客户端拉取；含 id + name 昵称 + online）
    val snapshot = RelaySwitchState.state
    val devices = snapshot.devices
    val deviceOrder = snapshot.deviceOrder

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器设置") },
        text = {
            Column {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    placeholder = { Text("如 http://192.168.1.10:15152") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // 选择设备：下拉框，展示服务器上配置的所有设备昵称，可切换（单卡片显示该设备）
                ExposedDropdownMenuBox(
                    expanded = deviceDropdownExpanded,
                    onExpandedChange = { deviceDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = RelaySwitchState.deviceName(devices, deviceId) +
                            if (devices.find { it.id == deviceId }?.online == false) " (离线)" else "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择设备") },
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = deviceDropdownExpanded,
                        onDismissRequest = { deviceDropdownExpanded = false },
                    ) {
                        val sorted = RelaySwitchState.sortedDevices(devices, deviceOrder)
                        if (sorted.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(deviceId) },
                                onClick = { deviceDropdownExpanded = false },
                            )
                        } else {
                            sorted.forEach { device ->
                                val label = device.name + if (device.online) "" else " (离线)"
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        deviceId = device.id
                                        // 切换设备时同步刷新该设备已保存的端口（照 device-select change）
                                        pio = RelaySwitchPrefs.pioForDevice(context, device.id).toString()
                                        deviceDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = pio,
                    onValueChange = { pio = it },
                    label = { Text("继电器端口 (1-9)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    placeholder = { Text("留空则不启用认证") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pioInt = pio.toIntOrNull()
                    if (pioInt == null || pioInt !in 1..9) {
                        Toast.makeText(context, "端口必须在 1-9 之间", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val id = deviceId.trim()
                    RelaySwitchPrefs.setString(context, RelaySwitchPrefs.KEY_SERVER, server.trim())
                    RelaySwitchPrefs.setString(context, RelaySwitchPrefs.KEY_DEVICE_ID, id)
                    RelaySwitchPrefs.setString(context, RelaySwitchPrefs.KEY_API_KEY, apiKey.trim())
                    RelaySwitchPrefs.setPioForDevice(context, id, pioInt)
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                    onDismiss()
                    // 对应 save-settings：保存后断开并重连
                    onSaved()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 对应 refresh-status：对当前设备发 get_status
                TextButton(onClick = { client.refreshCurrent(context) }) { Text("刷新") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 空状态提示。 */
@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SwitchGray)
    }
}
