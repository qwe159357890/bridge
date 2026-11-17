package my.bridge.com.immich

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

import my.bridge.com.core.common.DiagLog

/** 相册自动备份日志 tag。 */
private const val NTAG = "相册备份"

/** 上传前台服务通知渠道 ID。 */
private const val CHANNEL_ID = "immich_backup"

/** 单文件转前台服务的阈值：256MB（调研报告 §5.4，与 Immich 原版口径一致）。 */
private const val FOREGROUND_THRESHOLD_BYTES = 256L * 1024 * 1024

/** 单次备份 Worker 最多处理的文件数（防超长运行；余量下次内容触发继续）。 */
private const val MAX_UPLOADS_PER_RUN = 200

/**
 * 相册自动备份链路（D5 拍板方案，2026-11-16）。
 *
 * 能耗纪律（调研报告 §5.4，核心：空闲态 ≈ 0 消耗）：
 * - 触发：WorkManager addContentUriTrigger 监听 MediaStore 图片+视频 4 个 URI（照抄 Immich 原版），
 *   无新图时任务挂在系统进程、零唤醒零 CPU；不设周期扫描、不连长连接；
 * - 去抖合并：连拍 N 张只唤醒一次（UpdateDelay 30s / MaxDelay 300s）；
 * - 增量扫描：date_added > 水位（纯 DB 读），SHA-1 只算新候选；
 * - 仅 Wi-Fi：UNMETERED 约束由系统把关，不满足时挂起零消耗；
 * - 大文件 >256MB：setForeground 短暂存在，传完即销毁；
 * - 默认关闭：用户在设置页明确开启才注册 Observer。
 *
 * 链路：MediaStore 变更 → ImmichMediaObserver（自我续期）→ ImmichUploadWorker
 * （增量扫描 → SHA-1 → bulk-upload-check 去重 → multipart 上传 → DataStore 记账）。
 */
object ImmichBackup {

    /** Observer Worker 唯一名。 */
    private const val OBSERVER_NAME = "immich_media_observer"

    /** 上传 Worker 唯一名。 */
    private const val UPLOAD_NAME = "immich_upload_worker"

    /**
     * 注册内容触发 Observer（自动备份开启时调用；幂等，重复注册不打断现有链）。
     * 内容触发仅 OneTime 有效（JobScheduler 语义），Observer 跑完自我续期保持监听
     * （照 Immich MediaObserver 范式）。
     *
     * :param context: 任意 Context
     */
    fun ensureRegistered(context: Context) {
        val appContext = context.applicationContext
        if (!ImmichPrefs.autoBackup(appContext)) {
            DiagLog.i(NTAG, "自动备份未开启，跳过 Observer 注册")
            return
        }
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(300, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<ImmichMediaObserver>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            OBSERVER_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        DiagLog.i(NTAG, "内容触发 Observer 已注册（图片+视频 2 URI，去抖 30s/300s）")
    }

    /**
     * 注销 Observer（自动备份关闭时调用）。
     *
     * :param context: 任意 Context
     */
    fun unregister(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(OBSERVER_NAME)
        DiagLog.i(NTAG, "内容触发 Observer 已注销")
    }

    /**
     * 手动触发一次全量对账上传（进相册页兜底时机，能耗纪律允许的两个时机之一）。
     *
     * :param context: 任意 Context
     */
    fun triggerSyncNow(context: Context) {
        val appContext = context.applicationContext
        if (!ImmichPrefs.autoBackup(appContext)) return
        val request = OneTimeWorkRequestBuilder<ImmichUploadWorker>()
            .setConstraints(networkConstraints(appContext))
            .build()
        // REPLACE：被系统取消/受网络约束等待中的旧 Worker 不再阻塞新触发
        //（KEEP 曾导致「取消后唯一名被占，后续触发全部无效」）
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UPLOAD_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        DiagLog.i(NTAG, "手动触发备份对账")
    }

    /**
     * 上传 Worker 网络约束（仅 Wi-Fi 开关 → UNMETERED / 全网）。
     *
     * :param context: 任意 Context
     * :return: 网络约束
     */
    internal fun networkConstraints(context: Context): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (ImmichPrefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .build()

    /**
     * 查询本机媒体相册（bucket_display_name 去重，设置页勾选列表用）。
     *
     * :param context: 任意 Context
     * :return: 相册名列表（按名称排序）
     */
    fun fetchLocalBuckets(context: Context): List<String> {
        if (!hasMediaPermission(context)) return emptyList()
        val names = mutableSetOf<String>()
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) names.add(name)
                    }
                }
            }
        }
        return names.sorted()
    }

    /**
     * 媒体读取权限检查（API 33+ 分媒体类型；旧版 READ_EXTERNAL_STORAGE；
     * 已授「所有文件访问」时同样放行）。
     *
     * :param context: 任意 Context
     * :return: true = 可读 MediaStore
     */
    fun hasMediaPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 创建前台服务通知渠道（上传大文件时短暂展示进度）。
     *
     * :param context: 任意 Context
     */
    internal fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "相册自动备份", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }
}

/**
 * MediaStore 内容触发 Observer Worker（照抄 Immich MediaObserver 范式）。
 *
 * 职责：内容变更事件到达后入队上传 Worker（去抖由约束层的 UpdateDelay 完成），
 * 并自我重新入队保持监听（跑完即重排，长期存活于系统侧）。
 *
 * :param appContext: 应用上下文
 * :param params: Worker 参数
 */
class ImmichMediaObserver(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * 事件处理：入队上传 Worker（去抖 30s 由约束层完成）并自我续期。
     *
     * :return: 固定成功（监听链不断）
     */
    override suspend fun doWork(): Result {
        DiagLog.i(NTAG, "MediaStore 内容变更 → 入队备份 Worker")
        val wm = WorkManager.getInstance(applicationContext)
        wm.enqueueUniqueWork(
            "immich_upload_worker",
            // REPLACE：与手动触发同策略，避免取消/等待态旧 Worker 阻塞新内容触发
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ImmichUploadWorker>()
                .setConstraints(ImmichBackup.networkConstraints(applicationContext))
                .build(),
        )
        // 自我续期：重排自己（KEEP 不打断现有周期任务），保持内容监听链
        ImmichBackup.ensureRegistered(applicationContext)
        return Result.success()
    }
}

/**
 * 备份上传 Worker：增量扫描 → SHA-1 → 服务端去重 → multipart 上传 → 记账。
 *
 * :param appContext: 应用上下文
 * :param params: Worker 参数
 */
class ImmichUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** 单条媒体记录（扫描结果）。 */
    private data class MediaRow(
        val id: Long,
        val path: String,
        val size: Long,
        val dateAdded: Long,
        /** 真实拍摄时间（epoch 秒；DATE_TAKEN，无则 0 → 回退 dateAdded） */
        val dateTaken: Long,
        /** 文件修改时间（epoch 秒；DATE_MODIFIED，无则 0 → 回退 dateAdded） */
        val dateModified: Long,
        val bucket: String,
        val isVideo: Boolean,
        val durationMs: Long,
    )

    /**
     * 备份主流程。
     *
     * :return: 成功 / 重试（网络类失败）
     */
    override suspend fun doWork(): Result {
        val context = applicationContext
        DiagLog.i(NTAG, "备份 Worker 启动（attempt=$runAttemptCount）")
        // 总闸：开关关闭直接退出（能耗纪律 #7：出厂关闭，开启才生效）
        if (!ImmichPrefs.autoBackup(context)) {
            DiagLog.i(NTAG, "自动备份已关闭，Worker 退出")
            return Result.success()
        }
        // 未配置服务端账密：上传链路不可用
        if (!ImmichPrefs.isConfigured(context)) {
            DiagLog.i(NTAG, "服务端未配置账号，Worker 退出")
            return Result.success()
        }
        if (!ImmichBackup.hasMediaPermission(context)) {
            DiagLog.w(NTAG, "缺少媒体读取权限（READ_MEDIA_IMAGES/VIDEO），Worker 退出")
            return Result.success()
        }
        try {
            val candidates = scanCandidates(context)
            if (candidates.isEmpty()) {
                DiagLog.i(NTAG, "无新增媒体，本次对账结束")
                return Result.success()
            }
            // 大文件前台化（>256MB 时短暂前台，进度通知）
            if (candidates.any { it.size > FOREGROUND_THRESHOLD_BYTES }) {
                setForeground(createForegroundInfo("正在上传大文件…"))
            }
            val api = ImmichApiClient(context)
            // 第一步：批量预检（2026-11-17 重构）。旧实现逐文件单查且误判响应字段
            //（status vs action），导致官方 APP 已备份的资产全量重传一百多个。
            // 现在一次性算 SHA-1，分批（500/批）bulk-upload-check，已有的全部前置跳过。
            val hashedList = mutableListOf<Pair<MediaRow, String>>()
            for (row in candidates) {
                val file = File(row.path)
                if (!file.exists() || file.length() == 0L) continue
                runCatching { hashedList.add(row to api.sha1Base64(file)) }
                    .onFailure { DiagLog.w(NTAG, "计算校验失败 ${file.name}：${it.message}") }
            }
            DiagLog.i(NTAG, "批量预检：可校验候选 ${hashedList.size} 个")
            val existingChecksums = mutableSetOf<String>()
            hashedList.map { it.second }.chunked(500).forEach { batch ->
                existingChecksums.addAll(api.bulkUploadCheck(batch))
            }
            DiagLog.i(NTAG, "批量预检完成：服务端已有 ${existingChecksums.size} 个 / 待上传 ${hashedList.count { it.second !in existingChecksums }} 个")
            var uploaded = 0
            var skipped = 0
            var failed = 0
            var maxDateAdded = ImmichPrefs.lastSyncDateAdded(context)
            for ((row, checksum) in hashedList) {
                if (uploaded >= MAX_UPLOADS_PER_RUN) {
                    DiagLog.i(NTAG, "单次运行已达上限 $MAX_UPLOADS_PER_RUN，剩余下次继续")
                    break
                }
                // 服务端已有（官方 APP 或此前传过）→ 记账 + 推水位，绝不上传
                if (checksum in existingChecksums) {
                    markSynced(row)
                    maxDateAdded = maxOf(maxDateAdded, row.dateAdded)
                    skipped++
                    continue
                }
                val file = File(row.path)
                try {
                    updateForeground("正在上传 ${file.name}（已传 $uploaded）")
                    api.uploadAsset(
                        file = file,
                        isVideo = row.isVideo,
                        // 拍摄时间优先 DATE_TAKEN（DATE_ADDED 只是入库时间，会导致时间线日期错乱）
                        fileCreatedAt = (if (row.dateTaken > 0) row.dateTaken else row.dateAdded) * 1000,
                        fileModifiedAt = (if (row.dateModified > 0) row.dateModified else row.dateAdded) * 1000,
                        deviceAssetId = row.id.toString(),
                        duration = formatDuration(row.durationMs),
                        checksum = checksum,
                    )
                    markSynced(row)
                    maxDateAdded = maxOf(maxDateAdded, row.dateAdded)
                    uploaded++
                } catch (e: Exception) {
                    failed++
                    DiagLog.e(NTAG, "上传失败 ${file.name}：${e.message}", e)
                    // 连续失败过多视为服务不可达，整批改日重试
                    if (failed >= 5) {
                        DiagLog.w(NTAG, "连续失败 $failed 次，本轮中止（等待下次触发重试）")
                        break
                    }
                }
            }
            ImmichPrefs.saveLastSyncDateAdded(context, maxDateAdded)
            DiagLog.i(NTAG, "备份对账完成：上传 $uploaded / 跳过 $skipped / 失败 $failed")
            if (uploaded > 0) {
                // 成功反馈：顶部横幅（3 秒消失）+ 相册页前台时自动刷新时间线
                ImmichUiState.banner = "✓ 已备份 $uploaded 张照片到 Immich"
                ImmichUiState.refreshTick += 1
            }
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 系统停止 Worker（超时/省电策略）——记录后按取消传播，等待下次触发
            DiagLog.w(NTAG, "备份 Worker 被系统停止（${e.message}），等待下次触发")
            throw e
        } catch (e: Exception) {
            DiagLog.e(NTAG, "备份对账异常：${e.message}", e)
            return Result.retry()
        }
    }

    /**
     * 增量扫描 MediaStore 候选（date_added > 水位 + 记账集合去重 + 相册勾选过滤）。
     *
     * :param context: 应用上下文
     * :return: 候选列表（时间降序）
     */
    private fun scanCandidates(context: Context): List<MediaRow> {
        val watermark = ImmichPrefs.lastSyncDateAdded(context)
        val synced = ImmichPrefs.syncedItems(context)
        val selectedBuckets = ImmichPrefs.selectedBuckets(context)
        val out = mutableListOf<MediaRow>()
        val configs = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )
        configs.forEach { (uri, isVideo) ->
            runCatching {
                val projection = mutableListOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                )
                if (isVideo) projection.add(MediaStore.Video.Media.DURATION)
                // 增量水位过滤（纯 DB 读；水位以下但记账缺失的对账由手动触发全量兜底）
                context.contentResolver.query(
                    uri,
                    projection.toTypedArray(),
                    "${MediaStore.MediaColumns.DATE_ADDED} > ?",
                    arrayOf(watermark.toString()),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    // DATE_TAKEN = 真实拍摄时间（DATE_ADDED 只是入库时间）；旧机型可能无此列
                    val takenIdx = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    } else {
                        cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    }
                    val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val durIdx = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val dateAdded = cursor.getLong(dateIdx)
                        // 记账去重（id|size|dateAdded 三元组；删除重拍同 id 不同 size 会重传）
                        if ("$id|${cursor.getLong(sizeIdx)}|$dateAdded" in synced) continue
                        val bucket = cursor.getString(bucketIdx) ?: ""
                        // 勾选相册制：空集合 = 全部相册；非空 = 仅勾选项
                        if (selectedBuckets.isNotEmpty() && bucket !in selectedBuckets) continue
                        val dateTaken = if (takenIdx >= 0) cursor.getLong(takenIdx) else 0L
                        val dateModified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                        out.add(
                            MediaRow(
                                id = id,
                                path = cursor.getString(dataIdx) ?: continue,
                                size = cursor.getLong(sizeIdx),
                                dateAdded = dateAdded,
                                dateTaken = dateTaken,
                                dateModified = dateModified,
                                bucket = bucket,
                                isVideo = isVideo,
                                durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L,
                            ),
                        )
                    }
                }
            }.onFailure {
                DiagLog.w(NTAG, "扫描 ${if (isVideo) "视频" else "图片"} 失败：${it.message}")
            }
        }
        DiagLog.i(NTAG, "增量扫描候选 ${out.size} 个（水位 $watermark，勾选 ${if (selectedBuckets.isEmpty()) "全部" else selectedBuckets.size} 相册）")
        return out.sortedByDescending { it.dateAdded }
    }

    /**
     * 记账一条已同步记录（id|size|dateAdded 追加进集合）。
     *
     * :param row: 媒体记录
     */
    private fun markSynced(row: MediaRow) {
        val item = "${row.id}|${row.size}|${row.dateAdded}"
        ImmichPrefs.addSyncedItem(applicationContext, item)
    }

    /**
     * 视频毫秒时长转 Immich 格式（"0:00:12.00000" = H:mm:ss.sssss；图片空串）。
     *
     * :param durationMs: 毫秒
     * :return: Immich duration 字段格式
     */
    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return ""
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%d:%02d:%02d.00000".format(h, m, s)
    }

    /**
     * 构造前台服务进度通知（API 34+ 须声明 dataSync 前台类型）。
     *
     * :param text: 通知文案
     * :return: ForegroundInfo
     */
    private fun createForegroundInfo(text: String): androidx.work.ForegroundInfo {
        ImmichBackup.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("相册自动备份")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            androidx.work.ForegroundInfo(2001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(2001, notification)
        }
    }

    /**
     * 更新前台通知文案（未前台化时静默跳过）。
     *
     * :param text: 通知文案
     */
    private fun updateForeground(text: String) {
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(2001, createForegroundInfo(text).notification)
        }
    }
}
