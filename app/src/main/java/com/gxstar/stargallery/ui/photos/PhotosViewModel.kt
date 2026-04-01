package com.gxstar.stargallery.ui.photos

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
import com.gxstar.stargallery.data.paging.InMemoryPhotoPagingSource
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GroupType {
    DAY, MONTH, YEAR
}

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 60
    }

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount.asStateFlow()

    private val _allPhotos = MutableStateFlow<List<Photo>>(emptyList())

    init {
        loadPhotoCount()
        loadAllPhotos()
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

    fun loadPhotoCount() {
        viewModelScope.launch {
            _photoCount.value = mediaRepository.getPhotoCount()
        }
    }

    private fun loadAllPhotos() {
        viewModelScope.launch {
            _allPhotos.value = mediaRepository.getAllMedia()
        }
    }

    /**
     * 获取带日期分组的Paging Flow
     * 使用 flatMapLatest 响应排序/分组变化
     * 注意: cachedIn 必须在 flatMapLatest 内部，确保每次变化时创建新的缓存
     */
    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        _allPhotos, _currentSortType, _currentGroupType
    ) { photos, sortType, groupType ->
        Triple(photos, sortType, groupType)
    }.flatMapLatest { (photos, sortType, groupType) ->
        // 在后台线程（Flow）中对内存相册进行复杂排序
        val sortedPhotos = if (photos.isEmpty()) {
            emptyList()
        } else {
            when (sortType) {
                MediaRepository.SortType.DATE_TAKEN -> {
                    // 当没有EXIF或者拍摄时间为0时，使用文件创建时间作为补充
                    photos.sortedByDescending { photo ->
                        if (photo.dateTaken > 0) photo.dateTaken else (photo.dateAdded * 1000L)
                    }
                }
                MediaRepository.SortType.DATE_ADDED -> {
                    photos.sortedByDescending { it.dateAdded }
                }
            }
        }

        // 由于数据已在内存中，一次性加载所有数据
        // 这样 FastScroll 可以正确显示进度，避免滑块位置不准确
        val totalSize = sortedPhotos.size

        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = if (totalSize > 0) totalSize else PAGE_SIZE * 2,
                prefetchDistance = 30
            ),
            pagingSourceFactory = {
                InMemoryPhotoPagingSource(sortedPhotos)
            }
        ).flow
            .map { pagingData ->
                // 先将Photo转换为PhotoModel.PhotoItem
                pagingData.map { photo ->
                    PhotoModel.PhotoItem(photo)
                }
            }
            .map { pagingData ->
                // 使用insertSeparators插入日期分隔符
                pagingData.insertSeparators { before: PhotoModel.PhotoItem?, after: PhotoModel.PhotoItem? ->
                    if (after == null) {
                        // 到达列表末尾，不需要分隔符
                        null
                    } else if (before == null) {
                        // 列表开头，显示第一个日期分隔符
                        PhotoModel.SeparatorItem(DateUtils.formatDateText(after.photo, sortType, groupType))
                    } else {
                        // 比较前后两个item的日期，如果不同则插入分隔符
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
            // cachedIn 放在 flatMapLatest 内部，确保每次排序/分组变化时重新缓存
            .cachedIn(viewModelScope)
    }

    fun refresh() {
        loadPhotoCount()
        loadAllPhotos()
    }
}