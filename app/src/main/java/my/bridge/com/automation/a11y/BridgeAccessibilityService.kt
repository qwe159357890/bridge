package my.bridge.com.automation.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import my.bridge.com.automation.automator.GlobalActionAutomator
import my.bridge.com.automation.automator.UiObject
import my.bridge.com.media.MediaCaptureService
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bridge 辅助服务（无障碍服务，借鉴 autox 的实现思路）。
 *
 * 提供两个「无感」能力，均不需要任何系统弹窗/通知/授权框：
 * 1. **模拟电源键锁屏**：performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)（API 28+）。
 *    与用户手动按电源键完全等价——正常锁屏、正常解锁，不触发设备管理员
 *    lockNow() 的「为安全起见请输入密码解锁」强制认证（该行为是 lockNow
 *    的固有副作用，无法绕过，故 lock_screen 优先走本服务）。
 * 2. **静默截屏**：takeScreenshot()（API 30+，需 XML 声明 canTakeScreenshot）。
 *    系统直接返回屏幕 HardwareBuffer，全程无前台服务、无投屏授权框、无通知
 *    ——这正是 autox 截屏完全静默的原理，也是 MediaProjection 路线
 *    （授权框 + FGS 通知 + 偶发超时）的替代方案。
 *
 * 服务在系统设置 → 无障碍中由用户开启（引导见设置页「权限管理」）；
 * 开启后系统绑定本服务（进程重启也会自动重连），关闭/卸载时解绑。
 *
 * v0.2.39 起丰富无障碍能力（对标 autox，见 docs/research/accessibility-enrichment.md）：
 * 3. **读控件树 + 选择器查找 + 手势注入**：新增 currentRoot()（读根节点，供
 *    automator 的 UiObject/UiGlobalSelector 搜索）与手势封装（GlobalActionAutomator）。
 *    遵守耗电红线：**按命令拉取、零常驻轮询**；窗口切换事件仅用于缓存根节点，
 *    不订阅高频事件（typeViewScrolled/typeWindowContentChanged）。
 */
class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val COMPONENT =
            "my.bridge.com/my.bridge.com.automation.a11y.BridgeAccessibilityService"

        /** 系统当前绑定的服务实例；null = 未开启或未绑定。 */
        @Volatile
        private var instance: BridgeAccessibilityService? = null

        /** 本服务是否可执行动作（以实例绑定为准，比设置项检查更严格）。 */
        fun isReady(): Boolean = instance != null

        /** 设置里是否已开启本服务（读系统配置，不依赖进程绑定，用于 UI 展示）。 */
        fun isEnabledInSettings(context: Context): Boolean =
            try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )?.split(':')?.contains(COMPONENT) == true
            } catch (e: Exception) {
                false
            }

        /**
         * 是否持有 WRITE_SECURE_SETTINGS 权限（能否程序内直写无障碍开关）。
         *
         * 权限经 adb `pm grant` 一次性授予后重启不掉、仅卸载重装才需重做。
         * 探测方式：`checkSelfPermission` 对 signature|privileged 权限会如实返回
         * 授权状态（区别于「读设置值碰运气」，不会受 ROM 兼容性影响误判）。
         */
        fun canWriteSecureSettings(context: Context): Boolean =
            try {
                android.content.pm.PackageManager.PERMISSION_GRANTED ==
                    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            } catch (e: Exception) {
                false
            }

        /**
         * 程序内自动开启本无障碍服务（v0.2.47，退出后可无感恢复）。
         *
         * 直写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 追加本组件（保留其他 App
         * 的无障碍服务），并保证 `accessibility_enabled` 总开关为 1。系统随即**自动绑定**
         * 本服务，无需用户到系统设置手动开启。
         *
         * 前提：App 已一次性授予 `android.permission.WRITE_SECURE_SETTINGS`（经 adb
         * `pm grant`，重启不掉，仅卸载重装才需重做；见 docs/server/design.md §20）。
         * 未授权时静默失败（记日志），不抛异常——由设置页引导用户手动开启兜底。
         */
        fun enableInSettings(context: Context): Boolean =
            runCatching {
                val cr = context.contentResolver
                val existing = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?: ""
                val set = existing.split(':').filter { it.isNotBlank() }.toMutableSet()
                if (set.add(COMPONENT)) {
                    Settings.Secure.putString(
                        cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        set.joinToString(":"),
                    )
                }
                // 无障碍总开关：仅当需要时写 1（避免无谓重写）
                if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
                Timber.i("已自动开启 Bridge 无障碍服务")
                true
            }.onFailure { Timber.w(it, "自动开启无障碍失败（可能未授予 WRITE_SECURE_SETTINGS）") }
                .getOrDefault(false)

        /**
         * 程序内关闭本无障碍服务（v0.2.47，退出应用前调用）。
         *
         * 从 `ENABLED_ACCESSIBILITY_SERVICES` 移除本组件（保留其他 App 的无障碍服务）。
         * 目的：无障碍服务是系统级绑定角色，进程被杀后系统会**强制重启进程**恢复它，
         * 导致「退出应用」退不掉。退出前先移除绑定，进程才能真正被杀掉。
         *
         * 同样需 WRITE_SECURE_SETTINGS，未授权时静默失败（此时退出后进程仍会被无障碍
         * 复活，属预期降级——比无法退出更可接受）。
         */
        fun disableInSettings(context: Context): Boolean =
            runCatching {
                val cr = context.contentResolver
                val existing = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?: ""
                val set = existing.split(':').filter { it.isNotBlank() && it != COMPONENT }.toMutableSet()
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    set.joinToString(":"),
                )
                Timber.i("已自动关闭 Bridge 无障碍服务")
                true
            }.onFailure { Timber.w(it, "自动关闭无障碍失败（可能未授予 WRITE_SECURE_SETTINGS）") }
                .getOrDefault(false)

        /**
         * 模拟电源键锁屏（API 28+）。返回是否成功发起（发起即生效）。
         */
        fun lockScreen(): Boolean {
            val svc = instance
            if (svc == null) {
                Timber.w("模拟锁屏失败：无障碍服务未绑定")
                return false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Timber.w("模拟锁屏失败：GLOBAL_ACTION_LOCK_SCREEN 需要 Android 9+")
                return false
            }
            return try {
                svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (e: Exception) {
                Timber.e(e, "performGlobalAction 锁屏失败")
                false
            }
        }

        /**
         * 静默截屏（API 30+），阻塞至完成或超时。
         *
         * @return 产物文件路径（App 私有 media/ 目录）
         * @throws IllegalStateException 服务未绑定 / 系统版本过低 / 截屏失败 / 超时
         */
        fun takeScreenshotToFile(context: Context, timeoutMs: Long = 10_000L): String {
            val svc = instance ?: throw IllegalStateException("无障碍服务未开启")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw IllegalStateException("静默截屏需要 Android 11+（当前 ${Build.VERSION.SDK_INT}）")
            }
            val latch = CountDownLatch(1)
            var error: String? = null
            var file: File? = null
            // v0.1.98 防崩：包装 safeExecutor——超时分支 shutdown 后系统仍可能迟到回调，
            // 直接 execute 会抛 RejectedExecutionException（系统线程未捕获 → 进程崩溃）
            val executorInner = Executors.newSingleThreadExecutor()
            val executor = my.bridge.com.util.safeExecutor(executorInner)
            try {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    // TakeScreenshotCallback 是接口（非抽象类），匿名对象不带构造括号
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hardware = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer, screenshot.colorSpace,
                                ) ?: throw IllegalStateException("截屏缓冲转换失败")
                                // HardwareBuffer 属 GPU 内存，先拷贝成普通位图再释放
                                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: throw IllegalStateException("截屏位图拷贝失败")
                                val dir = MediaCaptureService.mediaDir(context).apply { mkdirs() }
                                val f = File(dir, "screenshot_${System.currentTimeMillis()}.jpg")
                                f.outputStream().use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                bitmap.recycle()
                                file = f
                            } catch (e: Throwable) {
                                error = e.message
                            } finally {
                                runCatching { screenshot.hardwareBuffer.close() }
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            error = "系统截屏失败（errorCode=$errorCode）"
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                executorInner.shutdown()
                throw IllegalStateException("无障碍截屏调用失败：${e.message}")
            }
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("无障碍截屏超时（${timeoutMs / 1000} 秒）")
                }
            } finally {
                executorInner.shutdown()
            }
            error?.let { throw IllegalStateException(it) }
            return file?.absolutePath ?: throw IllegalStateException("截屏文件未生成")
        }

        /**
         * 手势封装（坐标均为屏幕物理像素，API 24+）。返回是否成功派发并完成。
         */
        private fun automator(): GlobalActionAutomator? {
            val svc = instance ?: return null
            return GlobalActionAutomator { svc }
        }

        /** 坐标点击。 */
        fun click(x: Int, y: Int): Boolean = automator()?.click(x, y) == true

        /** 坐标长按。 */
        fun longClick(x: Int, y: Int): Boolean = automator()?.longClick(x, y) == true

        /** 坐标长按（指定按压时长 ms）。 */
        fun longClick(x: Int, y: Int, durationMs: Long): Boolean =
            automator()?.longClick(x, y, durationMs) == true

        /** 坐标滑动（durationMs 为时长）。 */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
            automator()?.swipe(x1, y1, x2, y2, durationMs) == true

        /** 多点手势（points 为坐标数组 [x,y]，durationMs 为时长）。 */
        fun gesture(points: Array<IntArray>, durationMs: Long): Boolean {
            val a = automator() ?: return false
            if (points.size < 1) return false
            return a.gesture(0, durationMs, *points)
        }

        /** 全局动作：返回（BACK）。 */
        fun back(): Boolean = automator()?.back() == true

        /** 全局动作：回桌面（HOME）。 */
        fun home(): Boolean = automator()?.home() == true

        /** 全局动作：最近任务（RECENTS）。 */
        fun recents(): Boolean = automator()?.recents() == true

        /** 全局动作：打开通知栏（NOTIFICATIONS）。 */
        fun globalNotifications(): Boolean = automator()?.notifications() == true

        /** 全局动作：打开快捷设置（QUICK_SETTINGS）。 */
        fun globalQuickSettings(): Boolean = automator()?.quickSettings() == true

        /** 屏幕物理尺寸（宽, 高）。 */
        fun screenSize(): Pair<Int, Int> {
            val svc = instance ?: return 0 to 0
            return try {
                val dm = svc.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            } catch (e: Exception) {
                0 to 0
            }
        }

        /**
         * IME 兜底：清空当前输入目标（selectAll + commit ""）。
         */
        fun imeReplaceAll(context: Context, text: String): Boolean {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return false
            val id = "${context.packageName}/.automation.a11y.BridgeKeyboard"
            if (!BridgeKeyboard.isEnabled(context)) return false
            val previousId = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            }.getOrNull()
            return try {
                imm.setInputMethod(null, id)
                Thread.sleep(120)
                val ok = BridgeKeyboard.replaceAll(text)
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                ok
            } catch (e: Exception) {
                Timber.e(e, "IME 清空文本失败")
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                false
            }
        }

        /**
         * IME 兜底：发送按键码（sendDownUpKeyEvents）。
         */
        fun imeSendKey(context: Context, keycode: Int): Boolean {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return false
            val id = "${context.packageName}/.automation.a11y.BridgeKeyboard"
            if (!BridgeKeyboard.isEnabled(context)) return false
            val previousId = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            }.getOrNull()
            return try {
                imm.setInputMethod(null, id)
                Thread.sleep(120)
                val ok = BridgeKeyboard.sendKey(keycode)
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                ok
            } catch (e: Exception) {
                Timber.e(e, "IME 发送按键失败")
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                false
            }
        }

        /** 读取指定坐标像素色 #RRGGBB（静默截屏后取像素）。 */
        fun pixelColor(context: Context, x: Int, y: Int): String? {
            val path = try {
                takeScreenshotToFile(context)
            } catch (e: Exception) {
                Timber.w(e, "取色截屏失败")
                return null
            }
            return try {
                val bmp = BitmapFactory.decodeFile(path) ?: return null
                try {
                    if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return null
                    val px = bmp.getPixel(x, y)
                    "#%06X".format(px and 0xFFFFFF)
                } finally {
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Timber.w(e, "取色失败")
                null
            }
        }

        /**
         * IME 兜底：切换到 Bridge 极简输入法并提交文本，完成后切回原输入法。
         *
         * 走 ACTION_SET_TEXT 失败时调用。原理：InputMethodService 的 InputConnection
         * 可直接 commitText，对密码框/自定义输入框等 ACTION_SET_TEXT 失效的场景有效。
         *
         * @return 是否成功提交（false = 未启用 BridgeKeyboard / 切换失败 / 提交失败）
         */
        fun imeCommitText(context: Context, text: String): Boolean {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return false
            val id = "${context.packageName}/.automation.a11y.BridgeKeyboard"
            if (!BridgeKeyboard.isEnabled(context)) {
                Timber.w("IME 兜底失败：BridgeKeyboard 输入法未启用")
                return false
            }
            val previousId = try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            } catch (e: Exception) {
                null
            }
            return try {
                // 切换到 BridgeKeyboard
                imm.setInputMethod(null, id)
                Thread.sleep(120) // 给系统切换 IME 的时间
                // 提交文本
                val ok = BridgeKeyboard.commitText(text)
                // 切回原输入法
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                ok
            } catch (e: Exception) {
                Timber.e(e, "IME 兜底提交文本失败")
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                false
            }
        }

        /**
         * 读取当前活动窗口根节点（包装为 automator UiObject，供 UiGlobalSelector 搜索）。
         * 优先走窗口切换事件缓存的快照，其次实时取（跨进程 IPC），均失败返回 null。
         *
         * @return 根节点 UiObject（调用方用完须 recycle），或 null（未绑定/取不到）
         */
        fun currentRoot(): UiObject? {
            val svc = instance ?: return null
            val node: AccessibilityNodeInfo = try {
                svc.cachedRoot ?: svc.rootInActiveWindow ?: return null
            } catch (e: Exception) {
                Timber.w(e, "读取当前窗口根节点失败")
                return null
            }
            return UiObject.createRoot(node)
        }

        /** 读取所有窗口根节点（含系统窗口，如状态栏/输入法），供 all_windows 导出。 */
        fun allWindowRoots(): List<UiObject> {
            return allWindowRootsWithType().map { it.second }
        }

        /**
         * 读取所有窗口根节点，并附带窗口类型（AccessibilityWindowInfo.type）。
         * 供 ui_dump(all_windows=true) 导出——状态栏图标（信号/电池/时间）是
         * SystemUI 的独立窗口，不在 rootInActiveWindow 里，必须遍历 windows 才能拿到。
         */
        fun allWindowRootsWithType(): List<Pair<Int, UiObject>> {
            val svc = instance ?: return emptyList()
            return try {
                svc.windows?.filterNotNull()?.mapNotNull { w ->
                    val n = w.root ?: return@mapNotNull null
                    val root = runCatching { UiObject.createRoot(n) }.getOrNull() ?: return@mapNotNull null
                    w.type to root
                } ?: emptyList()
            } catch (e: Exception) {
                Timber.w(e, "读取所有窗口根节点失败")
                emptyList()
            }
        }

        /** 最近一次捕获的 Toast 文本；null = 尚无。 */
        fun lastToast(): String? = instance?.lastToast

        /** 最近一次 Toast 时间戳（毫秒）。 */
        fun lastToastAt(): Long = instance?.lastToastAt ?: 0L

        /** 软键盘是否显示（窗口里有无 TYPE_INPUT_METHOD）。 */
        fun keyboardShown(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.windows?.any {
                    it?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                } == true
            } catch (e: Exception) {
                false
            }
        }

        /** 当前前台包名（无活动窗口返回 null）。 */
        fun currentPackage(): String? = instance?.currentPackage

        /** 当前前台 activity（无缓存返回 null）。 */
        fun currentActivity(): String? = instance?.currentActivity

        /** 通过 IME 读剪贴板（Android 10+ 唯一合法路径）。 */
        fun imeReadClipboard(context: Context): String? {
            val id = "${context.packageName}/.automation.a11y.BridgeKeyboard"
            if (!BridgeKeyboard.isEnabled(context)) return null
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return null
            val previousId = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            }.getOrNull()
            return try {
                imm.setInputMethod(null, id)
                Thread.sleep(120)
                val result = BridgeKeyboard.readClipboard()
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                result
            } catch (e: Exception) {
                Timber.e(e, "IME 读剪贴板失败")
                if (!previousId.isNullOrEmpty() && previousId != id) {
                    runCatching { imm.setInputMethod(null, previousId) }
                }
                null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("Bridge 辅助服务已连接（模拟锁屏 + 静默截屏 + 控件自动化可用）")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        clearCachedRoot()
        Timber.w("Bridge 辅助服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        clearCachedRoot()
        super.onDestroy()
    }

    /** 窗口切换事件缓存的根节点快照（避免命令频繁时反复跨进程 IPC 取树）。 */
    @Volatile
    private var cachedRoot: AccessibilityNodeInfo? = null

    /** 最近一次 Toast 文本 + 时间戳（verification code 场景）。 */
    @Volatile
    private var lastToast: String? = null

    @Volatile
    private var lastToastAt: Long = 0L

    /** 前台包名 + activity（窗口切换事件刷新）。 */
    @Volatile
    private var currentPackage: String? = null

    @Volatile
    private var currentActivity: String? = null

    private fun clearCachedRoot() {
        cachedRoot?.let { runCatching { it.recycle() } }
        cachedRoot = null
    }

    /**
     * 仅订阅窗口切换（低频）用于刷新根节点缓存；不订阅滚动/内容变化（高频，
     * 会频繁唤醒进程，违反「按命令拉取、零常驻轮询」的耗电红线）。
     * 同时捕获 Toast（TYPE_NOTIFICATION_STATE_CHANGED 且非 Notification）与前台 activity。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                clearCachedRoot()
                cachedRoot = runCatching { rootInActiveWindow }.getOrNull()
                event.packageName?.let { currentPackage = it.toString() }
                event.className?.let { currentActivity = it.toString() }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 非 Notification 且带文本的视为 Toast
                val text = event.text?.joinToString(" ") { it.toString() }
                if (text != null && text.isNotBlank()) {
                    lastToast = text
                    lastToastAt = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onInterrupt() = Unit
}
