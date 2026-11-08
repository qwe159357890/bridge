package my.bridge.com.data

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

/**
 * 播报音量临时提升（用户 2026-11-08 需求；v0.2.115 重构策略）。
 *
 * 背景：用户常把媒体音量调得很小，播报（尤其后台静默时）会因音量太小而听不清。本工具在
 * 播报前把媒体音量临时拉到最大、播报完成后恢复原值。
 *
 * 判断策略（v0.2.115 用户拍板「智能检测 + 强制开关」）：
 * - 主开关（默认开）关闭 → 不提升；
 * - 强制开关（默认关）打开 → 无条件提升（只要音量不是最大就拉满）；
 * - 否则走「智能检测」：播报前查有无**其他程序**正在播放声音（音乐/视频/游戏/导航），
 *   没有才提升（避免打断用户正在听的内容）。
 *
 * 本工具只提供「拉满/恢复」与「检测其他程序是否在播放」的纯能力；「是否该提升」的完整
 * 决策（读 [JikongPrefs] 开关 + 调 [isOtherAppPlaying]）由 jikong 包通过
 * [setShouldBoostProvider] 注入——data 包不反向依赖 jikong 包。未注入时 [shouldBoost] 恒
 * false（安全默认，不提升）。
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
     * 「是否应提升音量」决策函数（v0.2.115）：由 JikongController 注入（读 JikongPrefs 开关
     * + 调 [isOtherAppPlaying]）。为避免 data 包反向依赖 jikong 包，用注入 lambda 解耦；
     * 未注入时恒 false（安全默认，不提升）。
     */
    @Volatile
    private var shouldBoostProvider: ((Context) -> Boolean)? = null

    /** 注入「是否应提升音量」决策函数。传 null 清除（退回「永不提升」）。 */
    fun setShouldBoostProvider(provider: ((Context) -> Boolean)?) {
        shouldBoostProvider = provider
    }

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
        // v0.2.116 诊断：setStreamVolume 后立即回读，验证系统音量是否真的变到最大。
        // 用户实测「锁屏自动连接时音量没调（仍用原小音量）」，需确认是 setStreamVolume 未生效
        // 还是 MediaPlayer 未跟随。
        val after = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        original = cur
        boosted = true
        Timber.i(
            "后台静默播报音量提升：媒体 %d/%d → 最大（setStreamVolume 后回读=%d），播报后恢复",
            cur, max, after,
        )
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
        // v0.2.116 诊断：记录「恢复前」的实际音量——这正是播报期间系统真正用的音量。
        // 若这里回读仍等于原音量（而非 max），说明 setStreamVolume 提升根本没生效（锁屏/时序问题）。
        val before = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        Timber.i("后台静默播报音量恢复：恢复前实际=%d → %d", before, target)
    }

    /**
     * 重申音量提升（v0.2.116）：在 MediaPlayer/TTS 真正 start 之后再次 setStreamVolume(max)。
     * 背景：Android 6.0+ 存在「setStreamVolume 在 stream 未活跃播放时调用不立即生效」的时序
     * 问题，锁屏/后台尤其明显（用户实测：演示模式前台生效、锁屏自动连接不生效）。start 后
     * 再重申一次，确保 stream 已活跃时把媒体音量顶到最大。
     */
    @Synchronized
    fun reapply(context: Context) {
        if (!boosted) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ok = runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }.isSuccess
        val after = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        Timber.i("音量提升重申（start 后）：setStreamVolume(max=%d) %s，回读=%d", max, if (ok) "成功" else "失败", after)
    }

    /** 是否应提升音量（v0.2.115）：委托给注入的决策函数；未注入恒 false（安全默认，不提升）。 */
    fun shouldBoost(context: Context): Boolean =
        runCatching { shouldBoostProvider?.invoke(context) == true }.getOrDefault(false)

    /**
     * 是否有**其他程序**正在播放声音（音乐/视频/游戏/导航等）。
     *
     * Android 8.0+（API 26）用 [AudioManager.getActivePlaybackConfigurations] 遍历当前活跃
     * 播放配置，命中媒体/游戏/导航等 usage 即视为「有程序在放声音」；8.0 以下降级用
     * [AudioManager.isMusicActive]。调用时机在「播报真正开始之前」（此刻我们自己的播报尚未
     * 启动），所以检测到的活跃播放天然属于其他 app，无需排除自己。
     */
    fun isOtherAppPlaying(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = runCatching { am.activePlaybackConfigurations }.getOrNull()
                ?: return false
            val others = configs.filter { cfg ->
                cfg.audioAttributes.usage in OTHER_APP_PLAYING_USAGES
            }
            if (others.isNotEmpty()) {
                // 诊断日志：记录命中源（usage + contentType + toString），便于定位「锁屏时为何不提升」。
                // 注：getClientUid/getClientPid 是 @hide，公开 SDK 拿不到 uid/包名，只能记 usage/contentType。
                val desc = others.joinToString("; ") { cfg ->
                    "usage=${cfg.audioAttributes.usage},contentType=${cfg.audioAttributes.contentType}"
                }
                Timber.i("音量提升智能检测：检测到其他程序在播放，跳过提升 → %s", desc)
            }
            others.isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.isMusicActive }.getOrDefault(false)
        }
    }

    /** 视为「有程序在放声音」的 usage 集合（媒体/游戏/导航/助手/未知）。 */
    private val OTHER_APP_PLAYING_USAGES = setOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANT,
        AudioAttributes.USAGE_UNKNOWN,
    )

    /**
     * 查询锁屏状态（get_lock_state 命令）。
     * 回执文案按值动态给中文结论（避免「false + 屏幕点亮」这类自相矛盾的固定说明）。
     */
    fun describeLock(context: Context): String {
        val km = context.getSystemService(KeyguardManager::class.java)
        val locked = runCatching { km?.isDeviceLocked == true }.getOrDefault(false)
        val showing = runCatching { km?.inKeyguardRestrictedInputMode() == true }.getOrDefault(false)
        val lockedText = if (locked) "已锁定" else "未锁定"
        val showingText = if (showing) "锁屏界面显示中" else "锁屏界面未显示"
        return "锁屏状态：device_locked=$locked（$lockedText），" +
            "keyguard_showing=$showing（$showingText）"
    }

    /** 查询息屏状态（get_screen_state 命令）：屏幕是否点亮（亮屏中/息屏中）。 */
    fun describeScreen(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        val screenOnText = if (screenOn) "亮屏中" else "息屏中"
        return "屏幕状态：screen_on=$screenOn（$screenOnText）"
    }
}
