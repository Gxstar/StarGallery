package com.gxstar.stargallery.ui.detail

import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.util.SortUtils
import com.gxstar.stargallery.ui.util.DateUtils
import com.gxstar.stargallery.util.ExcludedAlbumManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val photoDao: PhotoDao,
    private val excludedAlbumManager: ExcludedAlbumManager,
    private val photoDetailListCache: PhotoDetailListCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 从导航参数获取初始照片和排序方式
    private val initialPhoto: Photo? = savedStateHandle["initialPhoto"]
    private val initialPhotoId: Long = savedStateHandle["photoId"] ?: -1L
    private val sortTypeValue: Int = savedStateHandle["sortType"] ?: 0
    private val bucketId: Long = savedStateHandle["bucketId"] ?: -1L
    private val favoritesOnly: Boolean = savedStateHandle["favoritesOnly"] ?: false
    private val filterCameraMake: Set<String> = parseFilterSet(savedStateHandle["filterCameraMake"])
    private val filterCameraModel: Set<String> = parseFilterSet(savedStateHandle["filterCameraModel"])
    private val filterLensModel: Set<String> = parseFilterSet(savedStateHandle["filterLensModel"])

    private fun parseFilterSet(encoded: String?): Set<String> {
        if (encoded.isNullOrEmpty()) return emptySet()
        return encoded.split("\n").toSet()
    }

    private val sortType = when (sortTypeValue) {
        0 -> MediaRepository.SortType.DATE_TAKEN
        1 -> MediaRepository.SortType.DATE_ADDED
        else -> MediaRepository.SortType.DATE_TAKEN
    }

    // 照片列表
    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    // 当前照片
    private val _currentPhoto = MutableStateFlow<Photo?>(null)
    val currentPhoto: StateFlow<Photo?> = _currentPhoto.asStateFlow()

    // 当前位置
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    // 当前浏览的照片 ID（用于 Flow 观察数据库更新）
    private val _currentPhotoId = MutableStateFlow(initialPhotoId)

    // 日期文本
    private val _dateText = MutableStateFlow("")
    val dateText: StateFlow<String> = _dateText.asStateFlow()

    // 信息文本
    private val _infoText = MutableStateFlow("")
    val infoText: StateFlow<String> = _infoText.asStateFlow()

    // 加载状态
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 用户是否已经手动滑动过位置（用于防止后台加载完成后重置位置）
    private var _userHasMovedPosition = false

    // 初始加载是否已完成（加载完成前 ViewPager 的 onPageSelected 回调不算用户操作）
    private var _initialLoadComplete = false

    init {
        // 优先读取网格页写入的缓存列表，实现打开即可左右滑动
        val cachedPhotos = photoDetailListCache.take(initialPhotoId)
        if (cachedPhotos != null) {
            val initialPos = cachedPhotos.indexOfFirst { it.id == initialPhotoId }.takeIf { it >= 0 } ?: 0
            _photos.value = cachedPhotos
            _currentPosition.value = initialPos
            _currentPhoto.value = cachedPhotos[initialPos]
            _currentPhotoId.value = cachedPhotos[initialPos].id
            updateDateInfo(cachedPhotos[initialPos])
            _isLoading.value = false
            _initialLoadComplete = true
            // 后台仍然刷新，避免缓存与数据库短暂不一致，但不阻塞滑动
            loadPhotosInBackground()
        } else {
            // 立即显示初始照片，不等待全部加载
            initialPhoto?.let { photo ->
                _currentPhoto.value = photo
                _photos.value = listOf(photo)
                updateDateInfo(photo)
            }
            // 后台渐进加载所有照片，加载完成后自动刷新列表
            loadPhotosInBackground()
        }

        // 响应式观察当前照片的数据库变更（例如 EXIF 扫描完成更新 dateTaken 后自动刷新日期显示）
        viewModelScope.launch {
            _currentPhotoId.flatMapLatest { id ->
                photoDao.getPhotoByIdFlow(id)
            }.collectLatest { entity ->
                if (entity != null) {
                    _dateText.value = DateUtils.formatDate(entity.dateTaken)
                    _infoText.value = DateUtils.formatTime(entity.dateTaken)
                }
            }
        }
    }

    private fun loadPhotosInBackground() {
        viewModelScope.launch {
            val allPhotos = withContext(Dispatchers.Default) {
                if (bucketId != -1L) {
                    val photos = mediaRepository.getPhotosByBucket(bucketId, sortType)
                    val hiddenIds = photoDao.getHiddenPhotoIds().toSet()
                    SortUtils.sortPhotos(photos.filter { it.id !in hiddenIds }, sortType)
                } else if (favoritesOnly || filterCameraMake.isNotEmpty() || filterCameraModel.isNotEmpty() || filterLensModel.isNotEmpty()) {
                    val entities = photoDao.getAllPhotos()
                    var filtered = entities
                    if (favoritesOnly) filtered = filtered.filter { it.isFavorite }
                    filtered = filtered.filter { !it.isHidden }
                    val excludedIds = excludedAlbumManager.excludedBucketIds.value
                    filtered = filtered.filter { it.bucketId !in excludedIds }
                    if (filterCameraMake.isNotEmpty()) {
                        filtered = filtered.filter { entity ->
                            entity.cameraMake in filterCameraMake ||
                                ("" in filterCameraMake && entity.cameraMake.isNullOrBlank())
                        }
                    }
                    if (filterCameraModel.isNotEmpty()) {
                        filtered = filtered.filter { entity ->
                            entity.cameraModel in filterCameraModel ||
                                ("" in filterCameraModel && entity.cameraModel.isNullOrBlank())
                        }
                    }
                    if (filterLensModel.isNotEmpty()) {
                        filtered = filtered.filter { entity ->
                            entity.lensModel in filterLensModel ||
                                ("" in filterLensModel && entity.lensModel.isNullOrBlank())
                        }
                    }
                    val photos = filtered.map { it.toPhoto() }
                    SortUtils.sortPhotos(photos, sortType)
                } else if (initialPhoto?.isHidden == true) {
                    // 从隐藏页进入 → 只显示隐藏照片
                    val entities = photoDao.getAllPhotos()
                    val photos = entities.filter { it.isHidden }.map { it.toPhoto() }
                    SortUtils.sortPhotos(photos, sortType)
                } else {
                    // 从首页进入 → 排除隐藏照片 + 排除被排除的相册
                    val entities = photoDao.getAllPhotos()
                    val excludedIds = excludedAlbumManager.excludedBucketIds.value
                    val photos = entities
                        .filter { !it.isHidden }
                        .filter { it.bucketId !in excludedIds }
                        .map { it.toPhoto() }
                    SortUtils.sortPhotos(photos, sortType)
                }
            }

            if (allPhotos.isNotEmpty()) {
                val initialPos = allPhotos.indexOfFirst { it.id == initialPhotoId }.takeIf { it >= 0 } ?: 0
                _photos.value = allPhotos
                if (!_userHasMovedPosition) {
                    _currentPosition.value = initialPos
                    _currentPhoto.value = allPhotos[initialPos]
                    updateDateInfo(allPhotos[initialPos])
                }
                _initialLoadComplete = true
            }
            _isLoading.value = false
        }
    }

    /**
     * 更新当前位置
     */
    fun setPosition(position: Int) {
        val photoList = _photos.value
        if (position in photoList.indices) {
            if (_initialLoadComplete) {
                _userHasMovedPosition = true
            }
            _currentPosition.value = position
            val photo = photoList[position]
            _currentPhoto.value = photo
            _currentPhotoId.value = photo.id
            updateDateInfo(photo)
        }
    }

    private fun updateDateInfo(photo: Photo) {
        _dateText.value = DateUtils.formatDate(photo.dateTaken)
        _infoText.value = DateUtils.formatTime(photo.dateTaken)
    }

    fun prepareToggleFavorite(): IntentSender? {
        var intentSender: IntentSender? = null
        _currentPhoto.value?.let { photo ->
            val newFavoriteState = !photo.isFavorite
            intentSender = mediaRepository.setFavorite(listOf(photo), newFavoriteState)
            if (intentSender != null) {
                _pendingFavoritePhoto = photo
                _pendingFavoriteState = newFavoriteState
            }
        }
        return intentSender
    }

    private var _pendingFavoritePhoto: Photo? = null
    private var _pendingFavoriteState: Boolean = false

    fun onFavoriteConfirmed() {
        _pendingFavoritePhoto?.let { photo ->
            val updatedPhoto = photo.copy(isFavorite = _pendingFavoriteState)
            _currentPhoto.value = updatedPhoto

            // 同步更新 _photos 列表，防止滑动回来后 setPosition 覆写 _currentPhoto
            val updatedList = _photos.value.toMutableList()
            val index = updatedList.indexOfFirst { it.id == photo.id }
            if (index >= 0) {
                updatedList[index] = updatedPhoto
                _photos.value = updatedList
            }

            // 同步到 Room，确保网格列表立即反映收藏变更
            viewModelScope.launch {
                photoDao.updateFavoriteBatch(listOf(photo.id), _pendingFavoriteState)
            }
        }
        _pendingFavoritePhoto = null
        _pendingFavoriteState = false
    }

    fun deletePhoto(onResult: (IntentSender?) -> Unit) {
        _currentPhoto.value?.let { photo ->
            val intentSender = mediaRepository.deletePhoto(photo)
            onResult(intentSender)
        }
    }

    fun hideCurrentPhoto() {
        _currentPhoto.value?.let { photo ->
            viewModelScope.launch {
                photoDao.updateHiddenBatch(listOf(photo.id), true)
            }
        }
    }

    /**
     * 编辑页面返回后刷新当前照片数据
     * （覆盖原图时 MediaStore 的 DATE_MODIFIED 会变，需要从 Room 重新读取）
     */
    fun refreshCurrentPhoto() {
        viewModelScope.launch {
            val id = _currentPhotoId.value
            if (id <= 0) return@launch
            val entity = photoDao.getPhotoByIdFlow(id).first { it != null } ?: return@launch
            val updated = entity.toPhoto()
            _currentPhoto.value = updated
            val list = _photos.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = updated
                _photos.value = list
            }
        }
    }

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
            thumbnailPath = thumbnailPath
        )
    }

    /**
     * 从列表中移除指定位置的照片
     * @param position 要移除的照片位置
     * @return 是否还有剩余照片（如果返回 false 表示已删除最后一张，需要返回列表页）
     */
    fun removeCurrentPhoto(position: Int): Boolean {
        val currentList = _photos.value.toMutableList()

        if (position !in currentList.indices) return false

        currentList.removeAt(position)
        _photos.value = currentList

        if (currentList.isEmpty()) {
            return false
        }

        val newPosition = if (position >= currentList.size) {
            currentList.size - 1
        } else {
            position
        }

        _currentPosition.value = newPosition
        val newPhoto = currentList[newPosition]
        _currentPhoto.value = newPhoto
        _currentPhotoId.value = newPhoto.id
        updateDateInfo(newPhoto)

        return true
    }

    /**
     * 获取初始位置
     */
    fun getInitialPosition(): Int {
        return _photos.value.indexOfFirst { it.id == initialPhotoId }.takeIf { it >= 0 } ?: 0
    }

}
