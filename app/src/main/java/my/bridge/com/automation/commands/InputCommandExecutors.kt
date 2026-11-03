package my.bridge.com.automation.commands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import my.bridge.com.automation.a11y.BridgeAccessibilityService
import my.bridge.com.automation.input.ColorFinder
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandResult
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 图像找色 + 传感器命令执行器（批次 4：AutoX ColorFinder + Sensors）。
 */
object InputCommandExecutors {

    /** 截屏并解码为 Bitmap。 */
    private fun screenshotBitmap(context: Context): Bitmap? {
        val path = try {
            BridgeAccessibilityService.takeScreenshotToFile(context)
        } catch (e: Exception) {
            Timber.w(e, "找色截屏失败")
            return null
        }
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Timber.w(e, "找色解码失败")
            null
        }
    }

    private fun parseColor(text: String): Int? = try {
        when {
            text.startsWith("#") -> android.graphics.Color.parseColor(text)
            text.startsWith("0x") -> text.substring(2).toLong(16).toInt()
            else -> text.toLong(16).toInt()
        }
    } catch (e: Exception) {
        null
    }

    /** ui_find_color：找色。 */
    fun findColor(context: Context, command: Command): CommandResult {
        val colorText = command.strParam("color", "")
        val color = parseColor(colorText)
            ?: return CommandResult(command.commandId, command.command, false, "color 格式无效（支持 #RRGGBB / 0xRRGGBB）")
        val threshold = command.intParam("threshold", 16, 0, 255)
        val algorithm = command.strParam("algorithm", "difference")
        val bmp = screenshotBitmap(context)
            ?: return CommandResult(command.commandId, command.command, false, "截屏失败（无障碍未开启或系统不支持）")
        return try {
            val region = command.params.get("region")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
                intArrayOf(arr[0].asInt, arr[1].asInt, arr[2].asInt, arr[3].asInt)
            }
            val p = ColorFinder.findColor(bmp, color, threshold, algorithm, region)
            if (p == null) {
                CommandResult(command.commandId, command.command, false, "未找到目标颜色")
            } else {
                CommandResult(command.commandId, command.command, true, "{\"x\":${p.x},\"y\":${p.y}}")
            }
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "找色失败：${e.message}")
        } finally {
            bmp.recycle()
        }
    }

    /** ui_find_multi_colors：多点找色。 */
    fun findMultiColors(context: Context, command: Command): CommandResult {
        val colorText = command.strParam("first_color", "")
        val color = parseColor(colorText)
            ?: return CommandResult(command.commandId, command.command, false, "first_color 格式无效")
        val pointsRaw = command.params.get("points")
            ?: return CommandResult(command.commandId, command.command, false, "points 必须是 [dx,dy,color,...] 数组")
        if (!pointsRaw.isJsonArray || pointsRaw.asJsonArray.size() < 3 || pointsRaw.asJsonArray.size() % 3 != 0) {
            return CommandResult(command.commandId, command.command, false, "points 必须是长度为 3 倍数的 [dx,dy,color,...] 数组")
        }
        val arr = pointsRaw.asJsonArray
        val points = IntArray(arr.size()) { arr.get(it).asInt }
        val threshold = command.intParam("threshold", 16, 0, 255)
        val algorithm = command.strParam("algorithm", "difference")
        val bmp = screenshotBitmap(context)
            ?: return CommandResult(command.commandId, command.command, false, "截屏失败")
        return try {
            val p = ColorFinder.findMultiColors(bmp, color, threshold, points, null, algorithm)
            if (p == null) {
                CommandResult(command.commandId, command.command, false, "未找到多点匹配")
            } else {
                CommandResult(command.commandId, command.command, true, "{\"x\":${p.x},\"y\":${p.y}}")
            }
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "多点找色失败：${e.message}")
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------
    // 传感器
    // ------------------------------------------------------------------

    private fun sensorType(name: String): Int? = when (name.lowercase()) {
        "accelerometer" -> Sensor.TYPE_ACCELEROMETER
        "magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
        "orientation" -> Sensor.TYPE_ORIENTATION
        "gyroscope" -> Sensor.TYPE_GYROSCOPE
        "light" -> Sensor.TYPE_LIGHT
        "temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
        "pressure" -> Sensor.TYPE_PRESSURE
        "proximity" -> Sensor.TYPE_PROXIMITY
        "gravity" -> Sensor.TYPE_GRAVITY
        "linear_acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
        "relative_humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
        else -> null
    }

    /** ui_sensors：读取指定传感器当前值（一次性）。 */
    fun sensors(context: Context, command: Command): CommandResult {
        val name = command.strParam("name", "")
        val type = sensorType(name)
            ?: return CommandResult(command.commandId, command.command, false, "不支持的传感器：$name")
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(type)
                ?: return CommandResult(command.commandId, command.command, false, "设备无此传感器：$name")
            val latch = CountDownLatch(1)
            var values: FloatArray? = null
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    values = event.values.clone()
                    latch.countDown()
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            val got = latch.await(2, TimeUnit.SECONDS)
            sm.unregisterListener(listener)
            if (!got || values == null) {
                return CommandResult(command.commandId, command.command, false, "读取传感器超时")
            }
            val v = values!!
            val sb = StringBuilder("[")
            v.forEachIndexed { i, f -> if (i > 0) sb.append(','); sb.append(f) }
            sb.append("]")
            CommandResult(command.commandId, command.command, true, "{\"name\":\"$name\",\"values\":$sb}")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读取传感器失败：${e.message}")
        }
    }
}
