package com.gxstar.stargallery.di

import android.content.Context
import androidx.room.Room
import com.gxstar.stargallery.data.local.database.AppDatabase
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.data.repository.MetadataRepository
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "stargallery.db"
        )
            .fallbackToDestructiveMigration() // 开发阶段允许破坏性迁移
            .build()
    }
    
    @Provides
    @Singleton
    fun provideMetadataScanner(
        @ApplicationContext context: Context,
        database: AppDatabase
    ): MetadataScanner {
        return MetadataScanner(context, database.mediaMetadataDao())
    }
    
    @Provides
    @Singleton
    fun provideMetadataRepository(
        @ApplicationContext context: Context,
        database: AppDatabase,
        scanner: MetadataScanner
    ): MetadataRepository {
        return MetadataRepository(context, database.mediaMetadataDao(), scanner)
    }
    
    @Provides
    @Singleton
    fun provideScanPreferences(@ApplicationContext context: Context): ScanPreferences {
        return ScanPreferences(context)
    }
}
