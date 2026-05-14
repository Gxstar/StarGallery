package com.gxstar.stargallery.ui.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private val _hiddenCount = MutableStateFlow(0)
    val hiddenCount: StateFlow<Int> = _hiddenCount.asStateFlow()

    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val isSearching: Flow<Boolean> = _searchQuery.map { !it.isNullOrBlank() }

    private val _filterCameraMake = MutableStateFlow<String?>(null)
    val filterCameraMake: StateFlow<String?> = _filterCameraMake.asStateFlow()

    private val _filterCameraModel = MutableStateFlow<String?>(null)
    val filterCameraModel: StateFlow<String?> = _filterCameraModel.asStateFlow()

    private val _filterLensModel = MutableStateFlow<String?>(null)
    val filterLensModel: StateFlow<String?> = _filterLensModel.asStateFlow()

    val isExifFilterActive: StateFlow<Boolean> = combine(
        _filterCameraMake, _filterCameraModel, _filterLensModel
    ) { make, model, lens ->
        make != null || model != null || lens != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 品牌选项 ← 受相机型号和镜头型号影响（双向联动）
    val cameraMakeOptions: StateFlow<List<FilterOption>> =
        combine(_filterCameraModel, _filterLensModel) { model, lens -> model to lens }
            .flatMapLatest { (model, lens) ->
                combine(
                    photoDao.getCameraMakeCountsFlow(model, lens),
                    photoDao.getFilteredCountFlow(null, model, lens)
                ) { counts, total ->
                    buildFilterOptions(counts, total, model == null && lens == null)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 相机型号选项 ← 受品牌和镜头型号影响
    val cameraModelOptions: StateFlow<List<FilterOption>> =
        combine(_filterCameraMake, _filterLensModel) { make, lens -> make to lens }
            .flatMapLatest { (make, lens) ->
                combine(
                    photoDao.getCameraModelCountsFlow(make, lens),
                    photoDao.getFilteredCountFlow(make, null, lens)
                ) { counts, total ->
                    buildFilterOptions(counts, total, make == null && lens == null)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 镜头型号选项 ← 受品牌和相机型号影响
    val lensModelOptions: StateFlow<List<FilterOption>> =
        combine(_filterCameraMake, _filterCameraModel) { make, model -> make to model }
            .flatMapLatest { (make, model) ->
                combine(
                    photoDao.getLensModelCountsFlow(make, model),
                    photoDao.getFilteredCountFlow(make, model, null)
                ) { counts, total ->
                    buildFilterOptions(counts, total, make == null && model == null)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildFilterOptions(
        counts: List<PhotoDao.ExifCount>,
        totalVisible: Int,
        showUnknown: Boolean = true
    ): List<FilterOption> {
        val options = mutableListOf<FilterOption>()
        val knownTotal = counts.sumOf { it.count }
        val unknownCount = (totalVisible - knownTotal).coerceAtLeast(0)

        options.add(FilterOption(null, context.getString(R.string.filter_all), totalVisible))
        counts.forEach { options.add(FilterOption(it.value, it.value, it.count)) }
        if (showUnknown && unknownCount > 0) {
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
            val isFirstLaunch = photoDao.getPhotoCount() == 0
            if (isFirstLaunch) {
                _isScanning.value = true
                mediaScanner.performFullScan()
                _isScanning.value = false
            }
            loadCounts()
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

    fun setFilterCameraMake(make: String?) {
        _filterCameraMake.value = make
    }

    fun setFilterCameraModel(model: String?) {
        _filterCameraModel.value = model
        if (model != null && model != "" && _filterCameraMake.value == null) {
            viewModelScope.launch {
                val inferred = photoDao.inferMakeFromModel(model)
                if (inferred != null && _filterCameraMake.value == null) {
                    _filterCameraMake.value = inferred
                }
            }
        }
    }

    fun setFilterLensModel(lens: String?) {
        _filterLensModel.value = lens
        if (lens != null && lens != "") {
            viewModelScope.launch {
                if (_filterCameraMake.value == null) {
                    val inferredMake = photoDao.inferMakeFromLens(lens)
                    if (inferredMake != null && _filterCameraMake.value == null) {
                        _filterCameraMake.value = inferredMake
                    }
                }
                if (_filterCameraModel.value == null) {
                    val inferredModel = photoDao.inferModelFromLens(lens)
                    if (inferredModel != null && _filterCameraModel.value == null) {
                        _filterCameraModel.value = inferredModel
                    }
                }
            }
        }
    }

    fun clearExifFilters() {
        _filterCameraMake.value = null
        _filterCameraModel.value = null
        _filterLensModel.value = null
    }

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query?.takeIf { it.isNotBlank() }
    }

    fun loadCounts() {
        viewModelScope.launch {
            _photoCount.value = photoDao.getPhotoCount()
            _favoriteCount.value = photoDao.getFavoriteCount()
            _hiddenCount.value = photoDao.getHiddenCount()
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
        }
    }

    /**
     * 触发增量扫描（由 ContentObserver 调用）
     * 扫描完成后 Room Flow 自动推送更新
     */
    fun requestIncrementalScan() {
        viewModelScope.launch {
            _isScanning.value = true
            mediaScanner.performIncrementalScan()
            loadCounts()
            _isScanning.value = false
        }
    }

    fun refreshOnResume() {
        viewModelScope.launch {
            mediaScanner.performIncrementalScan()
            loadCounts()
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
            loadCounts()
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
     * Room Flow 自动推送更新
     */
    fun deletePhotos(photoIds: List<Long>) {
        viewModelScope.launch {
            photoIds.forEach { mediaScanner.deletePhoto(it) }
            loadCounts()
        }
    }

    /**
     * 带日期分组的照片列表 StateFlow
     * 数据源：Room Flow（自动监听表变化推送更新）
     * 全量加载后在内存中排序、过滤、插入日期分隔符，适配 < 5w 张照片场景
     */
    private val exifFilterState: StateFlow<Triple<String?, String?, String?>> = combine(
        _filterCameraMake,
        _filterCameraModel,
        _filterLensModel
    ) { cameraMake, cameraModel, lensModel ->
        Triple(cameraMake, cameraModel, lensModel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(null, null, null))

    val photoListFlow: StateFlow<List<PhotoModel>> = combine(
        photoDao.getAllPhotosFlow(),
        _currentSortType,
        _showFavoritesOnly,
        exifFilterState,
        _currentGroupType
    ) { entities, sortType, favoritesOnly, exifFilters, groupType ->
        val (cameraMake, cameraModel, lensModel) = exifFilters
        var filtered = entities
        if (favoritesOnly) filtered = filtered.filter { it.isFavorite }
        filtered = filtered.filter { !it.isHidden }
        if (cameraMake != null) {
            filtered = if (cameraMake.isEmpty()) {
                filtered.filter { it.cameraMake.isNullOrBlank() }
            } else {
                filtered.filter { it.cameraMake == cameraMake }
            }
        }
        if (cameraModel != null) {
            filtered = if (cameraModel.isEmpty()) {
                filtered.filter { it.cameraModel.isNullOrBlank() }
            } else {
                filtered.filter { it.cameraModel == cameraModel }
            }
        }
        if (lensModel != null) {
            filtered = if (lensModel.isEmpty()) {
                filtered.filter { it.lensModel.isNullOrBlank() }
            } else {
                filtered.filter { it.lensModel == lensModel }
            }
        }
        val photos = filtered.map { it.toPhoto() }
        val sortedPhotos = SortUtils.sortPhotos(photos, sortType)
        buildPhotoModelList(sortedPhotos, sortType, groupType)
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
            loadCounts()
        }
    }

    fun refresh() {
        loadCounts()
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
            isHidden = isHidden
        )
    }
}
