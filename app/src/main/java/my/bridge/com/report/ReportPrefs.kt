package my.bridge.com.report

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reportDataStore by preferencesDataStore(name = "bridge_report")

/**
 * 主动上报配置持久化（服务端 config_update 推送 + 客户端设置页写入共用）。
 *
 * 默认值约定（与服务端 report_config_store.DEFAULT_CONFIG 对齐）：
 * 位置周期上报**开**、间隔 10 分钟；新短信即推**开**。
 */
@Singleton
class ReportPrefs @Inject constructor(@ApplicationContext context: Context) {

    data class Config(
        val locationEnabled: Boolean = true,
        val locationIntervalMin: Int = 10,
        val smsEnabled: Boolean = true,
    )

    private object Keys {
        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val LOCATION_INTERVAL_MIN = intPreferencesKey("location_interval_min")
        val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
    }

    private val store = context.applicationContext.reportDataStore

    /** 配置流（ReportManager 据此实时重排任务）。 */
    val configFlow: Flow<Config> = store.data.map { p ->
        Config(
            locationEnabled = p[Keys.LOCATION_ENABLED] ?: true,
            locationIntervalMin = (p[Keys.LOCATION_INTERVAL_MIN] ?: 10).coerceIn(1, 120),
            smsEnabled = p[Keys.SMS_ENABLED] ?: true,
        )
    }

    /** 当前配置快照。 */
    suspend fun current(): Config = configFlow.first()

    /** 保存配置（服务端 config_update / 设置页共用；返回收敛后的最终配置）。 */
    suspend fun save(cfg: Config): Config {
        val normalized = cfg.copy(
            locationIntervalMin = cfg.locationIntervalMin.coerceIn(1, 120),
        )
        store.edit { p ->
            p[Keys.LOCATION_ENABLED] = normalized.locationEnabled
            p[Keys.LOCATION_INTERVAL_MIN] = normalized.locationIntervalMin
            p[Keys.SMS_ENABLED] = normalized.smsEnabled
        }
        return normalized
    }
}

/** Compose 设置页经 EntryPoint 取 ReportPrefs（不引入 ViewModel 的轻量方案）。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ReportPrefsEntryPoint {
    fun reportPrefs(): ReportPrefs
}
