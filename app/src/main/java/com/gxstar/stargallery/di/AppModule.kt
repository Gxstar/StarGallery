package com.gxstar.stargallery.di

import android.content.Context
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // MediaRepository 用 @Inject constructor 注入（见 MediaRepository.kt），
    // 原 @Provides 重复绑定同一类型，已移除。

    @Provides
    @Singleton
    fun provideScanPreferences(@ApplicationContext context: Context): ScanPreferences {
        return ScanPreferences(context)
    }
}
