package my.bridge.com.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import timber.log.Timber

/**
 * 电池状态读取器（get_battery 命令核心）。
 *
 * 实现借鉴 autoxClient `device.js getBatteryInfo`：注册（不接收，仅拉取）
 * `ACTION_BATTERY_CHANGED` 粘性广播即可拿到完整电池快照，**无需任何权限**。
 * 只读，不注册常驻接收器，命令来一次读一次。
 */
object BatteryReporter {

    /** 读取电池状态，返回人类可读回执；拿不到广播时抛异常。 */
    fun read(context: Context): String {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: throw IllegalStateException("无法获取电池广播（设备可能无电池，如电视盒子）")

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "未知"
            else -> "未知"
        }

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugText = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC 电源"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            else -> null
        }

        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "未知故障"
            else -> null
        }

        val msg = buildString {
            append("电量：${if (percent >= 0) "$percent%" else "未知"}（$statusText")
            if (plugText != null && status in intArrayOf(
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL,
                )
            ) {
                append("·$plugText")
            }
            append("）")
            if (temperature != Int.MIN_VALUE) append("｜温度：${temperature / 10.0}℃")
            if (voltage > 0) append("｜电压：%.2fV".format(voltage / 1000.0))
            if (healthText != null) append("｜健康度：$healthText")
            if (!technology.isNullOrBlank()) append("｜电池：$technology")
        }
        Timber.i("电池状态：%s", msg)
        return msg
    }
}
