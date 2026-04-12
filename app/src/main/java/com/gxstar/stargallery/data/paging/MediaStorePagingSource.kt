package com.gxstar.stargallery.data.paging

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore 分页数据源
 * 使用 Android 10+ 的 Bundle 分页查询，避免一次性加载所有数据
 */
class MediaStorePagingSource(
    private val contentResolver: ContentResolver,
    private val sortType: MediaRepository.SortType
) : PagingSource<Int, Photo>() {

    companion object {
        // 注意：实际每页数据量由 PagingConfig.pageSize 决定
    }

    /**
     * getRefreshKey 使用 anchorPosition 找到最近的 page，然后计算恢复位置
     */
    override fun getRefreshKey(state: PagingState<Int, Photo>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.let { prevKey ->
                prevKey + 1
            } ?: anchorPage?.nextKey?.let { nextKey ->
                maxOf(0, nextKey - 1)
            }
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Photo> = withContext(Dispatchers.IO) {
        try {
            val page = params.key ?: 0
            val pageSize = params.loadSize
            val offset = page * pageSize

            // 使用 Bundle 进行分页查询（Android 10+ 支持）
            val photos = queryPhotosWithPagination(offset, pageSize)

            LoadResult.Page(
                data = photos,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (photos.size < pageSize) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * 使用 Bundle 进行分页查询（Android 10+）
     * 比传统的 offset/limit 更高效
     */
    private fun queryPhotosWithPagination(offset: Int, limit: Int): List<Photo> {
        val photos = mutableListOf<Photo>()
        val uri = MediaStore.Files.getContentUri("external")

        val selection = buildString {
            append("(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            append(" OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
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

        val sortColumn = when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> MediaStore.Files.FileColumns.DATE_TAKEN
            MediaRepository.SortType.DATE_ADDED -> MediaStore.Files.FileColumns.DATE_ADDED
        }

        // Android 10+ 使用 Bundle 参数进行高效分页
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

        contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val orientationIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
            val isFavoriteIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)
            val mediaTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val mimeType = cursor.getString(mimeTypeIndex) ?: "image/jpeg"
                val mediaType = cursor.getInt(mediaTypeIndex)

                val photoUri: Uri = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                photos.add(
                    Photo(
                        id = id,
                        uri = photoUri,
                        dateTaken = cursor.getLong(dateTakenIndex),
                        dateModified = cursor.getLong(dateModifiedIndex),
                        dateAdded = cursor.getLong(dateAddedIndex),
                        mimeType = mimeType,
                        width = cursor.getInt(widthIndex),
                        height = cursor.getInt(heightIndex),
                        size = cursor.getLong(sizeIndex),
                        bucketId = cursor.getLong(bucketIdIndex),
                        bucketName = cursor.getString(bucketNameIndex) ?: "Unknown",
                        latitude = null,
                        longitude = null,
                        orientation = if (orientationIndex >= 0) cursor.getInt(orientationIndex) else 0,
                        isFavorite = cursor.getInt(isFavoriteIndex) == 1
                    )
                )
            }
        }

        return photos
    }
}
