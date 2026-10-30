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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import my.bridge.com.switch.RelaySwitchClient
import my.bridge.com.switch.RelaySwitchPrefs
import my.bridge.com.switch.RelaySwitchProtocol
import my.bridge.com.switch.RelaySwitchState
import timber.log.Timber

// 智能控制页配色（Bridge 浅色风格：开启=绿，关闭=红，等待=灰，品牌蓝）
private val SwitchGreen = Color(0xFF2E9E5B)
private val SwitchRed = Color(0xFFE5484D)
private val SwitchGray = Color(0xFF8A919F)

/**
 * 智能控制页（主页「发现」Tab 改为「智能控制」）。
 *
 * ESP32 继电器控制，功能严格照 relay-control/src/main.ts 移植：
 * 单设备卡片 + 窗格多设备模式 + 开关按钮 + 状态徽章 + 关闭二次确认 + 服务器设置。
 */
@Composable
fun RelaySwitchPage() {
    val context = LocalContext.current
    val client = remember { SwitchClientHolder.get() }

    // UI 状态（由 RelaySwitchState 驱动）
    var devices by remember { mutableStateOf(RelaySwitchState.devices) }
    var revision by remember { mutableStateOf(RelaySwitchState.revision) }
    var connection by remember { mutableStateOf(RelaySwitchState.connection) }
    var paneMode by remember { mutableStateOf(RelaySwitchPrefs.paneMode(context)) }

    // 二次确认弹窗状态
    var pendingCloseDevice by remember { mutableStateOf<RelaySwitchState.Device?>(null) }
    // 设置弹窗状态
    var showSettings by remember { mutableStateOf(false) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 订阅状态变化（切主线程写 Compose snapshot state）
    DisposableEffect(Unit) {
        RelaySwitchState.setDeviceListener {
            mainHandler.post {
                devices = RelaySwitchState.devices
                revision = RelaySwitchState.revision
            }
        }
        RelaySwitchState.setConnectionListener { state ->
            mainHandler.post { connection = state }
        }
        RelaySwitchState.setErrorListener { message ->
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        // 页面进入即连接
        client.start(context)
        onDispose {
            // 离开页面即释放连接（省电优先，页面少用，不追求重连低延迟）
            client.stop()
            RelaySwitchState.setDeviceListener(null)
            RelaySwitchState.setConnectionListener(null)
            RelaySwitchState.setErrorListener(null)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        SectionTitle(if (paneMode) "设备列表" else "智能控制")

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
                    .clickable { togglePane(context, client, !paneMode) { paneMode = it } }
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
            ConnectionPill(connection)
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

        // 内容区：窗格模式用每行两列卡片网格，单设备模式用单卡
        if (paneMode) {
            val sorted = sortedDevices(devices)
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
                    gridItems(sorted) { device ->
                        PaneCard(device, revision) { isOn ->
                            if (isOn) pendingCloseDevice = device
                            else client.setRelay(device.id, RelaySwitchPrefs.pioForDevice(context, device.id), 0)
                        }
                    }
                }
            }
        } else {
            val currentId = RelaySwitchPrefs.deviceId(context)
            val device = devices.find { it.id == currentId }
                ?: RelaySwitchState.Device(currentId, currentId, false)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceCard(device, revision) { isOn ->
                    if (isOn) pendingCloseDevice = device
                    else client.setRelay(device.id, RelaySwitchPrefs.pioForDevice(context, device.id), 0)
                }
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

    // 关闭二次确认
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

    // 服务器设置弹窗
    if (showSettings) {
        RelaySwitchSettingsDialog(
            onDismiss = { showSettings = false },
            onSaved = {
                client.stop()
                client.start(context)
            },
        )
    }
}

/** 切换窗格模式：进入时订阅所有设备并取状态。 */
private fun togglePane(
    context: android.content.Context,
    client: RelaySwitchClient,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    RelaySwitchPrefs.setBoolean(context, RelaySwitchPrefs.KEY_PANE_MODE, enabled)
    onChanged(enabled)
    if (enabled) {
        RelaySwitchState.devices.forEach { d ->
            client.subscribe(d.id)
            client.getStatus(d.id)
        }
    }
}

/** 客户端单例持有器：保证页面重组间复用同一 WebSocket 客户端实例。 */
private object SwitchClientHolder {
    @Volatile
    private var client: RelaySwitchClient? = null

    @Synchronized
    fun get(): RelaySwitchClient = client ?: RelaySwitchClient().also { client = it }
}

/** 设备排序（照 compareDevices：在线优先 → 服务端顺序 → 中文名）。 */
private fun sortedDevices(devices: List<RelaySwitchState.Device>): List<RelaySwitchState.Device> {
    val order = RelaySwitchState.deviceOrder
    return devices.sortedWith(
        compareBy(
            { d -> if (d.online) 0 else 1 },
            { d -> order.indexOf(d.id).let { if (it >= 0) it else Int.MAX_VALUE } },
            { it.name },
        )
    )
}

/** 连接状态胶囊。 */
@Composable
private fun ConnectionPill(state: RelaySwitchState.Connection) {
    val (text, color) = when (state) {
        RelaySwitchState.Connection.CONNECTED -> "已连接" to SwitchGreen
        RelaySwitchState.Connection.CONNECTING -> "连接中…" to Color(0xFFE8862E)
        RelaySwitchState.Connection.DISCONNECTED -> "未连接" to SwitchGray
    }
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
    revision: Long,
    onToggle: (isOn: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val pio = RelaySwitchPrefs.pioForDevice(context, device.id)
    val value = RelaySwitchState.pioValue(device.id, pio)
    val isOn = device.online && value == 0
    val hasValue = device.online && value != null
    val isPending = RelaySwitchState.isPending(device.id)

    // 状态配色（与原项目一致：开启=绿，关闭/点击开启动作=红，等待=灰）
    val ringColor = when {
        !device.online -> SwitchGray
        isOn -> SwitchGreen
        else -> SwitchRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 背景粒子特效
            ParticleBackground(
                online = device.online,
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
                    online = device.online,
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

                // 状态徽章（含 SVG 图标）
                val (badgeText, badgeColor) = when {
                    !device.online -> "等待连接" to SwitchGray
                    !hasValue -> "获取中…" to Color(0xFFE8862E)
                    isOn -> "已开启" to SwitchGreen
                    else -> "已关闭" to SwitchGray
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    BadgeIcon(
                        isOnline = device.online,
                        hasValue = hasValue,
                        isOn = isOn,
                        tint = badgeColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(badgeText, style = MaterialTheme.typography.labelMedium, color = badgeColor)
                }
                Spacer(Modifier.height(14.dp))

                // 开关按钮（含 SVG 图标，颜色=点击后要执行的动作；执行中=转圈置灰）
                Button(
                    onClick = { onToggle(isOn) },
                    enabled = device.online && hasValue && !isPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            !device.online || !hasValue -> SwitchGray
                            isPending -> SwitchGray
                            isOn -> SwitchGreen
                            else -> SwitchRed
                        },
                    ),
                ) {
                    if (isPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "执行中…",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        ActionBtnIcon(
                            isOnline = device.online,
                            hasValue = hasValue,
                            isOn = isOn,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                !device.online -> "等待连接"
                                !hasValue -> "获取中…"
                                isOn -> "关闭设备"
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
}

/** 窗格模式紧凑卡片（每行两列，结构与单设备卡同构、更紧凑）。 */
@Composable
private fun PaneCard(
    device: RelaySwitchState.Device,
    revision: Long,
    onToggle: (isOn: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val pio = RelaySwitchPrefs.pioForDevice(context, device.id)
    val value = RelaySwitchState.pioValue(device.id, pio)
    val isOn = device.online && value == 0
    val hasValue = device.online && value != null
    val isPending = RelaySwitchState.isPending(device.id)

    val ringColor = when {
        !device.online -> SwitchGray
        isOn -> SwitchGreen
        else -> SwitchRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 背景粒子特效（与单设备卡一致）
            ParticleBackground(
                online = device.online,
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
                            .graphicsLayer { alpha = if (device.online) 1f else 0.5f }
                            .background(
                                ringColor.copy(alpha = if (device.online) 0.12f else 0.08f),
                                CircleShape,
                            ),
                    )
                    PowerRingIcon(
                        online = device.online,
                        isOn = isOn,
                        tint = if (device.online) ringColor else SwitchGray,
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

                // 状态徽章（含 SVG 图标）
                val (badgeText, badgeColor) = when {
                    !device.online -> "等待连接" to SwitchGray
                    !hasValue -> "获取中…" to Color(0xFFE8862E)
                    isOn -> "已开启" to SwitchGreen
                    else -> "已关闭" to SwitchGray
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BadgeIcon(
                        isOnline = device.online,
                        hasValue = hasValue,
                        isOn = isOn,
                        tint = badgeColor,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor)
                }
                Spacer(Modifier.height(10.dp))

                // 开关按钮（颜色=点击后要执行的动作；执行中=转圈置灰）
                Button(
                    onClick = { onToggle(isOn) },
                    enabled = device.online && hasValue && !isPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            !device.online || !hasValue -> SwitchGray
                            isPending -> SwitchGray
                            isOn -> SwitchGreen
                            else -> SwitchRed
                        },
                    ),
                ) {
                    if (isPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "执行中",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        ActionBtnIcon(
                            isOnline = device.online,
                            hasValue = hasValue,
                            isOn = isOn,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when {
                                !device.online -> "等待"
                                !hasValue -> "获取中"
                                isOn -> "关闭"
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

/** 关闭设备二次确认对话框。 */
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

/** 服务器设置弹窗。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySwitchSettingsDialog(
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
    // 设备列表（来自 RelaySwitchState，已由客户端拉取；含 id + name 昵称 + online）
    val devices = RelaySwitchState.devices

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
                    placeholder = { Text("如 127.0.0.1:15152") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // 选择设备：下拉框，展示服务器上配置的所有设备昵称，可切换（单卡片显示该设备）
                ExposedDropdownMenuBox(
                    expanded = deviceDropdownExpanded,
                    onExpandedChange = { deviceDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = deviceDisplayName(devices, deviceId),
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
                        val sorted = sortedDevices(devices)
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
                                        // 切换设备时同步刷新该设备已保存的端口
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
                    onSaved()
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 设备下拉框显示名：优先昵称，离线加后缀；找不到时回退为设备 ID。 */
private fun deviceDisplayName(devices: List<RelaySwitchState.Device>, deviceId: String): String {
    val device = devices.find { it.id == deviceId } ?: return deviceId
    return device.name + if (device.online) "" else " (离线)"
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
