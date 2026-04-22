package com.gxstar.stargallery.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.gxstar.stargallery.data.model.Photo

/**
 * 内存级照片分页数据源
 * 接收已经在 ViewModel 中排好序的 List<Photo> 进行分页显示
 */
class InMemoryPhotoPagingSource(
    private val photos: List<Photo>
) : PagingSource<Int, Photo>() {

    override fun getRefreshKey(state: PagingState<Int, Photo>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Photo> {
        return try {
            val position = params.key ?: 0
            val loadSize = params.loadSize

            val endPosition = minOf(photos.size, position + loadSize)
            val pagedData = photos.subList(position, endPosition)

            LoadResult.Page(
                data = pagedData,
                prevKey = if (position == 0) null else maxOf(0, position - loadSize),
                nextKey = if (endPosition == photos.size) null else endPosition
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}