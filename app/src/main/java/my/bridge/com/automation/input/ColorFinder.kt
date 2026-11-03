package my.bridge.com.automation.input

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 找色（移植 AutoX ColorFinder，用纯 Java Bitmap.getPixel 循环替代 OpenCV）。
 *
 * 避免引入 OpenCV 重量依赖（APK 体积 + 构建复杂度）。findColor/findMultiColors
 * 是游戏/无节点界面自动化的关键能力。
 */
object ColorFinder {

    data class Point(val x: Int, val y: Int)

    /** 找第一个匹配颜色的点（全图或区域）。threshold + 算法见 [ColorDetector]。 */
    fun findColor(
        bitmap: Bitmap,
        color: Int,
        threshold: Int,
        algorithm: String = "difference",
        region: IntArray? = null,
    ): Point? {
        val detector = ColorDetector.of(algorithm, color, threshold)
        val (x0, y0, x1, y1) = region?.let {
            intArrayOf(it[0], it[1], it[2], it[3])
        } ?: intArrayOf(0, 0, bitmap.width, bitmap.height)
        for (y in y0 until minOf(y1, bitmap.height)) {
            for (x in x0 until minOf(x1, bitmap.width)) {
                val px = bitmap.getPixel(x, y)
                if (detector.detectsColor(Color.red(px), Color.green(px), Color.blue(px))) {
                    return Point(x, y)
                }
            }
        }
        return null
    }

    /** 找所有匹配颜色的点。 */
    fun findAllPoints(
        bitmap: Bitmap,
        color: Int,
        threshold: Int,
        algorithm: String = "difference",
        region: IntArray? = null,
        max: Int = 10000,
    ): List<Point> {
        val detector = ColorDetector.of(algorithm, color, threshold)
        val (x0, y0, x1, y1) = region?.let {
            intArrayOf(it[0], it[1], it[2], it[3])
        } ?: intArrayOf(0, 0, bitmap.width, bitmap.height)
        val out = ArrayList<Point>()
        for (y in y0 until minOf(y1, bitmap.height)) {
            for (x in x0 until minOf(x1, bitmap.width)) {
                val px = bitmap.getPixel(x, y)
                if (detector.detectsColor(Color.red(px), Color.green(px), Color.blue(px))) {
                    out.add(Point(x, y))
                    if (out.size >= max) return out
                }
            }
        }
        return out
    }

    /**
     * 多点找色（游戏自动化核心）：先找 firstColor 的所有候选点，再验证相对偏移点的颜色。
     * @param points 相对坐标+颜色三元组数组 [dx, dy, color, dx, dy, color, ...]
     */
    fun findMultiColors(
        bitmap: Bitmap,
        firstColor: Int,
        threshold: Int,
        points: IntArray,
        region: IntArray? = null,
        algorithm: String = "difference",
    ): Point? {
        val firstPoints = findAllPoints(bitmap, firstColor, threshold, algorithm, region)
        for (fp in firstPoints) {
            if (checksPath(bitmap, fp, threshold, points, algorithm)) return fp
        }
        return null
    }

    private fun checksPath(
        bitmap: Bitmap,
        start: Point,
        threshold: Int,
        points: IntArray,
        algorithm: String,
    ): Boolean {
        var i = 0
        while (i + 2 < points.size) {
            val dx = points[i]
            val dy = points[i + 1]
            val color = points[i + 2]
            val detector = ColorDetector.of(algorithm, color, threshold)
            val x = start.x + dx
            val y = start.y + dy
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return false
            val c = bitmap.getPixel(x, y)
            if (!detector.detectsColor(Color.red(c), Color.green(c), Color.blue(c))) return false
            i += 3
        }
        return true
    }
}
