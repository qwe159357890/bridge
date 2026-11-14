package my.bridge.com.monitor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.bridge.com.core.common.DiagLog

private const val NTAG = "监控"

/** 缩放倍数范围：1 倍（原始）到 5 倍。 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/**
 * 全屏可缩放图片查看器（v0.2.149）。
 *
 * 统一承载监控内的静态图片查看（事件快照大图等），支持：
 * - 双指捏合缩放（1~5 倍）
 * - 单指拖动平移（放大后生效，原始倍率下自动归零）
 * - 双击快速复位、顶栏「返回原始」按钮复位
 * - 返回键 / 顶栏关闭按钮退出
 *
 * 缩放中心随手势焦点移动（detectTransformGestures 的 pan 已含缩放锚点偏移）。
 */
@Composable
fun ZoomableImageView(
    title: String,
    subtitle: String,
    bitmap: Bitmap,
    onClose: () -> Unit,
    actionLabel: String = "",
    onAction: (() -> Unit)? = null,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
        DiagLog.i(NTAG, "图片查看器复位到原始（$title）")
    }

    // 返回键：直接关闭查看器
    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 可缩放图片：双击复位
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = title,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures(
                        onGesture = { centroid, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            // 缩放中心锚点：以手势中心为基准，pan 已含锚点位移
                            offset = if (newScale > MIN_SCALE) {
                                offset + pan + (centroid - Offset(size.width / 2f, size.height / 2f)) * (newScale / scale - 1f)
                            } else {
                                Offset.Zero
                            }
                            scale = newScale
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { reset() })
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )

        // 顶栏信息（悬浮）
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
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = Color(0xFFCCCCCC), fontSize = 11.sp)
                }
            }
            // 返回原始（放大后可用）
            if (scale > MIN_SCALE) {
                Box(
                    modifier = Modifier
                        .background(Color(0x33FFFFFF), CircleShape)
                        .clickable { reset() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("返回原始", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            // 附加动作（如「看录像」）
            if (actionLabel.isNotBlank() && onAction != null) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1A6EE8), CircleShape)
                        .clickable { onAction() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(actionLabel, color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
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

        // 放大提示（原始倍率下显示，引导双指缩放）
        if (scale <= MIN_SCALE) {
            Text(
                "双指缩放 · 双击返回原始",
                color = Color(0xAAFFFFFF),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            )
        }
    }
}
