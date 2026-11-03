package my.bridge.com.automation.commands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import my.bridge.com.automation.a11y.BridgeAccessibilityService
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandResult
import timber.log.Timber

/**
 * 设备 + 应用命令执行器（批次 3：Droidline DeviceCommands + AutoX Device/AppUtils）。
 */
object DeviceCommandExecutors {

    /** ui_launch：启动应用（可指定 activity）。 */
    fun launch(context: Context, command: Command): CommandResult {
        val pkg = command.strParam("package", "")
        if (pkg.isEmpty()) return CommandResult(command.commandId, command.command, false, "package 不能为空")
        val activity = command.strParam("activity", "")
        return try {
            val intent = if (activity.isNotEmpty()) {
                Intent(Intent.ACTION_MAIN).setClassName(pkg, activity)
            } else {
                context.packageManager.getLaunchIntentForPackage(pkg)
            }
            if (intent == null) {
                return CommandResult(command.commandId, command.command, false, "应用未安装或无启动入口：$pkg")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(command.commandId, command.command, true, "已启动应用 $pkg")
        } catch (e: Exception) {
            Timber.e(e, "命令 ui_launch 执行失败")
            CommandResult(command.commandId, command.command, false, "启动失败：${e.message}")
        }
    }

    /** ui_open_url：ACTION_VIEW 开 URL。 */
    fun openUrl(context: Context, command: Command): CommandResult {
        val url = command.strParam("url", "")
        if (url.isEmpty()) return CommandResult(command.commandId, command.command, false, "url 不能为空")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(command.commandId, command.command, true, "已打开 $url")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "打开 URL 失败：${e.message}")
        }
    }

    /** ui_intent：按 action 开界面。 */
    fun intent(context: Context, command: Command): CommandResult {
        val action = command.strParam("action", "")
        if (action.isEmpty()) return CommandResult(command.commandId, command.command, false, "action 不能为空")
        return try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(command.commandId, command.command, true, "已触发 intent $action")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "触发 intent 失败：${e.message}")
        }
    }

    /** ui_apps：已装包列表。 */
    fun apps(context: Context, command: Command): CommandResult {
        val includeSystem = command.boolParam("system", false)
        return try {
            val pm = context.packageManager
            val list = pm.getInstalledApplications(0)
                .filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .sortedBy { it.loadLabel(pm).toString() }
            val sb = StringBuilder("[")
            list.forEachIndexed { i, info ->
                if (i > 0) sb.append(',')
                sb.append("{\"package\":").append(CommandJson.jsonStr(info.packageName))
                sb.append(",\"label\":").append(CommandJson.jsonStr(info.loadLabel(pm).toString()))
                sb.append(",\"version\":").append(CommandJson.jsonStr(
                    pm.getPackageInfo(info.packageName, 0).versionName ?: ""))
                sb.append(",\"system\":").append((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                sb.append("}")
            }
            sb.append("]")
            CommandResult(command.commandId, command.command, true, "共 ${list.size} 个应用：$sb")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "列出应用失败：${e.message}")
        }
    }

    /** ui_installed：某包版本名（未装返回空）。 */
    fun installed(context: Context, command: Command): CommandResult {
        val pkg = command.strParam("package", "")
        if (pkg.isEmpty()) return CommandResult(command.commandId, command.command, false, "package 不能为空")
        return try {
            val version = context.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
            CommandResult(command.commandId, command.command, true, version)
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, true, "")
        }
    }

    /** ui_screen_on：屏幕是否亮。 */
    fun screenOn(context: Context, command: Command): CommandResult {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return CommandResult(command.commandId, command.command, true, pm.isInteractive.toString())
    }

    /** ui_locked：是否锁屏。 */
    fun locked(context: Context, command: Command): CommandResult {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return CommandResult(command.commandId, command.command, true, km.isKeyguardLocked.toString())
    }

    /** ui_wake：点亮屏幕。 */
    fun wake(context: Context, command: Command): CommandResult {
        return try {
            val intent = Intent(context, Class.forName("${context.packageName}.MainActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            CommandResult(command.commandId, command.command, true, "已请求点亮屏幕")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "点亮屏幕失败：${e.message}")
        }
    }

    /** ui_orientation：横竖屏。 */
    fun orientation(context: Context, command: Command): CommandResult {
        val cfg = context.resources.configuration
        val o = when (cfg.orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        return CommandResult(command.commandId, command.command, true, o)
    }

    /** ui_info：设备信息汇总。 */
    fun info(context: Context, command: Command): CommandResult {
        val (w, h) = BridgeAccessibilityService.screenSize()
        val accReady = BridgeAccessibilityService.isReady()
        val sb = StringBuilder()
        sb.append("{\"model\":").append(CommandJson.jsonStr(Build.MODEL))
        sb.append(",\"brand\":").append(CommandJson.jsonStr(Build.BRAND))
        sb.append(",\"sdk\":").append(Build.VERSION.SDK_INT)
        sb.append(",\"release\":").append(CommandJson.jsonStr(Build.VERSION.RELEASE))
        sb.append(",\"product\":").append(CommandJson.jsonStr(Build.PRODUCT))
        sb.append(",\"device\":").append(CommandJson.jsonStr(Build.DEVICE))
        sb.append(",\"width\":").append(w)
        sb.append(",\"height\":").append(h)
        sb.append(",\"accessibility\":").append(accReady)
        sb.append(",\"ime\":").append(my.bridge.com.automation.a11y.BridgeKeyboard.isEnabled(context))
        sb.append("}")
        return CommandResult(command.commandId, command.command, true, sb.toString())
    }

    /** ui_set_brightness / ui_get_brightness：亮度读写。 */
    fun setBrightness(context: Context, command: Command): CommandResult {
        val value = command.intParam("value", -1, 0, 255)
        if (value < 0) return CommandResult(command.commandId, command.command, false, "value 必须是 0~255")
        return try {
            if (!Settings.System.canWrite(context)) {
                return CommandResult(command.commandId, command.command, false,
                    "缺少 WRITE_SETTINGS 权限，请在系统设置授予「修改系统设置」")
            }
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            CommandResult(command.commandId, command.command, true, "已设置亮度 $value")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "设置亮度失败：${e.message}")
        }
    }

    fun getBrightness(context: Context, command: Command): CommandResult {
        return try {
            val v = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            CommandResult(command.commandId, command.command, true, v.toString())
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读取亮度失败：${e.message}")
        }
    }

    /** ui_get_mem：总/可用内存。 */
    fun getMem(context: Context, command: Command): CommandResult {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val sb = StringBuilder()
            sb.append("{\"total\":").append(mi.totalMem)
            sb.append(",\"avail\":").append(mi.availMem)
            sb.append(",\"lowMemory\":").append(mi.lowMemory)
            sb.append("}")
            CommandResult(command.commandId, command.command, true, sb.toString())
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读取内存失败：${e.message}")
        }
    }

    /** ui_is_charging：充电状态。 */
    fun isCharging(context: Context, command: Command): CommandResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) bm.isCharging else true
            CommandResult(command.commandId, command.command, true, status.toString())
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "读取充电状态失败：${e.message}")
        }
    }

    /** ui_launch_app：按应用名启动（launchApp）。 */
    fun launchApp(context: Context, command: Command): CommandResult {
        val appName = command.strParam("name", "")
        if (appName.isEmpty()) return CommandResult(command.commandId, command.command, false, "name 不能为空")
        return try {
            val pm = context.packageManager
            val pkg = pm.getInstalledApplications(0)
                .firstOrNull { it.loadLabel(pm).toString() == appName }
                ?.packageName
            if (pkg == null) {
                return CommandResult(command.commandId, command.command, false, "未找到应用：$appName")
            }
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return CommandResult(command.commandId, command.command, false, "应用无启动入口：$appName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(command.commandId, command.command, true, "已启动应用 $appName（$pkg）")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "启动失败：${e.message}")
        }
    }

    /** ui_package_name / ui_app_name：包名/应用名互查。 */
    fun packageName(context: Context, command: Command): CommandResult {
        val appName = command.strParam("name", "")
        if (appName.isEmpty()) return CommandResult(command.commandId, command.command, false, "name 不能为空")
        val pm = context.packageManager
        val pkg = pm.getInstalledApplications(0)
            .firstOrNull { it.loadLabel(pm).toString() == appName }
            ?.packageName
        return CommandResult(command.commandId, command.command, true, pkg ?: "")
    }

    fun appName(context: Context, command: Command): CommandResult {
        val pkg = command.strParam("package", "")
        if (pkg.isEmpty()) return CommandResult(command.commandId, command.command, false, "package 不能为空")
        return try {
            val label = context.packageManager.getApplicationInfo(pkg, 0)
                .loadLabel(context.packageManager).toString()
            CommandResult(command.commandId, command.command, true, label)
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, true, "")
        }
    }

    /** ui_open_app_setting：打开应用设置页。 */
    fun openAppSetting(context: Context, command: Command): CommandResult {
        val pkg = command.strParam("package", "")
        if (pkg.isEmpty()) return CommandResult(command.commandId, command.command, false, "package 不能为空")
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(command.commandId, command.command, true, "已打开应用设置：$pkg")
        } catch (e: Exception) {
            CommandResult(command.commandId, command.command, false, "打开设置失败：${e.message}")
        }
    }
}
