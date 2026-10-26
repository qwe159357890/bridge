package my.bridge.com.interact

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import timber.log.Timber

/**
 * 设备震动（vibrate 命令）。
 *
 * API 26+ 用 [VibrationEffect.createOneShot]，低版本直接时长震动；
 * API 31+ 取 [VibratorManager] 默认震动器，低版本取旧 VIBRATOR_SERVICE。
 * VIBRATE 为普通权限（Manifest 已声明，安装即授予）。
 */
object DeviceVibrator {

    fun vibrate(context: Context, durationMs: Long): String {
        val appContext = context.applicationContext
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) {
            throw IllegalStateException("设备没有震动马达")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
        Timber.i("vibrate：已震动 %d ms", durationMs)
        return "已震动 ${durationMs} 毫秒"
    }
}
