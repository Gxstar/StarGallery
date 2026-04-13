package com.gxstar.stargallery.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gxstar.stargallery.data.local.dao.MediaMetadataDao
import com.gxstar.stargallery.data.local.entity.MediaMetadata

/**
 * Room 数据库
 */
@Database(
    entities = [MediaMetadata::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaMetadataDao(): MediaMetadataDao
}
