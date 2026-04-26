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
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
import com.gxstar.stargallery.data.local.scanner.MediaScanner
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.paging.RoomPagingSource
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
    private val photoDao: PhotoDao,
    private val mediaScanner: MediaScanner,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 20
        private const val PREFETCH_DISTANCE = 10
    }

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount.asStateFlow()

    private val _favoriteCount = MutableStateFlow(0)
    val favoriteCount: StateFlow<Int> = _favoriteCount.asStateFlow()

    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // 扫描完成后递增，触发 PagingSource 重新加载
    private val _refreshTrigger = MutableStateFlow(0L)
    val refreshTrigger: StateFlow<Long> = _refreshTrigger.asStateFlow()

    val isSearching: Flow<Boolean> = _searchQuery.map { !it.isNullOrBlank() }

    init {
        // 初始化时执行一次全量扫描
        viewModelScope.launch {
            _isScanning.value = true
            mediaScanner.performFullScan()
            loadCounts()
            _isScanning.value = false
            _refreshTrigger.value++  // 扫描完成后触发刷新
        }
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

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query?.takeIf { it.isNotBlank() }
    }

    fun loadCounts() {
        viewModelScope.launch {
            _photoCount.value = photoDao.getPhotoCount()
            _favoriteCount.value = photoDao.getFavoriteCount()
        }
    }

    /**
     * 触发增量扫描（由 ContentObserver 调用）
     */
    fun requestIncrementalScan() {
        viewModelScope.launch {
            _isScanning.value = true
            val changed = mediaScanner.performIncrementalScan()
            loadCounts()
            _isScanning.value = false
            if (changed) {
                _refreshTrigger.value++  // 有变化时触发刷新
            }
        }
    }

    /**
     * 基础照片数据流（从 Room 数据库读取）
     * 当 _refreshTrigger 变化时重新创建 PagingSource（实现数据刷新）
     */
    private val basePhotoPagingFlow: Flow<PagingData<PhotoModel.PhotoItem>> = combine(
        _currentSortType,
        _showFavoritesOnly,
        _searchQuery,
        _refreshTrigger
    ) { sortType, showFavoritesOnly, searchQuery, _ ->
        Triple(sortType, showFavoritesOnly, searchQuery)
    }.flatMapLatest { (sortType, showFavoritesOnly, searchQuery) ->
        // 目前搜索功能暂不支持（需要 Room 支持全文搜索），先忽略 searchQuery
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE
            ),
            pagingSourceFactory = {
                RoomPagingSource(
                    photoDao = photoDao,
                    sortType = sortType,
                    showFavoritesOnly = showFavoritesOnly
                )
            }
        ).flow
            .map { pagingData ->
                pagingData.map { entity -> PhotoModel.PhotoItem(entity.toPhoto()) }
            }
    }.cachedIn(viewModelScope)

    /**
     * 带日期分组的照片数据流
     */
    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        basePhotoPagingFlow,
        _currentSortType,
        _currentGroupType
    ) { pagingData, sortType, groupType ->
        // 插入日期分隔符
        pagingData.insertSeparators { before, after ->
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
        loadCounts()
    }

    fun getCurrentPhotoCount(): Int {
        return if (_showFavoritesOnly.value) {
            _favoriteCount.value
        } else {
            _photoCount.value
        }
    }

    /**
     * 将 PhotoEntity 转换为 Photo
     */
    private fun PhotoEntity.toPhoto(): Photo {
        return Photo(
            id = id,
            uri = android.net.Uri.parse(uri),
            dateTaken = dateTaken,
            dateModified = dateModified,
            dateAdded = dateAdded,
            mimeType = mimeType,
            width = width,
            height = height,
            size = size,
            bucketId = bucketId,
            bucketName = bucketName,
            latitude = latitude,
            longitude = longitude,
            orientation = orientation,
            isFavorite = isFavorite
        )
    }
}
