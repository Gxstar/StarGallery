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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GroupType {
    DAY, MONTH, YEAR
}

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

    init {
        // 初始化时执行一次全量扫描
        viewModelScope.launch {
            _isScanning.value = true
            mediaScanner.performFullScan()
            loadCounts()
            _isScanning.value = false
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
    val photoListFlow: StateFlow<List<PhotoModel>> = combine(
        photoDao.getAllPhotosFlow(),
        _currentSortType,
        _showFavoritesOnly,
        _currentGroupType
    ) { entities, sortType, favoritesOnly, groupType ->
        var filtered = entities
        if (favoritesOnly) filtered = filtered.filter { it.isFavorite }
        filtered = filtered.filter { !it.isHidden }
        val photos = filtered.map { it.toPhoto() }
        val sortedPhotos = SortUtils.sortPhotos(photos, sortType)
        buildPhotoModelList(sortedPhotos, sortType, groupType)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            isFavorite = isFavorite,
            isHidden = isHidden
        )
    }
}
