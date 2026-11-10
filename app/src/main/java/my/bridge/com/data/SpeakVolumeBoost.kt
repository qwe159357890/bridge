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
 * 引用计数设计（v0.2.120 由布尔幂等改引用计数）：同一时刻多次 [begin] 只记录一次原始
 * 音量、不重复拉高；每次 [end] 计数 -1，减到 0 才真正恢复。这样「连接成功 beep」与
 * 「电量播报」并发（beep 尚未播完、首帧播报已开始）时，beep 的 end 只减计数、不会提前
 * 把还在播的播报音量拉低。媒体流不受免打扰（DND）限制，setStreamVolume 无安全异常风险。
 */
object SpeakVolumeBoost {

    /**
     * 音量提升引用计数（>0 = 处于「已提升」态；0 = 未提升）。
     * [begin] 每次 +1，[end] 每次 -1，减到 0 才真正恢复原音量。
     */
    private var boostCount = 0

    /** 提升前记录的媒体音量档位（未提升时为 -1）。 */
    private var original = -1

    /**
     * 「连接成功保持窗口」标记（v0.2.122）：连接成功时 beep（提示音）与首帧电量播报之间
     * 存在时间 gap（beep 仅 0.12~0.34s，首帧数据 0.85s~几十秒才到）。若 beep 的 [end] 在
     * 首帧播报 [begin] 之前触发，会把音量提前恢复，导致「连接成功但播报没拉满」。
     * 此窗口让 beep 的 [end] 不再真正恢复音量，改由「首帧播报完成」或「超时兜底」释放。
     */
    private var connectHold = false

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
        if (boostCount > 0) {               // 重叠声音：已提升，仅计数 +1，不重复记录原始音量
            boostCount++
            return true
        }
        if (cur >= max) return false        // 已最大，无需提升
        // v0.2.118：改三段兜底（与命令下发 VolumeController 同款）——后台/锁屏时裸调
        // setStreamVolume(stream, max, 0) 常被系统静默忽略（真机日志回读仍=原值），
        // 而 FLAG_SHOW_UI / adjustStreamVolume 能真正生效。三段都失败也不影响播报本身。
        val after = forceMax(am)
        // v0.2.125b：connectHold 激活时说明原始音量已由 beginConnectHold 正确记录，
        // 这里（hold 拉满失败、cur 仍 < max 的极端降级）不覆盖 original，避免恢复错值。
        if (!connectHold) {
            original = cur
        }
        boostCount = 1
        Timber.i(
            "后台静默播报音量提升：媒体 %d/%d → 最大（三段兜底后回读=%d），播报后恢复",
            cur, max, after,
        )
        return true
    }

    /** 恢复播报前记录的媒体音量（引用计数：减到 0 才真正恢复，未处于提升态时无操作）。 */
    @Synchronized
    fun end(context: Context) {
        if (boostCount <= 0) return
        boostCount--
        if (boostCount > 0) return          // 还有其它声音在播，暂不恢复
        if (connectHold) {                  // v0.2.122：连接成功窗口内不恢复，交首帧播报/超时释放
            return
        }
        restoreOriginal(context)
    }

    /**
     * 开启「连接成功保持窗口」（v0.2.122；v0.2.125b 修正计数语义）。
     *
     * connectHold 是**纯抑制标记**：只记录原始音量 + 拉满 + 阻止 [end] 在窗口内恢复，
     * **不占一次 boostCount**。这样它与 [begin]/[end] 的引用计数完全解耦，不会在半死链
     * 重建（connected 反复翻转）+ 两次 beep + 播报穿插时错位（用户 11-10 实证的「播报完
     * 音量卡满不回」根因）。
     *
     * 幂等：connectHold 已激活时重复调用直接返回 true（重建路径会多次触发本方法）。
     */
    @Synchronized
    fun beginConnectHold(context: Context): Boolean {
        if (!shouldBoost(context)) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        if (connectHold) return true            // 已激活，幂等
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur >= max) return false            // 已最大，无需提升
        val after = forceMax(am)
        original = cur                          // 记录原始音量（供 release 时恢复）
        connectHold = true
        // 不碰 boostCount：beep/播报的 begin 读到 cur==max 会 return false，天然不 +1；
        // 其 end 因 boostCount==0 也是 no-op。计数完全由正常播报链路自理。
        Timber.i(
            "连接成功音量窗口开启：媒体 %d/%d → 最大（回读=%d），首帧播报后释放",
            cur, max, after,
        )
        return true
    }

    /**
     * 释放「连接成功保持窗口」（v0.2.122；v0.2.125b 修正计数语义）。
     *
     * connectHold 是**纯抑制标记**——它只阻止 [end] 在窗口内恢复音量，**本身不占一次
     * boostCount**（[beginConnectHold] 的「接管分支」不会 +1）。因此释放窗口时**只清标记，
     * 不增减计数**：若此刻已无其它声音在播（boostCount 已归 0 但音量因窗口抑制尚未恢复），
     * 则补一次恢复；若仍有声音在播（boostCount > 0），则交给那个声音自己的 [end] 收尾
     * （此时 connectHold 已清，end 能正常恢复）。
     *
     * 改前（v0.2.122）此处有 `else { boostCount-- }`，会与「接管分支不 +1」不对称，
     * 在半死链重建（connected 反复翻转）+ 两次 beep + 播报穿插时错减计数 → boostCount
     * 卡 >0 → 音量永不恢复（用户 11-10 实证：播报完音量仍接近满）。
     */
    @Synchronized
    fun releaseConnectHold(context: Context) {
        if (!connectHold) return
        connectHold = false
        // 仅当「已无活跃声音」时才补恢复；否则交还在播声音的 end 收尾。
        if (boostCount <= 0 && original != -1) {
            restoreOriginal(context)
        }
    }

    /** 真正恢复原音量（内部工具，抽取自 [end] 与 [releaseConnectHold] 的公共收尾）。 */
    private fun restoreOriginal(context: Context) {
        val target = original
        original = -1
        boostCount = 0
        val am = context.getSystemService(AudioManager::class.java) ?: return
        // v0.2.116 诊断：记录「恢复前」的实际音量——这正是播报期间系统真正用的音量。
        // 若这里回读仍等于原音量（而非 max），说明 setStreamVolume 提升根本没生效（锁屏/时序问题）。
        val before = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (target < 0 || before == target) {
            // 无有效目标（未记录过）或音量已在目标档（用户手动调过/提升未生效过）——无需恢复。
            Timber.i("后台静默播报音量恢复：跳过（before=%d, target=%d）", before, target)
            return
        }
        // v0.2.129：恢复方向补三段兜底（与 [forceMax] 完全对称）。一加 9Plus（ColorOS）
        // 实证（用户 11-10 16:10）：提升方向裸调 setStreamVolume + FLAG_SHOW_UI 均被
        // 静默忽略、只有 adjustStreamVolume 逐步调整生效；同一台设备恢复方向裸调同样
        // 被忽略——v0.2.122~128 期间日志打出「恢复 30 → 5」但用户实测音量仍卡 30，
        // 属「假恢复」（恢复日志只打印目标值、无回读校验，多轮排查被误导）。
        // ① 直接设置；② 回读未生效带 FLAG_SHOW_UI 重试；③ 仍未生效逐步 adjust 逼近。
        val stream = AudioManager.STREAM_MUSIC
        runCatching { am.setStreamVolume(stream, target, 0) }
        var after = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        if (after != target) {
            runCatching { am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI) }
            after = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        }
        if (after != target) {
            var guard = 0
            while (runCatching { am.getStreamVolume(stream) }.getOrDefault(target) != target && guard++ < 40) {
                val dir = if (runCatching { am.getStreamVolume(stream) }.getOrDefault(target) > target)
                    AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                runCatching { am.adjustStreamVolume(stream, dir, 0) }
            }
            after = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        }
        // v0.2.129：日志打出「回读」终值——恢复是否真正生效一目了然，杜绝假恢复误导排查。
        Timber.i("后台静默播报音量恢复：恢复前实际=%d → 目标=%d，三段兜底后回读=%d", before, target, after)
    }

    /**
     * 重申音量提升（v0.2.116）：在 MediaPlayer/TTS 真正 start 之后再次 setStreamVolume(max)。
     * 背景：Android 6.0+ 存在「setStreamVolume 在 stream 未活跃播放时调用不立即生效」的时序
     * 问题，锁屏/后台尤其明显（用户实测：演示模式前台生效、锁屏自动连接不生效）。start 后
     * 再重申一次，确保 stream 已活跃时把媒体音量顶到最大。
     */
    @Synchronized
    fun reapply(context: Context) {
        if (boostCount <= 0) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val after = forceMax(am)
        Timber.i("音量提升重申（start 后）：三段兜底后回读=%d", after)
    }

    /**
     * 把媒体音量顶到最大（三段兜底，与 [VolumeController] 同款策略，v0.2.118）。
     * ① 直接 setStreamVolume；② 回读未生效则带 FLAG_SHOW_UI 重试；③ 仍未生效用
     * adjustStreamVolume 逐步逼近。返回最终实际音量；异常/无法读取返回 -1。
     * 背景：后台/锁屏时裸调 setStreamVolume(stream, max, 0) 常被系统静默忽略
     * （真机日志：回读仍=原值），而 FLAG_SHOW_UI / adjustStreamVolume 能真正生效。
     */
    private fun forceMax(am: AudioManager): Int {
        val stream = AudioManager.STREAM_MUSIC
        val max = runCatching { am.getStreamMaxVolume(stream) }.getOrDefault(-1)
        if (max <= 0) return -1
        // ① 直接设置
        runCatching { am.setStreamVolume(stream, max, 0) }
        var actual = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        if (actual != max) {
            Timber.w("音量提升：直接设置未生效（回读=%d/%d），带 FLAG_SHOW_UI 重试", actual, max)
            runCatching { am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI) }
            actual = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        }
        if (actual != max) {
            Timber.w("音量提升：FLAG_SHOW_UI 仍未生效（回读=%d/%d），改用逐步调整", actual, max)
            var guard = 0
            while (runCatching { am.getStreamVolume(stream) }.getOrDefault(max) != max && guard++ < 40) {
                runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
            }
            actual = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        }
        return actual
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
