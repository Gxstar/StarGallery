package com.gxstar.stargallery.data.paging

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository

/**
 * 照片分页数据源
 * 使用正确的MediaStore分页方式
 * 同时支持图片和视频
 * 
 * 返回纯Photo对象，日期分组在ViewModel中通过insertSeparators处理
 */
class PhotoPagingSource(
    private val context: Context,
    private val sortType: MediaRepository.SortType
) : PagingSource<Int, Photo>() {

    companion object {
        private const val TAG = "PhotoPagingSource"
        // 使用 Files URI 查询所有媒体类型（图片+视频）
        private val URI: Uri = MediaStore.Files.getContentUri("external")
        
        // 过滤条件：只查询图片和视频
        private const val SELECTION = "(${FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        
        private val PROJECTION = arrayOf(
            FileColumns._ID,
            FileColumns.DATE_TAKEN,
            FileColumns.DATE_MODIFIED,
            FileColumns.DATE_ADDED,
            FileColumns.MIME_TYPE,
            FileColumns.WIDTH,
            FileColumns.HEIGHT,
            FileColumns.SIZE,
            FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME,
            FileColumns.ORIENTATION,
            FileColumns.IS_FAVORITE,
            FileColumns.MEDIA_TYPE  // 用于区分图片和视频
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Photo>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Photo> {
        val offset = params.key ?: 0
        val pageSize = params.loadSize

        Log.d(TAG, "load: offset=$offset, pageSize=$pageSize")

        // 排序逻辑：
        // 1. 按拍摄日期排序：优先DATE_TAKEN，为0时用DATE_ADDED * 1000（即创建时间）
        // 2. 按修改日期排序：直接使用DATE_MODIFIED
        val sortOrder = when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> 
                "COALESCE(NULLIF(${FileColumns.DATE_TAKEN}, 0), ${FileColumns.DATE_ADDED} * 1000) DESC, " +
                "${FileColumns._ID} DESC"
            MediaRepository.SortType.DATE_MODIFIED -> 
                "${FileColumns.DATE_MODIFIED} DESC, ${FileColumns._ID} DESC"
        }

        val items = mutableListOf<Photo>()

        try {
            // 使用 Bundle 方式分页（Android R+）
            val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, SELECTION)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
                }
                context.contentResolver.query(URI, PROJECTION, queryArgs, null)
            } else {
                // 旧版本：查询全部，手动跳过
                context.contentResolver.query(URI, PROJECTION, SELECTION, null, sortOrder)
            }

            cursor?.use { c ->
                // 旧版本手动分页
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    if (offset > 0) {
                        c.moveToPosition(offset - 1)
                    }
                }
                
                var count = 0
                while (c.moveToNext() && count < pageSize) {
                    count++
                    items.add(c.toPhoto())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load error", e)
            return LoadResult.Error(e)
        }

        Log.d(TAG, "load complete: items.size=${items.size}")

        val prevKey = if (offset == 0) null else maxOf(0, offset - pageSize)
        val nextKey = if (items.size < pageSize) null else offset + items.size

        return LoadResult.Page(
            data = items,
            prevKey = prevKey,
            nextKey = nextKey
        )
    }

    private fun Cursor.toPhoto(): Photo {
        val id = getLong(getColumnIndexOrThrow(FileColumns._ID))
        val mimeType = getString(getColumnIndexOrThrow(FileColumns.MIME_TYPE)) ?: "image/jpeg"
        
        // 根据MIME_TYPE确定URI类型（图片或视频）
        val uri: Uri = if (mimeType.startsWith("video/")) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

        val orientationIndex = getColumnIndex(FileColumns.ORIENTATION)
        val orientation = if (orientationIndex >= 0) getInt(orientationIndex) else 0

        return Photo(
            id = id,
            uri = uri,
            dateTaken = getLong(getColumnIndexOrThrow(FileColumns.DATE_TAKEN)),
            dateModified = getLong(getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)),
            dateAdded = getLong(getColumnIndexOrThrow(FileColumns.DATE_ADDED)),
            mimeType = mimeType,
            width = getInt(getColumnIndexOrThrow(FileColumns.WIDTH)),
            height = getInt(getColumnIndexOrThrow(FileColumns.HEIGHT)),
            size = getLong(getColumnIndexOrThrow(FileColumns.SIZE)),
            bucketId = getLong(getColumnIndexOrThrow(FileColumns.BUCKET_ID)),
            bucketName = getString(getColumnIndexOrThrow(FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
            latitude = null,
            longitude = null,
            orientation = orientation,
            isFavorite = getInt(getColumnIndexOrThrow(FileColumns.IS_FAVORITE)) == 1
        )
    }
}
