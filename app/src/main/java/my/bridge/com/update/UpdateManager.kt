package my.bridge.com.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.bridge.com.R
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.storage.DeviceStore
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用自动更新管理器（check_update 命令 + 关于页「检查更新」按钮共用）。
 *
 * 完整闭环：检查 → 下载（进度通知）→ 拉起系统安装页（Android 不允许静默安装，
 * 用户点一次「安装」完成）。产物缓存在 App 私有 updates/ 目录，同一版本已下载
 * 过则跳过下载直接进安装页。
 *
 * 防重复触发：[busy] 原子锁贯穿整个流程——下载/安装中再次触发（重复下发命令、
 * 连点按钮）直接拒绝并提示，绝不并发下载或重复拉安装页。
 *
 * 依赖获取：本对象不是 Hilt 单例（供命令执行器与 Compose 两处使用），经
 * [EntryPointAccessors] 从 SingletonComponent 现取 DeviceStore / BridgeApiClient。
 */
object UpdateManager {

    /** 流程阶段（防重复触发与 UI 展示共用）。 */
    enum class Phase { IDLE, DOWNLOADING, WAITING_INSTALL }

    private val busy = AtomicBoolean(false)

    /** 当前阶段（volatile 读，供 UI/命令判断）。 */
    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Hilt 依赖入口（从 App 上下文现取，见类注释）。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun deviceStore(): DeviceStore
        fun apiClient(): BridgeApiClient
    }

    private fun deps(context: Context): Deps =
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    /** APK 缓存目录（App 私有，无需存储权限）。 */
    fun updateDir(context: Context): File =
        context.getExternalFilesDir("updates") ?: File(context.filesDir, "updates")

    /**
     * 检查并执行更新全流程（阻塞调用方协程直至出结果或弹安装页）。
     *
     * @return 给用户看的结果说明（成功=已弹安装页 / 已是最新 / 忙 / 失败原因）
     */
    suspend fun checkAndInstall(context: Context): String {
        // 原子锁：下载/安装中再次触发直接拒绝（防连点、防重复下发并发下载）
        if (!busy.compareAndSet(false, true)) {
            return when (phase) {
                Phase.DOWNLOADING -> "更新包正在下载中，请稍候（通知栏有进度）"
                Phase.WAITING_INSTALL -> "更新包已就绪，请在安装页确认安装；或先取消安装再重试"
                else -> "更新任务进行中，请稍候"
            }
        }
        phase = Phase.DOWNLOADING
        try {
            val d = deps(context)
            val deviceId = d.deviceStore().deviceId()
            val token = runCatching { d.deviceStore().token() }.getOrNull()
            val current = my.bridge.com.core.common.DiagLog.appVersionName(context).ifBlank { "unknown" }

            // ① 检查
            val latest = try {
                d.apiClient().getLatestUpdate(deviceId, token, current)
            } catch (e: Exception) {
                Timber.e(e, "检查更新失败")
                return "检查更新失败：${e.message}"
            }
            if (!latest.found) return "服务器暂无更新包（updates 目录为空），当前版本 v$current"
            if (!latest.available) return "已是最新版本 v$current"

            val target = File(updateDir(context), latest.filename)
            // ② 下载（缓存命中跳过：同一文件已完整下载过）
            if (target.isFile && target.length() == latest.size_bytes && latest.size_bytes > 0) {
                Timber.i("更新包缓存命中：%s", target.name)
            } else {
                try {
                    download(context, d.apiClient(), deviceId, token, latest.filename, target, latest.size_bytes)
                } catch (e: Exception) {
                    Timber.e(e, "更新包下载失败")
                    target.delete() // 清掉半截文件
                    return "更新包下载失败：${e.message}"
                }
            }

            // ③ 安装权限（Android 8+ 需「允许安装未知应用」；未授权时引导开启，
            //    APK 已保留，授权回来再触发一次即可直接进安装页）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                openUnknownSourcesSettings(context)
                phase = Phase.IDLE
                return "已下载 v${latest.version}（%.1f MB），需允许「安装未知应用」：" +
                    "请在弹出的设置页中允许 Bridge 安装，然后再点一次检查更新".format(latest.size_bytes / 1048576.0)
            }

            // ④ 拉起系统安装页（用户点一次「安装」完成；无法静默安装是系统设计）
            phase = Phase.WAITING_INSTALL
            val launched = try {
                launchInstaller(context, target)
                true
            } catch (e: Exception) {
                Timber.e(e, "拉起安装页失败")
                false
            }
            phase = Phase.IDLE
            cancelProgressNotification(context)
            return if (launched) {
                "已下载 v${latest.version} 并弹出安装页，请在设备上点击「安装」完成更新"
            } else {
                "更新包已下载到 ${target.name}，但拉起安装页失败：请在文件管理器中手动安装"
            }
        } finally {
            busy.set(false)
        }
    }

    /** 流式下载 APK 并经进度通知展示（先 .tmp 再原子重命名，防半截文件）。 */
    private suspend fun download(
        context: Context,
        apiClient: BridgeApiClient,
        deviceId: String,
        token: String?,
        filename: String,
        target: File,
        totalBytes: Long,
    ) = withContext(Dispatchers.IO) {
        updateDir(context).mkdirs()
        val tmp = File(updateDir(context), "$filename.tmp")
        val body = apiClient.downloadUpdate(filename, deviceId, token)
        try {
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastNotify = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        // 进度通知节流：每 5% 或每 500ms 刷一次
                        val now = System.currentTimeMillis()
                        val pct = if (totalBytes > 0) (read * 100 / totalBytes).toInt() else -1
                        if (now - lastNotify > 500) {
                            lastNotify = now
                            notifyProgress(context, pct, read, totalBytes)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("更新包保存失败（重命名失败）")
            }
        } finally {
            tmp.delete()
            runCatching { body.close() }
        }
        Timber.i("更新包下载完成：%s（%d bytes）", target.name, target.length())
    }

    /** 用 FileProvider 拉起系统 APK 安装页。 */
    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 打开「允许安装未知应用」授权页（定位到 Bridge 自己）。 */
    private fun openUnknownSourcesSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ------------------------------------------------------------------
    // 进度通知（独立渠道 app_update，IMPORTANCE_LOW：下载过程不打扰）
    // ------------------------------------------------------------------

    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 2201

    private fun builder(context: Context): NotificationCompat.Builder {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private fun notifyProgress(context: Context, pct: Int, read: Long, total: Long) {
        try {
            val b = builder(context)
                .setContentTitle("正在下载更新…")
                .setContentText(
                    if (total > 0) "%.1f / %.1f MB".format(read / 1048576.0, total / 1048576.0)
                    else "%.1f MB".format(read / 1048576.0),
                )
            if (pct in 0..100) {
                b.setProgress(100, pct, false)
            } else {
                b.setProgress(0, 0, true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, b.build())
        } catch (e: Exception) {
            Timber.w(e, "更新进度通知失败（忽略）")
        }
    }

    private fun cancelProgressNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}
