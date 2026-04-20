package com.gxstar.stargallery.data.local.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
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
 * 负责扫描设备上的所有图片和视频，仅使用 MediaStore API
 */
@Singleton
class MetadataScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanPreferences: ScanPreferences
) {
    companion object {
        private const val TAG = "MetadataScanner"
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
     * 根据扫描偏好设置判断
     */
    suspend fun needsScan(): Boolean = withContext(Dispatchers.IO) {
        !scanPreferences.isScanCompleted
    }

    /**
     * 执行全量扫描
     * 扫描所有图片和视频
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

            // 获取所有媒体文件
            val allMedia = queryAllMediaFromMediaStore()
            val total = allMedia.size
            Log.i(TAG, "Found $total media files to scan")

            if (total == 0) {
                _scanState.emit(ScanState.Completed(0, 0))
                return@withContext
            }

            var processedCount = 0
            for (media in allMedia) {
                processedCount++
                // 发送进度
                if (processedCount % 10 == 0 || processedCount == total) {
                    val progress = processedCount.toFloat() / total
                    _scanState.emit(ScanState.Scanning(processedCount, total, progress))
                }
            }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Scan completed: $total media in ${duration}ms")

            _scanState.emit(ScanState.Completed(total, duration))
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
            val lastScanTime = scanPreferences.lastScanTime
            val newOrUpdated = queryMediaModifiedAfter(lastScanTime)

            if (newOrUpdated.isEmpty()) {
                Log.d(TAG, "No new or updated media found")
                return@withContext
            }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Incremental scan completed: ${newOrUpdated.size} new/updated in ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Incremental scan failed", e)
        } finally {
            isScanning = false
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
