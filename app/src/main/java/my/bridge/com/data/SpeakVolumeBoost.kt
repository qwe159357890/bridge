package my.bridge.com.data

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import timber.log.Timber

/**
 * 后台静默播报音量临时提升（用户 2026-11-08 穿插需求）。
 *
 * 背景：用户常把媒体音量调得很小，后台静默（熄屏/锁屏）状态下播报（如极空「靠近自动连接」
 * 连上后的电量播报）会因音量太小而听不清。本工具在「设备锁定 或 屏幕熄灭」时把媒体音量
 * 临时拉到最大，播报完成后恢复原值。
 *
 * 判断策略（用户拍板「锁定或熄屏」）：shouldBoost = isDeviceLocked() || !isInteractive()。
 * 覆盖「有锁/无锁 + 熄屏」以及「亮屏锁屏界面」；仅在「亮屏已解锁（用户正在用手机）」时不提升，
 * 尊重用户当前音量设置。仅动媒体流（STREAM_MUSIC），不动铃声/闹钟/通知/系统流。
 *
 * 幂等设计：同一时刻多次 [begin] 只记录一次原始音量、不重复拉高；直到最后一次 [end]
 * 才真正恢复（对齐 JikongSpeaker「同一时刻只保留一个播放实例」的语义，防连续/重叠播报
 * 提前恢复或停留在最大）。媒体流不受免打扰（DND）限制，setStreamVolume 无安全异常风险。
 */
object SpeakVolumeBoost {

    /** 是否处于「已提升」状态。 */
    private var boosted = false

    /** 提升前记录的媒体音量档位（未提升时为 -1）。 */
    private var original = -1

    /**
     * 「锁定或熄屏」时记录媒体音量并拉到最大；否则不动作。
     * @return 是否已进入「音量提升」态（调用方据此决定是否需要在播报完成后 [end]）。
     */
    @Synchronized
    fun begin(context: Context): Boolean {
        if (!shouldBoost(context)) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (boosted) return true            // 重叠播报：已提升，不再重复记录原始音量
        if (cur >= max) return false        // 已最大，无需提升
        val ok = runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }.isSuccess
        if (!ok) return false
        original = cur
        boosted = true
        Timber.i("后台静默播报音量提升：媒体 %d/%d → 最大，播报后恢复", cur, max)
        return true
    }

    /** 恢复播报前记录的媒体音量（幂等，未处于提升态时无操作）。 */
    @Synchronized
    fun end(context: Context) {
        if (!boosted) return
        boosted = false
        val target = original
        original = -1
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        Timber.i("后台静默播报音量恢复：媒体 → %d", target)
    }

    /** 是否应提升音量：设备锁定 或 屏幕熄灭（= 用户未在主动使用手机）。 */
    fun shouldBoost(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val locked = runCatching { km?.isDeviceLocked == true }.getOrDefault(false)
        val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        return locked || !screenOn
    }

    /** 查询锁屏状态（get_lock_state 命令）：是否锁定 / 锁屏界面是否显示中。 */
    fun describeLock(context: Context): String {
        val km = context.getSystemService(KeyguardManager::class.java)
        val locked = runCatching { km?.isDeviceLocked == true }.getOrDefault(false)
        val showing = runCatching { km?.inKeyguardRestrictedInputMode() == true }.getOrDefault(false)
        return "锁屏状态：device_locked=$locked（是否需要解锁），" +
            "keyguard_showing=$showing（锁屏界面显示中）"
    }

    /** 查询息屏状态（get_screen_state 命令）：屏幕是否点亮。 */
    fun describeScreen(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        return "屏幕状态：screen_on=$screenOn（屏幕点亮）"
    }
}
