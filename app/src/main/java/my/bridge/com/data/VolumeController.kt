package my.bridge.com.data

import android.content.Context
import android.media.AudioManager
import timber.log.Timber

/**
 * 系统音量控制器（set_volume / get_volume 命令核心）。
 *
 * 实现借鉴 autoxClient `sys.js` 的 setVolume 三段兜底：
 * ① 直接 setStreamVolume → ② 回读不生效则带 FLAG_SHOW_UI 重试 →
 * ③ 仍不生效用 adjustStreamVolume 逐步逼近；每一步都回读验证，
 * 回执带设备实际档位（不同设备每流的 max 档数不同，百分比先换算）。
 *
 * 支持五种音频流：媒体 / 铃声 / 闹钟 / 通知 / 系统
 * （部分 ROM 铃声与通知联动，改一个另一个跟着变，属系统行为）。
 *
 * 权限：无需任何运行时权限；唯一限制是免打扰（DND）开启时系统会拒绝
 * 修改铃声流（SecurityException），此处转为友好失败说明。
 */
object VolumeController {

    /** catalog 的 stream 参数 → AudioManager 流类型。 */
    fun streamFor(param: String): Int = when (param.lowercase()) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    /** set_volume 兼容：旧回执用的流中文名。 */
    fun streamName(stream: Int): String = when (stream) {
        AudioManager.STREAM_RING -> "铃声"
        AudioManager.STREAM_ALARM -> "闹钟"
        AudioManager.STREAM_NOTIFICATION -> "通知"
        AudioManager.STREAM_SYSTEM -> "系统"
        else -> "媒体"
    }

    /** 单条流的音量快照。 */
    data class VolumeInfo(
        val key: String,
        val name: String,
        val current: Int,
        val max: Int,
    ) {
        val percent: Int get() = if (max > 0) (current * 100 + max / 2) / max else 0
    }

    /** 全量音量快照（get_volume 回执数据源）。 */
    data class VolumeSnapshot(
        val streams: List<VolumeInfo>,
        val ringerMode: String,
        val dndEnabled: Boolean,
    )

    /** 读取当前全部五类音量 + 铃声模式 + 免打扰状态。 */
    fun snapshot(context: Context): VolumeSnapshot {
        val am = context.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("设备音频服务不可用")
        val streams = listOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "system" to AudioManager.STREAM_SYSTEM,
        ).map { (key, stream) ->
            VolumeInfo(
                key = key,
                name = streamName(stream),
                current = runCatching { am.getStreamVolume(stream) }.getOrDefault(0),
                max = runCatching { am.getStreamMaxVolume(stream) }.getOrDefault(0),
            )
        }
        val ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            else -> "响铃"
        }
        val dnd = runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm?.currentInterruptionFilter != null &&
                nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        return VolumeSnapshot(streams, ringer, dnd)
    }

    /** 快照 → 人类可读回执（get_volume 用）。 */
    fun snapshotMessage(snap: VolumeSnapshot): String {
        val lines = snap.streams.map {
            "${it.name} ${it.current}/${it.max}（${it.percent}%）"
        }
        val dndNote = if (snap.dndEnabled) "；免打扰已开启（铃声/通知流设置可能被系统拒绝）" else ""
        return "音量：" + lines.joinToString("，") + "；铃声模式 ${snap.ringerMode}$dndNote"
    }

    /** 设置音量，返回人类可读结果（成功为「已设为 x/max（p%）」）；失败抛异常。 */
    fun set(context: Context, stream: Int, percent: Int): String {
        val am = context.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("设备音频服务不可用")
        val max = am.getStreamMaxVolume(stream)
        val target = (max.toDouble() * percent / 100.0).toInt().coerceIn(0, max)
        val name = streamName(stream)

        try {
            am.setStreamVolume(stream, target, 0)
        } catch (e: SecurityException) {
            // 免打扰开启时系统拒绝改铃声流（Settings 里也改不了），给出可操作的指引
            if (stream == AudioManager.STREAM_RING) {
                throw IllegalStateException(
                    "系统拒绝了铃声音量修改：设备可能开启了「免打扰」，" +
                        "请先在设备上关闭免打扰后重试（${e.message}）",
                )
            }
            throw e
        }

        // ① 回读验证（部分设备/流对某些 flag 静默失败）
        var actual = am.getStreamVolume(stream)
        if (actual != target && target > 0) {
            Timber.w("音量第一次设置未生效（want=%d got=%d），带 FLAG_SHOW_UI 重试", target, actual)
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            actual = am.getStreamVolume(stream)
        }
        if (actual != target && target > 0) {
            // ② 最终兜底：adjustStreamVolume 逐步逼近（步数封顶 40 防死循环）
            Timber.w("FLAG_SHOW_UI 仍未生效，改用逐步调整")
            var guard = 0
            while (am.getStreamVolume(stream) != target && guard++ < 40) {
                val direction =
                    if (am.getStreamVolume(stream) < target) AudioManager.ADJUST_RAISE
                    else AudioManager.ADJUST_LOWER
                am.adjustStreamVolume(stream, direction, 0)
            }
            actual = am.getStreamVolume(stream)
        }

        if (actual == target) {
            Timber.i("音量设置成功：%s %d/%d（%d%%）", name, actual, max, percent)
            return "$name 音量已设为 $actual/$max（$percent%）"
        }
        // 极少数 ROM 三段都改不动，如实回执而非虚报
        throw IllegalStateException("$name 音量设置未生效（目标 $target/$max，实际 $actual/$max）")
    }
}
