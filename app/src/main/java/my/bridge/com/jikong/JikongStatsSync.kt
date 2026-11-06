package my.bridge.com.jikong

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.core.network.api.BridgeApi
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.storage.DeviceStore
import org.json.JSONObject
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 里程台账同步器（统计页 v0.2.85）。
 *
 * 语义（本地为准 + 远程 MySQL 留档）：
 * - 录入永远本地成功；本模块把 synced=false 的记录与待删除墓碑批量上行；
 * - **静默自动同步**：录入成功后自动尝试一次（[silent]=true，失败只打日志、
 *   保留待同步标记，不打扰用户）；
 * - **手动兜底**：统计页同步按钮触发（[silent]=false），失败把服务端返回的
 *   具体错误透传给回调（页面 Toast），按钮保持可用可重试；
 * - 服务端按 (device_id, record_id) 幂等 upsert，重复上传安全；
 * - 并发防抖：同一时刻只允许一个同步在飞（AtomicBoolean）。
 */
object JikongStatsSync {

    private const val TAG = "JikongStats"

    @Volatile
    private var apiClient: BridgeApiClient? = null

    @Volatile
    private var deviceStore: DeviceStore? = null

    private val inFlight = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 应用启动时注入（BridgeApplication.onCreate，与 JikongController.attach 同处）。 */
    fun attach(apiClient: BridgeApiClient, deviceStore: DeviceStore) {
        this.apiClient = apiClient
        this.deviceStore = deviceStore
    }

    /** 是否有东西可同步（按钮可用性）。 */
    fun hasPending(context: Context): Boolean = JikongStatsStore.unsyncedCount(context) > 0

    /**
     * 执行一次同步（待同步记录 + 待删除墓碑）。
     *
     * @param silent  true=自动触发（失败静默）；false=手动（失败透传错误信息）
     * @param onDone  完成回调（主线程）：ok=是否全部同步成功；msg=给用户看的话术
     */
    fun sync(context: Context, silent: Boolean, onDone: (ok: Boolean, msg: String) -> Unit) {
        val api = apiClient
        val ds = deviceStore
        if (api == null || ds == null) {
            onDone(false, "同步组件未就绪，请重启应用")
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            if (!silent) onDone(false, "同步进行中，请稍候")
            return
        }
        scope.launch {
            var ok = false
            var msg = ""
            try {
                val pendingRecords = JikongStatsStore.pendingSync(context)
                val pendingDeletes = JikongStatsStore.pendingDeletes(context)
                if (pendingRecords.isEmpty() && pendingDeletes.isEmpty()) {
                    ok = true
                    msg = "没有待同步的记录"
                } else {
                    val payload = pendingRecords.map {
                        BridgeApi.OdometerRecord(
                            record_id = it.id,
                            ts = it.ts,
                            odo_km = it.odoKm.toDouble(),
                            cycle_ah = it.cycleAh.toDouble(),
                            volt = if (it.volt > 0f) it.volt.toDouble() else null,
                        )
                    } + pendingDeletes.map {
                        BridgeApi.OdometerRecord(
                            record_id = it,
                            ts = 0L, odo_km = 0.0, cycle_ah = 0.0, volt = null,
                            deleted = true,
                        )
                    }
                    val resp = api.syncOdometer(
                        ds.deviceId(),
                        runCatching { ds.token() }.getOrNull(),
                        payload,
                    )
                    JikongStatsStore.markSynced(context, pendingRecords.map { it.id })
                    JikongStatsStore.markDeletesSynced(context, pendingDeletes)
                    ok = true
                    msg = "已同步 ${resp.received} 条到服务器"
                    Timber.tag(TAG).i("里程台账同步成功：received=%s inserted=%s updated=%s", resp.received, resp.inserted, resp.updated)
                }
            } catch (e: Exception) {
                msg = errText(e)
                Timber.tag(TAG).w(e, "里程台账同步失败（silent=%s）：%s", silent, msg)
            } finally {
                inFlight.set(false)
            }
            withContext(Dispatchers.Main) { onDone(ok, msg) }
            // 静默失败不回调错误话术给用户（保留标记，等下次录入或手动同步）
        }
    }

    /** 错误话术：优先透传服务端 detail（如「里程台账存储暂不可用」），网络层给直白原因。 */
    private fun errText(e: Exception): String = when (e) {
        is HttpException -> runCatching {
            val body = e.response()?.errorBody()?.string()
            if (body != null) JSONObject(body).optString("detail") else ""
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "服务端错误（HTTP ${e.code()}）"
        is IOException -> "网络不可达，请检查与服务器的连接"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "同步失败，请稍后重试"
    }
}
