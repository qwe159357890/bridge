package my.bridge.com.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import my.bridge.com.core.storage.DeviceStore
import my.bridge.com.core.storage.SettingsStore
import javax.inject.Singleton

/**
 * 存储层依赖装配。
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    fun provideDeviceStore(@ApplicationContext context: Context): DeviceStore =
        DeviceStore(context)
}
