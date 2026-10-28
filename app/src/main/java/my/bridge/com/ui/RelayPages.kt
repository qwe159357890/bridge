package my.bridge.com.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import my.bridge.com.relay.AudioRelayService
import my.bridge.com.relay.RelayClient
import my.bridge.com.relay.RelayPrefs

private val BrandBlue = Color(0xFF1A6EE8)
private val RelayGreen = Color(0xFF2E9E5B)
private val RelayRed = Color(0xFFE5484D)

/**
 * 音频中继页（主页「通讯录」tab 改为「音频中继」）。
 *
 * 状态卡 + 24 柱波形 + 中继音量滑块 + 开始/停止按钮。
 * 中继音量 = AudioTrack 软增益，仅调整中继播放音量，不影响系统音量。
 */
@Composable
fun AudioRelayPage() {
    val context = LocalContext.current

    // 中继状态（由 AudioRelayService 回调驱动）
    var state by remember { mutableStateOf(AudioRelayService.currentState()) }
    var stateDetail by remember { mutableStateOf("") }
    // 24 柱波形电平
    var levels by remember { mutableStateOf(FloatArray(24)) }
    // 中继音量
    var volume by remember { mutableFloatStateOf(RelayPrefs.volume(context)) }
    // 是否正在运行（驱动按钮文案）
    var running by remember { mutableStateOf(AudioRelayService.isRunning()) }

    // 订阅服务回调
    DisposableEffect(Unit) {
        AudioRelayService.setStateListener(object : AudioRelayService.RelayListener {
            override fun onState(s: RelayClient.State, detail: String) {
                state = s
                stateDetail = detail
                running = AudioRelayService.isRunning()
            }
        })
        AudioRelayService.setLevelsListener(object : AudioRelayService.AudioLevelsListener {
            override fun onLevels(l: FloatArray) {
                levels = l
            }
        })
        onDispose {
            AudioRelayService.setStateListener(null)
            AudioRelayService.setLevelsListener(null)
        }
    }

    // 周期刷新运行态（服务在后台可能被系统重启）
    LaunchedEffect(Unit) {
        while (true) {
            running = AudioRelayService.isRunning()
            state = AudioRelayService.currentState()
            kotlinx.coroutines.delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("音频中继") }

        // 状态卡
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔄", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "中继状态",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            relayStateText(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = relayStateColor(state),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (stateDetail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stateDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 24 柱波形
                    AudioWaveform(levels = levels, active = running)
                }
            }
        }

        // 中继音量
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("中继音量", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "仅调整中继播放音量，不影响系统音量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${(volume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = {
                            RelayPrefs.setFloat(context, RelayPrefs.KEY_VOLUME, volume)
                            AudioRelayService.setVolume(context, volume)
                        },
                        valueRange = 0f..1f,
                    )
                }
            }
        }

        // 开始/停止按钮
        item {
            Button(
                onClick = {
                    if (running) {
                        AudioRelayService.stop(context)
                        running = false
                    } else {
                        AudioRelayService.start(context)
                        running = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) RelayRed else BrandBlue,
                ),
            ) {
                Text(
                    if (running) "停止接收" else "开始接收",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        item {
            Text(
                text = "开启后 Bridge 主动连接中转服务器，接收电脑端推送的音频并在本机播放（手机放音）。" +
                    "中转服务器地址与自动重连可在「设置 → 连接 → 音频中继」中配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** 24 柱波形：空闲时播放一段轻动画，有数据时随电平跳动。 */
@Composable
private fun AudioWaveform(levels: FloatArray, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val idleProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "idle",
    )

    val hasData = active && levels.any { it > 0.001f }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val barCount = 24
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val level = if (hasData) {
                levels[i].coerceIn(0.02f, 1f)
            } else {
                // 空闲动画：正弦起伏
                val phase = (idleProgress * 2 * Math.PI + i * 0.5).toFloat()
                (0.15f + 0.25f * (1f + kotlin.math.sin(phase)) / 2f).coerceIn(0.05f, 0.5f)
            }
            val barHeight = size.height * level
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = if (active) RelayGreen else Color(0xFFCCD2DB),
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - barHeight) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun relayStateText(state: RelayClient.State): String = when (state) {
    RelayClient.State.IDLE -> "未启动"
    RelayClient.State.CONNECTING -> "连接中…"
    RelayClient.State.READY -> "已就绪"
    RelayClient.State.ERROR -> "错误"
}

private fun relayStateColor(state: RelayClient.State): Color = when (state) {
    RelayClient.State.READY -> RelayGreen
    RelayClient.State.ERROR -> RelayRed
    RelayClient.State.IDLE -> Color(0xFF8A919F)
    RelayClient.State.CONNECTING -> Color(0xFFE8862E)
}
