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
 * 支持 RAW 照片合并（延迟合并策略）
 */
class MediaStorePagingSource(
    private val contentResolver: ContentResolver,
    private val sortType: MediaRepository.SortType
) : PagingSource<Int, Photo>() {

    companion object {
        const val PAGE_SIZE = 60
        private const val RAW_MERGE_WINDOW = 100  // RAW 合并检查窗口大小
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
            // 注意：offset 必须基于固定的 PAGE_SIZE，不能用 params.loadSize
            // 因为首次加载 initialLoadSize=PAGE_SIZE=60，后续是 PAGE_SIZE=60
            // 如果用 params.loadSize=120，会导致 offset 计算错误产生数据重复
            val page = params.key ?: 0
            val offset = page * PAGE_SIZE
            val pageSize = params.loadSize

            // 使用 Bundle 进行分页查询（Android 10+ 支持）
            val photos = queryPhotosWithPagination(offset, pageSize)

            // 延迟 RAW 合并：只在当前页数据进行合并检查
            val mergedPhotos = if (photos.isNotEmpty()) {
                mergeRawPhotosInCurrentPage(photos, offset)
            } else {
                photos
            }

            LoadResult.Page(
                data = mergedPhotos,
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
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA  // 用于 RAW 识别
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
            val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val mimeType = cursor.getString(mimeTypeIndex) ?: "image/jpeg"
                val mediaType = cursor.getInt(mediaTypeIndex)

                val photoUri: Uri = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val displayName = cursor.getString(displayNameIndex) ?: ""
                val nameWithoutExtension = displayName.substringBeforeLast(".", displayName)

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
                        isFavorite = cursor.getInt(isFavoriteIndex) == 1,
                        displayName = nameWithoutExtension
                    )
                )
            }
        }

        return photos
    }

    /**
     * 延迟 RAW 合并策略
     * 只查询当前页数据附近的 RAW 文件进行合并，避免全表扫描
     */
    private fun mergeRawPhotosInCurrentPage(photos: List<Photo>, currentOffset: Int): List<Photo> {
        if (photos.isEmpty()) return photos

        // 获取当前页涉及的 bucketId 集合
        val bucketIds = photos.map { it.bucketId }.distinct()
        if (bucketIds.isEmpty()) return photos

        // 查询附近可能存在的 RAW 文件（窗口查询）
        val windowOffset = maxOf(0, currentOffset - RAW_MERGE_WINDOW / 2)
        val windowLimit = photos.size + RAW_MERGE_WINDOW

        val rawPhotos = queryRawPhotosInBuckets(bucketIds, windowOffset, windowLimit)
        if (rawPhotos.isEmpty()) return photos

        // 建立 RAW 文件查找表：bucketId_displayName -> RAW Photo
        val rawLookup = rawPhotos.associateBy { "${it.bucketId}_${it.displayName}" }

        // 合并逻辑：普通照片关联 RAW，RAW 照片检查是否有普通照片
        val result = mutableListOf<Photo>()
        val processedRawIds = mutableSetOf<Long>()

        for (photo in photos) {
            val lookupKey = "${photo.bucketId}_${photo.displayName}"
            val pairedRaw = rawLookup[lookupKey]

            if (photo.isRaw) {
                // 如果是 RAW，检查是否有对应的普通照片在前面处理
                // 有普通照片的话，这个 RAW 会被跳过
                val hasNormalVersion = photos.any { 
                    !it.isRaw && it.bucketId == photo.bucketId && it.displayName == photo.displayName 
                }
                if (!hasNormalVersion) {
                    result.add(photo)
                }
                processedRawIds.add(photo.id)
            } else {
                // 普通照片，关联 RAW
                result.add(
                    photo.copy(
                        pairedRawId = pairedRaw?.id,
                        hasRawPair = pairedRaw != null
                    )
                )
                if (pairedRaw != null) {
                    processedRawIds.add(pairedRaw.id)
                }
            }
        }

        return result
    }

    /**
     * 查询指定相册中的 RAW 文件
     */
    private fun queryRawPhotosInBuckets(bucketIds: List<Long>, offset: Int, limit: Int): List<Photo> {
        val rawPhotos = mutableListOf<Photo>()
        val uri = MediaStore.Files.getContentUri("external")

        // RAW 格式 MIME 类型
        val rawMimeTypes = listOf(
            "image/x-adobe-dng",  // DNG
            "image/x-sony-arw",   // ARW
            "image/x-canon-cr2",  // CR2
            "image/x-canon-cr3",  // CR3
            "image/x-nikon-nef",  // NEF
            "image/x-nikon-nrw",  // NRW
            "image/x-olympus-orf",// ORF
            "image/x-panasonic-rw2", // RW2
            "image/x-fuji-raf",   // RAF
            "image/x-pentax-pef", // PEF
            "image/x-samsung-srw",// SRW
            "image/x-sigma-x3f",  // X3F
            "image/x-hasselblad-3fr", // 3FR
            "image/x-leica-rwl",  // RWL
            "image/x-raw"         // 通用 RAW
        )

        val bucketIdInClause = bucketIds.joinToString(",") { it.toString() }
        val mimeTypeInClause = rawMimeTypes.joinToString(",") { "'$it'" }

        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            append(" AND ${MediaStore.Files.FileColumns.BUCKET_ID} IN ($bucketIdInClause)")
            append(" AND ${MediaStore.Files.FileColumns.MIME_TYPE} IN ($mimeTypeInClause)")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.IS_FAVORITE
        )

        val sortColumn = when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> MediaStore.Files.FileColumns.DATE_TAKEN
            MediaRepository.SortType.DATE_ADDED -> MediaStore.Files.FileColumns.DATE_ADDED
        }

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
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val isFavoriteIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val mimeType = cursor.getString(mimeTypeIndex) ?: "image/x-raw"
                val displayName = cursor.getString(displayNameIndex) ?: ""
                val nameWithoutExtension = displayName.substringBeforeLast(".", displayName)

                rawPhotos.add(
                    Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        dateTaken = cursor.getLong(dateTakenIndex),
                        dateModified = 0,
                        dateAdded = 0,
                        mimeType = mimeType,
                        width = 0,
                        height = 0,
                        size = 0,
                        bucketId = cursor.getLong(bucketIdIndex),
                        bucketName = "",
                        latitude = null,
                        longitude = null,
                        orientation = 0,
                        isFavorite = cursor.getInt(isFavoriteIndex) == 1,
                        displayName = nameWithoutExtension
                    )
                )
            }
        }

        return rawPhotos
    }
}
