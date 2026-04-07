package com.gxstar.stargallery.ui.photos

import android.content.ContentResolver
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
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
     * 使用 MediaStorePagingSource 进行真正的分页查询
     * 避免一次性加载所有照片到内存
     */
    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        _currentSortType,
        _currentGroupType,
        _showFavoritesOnly
    ) { sortType, groupType, showFavoritesOnly ->
        Triple(sortType, groupType, showFavoritesOnly)
    }.flatMapLatest { (sortType, groupType, showFavoritesOnly) ->
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE * 2,  // 初始加载2页，平衡速度与内存
                prefetchDistance = PREFETCH_DISTANCE
            ),
            pagingSourceFactory = {
                MediaStorePagingSource(
                    contentResolver = context.contentResolver,
                    sortType = sortType,
                    favoritesOnly = showFavoritesOnly
                )
            }
        ).flow
            .map { pagingData ->
                // 将 Photo 转换为 PhotoModel.PhotoItem
                pagingData.map { photo ->
                    PhotoModel.PhotoItem(photo)
                }
            }
            .map { pagingData ->
                // 使用 insertSeparators 插入日期分隔符
                pagingData.insertSeparators { before: PhotoModel.PhotoItem?, after: PhotoModel.PhotoItem? ->
                    if (after == null) {
                        // 到达列表末尾，不需要分隔符
                        null
                    } else if (before == null) {
                        // 列表开头，显示第一个日期分隔符
                        PhotoModel.SeparatorItem(DateUtils.formatDateText(after.photo, sortType, groupType))
                    } else {
                        // 比较前后两个 item 的日期，如果不同则插入分隔符
                        val beforeDate = DateUtils.formatDateText(before.photo, sortType, groupType)
                        val afterDate = DateUtils.formatDateText(after.photo, sortType, groupType)
                        if (beforeDate != afterDate) {
                            PhotoModel.SeparatorItem(afterDate)
                        } else {
                            null
                        }
                    }
                }
            }
            .cachedIn(viewModelScope)
    }

    fun refresh() {
        loadPhotoCount()
    }

    /**
     * 获取当前显示的照片数量（考虑收藏筛选）
     */
    fun getCurrentPhotoCount(): Int {
        // 分页模式下需要异步查询，这里先返回总数量
        // 实际显示数量会在 UI 层根据分页数据更新
        return if (_showFavoritesOnly.value) {
            // 收藏数量需要单独查询，这里简化处理
            _photoCount.value  // TODO: 实现收藏照片数量查询
        } else {
            _photoCount.value
        }
    }
}