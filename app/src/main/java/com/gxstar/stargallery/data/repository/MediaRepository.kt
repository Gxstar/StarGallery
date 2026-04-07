package com.gxstar.stargallery.data.repository

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.gxstar.stargallery.data.model.Album
import com.gxstar.stargallery.data.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver = context.contentResolver
    
    /**
     * 排序方式枚举
     */
    enum class SortType {
        DATE_TAKEN,      // 拍摄时间
        DATE_ADDED       // 创建时间
    }

    suspend fun getPhotos(page: Int, pageSize: Int = 50, sortType: SortType = SortType.DATE_TAKEN): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE
        )

        val sortOrder = when (sortType) {
            SortType.DATE_TAKEN -> "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            SortType.DATE_ADDED -> "${MediaStore.Images.Media.DATE_ADDED} DESC"
        }
        val offset = page * pageSize

        contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            if (offset > 0) {
                cursor.moveToPosition(offset - 1)
            }
            var count = 0
            while (cursor.moveToNext() && count < pageSize) {
                photos.add(cursor.toPhoto())
                count++
            }
        }
        photos
    }

    suspend fun getAllPhotos(sortType: SortType = SortType.DATE_TAKEN): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE
        )

        val sortOrder = when (sortType) {
            SortType.DATE_TAKEN -> "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            SortType.DATE_ADDED -> "${MediaStore.Images.Media.DATE_ADDED} DESC"
        }

        contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toPhoto())
            }
        }
        photos
    }

    /**
     * 加载全部媒体（图片+视频）到内存，用于自定义高级排序
     * 同名的普通图片和 RAW 照片会合并显示
     */
    suspend fun getAllMedia(sortType: SortType = SortType.DATE_TAKEN): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        
        val sortOrder = when (sortType) {
            SortType.DATE_TAKEN -> "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
            SortType.DATE_ADDED -> "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        }
        
        // 从 content resolver 加载所有基本属性到内存
        val bundle = Bundle().apply {
            // 显式排除回收站项
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(
                when (sortType) {
                    SortType.DATE_TAKEN -> MediaStore.Files.FileColumns.DATE_TAKEN
                    SortType.DATE_ADDED -> MediaStore.Files.FileColumns.DATE_ADDED
                }
            ))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }

        contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toMediaPhoto())
            }
        }
        
        // 合并同名照片（JPG+RAW 等）
        mergeSameNamePhotos(photos)
    }
    
    /**
     * 合并同名照片：如果存在同名的普通图片和 RAW，则合并显示
     * 优先级：普通图片 > RAW
     */
    private fun mergeSameNamePhotos(photos: List<Photo>): List<Photo> {
        // 按 displayName（不含扩展名）分组
        data class PhotoGroup(
            val normalPhoto: Photo?,  // 普通格式照片
            val rawPhoto: Photo?      // RAW 照片
        )
        
        val groupedPhotos = mutableMapOf<String, PhotoGroup>()
        
        for (photo in photos) {
            if (photo.displayName.isEmpty() || photo.isVideo) {
                // 没有文件名或是视频，直接保留
                continue
            }
            
            val key = "${photo.bucketId}_${photo.displayName}"
            
            val existing = groupedPhotos[key]
            if (photo.isRaw) {
                // RAW 照片
                val normalPhoto = existing?.normalPhoto
                groupedPhotos[key] = PhotoGroup(normalPhoto, photo)
            } else {
                // 普通照片
                val rawPhoto = existing?.rawPhoto
                groupedPhotos[key] = PhotoGroup(photo, rawPhoto)
            }
        }
        
        // 构建结果列表
        val result = mutableListOf<Photo>()
        val processedKeys = mutableSetOf<String>()
        
        for (photo in photos) {
            if (photo.isVideo) {
                // 视频直接保留
                result.add(photo)
                continue
            }
            
            if (photo.displayName.isEmpty()) {
                result.add(photo)
                continue
            }
            
            val key = "${photo.bucketId}_${photo.displayName}"
            
            if (processedKeys.contains(key)) {
                continue
            }
            
            val group = groupedPhotos[key]
            if (group != null) {
                if (group.normalPhoto != null) {
                    // 有普通格式照片，使用它作为主照片，并关联 RAW
                    val mergedPhoto = group.normalPhoto.copy(
                        pairedRawId = group.rawPhoto?.id
                    )
                    result.add(mergedPhoto)
                } else if (group.rawPhoto != null) {
                    // 只有 RAW 照片
                    result.add(group.rawPhoto)
                }
                processedKeys.add(key)
            } else {
                result.add(photo)
            }
        }
        
        return result
    }

    suspend fun getPhotoById(id: Long): Photo? = withContext(Dispatchers.IO) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE
        )

        contentResolver.query(uri, projection, "${MediaStore.Images.Media._ID} = ?", arrayOf(id.toString()), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.toPhoto()
            } else null
        }
    }

    suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val albums = mutableListOf<Album>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        // 使用 Map 存储相册信息：bucketId -> (封面photoId, 相册名, 数量)
        data class AlbumInfo(var coverPhotoId: Long, var name: String, var count: Int)
        val albumMap = mutableMapOf<Long, AlbumInfo>()

        // 按拍摄时间降序，这样第一个遇到的图片就是最新/封面
        contentResolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val photoId = cursor.getLong(idIndex)
                val bucketId = cursor.getLong(bucketIdIndex)
                val bucketName = cursor.getString(bucketNameIndex) ?: "Unknown"

                val existing = albumMap[bucketId]
                if (existing != null) {
                    existing.count++
                } else {
                    albumMap[bucketId] = AlbumInfo(photoId, bucketName, 1)
                }
            }
        }

        // 转换为 Album 列表
        for ((bucketId, info) in albumMap) {
            val coverUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, info.coverPhotoId)
            albums.add(Album(bucketId, info.name, coverUri, info.count))
        }

        albums.sortedByDescending { it.photoCount }
    }

    suspend fun getPhotosByBucket(bucketId: Long): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE
        )

        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toPhoto())
            }
        }
        photos
    }

    /**
     * 直接切换收藏状态（不需要用户确认）
     */
    suspend fun toggleFavoriteDirect(photo: Photo): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_FAVORITE, if (photo.isFavorite) 0 else 1)
            }
            contentResolver.update(photo.uri, values, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 切换单张照片收藏状态 - 返回 IntentSender 供用户确认
     */
    fun toggleFavorite(photo: Photo): android.content.IntentSender? {
        return try {
            val favoriteRequest = MediaStore.createFavoriteRequest(
                contentResolver,
                listOf(photo.uri),
                !photo.isFavorite
            )
            favoriteRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 批量设置照片收藏状态 - 返回 IntentSender 供用户确认
     */
    fun setFavorite(photos: List<Photo>, isFavorite: Boolean): android.content.IntentSender? {
        if (photos.isEmpty()) return null
        
        return try {
            val uris = photos.map { it.uri }
            val favoriteRequest = MediaStore.createFavoriteRequest(contentResolver, uris, isFavorite)
            favoriteRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 删除单张照片 - 返回 IntentSender 供用户确认
     */
    fun deletePhoto(photo: Photo): android.content.IntentSender? {
        return try {
            val deleteRequest = MediaStore.createDeleteRequest(
                contentResolver,
                listOf(photo.uri)
            )
            deleteRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 将单张照片移至回收站 - 返回 IntentSender 供用户确认
     */
    fun trashPhoto(photo: Photo): android.content.IntentSender? {
        return try {
            val trashRequest = MediaStore.createTrashRequest(
                contentResolver,
                listOf(photo.uri),
                true  // true = 移至回收站, false = 从回收站恢复
            )
            trashRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 获取媒体总数（图片+视频）
     */
    suspend fun getPhotoCount(): Int = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        }
        contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID), bundle, null)?.use { cursor ->
            cursor.count
        } ?: 0
    }

    /**
     * 批量删除照片 - 返回 IntentSender 供用户确认
     */
    fun deletePhotos(photos: List<Photo>): android.content.IntentSender? {
        if (photos.isEmpty()) return null
        
        return try {
            val uris = photos.map { it.uri }
            val deleteRequest = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 批量将照片移至回收站 - 返回 IntentSender 供用户确认
     */
    fun trashPhotos(photos: List<Photo>): android.content.IntentSender? {
        if (photos.isEmpty()) return null
        
        return try {
            val uris = photos.map { it.uri }
            val trashRequest = MediaStore.createTrashRequest(contentResolver, uris, true)
            trashRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 从回收站恢复照片 - 返回 IntentSender 供用户确认
     */
    fun restorePhotos(photos: List<Photo>): android.content.IntentSender? {
        if (photos.isEmpty()) return null
        
        return try {
            val uris = photos.map { it.uri }
            val restoreRequest = MediaStore.createTrashRequest(contentResolver, uris, false)
            restoreRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 获取回收站中的媒体（图片+视频）
     * 仅在 Android 11+ (API 30+) 支持系统级回收站
     */
    suspend fun getTrashedMedia(): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Files.getContentUri("external")
        
        val bundle = Bundle().apply {
            // 将模式调整为 MATCH_INCLUDE 以包含回收站项
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            
            // 明确增加过滤条件：is_trashed = 1 且必须是图片或视频
            val selection = "(${MediaStore.MediaColumns.IS_TRASHED} = 1) AND " +
                    "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                    "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            
            // 按拍摄时间降序
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_TAKEN))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        
        contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toTrashedPhoto())
            }
        }
        photos
    }

    private fun Cursor.toPhoto(): Photo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        
        val orientationIndex = getColumnIndex(MediaStore.Images.Media.ORIENTATION)
        val orientation = if (orientationIndex >= 0) getInt(orientationIndex) else 0
        
        return Photo(
            id = id,
            uri = uri,
            dateTaken = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)),
            dateModified = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)),
            dateAdded = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)),
            mimeType = getString(getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg",
            width = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
            height = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
            size = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
            bucketId = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)),
            bucketName = getString(getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)) ?: "Unknown",
            latitude = getColumnIndex(MediaStore.Images.Media.LATITUDE).takeIf { it >= 0 }?.let { getDouble(it) }?.takeIf { it != 0.0 },
            longitude = getColumnIndex(MediaStore.Images.Media.LONGITUDE).takeIf { it >= 0 }?.let { getDouble(it) }?.takeIf { it != 0.0 },
            orientation = orientation,
            isFavorite = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)) == 1
        )
    }

    private fun Cursor.toMediaPhoto(): Photo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
        val mimeType = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "image/jpeg"
        
        // 根据MIME_TYPE确定URI类型（图片或视频）
        val uri: Uri = if (mimeType.startsWith("video/")) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

        val orientationIndex = getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
        val orientation = if (orientationIndex >= 0) getInt(orientationIndex) else 0
        
        // 获取文件名（不含扩展名），用于同名照片合并
        val fileName = getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { getString(it) } ?: ""
        val displayName = fileName.substringBeforeLast(".")

        return Photo(
            id = id,
            uri = uri,
            dateTaken = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)),
            dateModified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)),
            dateAdded = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)),
            mimeType = mimeType,
            width = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
            height = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
            size = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
            bucketId = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
            bucketName = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
            latitude = null,
            longitude = null,
            orientation = orientation,
            isFavorite = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
            displayName = displayName
        )
    }
    
    private fun Cursor.toTrashedPhoto(): Photo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
        val mimeType = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "image/jpeg"
        
        val uri: Uri = if (mimeType.startsWith("video/")) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

        val orientationIndex = getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
        val orientation = if (orientationIndex >= 0) getInt(orientationIndex) else 0

        return Photo(
            id = id,
            uri = uri,
            dateTaken = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)),
            dateModified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)),
            dateAdded = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)),
            mimeType = mimeType,
            width = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
            height = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
            size = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
            bucketId = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
            bucketName = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
            latitude = null,
            longitude = null,
            orientation = orientation,
            isFavorite = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1
        )
    }

    /**
     * 获取最新媒体的时间戳（毫秒）
     * 用于检测媒体库是否有变化
     */
    suspend fun getLatestMediaTimestamp(sortType: SortType = SortType.DATE_TAKEN): Long = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri("external")
        val sortColumn = when (sortType) {
            SortType.DATE_TAKEN -> MediaStore.Files.FileColumns.DATE_TAKEN
            SortType.DATE_ADDED -> MediaStore.Files.FileColumns.DATE_ADDED
        }

        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                        "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            )
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }

        contentResolver.query(uri, arrayOf(sortColumn), bundle, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else 0L
        } ?: 0L
    }
}
