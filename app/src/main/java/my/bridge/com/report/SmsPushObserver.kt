package my.bridge.com.report

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import my.bridge.com.core.network.api.BridgeApi
import my.bridge.com.core.network.api.BridgeApiClient
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.data.SmsReader
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 短信推送水位独立 DataStore 文件（避免与 ReportPrefs 混用 key 冲突）。 */
private val Context.smsPushDataStore by preferencesDataStore(name = "bridge_sms_push")

/**
 * 新短信即推观察者（ContentObserver 方案，**不要求 Bridge 是默认短信应用**）。
 *
 * 原理：Android 4.4+ 只有默认短信应用能收到 SMS_DELIVER 广播，但短信库
 * （content://sms）任何持有 READ_SMS 的应用都可观察——系统默认短信应用把
 * 新短信写入数据库的瞬间 [onChange] 即回调，效果等同「收到即推」。
 * 若 Bridge 恰好是默认短信应用，`SmsDeliverReceiver` 的既有链路（写库+通知）
 * 写库后同样触发本观察者；服务端按 device+sms_key 去重，两条链路并存无副作用。
 *
 * 去重：本地记 lastPushedDateMs 水位（DataStore 持久化），只推比它新的短信；
 * 服务端另有 sms_key 去重兜底，重复上报无害。上报失败只记日志
 * （服务端 get_sms 全量拉取可兜底补齐）。
 */
@Singleton
class SmsPushObserver @Inject constructor(
    private val prefs: ReportPrefs,
    private val deviceStore: DeviceStore,
    private val apiClient: BridgeApiClient,
) {

    companion object {
        /** 每次触发最多推送条数（正常一条短信一次回调，长短信分段会一次多条）。 */
        private const val MAX_PUSH_PER_EVENT = 20

        /** 变更聚合延迟（毫秒）：等长短信分段全部入库再查，避免碎片推送。 */
        private const val AGGREGATE_DELAY_MS = 800L

        private val KEY_LAST_PUSH_DATE = longPreferencesKey("sms_last_pushed_date")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var observer: ContentObserver? = null
    private var registered = false

    /** 注册观察者（幂等）。无 READ_SMS 权限时静默不注册（日志说明，不报错）。 */
    fun register(context: Context) {
        val app = context.applicationContext
        if (registered) return
        if (!SmsReader.hasPermission(app)) {
            Timber.i("SmsPushObserver：无 READ_SMS 权限，新短信即推不可用（其余功能不受影响）")
            return
        }
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scope.launch {
                    delay(AGGREGATE_DELAY_MS)
                    pushNew(app)
                }
            }
        }
        observer = obs
        try {
            app.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs)
            appContext = app
            registered = true
            Timber.i("SmsPushObserver：已注册（新短信即推开）")
        } catch (e: Exception) {
            Timber.w(e, "SmsPushObserver 注册失败（短信库不可观察）")
            observer = null
        }
    }

    /** 注销观察者（关开关时调用；幂等）。 */
    fun unregister() {
        val app = appContext ?: return
        observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        observer = null
        registered = false
        Timber.i("SmsPushObserver：已注销（新短信即推关）")
    }

    /** 查询并推送水位之上的新短信（fire-and-forget）。 */
    private fun pushNew(app: Context) {
        scope.launch {
            try {
                val last = lastPushedDate(app)
                val entries = SmsReader.read(app, "inbox", 50)
                    .filter { it.dateMs > last }
                    .take(MAX_PUSH_PER_EVENT)
                if (entries.isEmpty()) return@launch
                val deviceId = deviceStore.deviceId()
                val token = runCatching { deviceStore.token() }.getOrNull()
                apiClient.reportSmsBatch(
                    deviceId, token,
                    entries.map {
                        BridgeApi.SmsItem(
                            sms_key = it.key, address = it.address, body = it.body,
                            type = it.type, date_ms = it.dateMs, read = it.read,
                            sim_slot = it.simSlot, sim_number = it.simNumber,
                        )
                    },
                )
                saveLastPushedDate(app, entries.maxOf { it.dateMs })
                Timber.i("新短信即推：已上报 %d 条（device=%s）", entries.size, deviceId)
            } catch (e: Exception) {
                Timber.w(e, "新短信即推失败（忽略，服务端 get_sms 可全量兜底）")
            }
        }
    }

    private suspend fun lastPushedDate(app: Context): Long =
        app.smsPushDataStore.data.map { it[KEY_LAST_PUSH_DATE] ?: 0L }.first()

    private suspend fun saveLastPushedDate(app: Context, dateMs: Long) {
        app.smsPushDataStore.edit { it[KEY_LAST_PUSH_DATE] = dateMs }
    }
}
