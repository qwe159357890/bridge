package my.bridge.com.monitor

import android.content.Context
import my.bridge.com.core.common.DiagLog
import java.io.File
import java.security.MessageDigest

private const val NTAG = "监控"

/**
 * 事件快照缩略图磁盘缓存（v0.2.161）。
 *
 * 背景：事件缩略图此前只用 EventListView 的 remember 内存缓存，离开监控页（切到主页其它
 * Tab）后 composition 销毁、缩略图全丢，下次进事件页又经 VNT 隧道重拉全部图（单张约 345KB，
 * 首屏数张 = 数 MB）。本类在 cacheDir 下做磁盘持久化，让「离开监控页再回来」能命中本地，
 * 省客户端流量 + 减服务端（hub 代理）压力。
 *
 * 设计（对齐 JikongSpeaker 的 TTS 缓存范式）：
 * - 存储位置：`cacheDir/nvr_snapshots/{md5(node|snapshotPath)}.jpg`（系统可回收，不进用户存储）。
 * - 缓存键：node 与 snapshotPath 共同 md5（不同节点同路径可能内容不同，必须区分）。
 * - 淘汰策略（双重，用户拍板）：容量 LRU 50MB + 时间 7 天，写入时懒清理（超限按最后访问时间淘汰）。
 * - 未命中由调用方自动回退网络下载（本类只负责磁盘读/写，不触网）。
 *
 * 阻塞式文件 IO，调用方须在 Dispatchers.IO 上下文执行。
 */
object NvrSnapshotCache {

    private const val CACHE_DIR = "nvr_snapshots"

    /** 容量上限（用户拍板 50MB）。 */
    private const val CACHE_LIMIT_BYTES = 50L * 1024 * 1024

    /** 时间上限（用户拍板 7 天，毫秒）。 */
    private const val CACHE_MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private fun cacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, CACHE_DIR).apply { mkdirs() }

    /** 缓存键：node|snapshotPath 的 md5（十六进制小写）。 */
    private fun keyOf(node: String, snapshotPath: String): String {
        val raw = "$node|$snapshotPath"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 读缓存：命中返回字节数组，未命中/过期/读失败返回 null（调用方回退网络）。 */
    fun read(context: Context, node: String, snapshotPath: String): ByteArray? {
        val f = File(cacheDir(context), "${keyOf(node, snapshotPath)}.jpg")
        if (!f.exists() || f.length() == 0L) return null
        // 时间淘汰：超过 7 天未访问直接删（不返回）
        if (System.currentTimeMillis() - f.lastModified() > CACHE_MAX_AGE_MS) {
            runCatching { f.delete() }
            return null
        }
        return try {
            val bytes = f.readBytes()
            // 更新最后访问时间，供 LRU 淘汰排序用（读命中算「访问」）
            f.setLastModified(System.currentTimeMillis())
            bytes
        } catch (e: Exception) {
            DiagLog.w(NTAG, "快照缓存读取失败 ${f.name}：${e.message}")
            runCatching { f.delete() }
            null
        }
    }

    /** 写缓存：落盘后触发懒清理（容量/时间双超限淘汰）。 */
    fun write(context: Context, node: String, snapshotPath: String, bytes: ByteArray) {
        val dir = cacheDir(context)
        val f = File(dir, "${keyOf(node, snapshotPath)}.jpg")
        try {
            f.writeBytes(bytes)
            f.setLastModified(System.currentTimeMillis())
        } catch (e: Exception) {
            DiagLog.w(NTAG, "快照缓存写入失败 ${f.name}：${e.message}")
            return
        }
        evict(dir)
    }

    /** 懒清理：容量超 50MB 或单项超 7 天未访问时，按最后访问时间从旧到新淘汰。 */
    private fun evict(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return
        // 先删时间过期的
        files.removeAll { f ->
            val expired = now - f.lastModified() > CACHE_MAX_AGE_MS
            if (expired) runCatching { f.delete() }
            expired
        }
        // 再按容量淘汰：总大小超限，从最旧（最后访问时间最早）开始删
        var total = files.sumOf { it.length() }
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= f.length()
            runCatching { f.delete() }
        }
        DiagLog.i(NTAG, "快照缓存清理完成：剩余 ${dir.listFiles()?.size ?: 0} 个文件")
    }
}
