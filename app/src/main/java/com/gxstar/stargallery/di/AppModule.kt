package com.gxstar.stargallery.di

import android.content.Context
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
import com.gxstar.stargallery.data.repository.MediaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMediaRepository(@ApplicationContext context: Context): MediaRepository {
        return MediaRepository(context)
    }

    @Provides
    @Singleton
    fun provideMetadataScanner(
        @ApplicationContext context: Context,
        scanPreferences: ScanPreferences
    ): MetadataScanner {
        return MetadataScanner(context, scanPreferences)
    }

    @Provides
    @Singleton
    fun provideScanPreferences(@ApplicationContext context: Context): ScanPreferences {
        return ScanPreferences(context)
    }
}
