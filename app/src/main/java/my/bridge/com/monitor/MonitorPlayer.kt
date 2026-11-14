package my.bridge.com.monitor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import my.bridge.com.core.common.DiagLog

private const val NTAG = "监控"

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

/**
 * 全屏播放器（v0.2.152）。
 *
 * 基于 Media3 ExoPlayer，统一承载：
 * - 实时音视频流（fMP4，H.264+AAC 有声音）
 * - 事件关联录像点播（MP4）
 * - 云端录像点播（MP4/fMP4）
 * - 延时摄影视频（MP4）
 *
 * 手势（v0.2.152 重构）：
 * - 双指捏合缩放 1~6 倍；放大后单指拖动平移（允许拖出黑边）；
 * - 点屏呼出「暂停/播放」键（3 秒自动隐藏），未放大时才呼出；
 * - 顶栏「返回原始」复位缩放；返回键三级优先级：设置面板 → 复位缩放 → 关闭播放器。
 *
 * 画质切换：实时流在 sub（流畅子码流）/ main（高清主码流）间切换，切换时重建播放 URL。
 */
@Composable
fun MonitorPlayer(
    title: String,
    urlProvider: (quality: String) -> String,
    isLive: Boolean,
    subtitle: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var quality by remember { mutableStateOf("sub") }
    var isBuffering by remember { mutableStateOf(true) }
    var playError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    // 控制条是否可见（点屏呼出，3 秒自动隐藏）
    var controllerVisible by remember { mutableStateOf(false) }
    // 设置抽屉是否打开
    var settingsOpen by remember { mutableStateOf(false) }
    // 当前倍速
    var speed by remember { mutableStateOf(1f) }

    // 视频画面缩放/平移（v0.2.153）：双指缩放 + 单指拖动平移，用 Compose 手势（与图片查看器同套）。
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun resetTransform() {
        scale = 1f
        offset = Offset.Zero
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    val stateName = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                    DiagLog.i(NTAG, "播放器状态 $stateName（${if (isLive) "实时" else "点播"} $title）")
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playError = error.message ?: "播放失败"
                    DiagLog.e(NTAG, "播放器错误（${if (isLive) "实时" else "点播"} $title）：$playError", error)
                }
            })
        }
    }

    // 首次组合 + 画质切换时（重）建媒体源
    LaunchedEffect(quality) {
        DiagLog.i(NTAG, "播放器建立媒体源 quality=$quality（$title）")
        player.setMediaItem(MediaItem.fromUri(urlProvider(quality)))
        player.prepare()
        player.playWhenReady = true
    }

    // 倍速同步
    LaunchedEffect(speed) {
        player.setPlaybackSpeed(speed)
    }

    // 控制条 3 秒自动隐藏
    LaunchedEffect(controllerVisible) {
        if (controllerVisible) {
            delay(3000)
            controllerVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            MonitorUiState.playerOpen = false
            DiagLog.i(NTAG, "播放器已释放（$title）")
        }
    }

    // 返回键三级优先级：设置面板 → 复位缩放 → 关闭播放器
    BackHandler {
        when {
            settingsOpen -> {
                settingsOpen = false
                DiagLog.i(NTAG, "返回：收起设置面板（$title）")
            }
            scale > 1f -> {
                resetTransform()
                DiagLog.i(NTAG, "返回：复位缩放（$title）")
            }
            else -> onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 默认 TransformOrigin.Center，配合下方手势层 detectTransformGestures 的锚点公式
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        // 透明手势覆盖层：接收双指缩放 + 单指平移 + 单点呼出控制条。
        // 用纯 Compose 手势（与 ZoomableImageView 同套），避开原生 ScaleGestureDetector/GestureDetector
        // 的状态机在「连续多次手势」时卡死的问题。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = if (newScale > MIN_SCALE) {
                            offset + pan + (centroid - Offset(size.width / 2f, size.height / 2f)) * (newScale / scale - 1f)
                        } else {
                            Offset.Zero
                        }
                        scale = newScale
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // 未放大时点屏：呼出/切换控制条
                            if (scale <= 1f && !settingsOpen) {
                                controllerVisible = !controllerVisible
                            }
                        },
                    )
                },
        )

        // 顶栏信息（悬浮，画质切换 + 设置入口 + 返回原始 + 关闭）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color(0xFFCCCCCC), fontSize = 11.sp)
            }
            // 画面放大后显示「返回原始」按钮
            if (scale > 1f) {
                Box(
                    modifier = Modifier
                        .background(Color(0x33FFFFFF), CircleShape)
                        .clickable {
                            DiagLog.i(NTAG, "视频画面复位到原始（$title）")
                            resetTransform()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("返回原始", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            // 画质切换（仅实时流），位于「设置」按钮左边
            if (isLive) {
                Box(
                    modifier = Modifier
                        .background(Color(0x33FFFFFF), CircleShape)
                        .clickable {
                            val next = if (quality == "sub") "main" else "sub"
                            DiagLog.i(NTAG, "切换画质 $quality → $next（$title）")
                            quality = next
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (quality == "sub") "流畅" else "高清",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            // 设置入口
            Box(
                modifier = Modifier
                    .background(Color(0x33FFFFFF), CircleShape)
                    .clickable {
                        settingsOpen = !settingsOpen
                        DiagLog.i(NTAG, "设置面板 ${if (settingsOpen) "打开" else "关闭"}（$title）")
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("设置", color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0x33FFFFFF), CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 14.sp)
            }
        }

        // 底部控制条（点屏呼出，3 秒自动隐藏）：暂停/播放键在进度条上方
        if (controllerVisible && scale <= 1f && !settingsOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // 暂停/播放键（进度条上方）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0x33FFFFFF), CircleShape)
                            .clickable {
                                val next = !isPlaying
                                DiagLog.i(NTAG, "切换播放状态 → ${if (next) "播放" else "暂停"}（$title）")
                                if (next) player.play() else player.pause()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isPlaying) "⏸" else "▶",
                            color = Color.White,
                            fontSize = 22.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 进度条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x44FFFFFF), RoundedCornerShape(2.dp)),
                ) {
                    val progress = if (player.duration > 0) {
                        (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(Color(0xFF1A6EE8), RoundedCornerShape(2.dp)),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 时间 + 速度提示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${formatDuration(player.currentPosition)} / ${formatDuration(player.duration)}",
                        color = Color(0xFFCCCCCC),
                        fontSize = 11.sp,
                    )
                    if (speed != 1f) {
                        Text("${speed}x", color = Color(0xFF1A6EE8), fontSize = 11.sp)
                    }
                }
            }
        }

        // 设置抽屉（底部）
        if (settingsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x00000000))
                    .clickable { settingsOpen = false },
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xF0161626), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(20.dp),
            ) {
                Text("设置", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))

                // 速度
                Text("速度", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        Box(
                            modifier = Modifier
                                .background(
                                    if (speed == s) Color(0xFF1A6EE8) else Color(0x33FFFFFF),
                                    CircleShape,
                                )
                                .clickable {
                                    speed = s
                                    DiagLog.i(NTAG, "设置倍速 ${s}x（$title）")
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("${s}x", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 音频：音量（用系统音量，此处仅提供音量提示/静音切换）
                Text("音频", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Color(0x33FFFFFF), CircleShape)
                            .clickable {
                                val next = if (player.volume > 0f) 0f else 1f
                                player.volume = next
                                DiagLog.i(NTAG, "切换静音 → ${if (next == 0f) "静音" else "有声"}（$title）")
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (player.volume > 0f) "🔊 有声" else "🔇 静音",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // 缓冲中
        if (isBuffering && playError == null) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 错误态
        if (playError != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚠️", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "播放失败：$playError",
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1A6EE8), CircleShape)
                        .clickable {
                            DiagLog.i(NTAG, "播放器重试（$title）")
                            playError = null
                            player.setMediaItem(MediaItem.fromUri(urlProvider(quality)))
                            player.prepare()
                            player.playWhenReady = true
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text("重试", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
