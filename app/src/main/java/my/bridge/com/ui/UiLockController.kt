package my.bridge.com.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import my.bridge.com.core.common.DiagLog

/**
 * 「无界面模式」（儿童锁 / 防误触）控制器（v0.2.51）。
 *
 * 目标（用户原话对时）：
 * - 开启后用户点图标进不去 App 界面（或进入即被全屏遮挡层盖住），禁止一切操作；
 * - 只能通过管理后台 `ui_lock_stop` 取消（设备端无退出入口，防误触）；
 * - **主 WS 与保活不能断**（实时沟通硬约束），组网服务级常驻不参与释放。
 *
 * 实现采用「方案 B：全屏遮挡层」——比「拦截启动即退」更稳、可恢复性最好：
 * - [locked]=true 时，[MainScreen] 顶层直接渲染全屏遮挡层（空白/提示），
 *   **不组合** 主页/微信/销售/设置各页 ⇒ 各页面的 DisposableEffect.onDispose
 *   自动触发资源释放（销售 exitPage / 智能控制 stop / 扫码 release / 音频中继 stop），
 *   无需逐个手动调用（页面级连接随组合销毁而断）。
 * - 微信是进程级常驻（不随页面销毁释放），需按「离开即释放」开关决定：开 → stopAll，关 → 不动。
 * - 组网：服务级常驻，**不动**（无界面模式下仍可通过后台 vnt_start/vnt_stop 开关）。
 * - 主 WS + 保活：**不动**。
 *
 * 状态持久化到 Prefs（`ui_prefs` 的 `ui_locked`），冷启动 / 进程重建后仍保持锁定，
 * 避免「重启 App 就绕过锁」。管理后台命令读写同一份 Prefs。
 */
object UiLockController {

    private const val TAG = "无界面模式"
    private const val PREFS = "ui_prefs"
    private const val KEY = "ui_locked"
    private const val KEY_PIN = "ui_pin"  // 本地缓存的解锁 PIN（服务端权威，首连后缓存）

    /** 是否处于无界面模式（Compose 可观察状态）。 */
    var locked by mutableStateOf(false)
        private set

    /** 当前锁定文案（进入锁定时设置，供遮挡层展示）。 */
    var lockedMessage by mutableStateOf("")
        private set

    /** 临时解锁令牌（PIN 校验通过后置 true，允许进设置页改服务器；重进无界面即失效）。 */
    var pinUnlocked by mutableStateOf(false)
        private set

    /**
     * 解锁 PIN 的本地缓存（服务端权威，客户端首连成功后向服务端读取并缓存，此后只在本地判断）。
     * 本地无缓存（从未连上过服务器）时无法用 PIN 解锁，只能走首次配置 / 后台下发。
     */
    fun cachedPin(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PIN, "") ?: ""

    /** 服务端下发 PIN 后写入本地缓存（覆盖旧值）。 */
    fun cachePin(context: Context, pin: String) {
        val p = pin.trim()
        if (p.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PIN, p).apply()
        DiagLog.i(TAG, "已缓存服务端 PIN（长度 ${p.length}）")
    }

    /**
     * 本地校验 PIN（只读本地缓存，不依赖服务端在线——服务器改地址连不上时仍可离线解锁）。
     * 本地无缓存时返回 false（无法离线解锁）。
     */
    fun verifyPin(context: Context, input: String): Boolean {
        val cached = cachedPin(context)
        if (cached.isBlank()) return false
        return cached == input.trim()
    }

    /** PIN 校验通过后临时解锁（允许进设置页）；重新进入无界面时清零。 */
    fun grantPinUnlock() {
        pinUnlocked = true
        DiagLog.i(TAG, "PIN 校验通过，临时解锁进设置页")
    }

    /** 清除临时解锁态（离开无界面 / 重新锁定 / 进设置页改完地址后调用）。 */
    fun clearPinUnlock() {
        pinUnlocked = false
    }

    /** 持久化读取（进程启动 / MainScreen 组合前调用）。 */
    fun load(context: Context): Boolean {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        locked = v
        if (v) lockedMessage = "界面已锁定"
        return v
    }

    /**
     * 开启无界面模式（管理后台 `ui_lock_start` 调用）：
     * ① 写 Prefs 持久化；② 置 locked=true（遮挡层立即生效、各页组合销毁自动释放资源）；
     * ③ 微信按「离开即释放」开关决定是否释放。
     */
    fun lock(context: Context): String {
        if (locked) return "无界面模式已开启，无需重复开启"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
        locked = true
        lockedMessage = "界面已锁定"
        DiagLog.i(TAG, "开启无界面模式")

        // 微信：进程级常驻，不随页面组合销毁释放。读「离开即释放」开关：
        // 开 → stopAll（等同离开）；关 → 保持常驻（同组网）。
        return try {
            val releaseOnLeave = my.bridge.com.wechat.WechatPrefs.releaseOnLeave(context)
            if (releaseOnLeave) {
                my.bridge.com.wechat.WechatRuntime.stopAll("开启无界面模式（离开即释放已开）")
                "已开启无界面模式；微信「离开即释放」已开，微信资源已同步释放"
            } else {
                "已开启无界面模式；微信「离开即释放」未开，微信保持常驻在线"
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "微信资源处置失败（忽略）：${e.message}")
            "已开启无界面模式（微信资源处置异常，见设备日志）"
        }
    }

    /**
     * 关闭无界面模式（管理后台 `ui_lock_stop` 调用）：写 Prefs + 置 locked=false，
     * 遮挡层消失，各页恢复可组合（重新进入时按各自 enterPage/onPageEnter 重连）。
     */
    fun unlock(context: Context): String {
        if (!locked) return "无界面模式未开启，无需关闭"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, false).apply()
        locked = false
        lockedMessage = ""
        DiagLog.i(TAG, "关闭无界面模式")
        return "已关闭无界面模式，界面恢复正常"
    }

    /** 当前是否锁定（供命令执行器/其它模块读取）。 */
    fun isLocked(): Boolean = locked
}
