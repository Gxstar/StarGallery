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

    /**
     * 加载全部媒体（图片+视频）到内存，用于自定义高级排序
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
            MediaStore.Files.FileColumns.MEDIA_TYPE
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

        photos
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

    suspend fun getPhotosByBucket(bucketId: Long, sortType: SortType = SortType.DATE_TAKEN): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Files.getContentUri("external")
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

        val selection = "${MediaStore.Files.FileColumns.BUCKET_ID} = ? " +
            "AND (${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
            "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val selectionArgs = arrayOf(bucketId.toString())

        val sortColumn = when (sortType) {
            SortType.DATE_TAKEN -> MediaStore.Files.FileColumns.DATE_TAKEN
            SortType.DATE_ADDED -> MediaStore.Files.FileColumns.DATE_ADDED
        }
        val sortOrder = "$sortColumn DESC"

        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toMediaPhoto())
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
     * 获取收藏媒体总数（图片+视频）
     */
    suspend fun getFavoriteCount(): Int = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) " +
                "AND ${MediaStore.Files.FileColumns.IS_FAVORITE} = 1"
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
                photos.add(cursor.toMediaPhoto())
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

    private fun Cursor.extractDateTaken(): Long {
        val dateTakenRaw = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
        val dateModified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
        val dateAdded = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))
        return if (dateTakenRaw > 0) dateTakenRaw else (if (dateModified > 0) dateModified else dateAdded) * 1000L
    }

    private fun Cursor.toMediaPhoto(): Photo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
        val mimeType = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "image/jpeg"

        val uri: Uri = if (mimeType.startsWith("video/")) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

        val orientationIndex = getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
        val orientation = if (orientationIndex >= 0) getInt(orientationIndex) else 0

        val dateTaken = extractDateTaken()
        val dateModified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
        val dateAdded = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))

        return Photo(
            id = id,
            uri = uri,
            dateTaken = dateTaken,
            dateModified = dateModified,
            dateAdded = dateAdded,
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
