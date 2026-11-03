package my.bridge.com.automation.input

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 颜色判定器（移植 AutoX ColorDetector，7 种算法，纯计算零依赖）。
 *
 * 供 find_color / find_multi_colors 使用，threshold 语义各算法不同（见各实现）。
 */
interface ColorDetector {
    fun detectsColor(red: Int, green: Int, blue: Int): Boolean

    /** RGB 完全相等。 */
    class Equality(private val color: Int) : ColorDetector {
        private val r = Color.red(color)
        private val g = Color.green(color)
        private val b = Color.blue(color)
        override fun detectsColor(red: Int, green: Int, blue: Int) =
            r == red && g == green && b == blue
    }

    /** 曼哈顿距离 |R-r|+|G-g|+|B-b| ≤ 3*threshold。 */
    class Difference(private val color: Int, threshold: Int) : ColorDetector {
        private val r = Color.red(color)
        private val g = Color.green(color)
        private val b = Color.blue(color)
        private val th = threshold * 3
        override fun detectsColor(red: Int, green: Int, blue: Int) =
            abs(red - r) + abs(green - g) + abs(blue - b) <= th
    }

    /** 仅 R 分量距离。 */
    class RDistance(private val color: Int, private val threshold: Int) : ColorDetector {
        private val r = Color.red(color)
        override fun detectsColor(red: Int, green: Int, blue: Int) = abs(r - red) <= threshold
    }

    /** RGB 欧氏距离平方 ≤ 3*threshold²。 */
    class RgbDistance(private val color: Int, threshold: Int) : ColorDetector {
        private val r = Color.red(color)
        private val g = Color.green(color)
        private val b = Color.blue(color)
        private val th = threshold * threshold * 3
        override fun detectsColor(red: Int, green: Int, blue: Int): Boolean {
            val dR = red - r
            val dG = green - g
            val dB = blue - b
            return dR * dR + dG * dG + dB * dB <= th
        }
    }

    /** 加权 RGB 距离（人眼感知）。 */
    class WeightedRgb(private val color: Int, threshold: Int) : ColorDetector {
        private val r = (color and 0xff0000) shr 16
        private val g = (color and 0x00ff00) shr 8
        private val b = color and 0xff
        private val th = threshold * threshold * 8
        override fun detectsColor(red: Int, green: Int, blue: Int): Boolean {
            val dR = red - r
            val dG = green - g
            val dB = blue - b
            val meanR = (r + red) / 2
            val weightR = 2 + meanR / 256
            val weightG = 4.0
            val weightB = 2 + (255 - meanR) / 256
            return weightR * dR * dR + weightG * dG * dG + weightB * dB * dB <= th
        }
    }

    /** 色相距离。 */
    class HDistance(private val color: Int, private val threshold: Int) : ColorDetector {
        private val h = hue(Color.red(color), Color.green(color), Color.blue(color))
        override fun detectsColor(red: Int, green: Int, blue: Int) =
            abs(h - hue(red, green, blue)) <= threshold

        private fun hue(R: Int, G: Int, B: Int): Int {
            val maxV: Int
            val minV: Int
            if (R > G) {
                minV = min(G, B); maxV = max(R, B)
            } else {
                minV = min(R, B); maxV = max(G, B)
            }
            var h = when (maxV) {
                R -> (G - B) / (maxV - minV) * 60
                G -> 120 + (B - R) / (maxV - minV) * 60
                else -> 240 + (R - G) / (maxV - minV) * 60
            }
            if (h < 0) h += 360
            return h
        }
    }

    /** 色相 + 饱和度距离。 */
    class HSDistance(private val color: Int, threshold: Int) : ColorDetector {
        private val hs = hs(Color.red(color), Color.green(color), Color.blue(color))
        private val h = (hs and 0xffffffffL).toInt()
        private val s = ((hs shr 32) and 0xffffffffL).toInt()
        private val th = threshold * 3729600 / 255
        override fun detectsColor(red: Int, green: Int, blue: Int): Boolean {
            val cur = hs(red, green, blue)
            val dH = (cur and 0xffffffffL).toInt() - h
            val dS = ((cur shr 32) and 0xffffffffL).toInt() - s
            return dH * dH + dS * dS <= th
        }

        private fun hs(R: Int, G: Int, B: Int): Long {
            val maxV: Int
            val minV: Int
            if (R > G) {
                minV = min(G, B); maxV = max(R, B)
            } else {
                minV = min(R, B); maxV = max(G, B)
            }
            var h = when (maxV) {
                R -> (G - B) / (maxV - minV) * 60
                G -> 120 + (B - R) / (maxV - minV) * 60
                else -> 240 + (R - G) / (maxV - minV) * 60
            }
            if (h < 0) h += 360
            val s = (maxV - minV) * 100 / maxV
            return h.toLong() and (s.toLong() shl 32)
        }
    }

    companion object {
        /** 按算法名构造检测器。 */
        fun of(algorithm: String, color: Int, threshold: Int): ColorDetector = when (algorithm) {
            "equality" -> Equality(color)
            "difference" -> Difference(color, threshold)
            "r_distance" -> RDistance(color, threshold)
            "rgb_distance" -> RgbDistance(color, threshold)
            "weighted_rgb" -> WeightedRgb(color, threshold)
            "h_distance" -> HDistance(color, threshold)
            "hs_distance" -> HSDistance(color, threshold)
            else -> Difference(color, threshold)
        }
    }
}
