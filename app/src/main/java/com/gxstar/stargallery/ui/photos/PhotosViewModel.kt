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
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.paging.MediaStorePagingSource
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.data.repository.MetadataRepository
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
    private val metadataRepository: MetadataRepository,
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _needsScan = MutableStateFlow(false)
    val needsScan: StateFlow<Boolean> = _needsScan.asStateFlow()

    private val _scanState = MutableStateFlow<MetadataScanner.ScanState>(MetadataScanner.ScanState.Idle)
    val scanState: StateFlow<MetadataScanner.ScanState> = _scanState.asStateFlow()

    private val _useMetadataDb = MutableStateFlow(false)
    val useMetadataDb: StateFlow<Boolean> = _useMetadataDb.asStateFlow()

    init {
        checkNeedsScan()
        loadCounts()
    }

    /**
     * 检查是否需要首次扫描
     */
    private fun checkNeedsScan() {
        viewModelScope.launch {
            val needs = metadataRepository.needsScan()
            _needsScan.value = needs
            _useMetadataDb.value = !needs
        }
    }

    /**
     * 开始扫描
     */
    fun startScan() {
        viewModelScope.launch {
            metadataRepository.performFullScan()
        }
    }

    /**
     * 观察扫描状态
     */
    fun observeScanState() {
        viewModelScope.launch {
            metadataRepository.getScanState().collect { state ->
                _scanState.value = state
                if (state is MetadataScanner.ScanState.Completed) {
                    _needsScan.value = false
                    _useMetadataDb.value = true
                    loadCounts()
                }
            }
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

    fun loadCounts() {
        viewModelScope.launch {
            if (_useMetadataDb.value) {
                _photoCount.value = metadataRepository.getPhotoCount()
                _favoriteCount.value = metadataRepository.getFavoriteCount()
            } else {
                _photoCount.value = mediaRepository.getPhotoCount()
                _favoriteCount.value = mediaRepository.getFavoriteCount()
            }
        }
    }

    /**
     * 基础照片数据流（不包含分组逻辑和收藏筛选）
     * 根据是否使用元数据库选择不同的数据源
     */
    private val basePhotoPagingFlow: Flow<PagingData<PhotoModel.PhotoItem>> = combine(
        _useMetadataDb,
        _currentSortType
    ) { useDb, sortType ->
        Pair(useDb, sortType)
    }.flatMapLatest { (useDb, sortType) ->
        if (useDb) {
            // 从元数据库读取
            metadataRepository.getPhotosPaging(
                when (sortType) {
                    MediaRepository.SortType.DATE_TAKEN -> MetadataRepository.SortType.DATE_TAKEN
                    MediaRepository.SortType.DATE_ADDED -> MetadataRepository.SortType.DATE_ADDED
                }
            ).map { pagingData ->
                pagingData.map { metadata ->
                    PhotoModel.PhotoItem(metadataRepository.toPhoto(metadata))
                }
            }
        } else {
            // 从 MediaStore 读取
            Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    enablePlaceholders = false,
                    initialLoadSize = PAGE_SIZE,
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
        }
    }.cachedIn(viewModelScope)

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
     * 更新单个媒体的元数据
     */
    fun updateSingleMedia(id: Long) {
        viewModelScope.launch {
            metadataRepository.updateSingleMedia(id)
        }
    }
    
    /**
     * 执行增量扫描
     */
    fun performIncrementalScan() {
        viewModelScope.launch {
            metadataRepository.performIncrementalScan()
        }
    }
}