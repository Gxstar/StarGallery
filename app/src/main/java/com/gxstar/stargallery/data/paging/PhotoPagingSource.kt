package com.gxstar.stargallery.data.paging

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.PhotoWithHeader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 照片分页数据源
 * 使用正确的MediaStore分页方式
 */
class PhotoPagingSource(
    private val context: Context,
    private val sortType: MediaRepository.SortType
) : PagingSource<Int, PhotoWithHeader>() {

    companion object {
        private const val TAG = "PhotoPagingSource"
        private val URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE
        )
    }

    private val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

    override fun getRefreshKey(state: PagingState<Int, PhotoWithHeader>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PhotoWithHeader> {
        val page = params.key ?: 0
        val pageSize = params.loadSize
        val offset = page * pageSize

        Log.d(TAG, "load: page=$page, pageSize=$pageSize, offset=$offset")

        val sortOrder = when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            MediaRepository.SortType.DATE_MODIFIED -> "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        }

        val items = mutableListOf<PhotoWithHeader>()

        // 获取今天和昨天的日期字符串
        val calendar = Calendar.getInstance()
        val today = calendar.time
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = calendar.time
        val todayStr = dateFormat.format(today)
        val yesterdayStr = dateFormat.format(yesterday)

        var lastDate = ""

        try {
            // 使用 Bundle 方式分页（Android R+）
            val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, null)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
                }
                context.contentResolver.query(URI, PROJECTION, queryArgs, null)
            } else {
                // 旧版本：查询全部，手动跳过
                context.contentResolver.query(URI, PROJECTION, null, null, sortOrder)
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
                    val photo = c.toPhoto()
                    
                    // 计算日期
                    val timestamp = when (sortType) {
                        MediaRepository.SortType.DATE_TAKEN -> photo.dateTaken
                        MediaRepository.SortType.DATE_MODIFIED -> photo.dateModified * 1000L
                    }
                    val date = Date(timestamp)
                    val dateStr = dateFormat.format(date)

                    val displayDate = when (dateStr) {
                        todayStr -> "今天"
                        yesterdayStr -> "昨天"
                        else -> dateStr
                    }

                    val showHeader = lastDate != displayDate
                    lastDate = displayDate

                    items.add(
                        PhotoWithHeader(
                            photo = photo,
                            showHeader = showHeader,
                            headerText = displayDate
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load error", e)
            return LoadResult.Error(e)
        }

        Log.d(TAG, "load complete: items.size=${items.size}")

        val prevKey = if (page == 0) null else page - 1
        val nextKey = if (items.size < pageSize) null else page + 1

        return LoadResult.Page(
            data = items,
            prevKey = prevKey,
            nextKey = nextKey
        )
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
            latitude = null,
            longitude = null,
            orientation = orientation,
            isFavorite = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)) == 1
        )
    }
}