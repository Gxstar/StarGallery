package com.gxstar.stargallery.data.repository

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.gxstar.stargallery.data.model.Album
import com.gxstar.stargallery.data.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.DISPLAY_NAME
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
            MediaStore.Images.Media.IS_FAVORITE,
            MediaStore.Images.Media.DISPLAY_NAME
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
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME
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

            // 按到期时间降序（最新进入回收站的排最前）
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_EXPIRES))
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
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_EXPIRES
        )

        contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toMediaPhoto())
            }
        }
        photos
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

        // 优先使用系统返回的 DATE_EXPIRES（回收站自动到期时间）
        // 该字段由系统在文件移入回收站时自动填充，各厂商可能设置不同的天数（如30天/60天）
        // 比用 DATE_MODIFIED + 30天 更准确，因为 DATE_MODIFIED 是文件内容最后修改时间而非移入回收站时间
        val dateExpiration = try {
            val expiresIndex = getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            if (expiresIndex >= 0) {
                val expires = getLong(expiresIndex)
                if (expires > 0) expires * 1000L // DATE_EXPIRES 为秒级，转毫秒
                else (dateModified + 30 * 24 * 60 * 60) * 1000L // 系统未填充时回退
            } else {
                (dateModified + 30 * 24 * 60 * 60) * 1000L // 旧 ROM 不支持该字段时回退
            }
        } catch (_: Exception) {
            (dateModified + 30 * 24 * 60 * 60) * 1000L
        }

        val displayName = try {
            getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
        } catch (_: Exception) { null }

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
            isFavorite = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
            dateExpiration = dateExpiration,
            displayName = displayName
        )
    }

    private fun Cursor.toPhoto(): Photo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        val orientationIndex = getColumnIndex(MediaStore.Images.Media.ORIENTATION)
        val orientation = if (orientationIndex >= 0) getInt(orientationIndex) else 0

        val displayName = try {
            getString(getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
        } catch (_: Exception) { null }

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
            isFavorite = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)) == 1,
            displayName = displayName
        )
    }

    private fun Cursor.extractDateTaken(): Long {
        val dateTakenRaw = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
        val dateModified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
        val dateAdded = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))
        return Photo.normalizeDateTaken(dateTakenRaw, dateModified, dateAdded)
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

    fun getContentResolver(): ContentResolver = contentResolver

    suspend fun insertImageCopy(
        sourcePhoto: Photo,
        bitmap: Bitmap
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EDIT_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.BUCKET_ID, sourcePhoto.bucketId)
                put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, sourcePhoto.bucketName)
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            val insertUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext null
            contentResolver.openOutputStream(insertUri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            copyAllExif(sourcePhoto.uri, insertUri, bitmap.width, bitmap.height)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(insertUri, values, null, null)
            insertUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 创建空的 MediaStore 条目（IS_PENDING=1），返回 Uri 供外部写入。
     * 用于 uCrop 直接输出裁剪结果到 MediaStore，避免临时文件。
     */
    suspend fun createImageCopyPlaceholder(photo: Photo): Uri? = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EDIT_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.BUCKET_ID, photo.bucketId)
                put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, photo.bucketName)
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 将 IS_PENDING 设为 0，完成 MediaStore 条目的发布。
     */
    suspend fun finalizeImageCopy(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 删除空的 MediaStore 条目（用户取消裁剪时调用）。
     */
    suspend fun deleteImagePlaceholder(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun overwriteOriginal(photo: Photo): android.content.IntentSender? {
        return try {
            val editRequest = MediaStore.createWriteRequest(contentResolver, listOf(photo.uri))
            editRequest.intentSender
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun applyOverwrite(
        uri: Uri,
        bitmap: Bitmap,
        originalUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            copyAllExif(originalUri, uri, bitmap.width, bitmap.height)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 打开 Uri 用于 EXIF 写入。支持 file:// 和 content:// 两种 URI。
     * file:// 直接用 ParcelFileDescriptor.open(File, MODE_READ_WRITE)，避免 contentResolver 对 file:// 不可靠的问题。
     */
    private fun openFileDescriptorForWrite(uri: Uri): ParcelFileDescriptor? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_WRITE)
            } else {
                contentResolver.openFileDescriptor(uri, "rw")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从源文件复制所有 EXIF 信息到目标文件。
     * 方向强制设为 1（编辑后旋转/翻转已直接应用到图像数据），尺寸更新为编辑后的值。
     * 支持 file:// 和 content:// 两种 URI。
     */
    fun copyAllExif(sourceUri: Uri, destUri: Uri, newWidth: Int, newHeight: Int) {
        try {
            contentResolver.openInputStream(sourceUri)?.use { sourceStream ->
                val srcExif = ExifInterface(sourceStream)
                val pfd = openFileDescriptorForWrite(destUri) ?: return
                val destExif = ExifInterface(pfd.fileDescriptor)
                copyExifAttributes(srcExif, destExif)
                destExif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                destExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, newWidth.toString())
                destExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, newHeight.toString())
                destExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, newWidth.toString())
                destExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, newHeight.toString())
                destExif.saveAttributes()
                pfd.close()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 逐标签复制 EXIF，跳过缩略图相关标签（编辑后尺寸不匹配）。
     */
    private fun copyExifAttributes(source: ExifInterface, dest: ExifInterface) {
        for (tag in exifTagNames) {
            val value = source.getAttribute(tag) ?: continue
            dest.setAttribute(tag, value)
        }
    }

    companion object {
        /**
         * 所有可用 EXIF 标签（字符串形式），排除缩略图/已修正的标签。
         * TAG_ORIENTATION 和 TAG_IMAGE_WIDTH/LENGTH 由调用方根据编辑结果覆盖。
         */
        private val exifTagNames = listOf(
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_BITS_PER_SAMPLE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_CFA_PATTERN,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_COMPONENTS_CONFIGURATION,
            ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
            ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_CUSTOM_RENDERED,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DEFAULT_CROP_SIZE,
            ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_DNG_VERSION,
            ExifInterface.TAG_EXIF_VERSION,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_INDEX,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FILE_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FLASHPIX_VERSION,
            ExifInterface.TAG_FLASH_ENERGY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
            ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
            ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
            ExifInterface.TAG_GAIN_CONTROL,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_INTEROPERABILITY_INDEX,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_OECF,
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
            ExifInterface.TAG_PLANAR_CONFIGURATION,
            ExifInterface.TAG_PRIMARY_CHROMATICITIES,
            ExifInterface.TAG_REFERENCE_BLACK_WHITE,
            ExifInterface.TAG_RELATED_SOUND_FILE,
            ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_ROWS_PER_STRIP,
            ExifInterface.TAG_SAMPLES_PER_PIXEL,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SCENE_TYPE,
            ExifInterface.TAG_SENSING_METHOD,
            ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE,
            ExifInterface.TAG_SPECTRAL_SENSITIVITY,
            ExifInterface.TAG_STRIP_BYTE_COUNTS,
            ExifInterface.TAG_STRIP_OFFSETS,
            ExifInterface.TAG_SUBFILE_TYPE,
            ExifInterface.TAG_SUBJECT_AREA,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_SUBJECT_LOCATION,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_TRANSFER_FUNCTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_WHITE_POINT,
            ExifInterface.TAG_X_RESOLUTION,
            ExifInterface.TAG_Y_CB_CR_COEFFICIENTS,
            ExifInterface.TAG_Y_CB_CR_POSITIONING,
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
            ExifInterface.TAG_Y_RESOLUTION,
            "LensMake",
            "LensModel",
            "LensSerialNumber",
            "LensSpecification",
            "BodySerialNumber",
            "FocalPlaneXResolution",
            "FocalPlaneYResolution",
            "OwnerName",
            "CameraOwnerName",
            "RecommendedExposureIndex",
            "SensitivityType",
            "StandardOutputSensitivity",
            "OffsetTime",
            "OffsetTimeOriginal",
            "OffsetTimeDigitized",
        )
    }
}