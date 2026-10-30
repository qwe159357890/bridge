package my.bridge.com.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * 智能控制 SVG 线框图标（严格照 relay-control/src/main.ts 的 ICONS 移植）。
 *
 * 原项目约定（main.ts svgIcon()）：viewBox 0 0 24 24、fill=none、
 * stroke=currentColor、stroke-width=2、stroke-linecap/linejoin=round。
 * 这里用 Compose PathParser 直接解析原始 SVG path 数据，1:1 还原。
 */

/** 电源图标：开启（实线闪电弧）。 */
private const val POWER_ON = "M12 2v6 M18.36 6.64a9 9 0 1 1-12.73 0"

/** 电源图标：关闭（虚线闪电弧，dasharray 3 3）。 */
private const val POWER_OFF = "M12 2v6 M18.36 6.64a9 9 0 1 1-12.73 0"

/** 徽章图标：开启（对勾）。 */
private const val BADGE_ON = "M22 11.08V12a10 10 0 1 1-5.93-9.14 M22 4 12 14.01 9 11.01"

/** 按钮图标：开启 = 闪电。 */
private const val BTN_ON = "M13 2L3 14h9l-1 8 10-12h-9l1-8z"

/** 按钮图标：关闭 = 电源符号。 */
private const val BTN_OFF = "M18.36 6.64a9 9 0 1 1-12.73 0 M12 2 12 12"

/** 解析一个 SVG path 字符串为 Compose Path。 */
private fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

/** 在 24×24 坐标系中绘制圆（stroke）。 */
private fun DrawScope.circle(cx: Float, cy: Float, r: Float, dashed: Boolean, color: Color, s: Float) {
    val center = Offset(cx * s, cy * s)
    val radius = r * s
    val style = Stroke(
        width = 2f * s,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f * s, 4f * s), 0f) else null,
    )
    drawCircle(color = color, radius = radius, center = center, style = style)
}

/** 在 24×24 坐标系中绘制实心圆点（fill）。 */
private fun DrawScope.dot(cx: Float, cy: Float, r: Float, color: Color, s: Float) {
    drawCircle(color = color, radius = r * s, center = Offset(cx * s, cy * s), style = Fill)
}

/**
 * 电源环中心图标。
 *
 * 原项目三态：
 *  - powerOn  = 竖线(实) + 弧(实)
 *  - powerOff = 竖线(实) + 弧(虚线 3 3)
 *  - powerWait= 圆(虚线 4 4) + 问号弧 + 圆点
 */
@Composable
fun PowerRingIcon(online: Boolean, isOn: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        withTransform({
            translate(
                left = (size.width - 24f * s) / 2f,
                top = (size.height - 24f * s) / 2f,
            )
            scale(scaleX = s, scaleY = s, pivot = Offset.Zero)
        }) {
            when {
                !online -> {
                    // 虚线圆 + 问号 + 点
                    circle(12f, 12f, 10f, dashed = true, color = tint, s = 1f)
                    drawPath(
                        path = parsePath("M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"),
                        color = tint,
                        style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    dot(12f, 17f, 0.5f, tint, 1f)
                }
                isOn -> {
                    drawPath(
                        path = parsePath(POWER_ON),
                        color = tint,
                        style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                else -> {
                    val dashedStyle = Stroke(
                        width = 2f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                    )
                    // 竖线实线 + 弧虚线
                    drawPath(
                        path = parsePath("M12 2v6"),
                        color = tint,
                        style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = parsePath("M18.36 6.64a9 9 0 1 1-12.73 0"),
                        color = tint,
                        style = dashedStyle,
                    )
                }
            }
        }
    }
}

/**
 * 状态徽章图标（16dp 小图标）。
 *
 *  - badgeOn   = 对勾（圆弧 + polyline）
 *  - badgeOff  = 圆(实) + 双斜线叉
 *  - badgeWait = 圆(虚线) + 感叹号（竖线 + 点）
 */
@Composable
fun BadgeIcon(isOnline: Boolean, hasValue: Boolean, isOn: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        withTransform({
            translate(
                left = (size.width - 24f * s) / 2f,
                top = (size.height - 24f * s) / 2f,
            )
            scale(scaleX = s, scaleY = s, pivot = Offset.Zero)
        }) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when {
                !isOnline || !hasValue -> {
                    // badgeWait：虚线圆 + 感叹号
                    circle(12f, 12f, 10f, dashed = true, color = tint, s = 1f)
                    drawLine(
                        color = tint,
                        start = Offset(12f, 8f),
                        end = Offset(12f, 12f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                    dot(12f, 16f, 1f, tint, 1f)
                }
                isOn -> {
                    // badgeOn：对勾
                    drawPath(parsePath(BADGE_ON), color = tint, style = stroke)
                }
                else -> {
                    // badgeOff：圆 + 叉
                    circle(12f, 12f, 10f, dashed = false, color = tint, s = 1f)
                    drawLine(
                        color = tint,
                        start = Offset(15f, 9f),
                        end = Offset(9f, 15f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = tint,
                        start = Offset(9f, 9f),
                        end = Offset(15f, 15f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * 按钮图标（表示点击后要执行的动作）。
 *
 *  - 已开启 → 点击关闭 → btnOff（电源符号：弧 + 竖线）
 *  - 已关闭 → 点击开启 → btnOn（闪电）
 *  - 等待/获取中 → btnWait（虚线圆 + 感叹号）
 */
@Composable
fun ActionBtnIcon(isOnline: Boolean, hasValue: Boolean, isOn: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        withTransform({
            translate(
                left = (size.width - 24f * s) / 2f,
                top = (size.height - 24f * s) / 2f,
            )
            scale(scaleX = s, scaleY = s, pivot = Offset.Zero)
        }) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when {
                !isOnline || !hasValue -> {
                    // btnWait：虚线圆 + 感叹号
                    circle(12f, 12f, 10f, dashed = true, color = tint, s = 1f)
                    drawLine(
                        color = tint,
                        start = Offset(12f, 8f),
                        end = Offset(12f, 12f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                    dot(12f, 16f, 1f, tint, 1f)
                }
                isOn -> {
                    // btnOff：电源符号（弧 + 竖线）
                    drawPath(parsePath(BTN_OFF), color = tint, style = stroke)
                }
                else -> {
                    // btnOn：闪电
                    drawPath(parsePath(BTN_ON), color = tint, style = stroke)
                }
            }
        }
    }
}
