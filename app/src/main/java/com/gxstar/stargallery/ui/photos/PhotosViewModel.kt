package com.gxstar.stargallery.ui.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.paging.MediaStorePagingSource
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GroupType {
    DAY, MONTH, YEAR
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 60
        private const val PREFETCH_DISTANCE = 30
    }

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPhotoCount()
    }

    fun setSortType(sortType: MediaRepository.SortType) {
        if (_currentSortType.value != sortType) {
            _currentSortType.value = sortType
        }
    }

    fun setGroupType(groupType: GroupType) {
        if (_currentGroupType.value != groupType) {
            _currentGroupType.value = groupType
        }
    }

    fun toggleFavoritesOnly() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    fun loadPhotoCount() {
        viewModelScope.launch {
            _photoCount.value = mediaRepository.getPhotoCount()
        }
    }

    /**
     * 基础照片数据流（不包含分组逻辑和收藏筛选）
     * 只有排序方式变化时才会重新查询数据库
     * 收藏筛选改为内存筛选，切换时不会重新加载
     */
    private val basePhotoPagingFlow: Flow<PagingData<PhotoModel.PhotoItem>> = _currentSortType
        .flatMapLatest { sortType ->
            Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    enablePlaceholders = false,
                    initialLoadSize = PAGE_SIZE * 2,
                    prefetchDistance = PREFETCH_DISTANCE
                ),
                pagingSourceFactory = {
                    MediaStorePagingSource(
                        contentResolver = context.contentResolver,
                        sortType = sortType
                    )
                }
            ).flow
                .map { pagingData ->
                    pagingData.map { photo -> PhotoModel.PhotoItem(photo) }
                }
                .cachedIn(viewModelScope)
        }

    /**
     * 带日期分组和收藏筛选的照片数据流
     * 分组模式和收藏筛选切换时只重新计算，不重新查询数据库
     */
    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        basePhotoPagingFlow,
        _currentSortType,
        _currentGroupType,
        _showFavoritesOnly
    ) { pagingData, sortType, groupType, showFavoritesOnly ->
        // 先进行内存筛选（收藏筛选）
        val filteredData = if (showFavoritesOnly) {
            pagingData.filter { it.photo.isFavorite }
        } else {
            pagingData
        }

        // 再插入分隔符（分组）
        // 注意：这里只是转换，不会触发重新加载
        filteredData.insertSeparators { before, after ->
            if (after == null) {
                null
            } else if (before == null) {
                PhotoModel.SeparatorItem(DateUtils.formatDateText(context, after.photo, sortType, groupType))
            } else {
                val beforeDate = DateUtils.formatDateText(context, before.photo, sortType, groupType)
                val afterDate = DateUtils.formatDateText(context, after.photo, sortType, groupType)
                if (beforeDate != afterDate) {
                    PhotoModel.SeparatorItem(afterDate)
                } else {
                    null
                }
            }
        }
    }.cachedIn(viewModelScope)

    fun refresh() {
        loadPhotoCount()
    }

    fun getCurrentPhotoCount(): Int {
        return _photoCount.value
    }
}
