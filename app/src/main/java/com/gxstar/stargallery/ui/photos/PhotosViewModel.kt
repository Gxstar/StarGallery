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
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.model.PhotoModel
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
        private const val PAGE_SIZE = 50
        private const val PREFETCH_DISTANCE = 20
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
     * 权限授权后重新扫描
     */
    fun rescanAfterPermissionGranted() {
        viewModelScope.launch {
            _isScanning.value = true
            mediaScanner.performFullScan()
            loadCounts()
            _isScanning.value = false
            _refreshTrigger.value++
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
                _refreshTrigger.value++
            }
        }
    }

    /**
     * 更新收藏状态
     */
    fun updateFavorite(photoIds: List<Long>, isFavorite: Boolean) {
        viewModelScope.launch {
            mediaScanner.updateFavorite(photoIds, isFavorite)
            loadCounts()
        }
    }

    /**
     * 更新混合收藏状态
     */
    fun updateFavoriteMixed(toFavorite: List<Long>, toUnfavorite: List<Long>) {
        viewModelScope.launch {
            if (toFavorite.isNotEmpty()) {
                mediaScanner.updateFavorite(toFavorite, true)
            }
            if (toUnfavorite.isNotEmpty()) {
                mediaScanner.updateFavorite(toUnfavorite, false)
            }
            loadCounts()
        }
    }

    /**
     * 删除照片
     */
    fun deletePhotos(photoIds: List<Long>) {
        viewModelScope.launch {
            photoIds.forEach { mediaScanner.deletePhoto(it) }
            loadCounts()
        }
    }

    /**
     * 带日期分组的照片数据流
     * 使用 flatMapLatest 确保当排序或过滤条件变化时重新创建 Pager
     * 启用 placeholders 以提高滚动稳定性
     */
    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        _currentSortType,
        _showFavoritesOnly,
        _searchQuery,
        _currentGroupType
    ) { sortType, showFavoritesOnly, searchQuery, groupType ->
        DataConfig(sortType, showFavoritesOnly, searchQuery, groupType)
    }.flatMapLatest { config ->
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false, // 禁用占位符，占位符与动态高度的分隔符结合会导致严重的滚动跳变
                initialLoadSize = PAGE_SIZE * 3,
                prefetchDistance = PREFETCH_DISTANCE
            ),
            pagingSourceFactory = {
                if (config.showFavoritesOnly) {
                    if (config.sortType == MediaRepository.SortType.DATE_TAKEN) {
                        photoDao.pagingFavoritePhotosByDateTaken()
                    } else {
                        photoDao.pagingFavoritePhotosByDateAdded()
                    }
                } else {
                    if (config.sortType == MediaRepository.SortType.DATE_TAKEN) {
                        photoDao.pagingPhotosByDateTaken()
                    } else {
                        photoDao.pagingPhotosByDateAdded()
                    }
                }
            }
        ).flow
            .map { pagingData ->
                // 先转换为 UI 模型
                pagingData.map { entity -> PhotoModel.PhotoItem(entity.toPhoto()) as PhotoModel }
            }
            .map { pagingData ->
                // 插入日期分隔符
                pagingData.insertSeparators { before, after ->
                    if (after == null) {
                        null
                    } else if (before == null) {
                        if (after is PhotoModel.PhotoItem) {
                            PhotoModel.SeparatorItem(DateUtils.formatDateText(context, after.photo, config.sortType, config.groupType))
                        } else null
                    } else {
                        if (before is PhotoModel.PhotoItem && after is PhotoModel.PhotoItem) {
                            val beforeDate = DateUtils.formatDateText(context, before.photo, config.sortType, config.groupType)
                            val afterDate = DateUtils.formatDateText(context, after.photo, config.sortType, config.groupType)
                            if (beforeDate != afterDate) {
                                PhotoModel.SeparatorItem(afterDate)
                            } else {
                                null
                            }
                        } else null
                    }
                }
            }
    }.cachedIn(viewModelScope)

    private data class DataConfig(
        val sortType: MediaRepository.SortType,
        val showFavoritesOnly: Boolean,
        val searchQuery: String?,
        val groupType: GroupType
    )

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
