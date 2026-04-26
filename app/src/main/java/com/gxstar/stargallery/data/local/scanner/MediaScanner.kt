package com.gxstar.stargallery.data.local.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
import com.gxstar.stargallery.data.local.exif.ExifExtractor
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体数据库扫描器
 * 负责将 MediaStore 中的媒体信息同步到 Room 数据库
 * 支持全量扫描和增量扫描
 * EXIF 信息在扫描完成后后台提取
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
    private val scanPreferences: ScanPreferences,
    private val exifExtractor: ExifExtractor
) {
    companion object {
        private const val TAG = "MediaScanner"
        private const val EXIF_BATCH_SIZE = 20
    }

    private val mutex = Mutex()

    // 扫描状态流
    private val _scanState = MutableSharedFlow<ScanState>(replay = 1)
    val scanState: SharedFlow<ScanState> = _scanState.asSharedFlow()

    sealed class ScanState {
        object Idle : ScanState()
        data class Scanning(val current: Int, val total: Int, val progress: Float) : ScanState()
        data class Completed(val totalScanned: Int, val durationMs: Long) : ScanState()
        data class Error(val message: String) : ScanState()
    }

    // 是否正在扫描
    @Volatile
    private var isScanning = false

    /**
     * 执行全量扫描
     * 扫描所有图片和视频，写入 Room 数据库
     */
    suspend fun performFullScan() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isScanning) {
                Log.w(TAG, "Scan already in progress")
                return@withContext
            }
            isScanning = true
        }

        val startTime = System.currentTimeMillis()

        try {
            _scanState.emit(ScanState.Scanning(0, 0, 0f))

            // 1. 查询所有媒体
            val allMedia = queryAllMediaFromMediaStore()
            val total = allMedia.size
            Log.i(TAG, "Found $total media files to scan")

            if (total == 0) {
                _scanState.emit(ScanState.Completed(0, 0))
                return@withContext
            }

            // 2. 批量写入 Room
            var processedCount = 0
            val batchSize = 50
            val batches = allMedia.chunked(batchSize)

            for (batch in batches) {
                val entities = batch.map { item ->
                    PhotoEntity(
                        id = item.id,
                        uri = item.uri,
                        dateTaken = item.dateTaken,
                        dateModified = item.dateModified,
                        dateAdded = item.dateAdded,
                        mimeType = item.mimeType,
                        width = item.width,
                        height = item.height,
                        size = item.size,
                        bucketId = item.bucketId,
                        bucketName = item.bucketName,
                        latitude = null,
                        longitude = null,
                        orientation = item.orientation,
                        isFavorite = item.isFavorite
                    )
                }
                photoDao.insertAll(entities)

                processedCount += batch.size
                val progress = processedCount.toFloat() / total
                _scanState.emit(ScanState.Scanning(processedCount, total, progress))
            }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Full scan completed: $total media in ${duration}ms")

            _scanState.emit(ScanState.Completed(total, duration))
            scanPreferences.lastScanTime = System.currentTimeMillis()

            // 3.5 全量扫描完成后，后台提取 EXIF 信息
            extractExifForAllPhotos()

            // 3.6 删除已不存在于 MediaStore 的记录
            val validIds = allMedia.map { it.id }
            photoDao.deleteRemovedPhotos(validIds)
        } catch (e: Exception) {
            Log.e(TAG, "Full scan failed", e)
            _scanState.emit(ScanState.Error(e.message ?: "Unknown error"))
        } finally {
            isScanning = false
        }
    }

    /**
     * 后台批量提取 EXIF 信息
     * 在全量扫描完成后异步执行
     */
    private fun extractExifForAllPhotos() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allPhotoIds = photoDao.getAllPhotoIds()
                Log.i(TAG, "Starting EXIF extraction for ${allPhotoIds.size} photos")

                allPhotoIds.chunked(EXIF_BATCH_SIZE).forEachIndexed { batchIndex, ids ->
                    Log.d(TAG, "Processing EXIF batch $batchIndex, photos: ${ids.size}")
                    ids.forEach { id ->
                        val photo = photoDao.getPhotoById(id)
                        if (photo == null) {
                            Log.w(TAG, "Photo $id not found in database")
                            return@forEach
                        }
                        if (photo.cameraMake != null || photo.cameraModel != null) {
                            // 已有 EXIF 信息，跳过
                            Log.d(TAG, "Photo $id already has EXIF, skipping")
                            return@forEach
                        }

                        val uri = try {
                            android.net.Uri.parse(photo.uri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Invalid URI for photo $id: ${photo.uri}")
                            return@forEach
                        }

                        val exifData = exifExtractor.extractExif(uri)
                        if (exifData != null) {
                            val updatedEntity = ExifExtractor.applyToEntity(photo, exifData)
                            photoDao.update(updatedEntity)
                            Log.d(TAG, "Photo $id EXIF extracted: make=${exifData.cameraMake}, model=${exifData.cameraModel}")
                        } else {
                            Log.d(TAG, "Photo $id no EXIF data extracted")
                        }
                    }
                    Log.d(TAG, "EXIF extraction batch $batchIndex/${(allPhotoIds.size / EXIF_BATCH_SIZE) + 1} completed")
                }
                Log.i(TAG, "EXIF extraction completed")
            } catch (e: Exception) {
                Log.e(TAG, "EXIF extraction failed", e)
            }
        }
    }

    /**
     * 执行增量扫描
     * 只扫描新增或修改的媒体
     * @return 是否有数据变化
     */
    suspend fun performIncrementalScan(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isScanning) {
                Log.w(TAG, "Scan already in progress")
                return@withContext false
            }
            isScanning = true
        }

        val startTime = System.currentTimeMillis()

        try {
            val lastScanTime = scanPreferences.lastScanTime
            val changedMedia = queryMediaModifiedAfter(lastScanTime)

            if (changedMedia.isEmpty()) {
                Log.d(TAG, "No new or updated media found")
                return@withContext false
            }

            // 增量更新到 Room
            val entities = changedMedia.map { item ->
                PhotoEntity(
                    id = item.id,
                    uri = item.uri,
                    dateTaken = item.dateTaken,
                    dateModified = item.dateModified,
                    dateAdded = item.dateAdded,
                    mimeType = item.mimeType,
                    width = item.width,
                    height = item.height,
                    size = item.size,
                    bucketId = item.bucketId,
                    bucketName = item.bucketName,
                    latitude = null,
                    longitude = null,
                    orientation = item.orientation,
                    isFavorite = item.isFavorite
                )
            }
            photoDao.insertAll(entities)

            // 立即为新增的照片提取 EXIF 信息
            extractExifForPhotos(changedMedia.map { it.id })

            // 更新最后扫描时间
            scanPreferences.lastScanTime = System.currentTimeMillis()

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Incremental scan completed: ${changedMedia.size} media in ${duration}ms")

            _scanState.emit(ScanState.Completed(changedMedia.size, duration))
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Incremental scan failed", e)
            _scanState.emit(ScanState.Error(e.message ?: "Unknown error"))
            return@withContext false
        } finally {
            isScanning = false
        }
    }

    /**
     * 删除单条媒体记录
     */
    suspend fun deletePhoto(photoId: Long) = withContext(Dispatchers.IO) {
        photoDao.deleteById(photoId)
    }

    /**
     * 批量更新收藏状态
     */
    suspend fun updateFavorite(photoIds: List<Long>, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        photoDao.updateFavoriteBatch(photoIds, isFavorite)
    }

    /**
     * 为指定照片批量提取 EXIF 信息
     */
    private suspend fun extractExifForPhotos(photoIds: List<Long>) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Extracting EXIF for ${photoIds.size} photos")
        photoIds.forEach { id ->
            val photo = photoDao.getPhotoById(id) ?: return@forEach
            val uri = android.net.Uri.parse(photo.uri)
            val exifData = exifExtractor.extractExif(uri)
            if (exifData != null) {
                val updatedEntity = ExifExtractor.applyToEntity(photo, exifData)
                photoDao.update(updatedEntity)
                Log.d(TAG, "Photo $id EXIF extracted: make=${exifData.cameraMake}")
            }
        }
    }

    // ==================== MediaStore 查询 ====================

    private fun queryAllMediaFromMediaStore(): List<MediaStoreItem> {
        val items = mutableListOf<MediaStoreItem>()
        val uri = MediaStore.Files.getContentUri("external")

        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
        }

        context.contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: continue

                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                val photoUri: Uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
                val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))

                // Fallback 逻辑
                val finalDateTaken = when {
                    dateTaken > 0 -> dateTaken
                    dateModified > 0 -> dateModified * 1000L
                    dateAdded > 0 -> dateAdded * 1000L
                    else -> System.currentTimeMillis()
                }

                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri.toString(),
                        mimeType = mimeType,
                        dateTaken = finalDateTaken,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                        bucketId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
                        orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)),
                        isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
                        isVideo = isVideo
                    )
                )
            }
        }

        return items
    }

    private fun queryMediaModifiedAfter(modifiedAfter: Long): List<MediaStoreItem> {
        val items = mutableListOf<MediaStoreItem>()
        val uri = MediaStore.Files.getContentUri("external")

        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) " +
                "AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf(modifiedAfter.toString())

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: continue

                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                val photoUri: Uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
                val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))

                val finalDateTaken = when {
                    dateTaken > 0 -> dateTaken
                    dateModified > 0 -> dateModified * 1000L
                    dateAdded > 0 -> dateAdded * 1000L
                    else -> System.currentTimeMillis()
                }

                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri.toString(),
                        mimeType = mimeType,
                        dateTaken = finalDateTaken,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                        bucketId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
                        orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)),
                        isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
                        isVideo = isVideo
                    )
                )
            }
        }

        return items
    }

    /**
     * MediaStore 查询结果的内部数据类
     */
    private data class MediaStoreItem(
        val id: Long,
        val uri: String,
        val mimeType: String,
        val dateTaken: Long,
        val dateModified: Long,
        val dateAdded: Long,
        val width: Int,
        val height: Int,
        val size: Long,
        val bucketId: Long,
        val bucketName: String,
        val orientation: Int,
        val isFavorite: Boolean,
        val isVideo: Boolean
    )
}