package com.gxstar.stargallery.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
import com.gxstar.stargallery.data.repository.MediaRepository

/**
 * Room 数据库 PagingSource
 * 从 Room 数据库读取照片数据进行分页
 * 支持按 dateTaken 或 dateAdded 排序，支持收藏过滤
 */
class RoomPagingSource(
    private val photoDao: PhotoDao,
    private val sortType: MediaRepository.SortType,
    private val showFavoritesOnly: Boolean
) : PagingSource<Int, PhotoEntity>() {

    override fun getRefreshKey(state: PagingState<Int, PhotoEntity>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PhotoEntity> {
        return try {
            val position = params.key ?: 0
            val pageSize = params.loadSize
            val offset = position * pageSize

            // 获取对应页的数据
            val photos = if (showFavoritesOnly) {
                if (sortType == MediaRepository.SortType.DATE_TAKEN) {
                    photoDao.getFavoritePhotosByDateTakenPaged(offset, pageSize)
                } else {
                    photoDao.getFavoritePhotosByDateAddedPaged(offset, pageSize)
                }
            } else {
                if (sortType == MediaRepository.SortType.DATE_TAKEN) {
                    photoDao.getPhotosByDateTakenPaged(offset, pageSize)
                } else {
                    photoDao.getPhotosByDateAddedPaged(offset, pageSize)
                }
            }

            LoadResult.Page(
                data = photos,
                prevKey = if (position == 0) null else maxOf(0, position - pageSize),
                nextKey = if (photos.size < pageSize) null else position + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}