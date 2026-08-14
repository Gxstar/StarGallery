package com.gxstar.stargallery.ui.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import com.gxstar.stargallery.data.local.scanner.MediaScanner
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.filter.ActiveCondition
import com.gxstar.stargallery.ui.photos.filter.FilterDimensionId
import com.gxstar.stargallery.ui.photos.filter.FilterDimensions
import com.gxstar.stargallery.ui.photos.filter.FilterOption
import com.gxstar.stargallery.ui.photos.filter.FilterState
import com.gxstar.stargallery.ui.photos.filter.UNKNOWN_KEY
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.util.DateUtils
import com.gxstar.stargallery.ui.util.SortUtils
import com.gxstar.stargallery.util.ExcludedAlbumManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gxstar.stargallery.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class GroupType {
    DAY, MONTH, YEAR
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val photoDao: PhotoDao,
    private val mediaScanner: MediaScanner,
    private val scanPreferences: ScanPreferences,
    private val excludedAlbumManager: ExcludedAlbumManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    val photoCount: StateFlow<Int> = photoDao.getPhotoCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val favoriteCount: StateFlow<Int> = photoDao.getFavoriteCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val hiddenCount: StateFlow<Int> = photoDao.getHiddenCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // 后台静默同步（增量扫描）状态，不阻塞首屏渲染，仅用于轻量进度提示
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val isExtractingExif: StateFlow<Boolean> = mediaScanner.isExtractingExifFlow

    val exifProgress: StateFlow<Float> = mediaScanner.exifProgress

    // ==================== 筛选状态（单一数据源） ====================

    /**
     * 全部筛选条件收敛到这一个 StateFlow
     * 取代原先「每个维度两个 StateFlow（显式选择 + 级联写回结果）」的写法
     */
    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    val showFavoritesOnly: StateFlow<Boolean> = _filterState
        .map { it.favoritesOnly }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val searchQuery: StateFlow<String?> = _filterState
        .map { it.searchQuery }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isSearching: StateFlow<Boolean> = searchQuery
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * 只投影「影响照片过滤的维度条件」，搜索词变化不会让它重新发射。
     * 这样连续输入搜索词时不会反复重跑全量维度过滤与选项统计。
     */
    private val dimensionFilterState: StateFlow<FilterState> = _filterState
        .map { it.copy(searchQuery = null) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FilterState())

    private val _rawPhotoEntities = MutableStateFlow<List<PhotoEntity>>(emptyList())

    /** 参与筛选的候选集：排除隐藏与被排除相册，选项统计与过滤共用同一基准 */
    private val filterCandidates: StateFlow<List<PhotoEntity>> = combine(
        _rawPhotoEntities,
        excludedAlbumManager.excludedBucketIds
    ) { entities, excludedBucketIds ->
        withContext(Dispatchers.Default) {
            entities.filter { entity ->
                !entity.isHidden &&
                    (excludedBucketIds.isEmpty() || entity.bucketId !in excludedBucketIds)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 各维度的可选项与命中张数（faceted 统计）
     *
     * 统计某维度选项时排除该维度自身条件，因此天然实现层级联动：
     * 选了品牌后，型号列表只会剩下该品牌下的型号，且计数是「选了会剩几张」的真实值。
     * 取代了原先 DAO 里 6 条静态 GROUP BY 查询 + 级联写回的做法。
     *
     * 仅在筛选面板订阅时计算，面板关闭后立即停止，避免常驻开销。
     */
    val filterOptions: StateFlow<Map<FilterDimensionId, List<FilterOption>>> = combine(
        filterCandidates,
        dimensionFilterState
    ) { candidates, state ->
        withContext(Dispatchers.Default) {
            buildFilterOptions(candidates, state)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyMap())

    private fun buildFilterOptions(
        candidates: List<PhotoEntity>,
        state: FilterState
    ): Map<FilterDimensionId, List<FilterOption>> {
        val unknownLabel = context.getString(R.string.filter_unknown)
        return FilterDimensions.ALL.associate { dimension ->
            val counts = HashMap<String, Int>()
            for (entity in candidates) {
                if (!state.matchesExcept(entity, dimension.id)) continue
                val key = dimension.keyOf(entity)
                counts[key] = (counts[key] ?: 0) + 1
            }
            val options = counts.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key }
                )
                .map { (key, count) ->
                    FilterOption(
                        key = key,
                        display = if (key == UNKNOWN_KEY) unknownLabel else key,
                        count = count
                    )
                }
            // 未知项固定排在末尾，避免大量无 EXIF 照片时把它顶到第一个
            dimension.id to options.sortedBy { it.isUnknown }
        }
    }

    /**
     * 当前全部生效条件，供顶栏 chip 条展示
     * 维度类条件是通用的，新增维度不需要改这里
     */
    val activeConditions: StateFlow<List<ActiveCondition>> = combine(
        _filterState,
        excludedAlbumManager.excludedBucketIds
    ) { state, excludedBucketIds ->
        buildList {
            if (state.favoritesOnly) {
                add(ActiveCondition.Favorites(context.getString(R.string.filter_chip_favorites)))
            }
            state.searchQuery?.takeIf { it.isNotBlank() }?.let { query ->
                add(
                    ActiveCondition.Search(
                        query = query,
                        label = context.getString(R.string.filter_chip_search, query)
                    )
                )
            }
            FilterDimensions.ALL.forEach { dimension ->
                val selected = state.selectionOf(dimension.id)
                if (selected.isEmpty()) return@forEach
                val title = context.getString(dimension.titleRes)
                val value = if (selected.size == 1) {
                    selected.first().takeIf { it != UNKNOWN_KEY }
                        ?: context.getString(R.string.filter_unknown)
                } else {
                    context.getString(R.string.filter_selected_count, selected.size)
                }
                add(
                    ActiveCondition.Dimension(
                        id = dimension.id,
                        label = context.getString(R.string.filter_chip_dimension, title, value)
                    )
                )
            }
            if (excludedBucketIds.isNotEmpty()) {
                add(
                    ActiveCondition.ExcludedAlbums(
                        label = context.getString(
                            R.string.filter_chip_excluded_albums,
                            excludedBucketIds.size
                        )
                    )
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val TAG = "PhotosViewModel"

    init {
        // Room Flow 自动监听表变化推送更新，替代 2 秒轮询
        viewModelScope.launch {
            photoDao.getAllPhotosFlow()
                .retryWhen { cause, attempt ->
                    android.util.Log.w(TAG, "Room Flow error #$attempt: ${cause.message}")
                    delay(minOf(2000L * (attempt + 1), 10000L))
                    true
                }
                .collect { entities ->
                    _rawPhotoEntities.value = entities
                }
        }

        viewModelScope.launch {
            if (!scanPreferences.isScanCompleted) {
                _isScanning.value = true
                try {
                    mediaScanner.performFullScan()
                } finally {
                    _isScanning.value = false
                }
            } else if (!scanPreferences.isExifExtractionCompleted) {
                mediaScanner.recoverExifExtraction()
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

    // ==================== 筛选操作 ====================

    fun toggleFavoritesOnly() {
        _filterState.value = _filterState.value
            .withFavoritesOnly(!_filterState.value.favoritesOnly)
    }

    fun setFavoritesOnly(enabled: Boolean) {
        if (_filterState.value.favoritesOnly != enabled) {
            _filterState.value = _filterState.value.withFavoritesOnly(enabled)
        }
    }

    /**
     * 切换某个维度上的一个取值（维度内多选）
     * 通用实现，新增维度无需新增 toggle 方法
     */
    fun toggleDimension(id: FilterDimensionId, key: String) {
        _filterState.value = _filterState.value.toggle(id, key)
        schedulePrune(id)
    }

    fun clearDimension(id: FilterDimensionId) {
        _filterState.value = _filterState.value.clearDimension(id)
    }

    fun clearDimensionFilters() {
        _filterState.value = _filterState.value.clearDimensions()
    }

    /** 清除全部条件：收藏 + 各维度（搜索由搜索栏自身负责退出） */
    fun clearAllFilters() {
        _filterState.value = _filterState.value
            .clearDimensions()
            .withFavoritesOnly(false)
    }

    fun clearExcludedAlbums() {
        excludedAlbumManager.setAllExcluded(emptySet())
    }

    fun setSearchQuery(query: String?) {
        _filterState.value = _filterState.value.withSearchQuery(query)
    }

    /**
     * 上游维度变化后，异步裁剪下游维度中已失效的选择
     *
     * 先同步更新状态保证 UI 立即响应，再在后台线程做裁剪，
     * 只有确实产生变化时才二次发射。
     */
    private var pruneJob: Job? = null

    private fun schedulePrune(changed: FilterDimensionId) {
        if (FilterDimensions.descendantsOf(changed).isEmpty()) return
        pruneJob?.cancel()
        pruneJob = viewModelScope.launch(Dispatchers.Default) {
            val candidates = filterCandidates.value
            if (candidates.isEmpty()) return@launch
            val current = _filterState.value
            val pruned = current.pruneUnavailable(candidates)
            if (pruned != current) {
                _filterState.value = pruned
            }
        }
    }

    /**
     * 权限授权后重新扫描
     */
    fun rescanAfterPermissionGranted() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                mediaScanner.performFullScan()
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * 触发增量扫描（由 ContentObserver 调用）
     * 扫描完成后 Room Flow 自动推送更新
     */
    fun requestIncrementalScan() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                mediaScanner.performIncrementalScan()
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** 静默增量扫描：不触发 _isScanning UI，用于后台补盲，仅维护轻量 _isSyncing 提示 */
    fun silentRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                mediaScanner.performIncrementalScan()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * 从 MediaStore 精确同步指定 ID 的照片到 Room
     * 用于回收站恢复后回写，不依赖时间戳
     * Room Flow 自动推送更新
     */
    fun syncPhotosFromMediaStore(photoIds: List<Long>) {
        if (photoIds.isEmpty()) return
        viewModelScope.launch {
            mediaScanner.syncSpecificPhotos(photoIds)
        }
    }

    /**
     * 更新收藏状态
     */
    fun updateFavorite(photoIds: List<Long>, isFavorite: Boolean) {
        viewModelScope.launch {
            mediaScanner.updateFavorite(photoIds, isFavorite)
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
        }
    }

    /**
     * 删除照片
     * Room Flow 自动推送更新
     */
    fun deletePhotos(photoIds: List<Long>) {
        viewModelScope.launch {
            mediaScanner.deletePhotos(photoIds)
        }
    }

    /**
     * 带日期分组的照片列表 StateFlow
     * 数据源：Room Flow（自动监听表变化推送更新）
     * 全量加载后在内存中排序、过滤、插入日期分隔符，适配 < 5w 张照片场景
     */
    private val baseFilteredList: StateFlow<List<PhotoEntity>> = combine(
        filterCandidates,
        dimensionFilterState
    ) { candidates, state ->
        withContext(Dispatchers.Default) {
            candidates.filter { state.matches(it) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val sortedPhotos: StateFlow<Pair<List<Photo>, MediaRepository.SortType>> = combine(
        baseFilteredList,
        _currentSortType,
        searchQuery
    ) { filtered, sortType, query ->
        withContext(Dispatchers.Default) {
            val queryResult = if (!query.isNullOrBlank()) {
                val lowered = query.lowercase()
                filtered.filter { entity ->
                    entity.displayName?.lowercase()?.contains(lowered) == true ||
                        entity.bucketName.lowercase().contains(lowered)
                }
            } else {
                filtered
            }
            val photos = queryResult.map { it.toPhoto() }
            val sorted = SortUtils.sortPhotos(photos, sortType)
            sorted to sortType
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Photo>() to MediaRepository.SortType.DATE_TAKEN)

    val photoListFlow: StateFlow<List<PhotoModel>> = sortedPhotos
        .combine(_currentGroupType) { (sorted, sortType), groupType ->
            withContext(Dispatchers.Default) {
                buildPhotoModelList(sorted, sortType, groupType)
            }
        }
        .let { flow ->
            var emissionCount = 0
            flow.debounce {
                emissionCount++
                if (emissionCount <= 2) 0L else 300L
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredPhotoCount: StateFlow<Int> = photoListFlow.map { models ->
        models.count { it is PhotoModel.PhotoItem }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private fun buildPhotoModelList(
        sortedPhotos: List<Photo>,
        sortType: MediaRepository.SortType,
        groupType: GroupType
    ): List<PhotoModel> {
        if (sortedPhotos.isEmpty()) return emptyList()
        val result = mutableListOf<PhotoModel>()
        var lastDateText: String? = null
        for (photo in sortedPhotos) {
            val dateText = DateUtils.formatDateText(photo, sortType, groupType)
            if (dateText != lastDateText) {
                result.add(PhotoModel.SeparatorItem(dateText))
                lastDateText = dateText
            }
            result.add(PhotoModel.PhotoItem(photo))
        }
        return result
    }

    /**
     * 更新隐藏状态
     */
    fun updateHidden(photoIds: List<Long>, isHidden: Boolean) {
        viewModelScope.launch {
            photoDao.updateHiddenBatch(photoIds, isHidden)
        }
    }

    fun getCurrentPhotoCount(): Int = filteredPhotoCount.value

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
            isFavorite = isFavorite,
            isHidden = isHidden,
            isHdr = isHdr,
            thumbnailPath = thumbnailPath,
            displayName = displayName
        )
    }
}
