package com.gxstar.stargallery.data.local.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.gxstar.stargallery.data.local.dao.MediaIdAndModifiedTime
import com.gxstar.stargallery.data.local.dao.MediaMetadataDao
import com.gxstar.stargallery.data.local.entity.MediaMetadata
import com.gxstar.stargallery.data.local.util.ExifExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体元数据扫描器
 * 负责扫描设备上的所有图片和视频，提取 EXIF 信息并存入数据库
 */
@Singleton
class MetadataScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MediaMetadataDao
) {
    companion object {
        private const val TAG = "MetadataScanner"
        private const val BATCH_SIZE = 100 // 每批处理数量
    }
    
    // 扫描状态流
    private val _scanState = MutableSharedFlow<ScanState>(replay = 1)
    val scanState: SharedFlow<ScanState> = _scanState.asSharedFlow()
    
    sealed class ScanState {
        object Idle : ScanState()
        data class Scanning(
            val current: Int,
            val total: Int,
            val progress: Float
        ) : ScanState()
        data class Completed(
            val totalScanned: Int,
            val durationMs: Long
        ) : ScanState()
        data class Error(val message: String) : ScanState()
    }
    
    // 是否正在扫描
    @Volatile
    private var isScanning = false
    
    /**
     * 是否需要扫描
     * 数据库为空时需要扫描
     */
    suspend fun needsScan(): Boolean = withContext(Dispatchers.IO) {
        dao.isEmpty()
    }
    
    /**
     * 执行全量扫描
     * 扫描所有图片和视频，提取元数据
     */
    suspend fun performFullScan() = withContext(Dispatchers.IO) {
        if (isScanning) {
            Log.w(TAG, "Scan already in progress")
            return@withContext
        }
        
        isScanning = true
        val startTime = System.currentTimeMillis()
        
        try {
            _scanState.emit(ScanState.Scanning(0, 0, 0f))
            
            // 1. 获取所有媒体文件
            val allMedia = queryAllMediaFromMediaStore()
            val total = allMedia.size
            Log.i(TAG, "Found $total media files to scan")
            
            if (total == 0) {
                _scanState.emit(ScanState.Completed(0, 0))
                return@withContext
            }
            
            // 2. 获取现有数据库中的 ID 和修改时间
            val existingMedia = dao.getAllIdsAndModifiedTimes()
            val existingMap = existingMedia.associateBy { it.id }
            
            // 3. 批量处理
            val toInsert = mutableListOf<MediaMetadata>()
            var processedCount = 0
            var newCount = 0
            var updatedCount = 0
            
            for (media in allMedia) {
                try {
                    // 检查是否需要更新
                    val existing = existingMap[media.id]
                    val needsUpdate = existing == null || existing.dateModified != media.dateModified
                    
                    if (needsUpdate) {
                        val metadata = extractMetadata(media)
                        toInsert.add(metadata)
                        if (existing == null) newCount++ else updatedCount++
                    }
                    
                    processedCount++
                    
                    // 批量插入
                    if (toInsert.size >= BATCH_SIZE) {
                        dao.insertAll(toInsert)
                        toInsert.clear()
                    }
                    
                    // 发送进度
                    if (processedCount % 10 == 0 || processedCount == total) {
                        val progress = processedCount.toFloat() / total
                        _scanState.emit(ScanState.Scanning(processedCount, total, progress))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process media ${media.id}: ${e.message}")
                }
            }
            
            // 插入剩余数据
            if (toInsert.isNotEmpty()) {
                dao.insertAll(toInsert)
            }
            
            // 4. 删除已不存在的媒体
            val currentIds = allMedia.map { it.id }.toSet()
            val deletedIds = existingMap.keys.filter { it !in currentIds }
            if (deletedIds.isNotEmpty()) {
                Log.i(TAG, "Deleting ${deletedIds.size} removed media")
                dao.deleteByIds(deletedIds)
            }
            
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Scan completed: $newCount new, $updatedCount updated, ${deletedIds.size} deleted in ${duration}ms")
            
            _scanState.emit(ScanState.Completed(newCount + updatedCount, duration))
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            _scanState.emit(ScanState.Error(e.message ?: "Unknown error"))
        } finally {
            isScanning = false
        }
    }
    
    /**
     * 执行增量扫描
     * 只扫描新增或修改的文件
     */
    suspend fun performIncrementalScan() = withContext(Dispatchers.IO) {
        if (isScanning) {
            Log.w(TAG, "Scan already in progress")
            return@withContext
        }

        isScanning = true
        val startTime = System.currentTimeMillis()

        try {
            // 获取数据库中最新的修改时间
            val existingMedia = dao.getAllIdsAndModifiedTimes()
            val existingMap = existingMedia.associateBy { it.id }
            val latestModified = existingMedia.maxOfOrNull { it.dateModified } ?: 0L

            // 查询在此时间之后修改的媒体
            val newOrUpdated = queryMediaModifiedAfter(latestModified)

            if (newOrUpdated.isEmpty()) {
                Log.d(TAG, "No new or updated media found")
                return@withContext
            }

            // 提取并插入/更新元数据
            val toInsert = mutableListOf<MediaMetadata>()
            for (media in newOrUpdated) {
                val existing = existingMap[media.id]
                // 需要更新条件：数据库没有 OR 文件修改时间变化
                if (existing == null || existing.dateModified != media.dateModified) {
                    try {
                        val metadata = extractMetadata(media)
                        toInsert.add(metadata)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract metadata for ${media.id}: ${e.message}")
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                dao.insertAll(toInsert)
            }

            // 注意：删除清理由全量扫描处理
            // 增量扫描只负责新增/更新，不处理删除
            // 原因：准确判断删除需要查询所有当前媒体 ID，开销较大
            // 已删除的媒体在详情页访问时会被发现并跳过，不影响正常功能

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Incremental scan completed: ${toInsert.size} items in ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Incremental scan failed", e)
        } finally {
            isScanning = false
        }
    }
    
    /**
     * 更新单个媒体的元数据
     */
    suspend fun updateSingleMedia(id: Long) = withContext(Dispatchers.IO) {
        try {
            val media = queryMediaById(id) ?: return@withContext
            val metadata = extractMetadata(media)
            dao.insert(metadata)
            Log.d(TAG, "Updated metadata for media $id")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update media $id: ${e.message}")
        }
    }
    
    /**
     * 从 MediaStore 查询所有媒体
     */
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
            // 按修改时间降序，便于增量扫描
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
                
                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri,
                        mimeType = mimeType,
                        dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)),
                        dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)),
                        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)),
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
     * 查询指定时间之后修改的媒体
     */
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
                
                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri,
                        mimeType = mimeType,
                        dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)),
                        dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)),
                        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)),
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
     * 根据 ID 查询单个媒体
     */
    private fun queryMediaById(id: Long): MediaStoreItem? {
        val uri = MediaStore.Files.getContentUri("external")
        
        val selection = "${MediaStore.Files.FileColumns._ID} = ?"
        val selectionArgs = arrayOf(id.toString())
        
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
            if (cursor.moveToNext()) {
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: return null
                
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                
                val photoUri: Uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
                
                return MediaStoreItem(
                    id = id,
                    uri = photoUri,
                    mimeType = mimeType,
                    dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)),
                    dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)),
                    dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)),
                    width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
                    height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                    bucketId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
                    bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
                    orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)),
                    isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
                    isVideo = isVideo
                )
            }
        }
        
        return null
    }
    
    /**
     * 从 MediaStoreItem 提取完整元数据
     */
    private fun extractMetadata(item: MediaStoreItem): MediaMetadata {
        // 对于图片，提取 EXIF 信息
        val exifResult = if (!item.isVideo && item.mimeType.startsWith("image/")) {
            ExifExtractor.extract(context, item.uri)
        } else {
            ExifExtractor.ExifResult()
        }
        
        // 计算拍摄时间
        val dateTaken = ExifExtractor.calculateDateTaken(
            exifResult.dateTaken,
            item.dateTaken,
            item.dateModified
        )
        
        // 使用 EXIF 的尺寸（如果有的话）
        val width = if (exifResult.width > 0) exifResult.width else item.width
        val height = if (exifResult.height > 0) exifResult.height else item.height
        val orientation = if (exifResult.orientation > 0) exifResult.orientation else item.orientation
        
        return MediaMetadata(
            id = item.id,
            uri = item.uri.toString(),
            mimeType = item.mimeType,
            dateTakenOriginal = exifResult.dateTakenOriginal,
            dateTaken = dateTaken,
            dateAdded = item.dateAdded,
            dateModified = item.dateModified,
            width = width,
            height = height,
            orientation = orientation,
            fileSize = item.size,
            bucketId = item.bucketId,
            bucketName = item.bucketName,
            isFavorite = item.isFavorite,
            latitude = exifResult.latitude,
            longitude = exifResult.longitude,
            altitude = exifResult.altitude,
            cameraMake = exifResult.cameraMake,
            cameraModel = exifResult.cameraModel,
            lensModel = exifResult.lensModel,
            focalLength = exifResult.focalLength,
            aperture = exifResult.aperture,
            iso = exifResult.iso,
            exposureTime = exifResult.exposureTime,
            dateScanned = System.currentTimeMillis(),
            isVideo = item.isVideo
        )
    }
    
    /**
     * MediaStore 查询结果的内部数据类
     */
    private data class MediaStoreItem(
        val id: Long,
        val uri: Uri,
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
