package com.gxstar.stargallery.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库
 *
 * 数据库升级策略：
 * - 每次升级 version 时，必须编写对应的 Migration 对象并加入到 addMigrations() 中
 * - fallbackToDestructiveMigration() 仅作为兜底，正常情况下不应触发
 * - schema JSON 文件自动生成到 app/schemas/ 目录，提交到版本控制
 */
@Database(
    entities = [PhotoEntity::class],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        const val DATABASE_NAME = "stargallery_db"

        /**
         * V1 → V2：新增 EXIF 扩展字段
         * 防御式写法：若字段已存在（如早期开发版本），忽略异常
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val exifColumns = listOf(
                    "cameraMake TEXT",
                    "cameraModel TEXT",
                    "lensModel TEXT",
                    "isoEquivalent INTEGER",
                    "focalLength REAL",
                    "focalLength35mmEquiv INTEGER",
                    "fNumber REAL",
                    "shutterSpeed REAL",
                    "exifImageWidth INTEGER",
                    "exifImageHeight INTEGER",
                    "lut1 TEXT",
                    "lut2 TEXT"
                )
                exifColumns.forEach { col ->
                    try { db.execSQL("ALTER TABLE photos ADD COLUMN $col") } catch (_: Exception) {}
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN displayName TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE photos ADD COLUMN flash INTEGER") } catch (_: Exception) {}
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE photos ADD COLUMN photoStyle TEXT") } catch (_: Exception) {}
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE photos ADD COLUMN exposureCompensation REAL") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE photos ADD COLUMN meteringMode TEXT") } catch (_: Exception) {}
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_dateTaken ON photos(dateTaken)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_dateAdded ON photos(dateAdded)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_isHidden ON photos(isHidden)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_isFavorite ON photos(isFavorite)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_cameraMake ON photos(cameraMake)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_cameraModel ON photos(cameraModel)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_lensModel ON photos(lensModel)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_bucketId ON photos(bucketId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_hidden_dateTaken ON photos(isHidden, dateTaken)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_hidden_favorite ON photos(isHidden, isFavorite)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}