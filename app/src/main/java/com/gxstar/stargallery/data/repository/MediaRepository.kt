package com.gxstar.stargallery.data.repository

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
        DATE_MODIFIED    // 修改时间
    }

    suspend fun getPhotos(page: Int, pageSize: Int = 50, sortType: SortType = SortType.DATE_TAKEN): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
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
            SortType.DATE_MODIFIED -> "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
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
            SortType.DATE_MODIFIED -> "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        }

        contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                photos.add(cursor.toPhoto())
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
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val bucketMap = mutableMapOf<Long, MutableList<Photo>>()

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketIdIndex)
                val photoId = cursor.getLong(idIndex)
                
                if (!bucketMap.containsKey(bucketId)) {
                    bucketMap[bucketId] = mutableListOf()
                }
            }
        }

        // 获取每个相册的照片数量和封面
        for ((bucketId, _) in bucketMap) {
            val countProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            val selectionArgs = arrayOf(bucketId.toString())

            var count = 0
            var coverUri: Uri? = null
            var albumName = ""

            contentResolver.query(uri, countProjection, selection, selectionArgs, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (count == 0) {
                        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val photoId = cursor.getLong(idIndex)
                        coverUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
                        albumName = cursor.getString(nameIndex) ?: "Unknown"
                    }
                    count++
                }
            }

            if (count > 0) {
                albums.add(Album(bucketId, albumName, coverUri, count))
            }
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
     * 获取照片总数
     */
    suspend fun getPhotoCount(): Int = withContext(Dispatchers.IO) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
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
}
