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
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.util.DateUtils
import com.gxstar.stargallery.ui.util.SortUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gxstar.stargallery.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class GroupType {
    DAY, MONTH, YEAR
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val photoDao: PhotoDao,
    private val mediaScanner: MediaScanner,
    private val scanPreferences: ScanPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    val photoCount: StateFlow<Int> = photoDao.getPhotoCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val favoriteCount: StateFlow<Int> = photoDao.getFavoriteCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val hiddenCount: StateFlow<Int> = photoDao.getHiddenCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val isSearching: StateFlow<Boolean> = _searchQuery.map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isExtractingExif: StateFlow<Boolean> = mediaScanner.isExtractingExifFlow

    val exifProgress: StateFlow<Float> = mediaScanner.exifProgress

    // 用户显式选择（仅由 toggle 修改，不含级联）
    private val _explicitCameraMake = MutableStateFlow<Set<String>>(emptySet())
    private val _explicitCameraModel = MutableStateFlow<Set<String>>(emptySet())
    private val _explicitLensModel = MutableStateFlow<Set<String>>(emptySet())

    // 有效筛选结果（显式选择 + 级联自动选择）
    private val _filterCameraMake = MutableStateFlow<Set<String>>(emptySet())
    val filterCameraMake: StateFlow<Set<String>> = _filterCameraMake.asStateFlow()

    private val _filterCameraModel = MutableStateFlow<Set<String>>(emptySet())
    val filterCameraModel: StateFlow<Set<String>> = _filterCameraModel.asStateFlow()

    private val _filterLensModel = MutableStateFlow<Set<String>>(emptySet())
    val filterLensModel: StateFlow<Set<String>> = _filterLensModel.asStateFlow()

    val isExifFilterActive: StateFlow<Boolean> = combine(
        _filterCameraMake, _filterCameraModel, _filterLensModel
    ) { make, model, lens ->
        make.isNotEmpty() || model.isNotEmpty() || lens.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val visiblePhotoCount = photoDao.getVisiblePhotoCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val cameraMakeOptions: StateFlow<List<FilterOption>> = combine(
        photoDao.getCameraMakeCountsFlow(), visiblePhotoCount
    ) { counts, total -> buildFilterOptions(counts, total) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cameraModelOptions: StateFlow<List<FilterOption>> = combine(
        photoDao.getCameraModelCountsFlow(), visiblePhotoCount
    ) { counts, total -> buildFilterOptions(counts, total) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lensModelOptions: StateFlow<List<FilterOption>> = combine(
        photoDao.getLensModelCountsFlow(), visiblePhotoCount
    ) { counts, total -> buildFilterOptions(counts, total) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildFilterOptions(
        counts: List<PhotoDao.ExifCount>,
        totalVisible: Int
    ): List<FilterOption> {
        val options = mutableListOf<FilterOption>()
        val knownTotal = counts.sumOf { it.count }
        val unknownCount = (totalVisible - knownTotal).coerceAtLeast(0)

        counts.forEach { options.add(FilterOption(it.value, it.value, it.count)) }
        if (unknownCount > 0) {
            options.add(FilterOption("", context.getString(R.string.filter_unknown_device), unknownCount))
        }
        return options
    }

    data class FilterOption(
        val key: String?,
        val display: String,
        val count: Int
    )

    init {
        viewModelScope.launch {
            if (!scanPreferences.isScanCompleted) {
                _isScanning.value = true
                try {
                    mediaScanner.performFullScan()
                } finally {
                    _isScanning.value = false
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

    fun toggleFilterCameraMake(key: String?) {
        _explicitCameraMake.value = if (key == null) {
            emptySet()
        } else {
            _explicitCameraMake.value.let { current ->
                if (key in current) current - key else current + key
            }
        }
        recomputeEffective()
    }

    fun toggleFilterCameraModel(key: String?) {
        _explicitCameraModel.value = if (key == null) {
            emptySet()
        } else {
            _explicitCameraModel.value.let { current ->
                if (key in current) current - key else current + key
            }
        }
        recomputeEffective()
    }

    fun toggleFilterLensModel(key: String?) {
        _explicitLensModel.value = if (key == null) {
            emptySet()
        } else {
            _explicitLensModel.value.let { current ->
                if (key in current) current - key else current + key
            }
        }
        recomputeEffective()
    }

    fun clearExifFilters() {
        _explicitCameraMake.value = emptySet()
        _explicitCameraModel.value = emptySet()
        _explicitLensModel.value = emptySet()
        _filterCameraMake.value = emptySet()
        _filterCameraModel.value = emptySet()
        _filterLensModel.value = emptySet()
    }

    /**
     * 根据显式选择 + 级联关系，重新计算有效筛选 Sets
     * 镜头→型号：选镜头时自动勾选对应型号
     * 镜头/型号→品牌：选镜头或型号时自动勾选对应品牌
     */
    private var recomputeJob: kotlinx.coroutines.Job? = null

    private fun recomputeEffective() {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            val explicitMakes = _explicitCameraMake.value
            val explicitModels = _explicitCameraModel.value
            val explicitLenses = _explicitLensModel.value

            val impliedMakesFromLens = if (explicitLenses.isNotEmpty()) {
                photoDao.getMakesForLenses(explicitLenses.toList()).toSet()
            } else emptySet()

            val impliedModelsFromLens = if (explicitLenses.isNotEmpty()) {
                photoDao.getModelsForLenses(explicitLenses.toList()).toSet()
            } else emptySet()

            val impliedMakesFromModel = if (explicitModels.isNotEmpty()) {
                photoDao.getMakesForModels(explicitModels.toList()).toSet()
            } else emptySet()

            _filterCameraMake.value = explicitMakes + impliedMakesFromLens + impliedMakesFromModel
            _filterCameraModel.value = explicitModels + impliedModelsFromLens
            _filterLensModel.value = explicitLenses
        }
    }

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query?.takeIf { it.isNotBlank() }
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

    /** 静默增量扫描：不触发 _isScanning UI，用于后台补盲 */
    fun silentRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            mediaScanner.performIncrementalScan()
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
            photoIds.forEach { mediaScanner.deletePhoto(it) }
        }
    }

    /**
     * 带日期分组的照片列表 StateFlow
     * 数据源：Room Flow（自动监听表变化推送更新）
     * 全量加载后在内存中排序、过滤、插入日期分隔符，适配 < 5w 张照片场景
     */
    private val exifFilterState: StateFlow<Triple<Set<String>, Set<String>, Set<String>>> = combine(
        _filterCameraMake,
        _filterCameraModel,
        _filterLensModel
    ) { cameraMake, cameraModel, lensModel ->
        Triple(cameraMake, cameraModel, lensModel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(emptySet(), emptySet(), emptySet()))

    private val baseFilteredList: StateFlow<List<PhotoEntity>> = combine(
        photoDao.getAllPhotosFlow(),
        _currentSortType,
        _showFavoritesOnly,
        exifFilterState
    ) { entities, sortType, favoritesOnly, exifFilters ->
        withContext(Dispatchers.Default) {
            val (cameraMake, cameraModel, lensModel) = exifFilters
            var filtered = entities
            if (favoritesOnly) filtered = filtered.filter { it.isFavorite }
            filtered = filtered.filter { !it.isHidden }
            if (cameraMake.isNotEmpty()) {
                filtered = filtered.filter { entity ->
                    entity.cameraMake in cameraMake ||
                        ("" in cameraMake && entity.cameraMake.isNullOrBlank())
                }
            }
            if (cameraModel.isNotEmpty()) {
                filtered = filtered.filter { entity ->
                    entity.cameraModel in cameraModel ||
                        ("" in cameraModel && entity.cameraModel.isNullOrBlank())
                }
            }
            if (lensModel.isNotEmpty()) {
                filtered = filtered.filter { entity ->
                    entity.lensModel in lensModel ||
                        ("" in lensModel && entity.lensModel.isNullOrBlank())
                }
            }
            filtered
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val photoListFlow: StateFlow<List<PhotoModel>> = combine(
        baseFilteredList,
        _currentSortType,
        _currentGroupType,
        _searchQuery
    ) { filtered, sortType, groupType, searchQuery ->
        withContext(Dispatchers.Default) {
            val queryResult = if (!searchQuery.isNullOrBlank()) {
                val q = searchQuery.lowercase()
                filtered.filter { entity ->
                    entity.displayName?.lowercase()?.contains(q) == true ||
                        entity.bucketName?.lowercase()?.contains(q) == true
                }
            } else {
                filtered
            }
            val photos = queryResult.map { it.toPhoto() }
            val sortedPhotos = SortUtils.sortPhotos(photos, sortType)
            buildPhotoModelList(sortedPhotos, sortType, groupType)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            val dateText = DateUtils.formatDateText(context, photo, sortType, groupType)
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
            thumbnailPath = thumbnailPath
        )
    }
}
