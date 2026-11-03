package my.bridge.com.automation.commands

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import my.bridge.com.automation.a11y.BridgeAccessibilityService
import my.bridge.com.automation.automator.UiObject
import my.bridge.com.automation.query.Matcher
import my.bridge.com.automation.query.SelectorParser
import my.bridge.com.automation.query.Target
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandResult
import timber.log.Timber

/**
 * 元素 / 动作类命令执行器（批次 2：Droidline UiCommands 全集 + 动作鲁棒性）。
 *
 * 统一约定：
 * - selector 参数支持「链式 DSL」或「对象 JSON（关系查询）」两种形式。
 * - 元素命令统一带 nth（0-based，兼容旧 index）。
 * - 失败回执 message 携带结构化提示；错误码经 RPC 层映射（见 AutomationError）。
 */
object UiCommandExecutors {

    private const val POLL_MS = 200L

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    private fun notReady(cmd: Command): CommandResult =
        CommandResult(cmd.commandId, cmd.command, false,
            "无障碍服务未开启：请在设备「设置 → 无障碍 → Bridge 辅助服务」开启后重试")

    private fun rootOrNull(): UiObject? = BridgeAccessibilityService.currentRoot()

    private fun parseSelector(command: Command): Target {
        val expr = command.strParam("selector", "")
        return SelectorParser.parse(expr)
    }

    private fun nth(command: Command): Int {
        // 兼容旧 index 参数与新的 nth 参数
        return command.intParam("nth", command.intParam("index", 0, 0, 200), 0, 200)
    }

    private fun timeoutMs(command: Command, default: Long = 0): Long =
        command.longParam("timeout_ms", command.longParam("timeout", default, 0, 3600000), 0, 3600000)

    /** 立即查找（不等待）。 */
    private fun findNow(target: Target, nth: Int): UiObject? {
        val root = rootOrNull() ?: return null
        return try {
            target.findAll(root, nth + 1).getOrNull(nth)
        } finally {
            root.recycle()
        }
    }

    /** 等待元素出现（timeout>0 轮询，间隔 POLL_MS，命令期间零常驻）。 */
    private fun await(target: Target, nth: Int, timeoutMs: Long): UiObject? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            findNow(target, nth)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(POLL_MS)
        }
    }

    /** 节点坐标点击（含三级降级）。返回 via 描述或 null。 */
    private fun clickVia(n: UiObject): String? {
        if (n.clickable() && n.enabled() && n.click()) return "node"
        Matcher.clickableAncestor(n)?.let { if (it.click()) return "parent" }
        val b = n.bounds()
        if (!b.isEmpty) {
            val cx = b.centerX()
            val cy = b.centerY()
            if (BridgeAccessibilityService.click(cx, cy)) return "gesture"
        }
        return null
    }

    /** 节点坐标长按（三级降级）。 */
    private fun longClickVia(n: UiObject, ms: Long): String? {
        if (n.longClickable() && n.enabled() && n.longClick()) return "node"
        Matcher.longClickableAncestor(n)?.let { if (it.longClick()) return "parent" }
        val b = n.bounds()
        if (!b.isEmpty) {
            if (BridgeAccessibilityService.longClick(b.centerX(), b.centerY())) return "gesture"
        }
        return null
    }

    // ------------------------------------------------------------------
    // 批次 2：元素 / 动作命令
    // ------------------------------------------------------------------

    /** ui_scroll_to：滚动直到目标可见（signature 死循环保护）。 */
    fun scrollTo(command: Command): CommandResult {
        try {
            val target = parseSelector(command)
            val n = nth(command)
            val dir = command.strParam("direction", "down")
            if (dir !in setOf("down", "up", "left", "right")) {
                return CommandResult(command.commandId, command.command, false, "direction 必须是 down/up/left/right")
            }
            val maxSwipes = command.intParam("max_swipes", 20, 0, 200)
            var swipes = 0
            var lastSig: Int? = null
            var unchanged = 0
            while (true) {
                val r = rootOrNull()
                if (r == null) {
                    return CommandResult(command.commandId, command.command, false, "滚动失败：无障碍服务不可用")
                }
                val hit = target.findAll(r, n + 1).getOrNull(n)
                if (hit != null && hit.visibleToUser() && !hit.bounds().isEmpty) {
                    r.recycle()
                    return CommandResult(command.commandId, command.command, true,
                        "滚动 $swipes 次后找到目标：${CommandJson.nodeToJson(hit)}")
                }
                val sig = Matcher.signature(r)
                unchanged = if (sig == lastSig) unchanged + 1 else 0
                lastSig = sig
                if (swipes >= maxSwipes || unchanged >= 2) {
                    val still = target.findAll(r, n + 1).getOrNull(n)
                    r.recycle()
                    return if (still != null) {
                        CommandResult(command.commandId, command.command, true,
                            "滚动 $swipes 次后目标仍部分可见：${CommandJson.nodeToJson(still)}")
                    } else {
                        CommandResult(command.commandId, command.command, false,
                            "滚动 $maxSwipes 次后仍未找到目标")
                    }
                }
                scrollOnce(r, dir)
                swipes++
                r.recycle()
                Thread.sleep(450)
            }
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_scroll_to 执行失败")
            return CommandResult(command.commandId, command.command, false, "滚动失败：${e.message}")
        }
    }

    private fun scrollOnce(root: UiObject, dir: String) {
        val forward = dir == "down" || dir == "right"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        Matcher.mainScrollable(root)?.let { if (it.performAction(action)) return }
        // 无 scrollable 容器 → 方向滑动手势（内容向下移动 = 手指向上）
        val (w, h) = BridgeAccessibilityService.screenSize()
        val finger = when (dir) { "down" -> "up"; "up" -> "down"; "right" -> "left"; else -> "right" }
        val l = directionLine(finger, w, h)
        BridgeAccessibilityService.swipe(l[0], l[1], l[2], l[3], 300)
    }

    private fun directionLine(dir: String, w: Int, h: Int): IntArray = when (dir) {
        "up" -> intArrayOf(w / 2, h * 8 / 10, w / 2, h * 2 / 10)
        "down" -> intArrayOf(w / 2, h * 2 / 10, w / 2, h * 8 / 10)
        "left" -> intArrayOf(w * 8 / 10, h / 2, w * 2 / 10, h / 2)
        "right" -> intArrayOf(w * 2 / 10, h / 2, w * 8 / 10, h / 2)
        else -> throw IllegalArgumentException(dir)
    }

    /** ui_wait_idle：等画面静止（signature 连续 quiet 毫秒不变）。 */
    fun waitIdle(command: Command): CommandResult {
        val quiet = command.longParam("ms", 500, 50, 60000)
        val timeout = timeoutMs(command).takeIf { it > 0 } ?: 10000L
        val deadline = SystemClock.elapsedRealtime() + timeout
        var layout: Int? = null
        var layoutAt = SystemClock.elapsedRealtime()
        while (true) {
            val root = rootOrNull()
            if (root == null) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return CommandResult(command.commandId, command.command, false, "等待画面静止超时（无障碍未就绪）")
                }
                Thread.sleep(POLL_MS)
                continue
            }
            val now = Matcher.signature(root)
            root.recycle()
            if (layout == null || now != layout) {
                layout = now
                layoutAt = SystemClock.elapsedRealtime()
            }
            val since = SystemClock.elapsedRealtime() - layoutAt
            if (since >= quiet) {
                return CommandResult(command.commandId, command.command, true, "画面已静止（持续 $quiet 毫秒）")
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return CommandResult(command.commandId, command.command, false, "等待画面静止超时（${timeout}ms）")
            }
            Thread.sleep(minOf(POLL_MS, (quiet - since).coerceAtLeast(20)))
        }
    }

    /** ui_wait_gone：等元素消失。 */
    fun waitGone(command: Command): CommandResult {
        val target = parseSelector(command)
        val timeout = timeoutMs(command).takeIf { it > 0 } ?: 10000L
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            if (findNow(target, 0) == null) {
                return CommandResult(command.commandId, command.command, true, "元素已消失")
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return CommandResult(command.commandId, command.command, false, "等待元素消失超时（${timeout}ms）")
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** ui_clear：清空输入框。 */
    fun clear(context: Context, command: Command): CommandResult {
        val root = rootOrNull() ?: return notReady(command)
        return try {
            val target = parseSelector(command)
            val n = nth(command)
            val list = target.findAll(root, n + 1)
            val node = list.getOrNull(n)
            root.recycle()
            if (node == null) {
                return CommandResult(command.commandId, command.command, false, "未找到匹配输入框")
            }
            val editable = Matcher.editableTarget(node)
            if (editable.setText("") && (editable.password() || editable.text().isEmpty())) {
                return CommandResult(command.commandId, command.command, true, "已清空输入框：${CommandJson.nodeToJson(editable)}")
            }
            // IME 兜底：聚焦后 replaceAll("")
            val b = editable.bounds()
            BridgeAccessibilityService.click(b.centerX(), b.centerY())
            Thread.sleep(150)
            if (BridgeAccessibilityService.imeReplaceAll(context, "")) {
                CommandResult(command.commandId, command.command, true, "已清空输入框（IME 兜底）：${CommandJson.nodeToJson(editable)}")
            } else {
                CommandResult(command.commandId, command.command, false,
                    "清空失败：ACTION_SET_TEXT 无效且 IME 兜底未成功（请启用 Bridge 输入辅助）")
            }
        } catch (e: Exception) {
            runCatching { root.recycle() }
            Timber.e(e, "命令 ui_clear 执行失败")
            CommandResult(command.commandId, command.command, false, "清空失败：${e.message}")
        }
    }

    /** ui_sendkey：键名/键码/文字三路。 */
    fun sendKey(context: Context, command: Command): CommandResult {
        val text = command.strParam("text", "")
        val key = command.strParam("key", "")
        return try {
            // 全局动作
            if (key in setOf("back", "home", "recents", "notifications", "quick_settings", "lock")) {
                val ok = when (key) {
                    "back" -> BridgeAccessibilityService.back()
                    "home" -> BridgeAccessibilityService.home()
                    "recents" -> BridgeAccessibilityService.recents()
                    else -> globalAction(key)
                }
                return if (ok) CommandResult(command.commandId, command.command, true, "已触发全局动作 $key")
                else notReady(command)
            }
            // 键码
            if (key.isNotEmpty() && key.toIntOrNull() != null) {
                val code = key.toInt()
                return if (BridgeAccessibilityService.imeSendKey(context, code)) {
                    CommandResult(command.commandId, command.command, true, "已发送键码 $code（IME）")
                } else {
                    CommandResult(command.commandId, command.command, false, "发送键码失败（IME 未启用）")
                }
            }
            // 键名
            if (key.isNotEmpty()) {
                val code = keyNameToCode(key)
                if (code != 0) {
                    return if (BridgeAccessibilityService.imeSendKey(context, code)) {
                        CommandResult(command.commandId, command.command, true, "已发送键 $key（IME）")
                    } else {
                        CommandResult(command.commandId, command.command, false, "发送键失败（IME 未启用）")
                    }
                }
            }
            // 文字
            if (text.isNotEmpty()) {
                return if (BridgeAccessibilityService.imeCommitText(context, text)) {
                    CommandResult(command.commandId, command.command, true, "已提交文本（IME）")
                } else {
                    CommandResult(command.commandId, command.command, false, "提交文本失败（IME 未启用）")
                }
            }
            CommandResult(command.commandId, command.command, false, "请提供 key（键名/键码）或 text（文字）")
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_sendkey 执行失败")
            CommandResult(command.commandId, command.command, false, "发送按键失败：${e.message}")
        }
    }

    private fun globalAction(name: String): Boolean =
        when (name) {
            "recents" -> BridgeAccessibilityService.recents()
            else -> false
        }

    private fun keyNameToCode(name: String): Int = when (name) {
        "enter" -> 66
        "tab" -> 61
        "del", "backspace" -> 67
        "forward_del" -> 112
        "space" -> 62
        "escape" -> 111
        "up" -> 19
        "down" -> 20
        "left" -> 21
        "right" -> 22
        "search" -> 84
        "menu" -> 82
        "page_up" -> 92
        "page_down" -> 93
        "move_home" -> 122
        "move_end" -> 123
        else -> 0
    }

    /** ui_exists：元素是否存在（不等待）。 */
    fun exists(command: Command): CommandResult {
        return try {
            val target = parseSelector(command)
            val hit = findNow(target, nth(command))
            CommandResult(command.commandId, command.command, true, (hit != null).toString())
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "exists 失败：${e.message}")
        }
    }

    /** ui_get_text：元素文本（密码框返回空）。 */
    fun getText(command: Command): CommandResult {
        return try {
            val target = parseSelector(command)
            val hit = findNow(target, nth(command))
            if (hit == null) {
                CommandResult(command.commandId, command.command, false, "未找到匹配控件")
            } else {
                val t = if (hit.password()) "" else hit.text()
                CommandResult(command.commandId, command.command, true, t)
            }
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "get_text 失败：${e.message}")
        }
    }

    /** ui_checked / ui_enabled / ui_selected：读布尔 flag。 */
    fun flag(command: Command, pick: (UiObject) -> Boolean): CommandResult {
        return try {
            val target = parseSelector(command)
            val hit = findNow(target, nth(command))
            val v = hit?.let(pick) ?: false
            CommandResult(command.commandId, command.command, true, v.toString())
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读取属性失败：${e.message}")
        }
    }

    /** ui_count：匹配数量。 */
    fun count(command: Command): CommandResult {
        val root = rootOrNull() ?: return notReady(command)
        return try {
            val target = parseSelector(command)
            val list = target.findAll(root, Int.MAX_VALUE)
            root.recycle()
            CommandResult(command.commandId, command.command, true, list.size.toString())
        } catch (e: Exception) {
            runCatching { root.recycle() }
            CommandResult(command.commandId, command.command, false, "count 失败：${e.message}")
        }
    }

    /** ui_which：多个候选中哪个先出现（返回序号或 -1）。 */
    fun which(command: Command): CommandResult {
        try {
            val candidates = command.params.get("candidates")
            if (candidates == null || !candidates.isJsonArray) {
                return CommandResult(command.commandId, command.command, false, "candidates 必须是选择器数组")
            }
            val arr = candidates.asJsonArray
            val targets: List<Target> = (0 until arr.size()).map { i ->
                val el = arr[i]
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                    SelectorParser.parse(el.asString)
                } else {
                    SelectorParser.parse(el.toString())
                }
            }
            val timeout = timeoutMs(command).takeIf { it > 0 } ?: 10000L
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (true) {
                val root = rootOrNull()
                if (root != null) {
                    for ((idx, t) in targets.withIndex()) {
                        val hit = t.findAll(root, 1).isNotEmpty()
                        if (hit) {
                            root.recycle()
                            return CommandResult(command.commandId, command.command, true, idx.toString())
                        }
                    }
                    root.recycle()
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return CommandResult(command.commandId, command.command, true, "-1")
                }
                Thread.sleep(POLL_MS)
            }
        } catch (e: Exception) {
            return CommandResult(command.commandId, command.command, false, "which 失败：${e.message}")
        }
    }

    /** ui_in_app：某包是否前台。 */
    fun inApp(command: Command): CommandResult {
        val pkg = command.strParam("package", "")
        if (pkg.isEmpty()) return CommandResult(command.commandId, command.command, false, "package 不能为空")
        val cur = BridgeAccessibilityService.currentPackage()
        return CommandResult(command.commandId, command.command, true, (cur == pkg).toString())
    }

    /** ui_keyboard_shown：软键盘是否显示。 */
    fun keyboardShown(command: Command): CommandResult =
        CommandResult(command.commandId, command.command, true, BridgeAccessibilityService.keyboardShown().toString())

    /** ui_last_toast：最近 toast（max_age 秒内）。 */
    fun lastToast(command: Command): CommandResult {
        val maxAge = command.longParam("max_age", 30, 1, 3600)
        val age = System.currentTimeMillis() - BridgeAccessibilityService.lastToastAt()
        return if (BridgeAccessibilityService.lastToastAt() > 0 && age <= maxAge * 1000) {
            CommandResult(command.commandId, command.command, true, BridgeAccessibilityService.lastToast() ?: "")
        } else {
            CommandResult(command.commandId, command.command, true, "")
        }
    }

    /** ui_color：坐标像素色 #RRGGBB。 */
    fun color(context: Context, command: Command): CommandResult {
        val x = command.params.get("x")?.takeIf { it.isJsonPrimitive }?.asInt
        val y = command.params.get("y")?.takeIf { it.isJsonPrimitive }?.asInt
        if (x == null || y == null) {
            return CommandResult(command.commandId, command.command, false, "缺少 x/y 坐标")
        }
        return try {
            val hex = BridgeAccessibilityService.pixelColor(context, x, y)
                ?: return CommandResult(command.commandId, command.command, false, "取色失败（截屏不可用）")
            CommandResult(command.commandId, command.command, true, hex)
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "取色失败：${e.message}")
        }
    }

    /** ui_long_click：长按控件（三级降级）。 */
    fun longClick(command: Command): CommandResult {
        return try {
            val target = parseSelector(command)
            val ms = command.longParam("duration_ms", 800, 50, 60000)
            val node = await(target, nth(command), timeoutMs(command).takeIf { it > 0 } ?: 5000)
            if (node == null) {
                return CommandResult(command.commandId, command.command, false, "未找到匹配控件")
            }
            val via = longClickVia(node, ms)
            if (via != null) {
                CommandResult(command.commandId, command.command, true, "已长按控件（$via）：${CommandJson.nodeToJson(node)}")
            } else {
                CommandResult(command.commandId, command.command, false, "长按失败：控件及其祖先均不可长按，坐标手势也未成功")
            }
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "长按失败：${e.message}")
        }
    }

    /** ui_recents / ui_open_notifications / ui_quick_settings：全局动作。 */
    fun global(command: Command, name: String): CommandResult {
        val ok = when (name) {
            "recents" -> BridgeAccessibilityService.recents()
            "notifications" -> BridgeAccessibilityService.globalNotifications()
            "quick_settings" -> BridgeAccessibilityService.globalQuickSettings()
            else -> false
        }
        return if (ok) CommandResult(command.commandId, command.command, true, "已触发 $name")
        else notReady(command)
    }
}
