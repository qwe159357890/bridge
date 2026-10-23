package my.bridge.com.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bridgeDataStore by preferencesDataStore(name = "bridge_prefs")

/**
 * 本地键值存储封装（DataStore）。
 *
 * 简单配置走这里；结构化数据走 Room（后续按需扩展）。
 * 业务模块通过 Hilt 注入 [SettingsStore] 使用，不直接触碰 DataStore API。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val FIRST_LAUNCH = stringPreferencesKey("first_launch")
        val KEEP_ALIVE_ENABLED = stringPreferencesKey("keep_alive_enabled")
    }

    /** 是否首次启动 */
    val isFirstLaunch: Flow<Boolean> = context.bridgeDataStore.data.map {
        it[Keys.FIRST_LAUNCH] ?: "true"
    }.map { it.toBooleanStrictOrNull() ?: true }

    suspend fun markLaunched() {
        context.bridgeDataStore.edit { it[Keys.FIRST_LAUNCH] = "false" }
    }
}
