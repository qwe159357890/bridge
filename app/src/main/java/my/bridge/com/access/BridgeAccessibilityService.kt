package my.bridge.com.access

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
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
 */
class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val COMPONENT =
            "my.bridge.com/my.bridge.com.access.BridgeAccessibilityService"

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
            val executor = Executors.newSingleThreadExecutor()
            try {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback() {
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
                executor.shutdown()
                throw IllegalStateException("无障碍截屏调用失败：${e.message}")
            }
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("无障碍截屏超时（${timeoutMs / 1000} 秒）")
                }
            } finally {
                executor.shutdown()
            }
            error?.let { throw IllegalStateException(it) }
            return file?.absolutePath ?: throw IllegalStateException("截屏文件未生成")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("Bridge 辅助服务已连接（模拟锁屏 + 静默截屏可用）")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Timber.w("Bridge 辅助服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 纯动作型服务：不订阅窗口内容（canRetrieveWindowContent=false），事件仅作占位。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
