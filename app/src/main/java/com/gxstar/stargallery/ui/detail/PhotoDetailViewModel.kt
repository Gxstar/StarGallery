package com.gxstar.stargallery.ui.detail

import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.entity.MediaMetadata
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.data.repository.MetadataRepository
import com.gxstar.stargallery.ui.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val metadataRepository: MetadataRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 从导航参数获取初始照片ID和排序方式
    private val initialPhotoId: Long = savedStateHandle["photoId"] ?: -1L
    private val sortTypeValue: Int = savedStateHandle["sortType"] ?: 0
    private val bucketId: Long = savedStateHandle["bucketId"] ?: -1L

    private val sortType = when (sortTypeValue) {
        0 -> MetadataRepository.SortType.DATE_TAKEN
        1 -> MetadataRepository.SortType.DATE_ADDED
        else -> MetadataRepository.SortType.DATE_TAKEN
    }

    // 元数据列表
    private val _metadataList = MutableStateFlow<List<MediaMetadata>>(emptyList())
    val metadataList: StateFlow<List<MediaMetadata>> = _metadataList.asStateFlow()

    // 照片列表（从元数据转换）
    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    // 当前照片
    private val _currentPhoto = MutableStateFlow<Photo?>(null)
    val currentPhoto: StateFlow<Photo?> = _currentPhoto.asStateFlow()
    
    // 当前元数据
    private val _currentMetadata = MutableStateFlow<MediaMetadata?>(null)
    val currentMetadata: StateFlow<MediaMetadata?> = _currentMetadata.asStateFlow()

    // 当前位置
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    // 日期文本
    private val _dateText = MutableStateFlow("")
    val dateText: StateFlow<String> = _dateText.asStateFlow()

    // 信息文本
    private val _infoText = MutableStateFlow("")
    val infoText: StateFlow<String> = _infoText.asStateFlow()

    // 加载状态
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPhotos()
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true

            // 检查是否使用元数据库
            val useMetadataDb = !metadataRepository.needsScan()
            
            if (useMetadataDb) {
                // 从元数据库加载
                loadFromMetadataDatabase()
            } else {
                // 从 MediaStore 加载（降级方案）
                loadFromMediaStore()
            }
            
            _isLoading.value = false
        }
    }
    
    private suspend fun loadFromMetadataDatabase() {
        // TODO: 实现从数据库按相册和排序查询
        // 目前先用 MediaStore 获取 ID 列表，然后用数据库查询详情
        loadFromMediaStore()
        
        // 更新当前元数据
        _currentPhoto.value?.let { photo ->
            _currentMetadata.value = metadataRepository.getMetadataById(photo.id)
        }
    }
    
    private suspend fun loadFromMediaStore() {
        val mediaSortType = when (sortType) {
            MetadataRepository.SortType.DATE_TAKEN -> MediaRepository.SortType.DATE_TAKEN
            MetadataRepository.SortType.DATE_ADDED -> MediaRepository.SortType.DATE_ADDED
        }
        
        // 根据 bucketId 决定加载全部照片还是相册内照片
        val allPhotos = if (bucketId > 0) {
            mediaRepository.getPhotosByBucket(bucketId, mediaSortType)
        } else {
            mediaRepository.getAllMedia(mediaSortType)
        }
        _photos.value = allPhotos

        // 找到初始照片的位置
        val initialPosition = allPhotos.indexOfFirst { it.id == initialPhotoId }
        if (initialPosition >= 0) {
            _currentPosition.value = initialPosition
            val photo = allPhotos[initialPosition]
            _currentPhoto.value = photo
            updateDateInfo(photo)
            
            // 异步加载元数据
            loadMetadataForPhoto(photo.id)
        }
    }
    
    private fun loadMetadataForPhoto(photoId: Long) {
        viewModelScope.launch {
            val metadata = metadataRepository.getMetadataById(photoId)
            _currentMetadata.value = metadata
            
            // 元数据加载完成后，更新日期显示（使用数据库中的准确时间）
            metadata?.let {
                updateDateInfoFromMetadata(it)
            }
        }
    }

    /**
     * 更新当前位置
     */
    fun setPosition(position: Int) {
        val photoList = _photos.value
        if (position in photoList.indices) {
            _currentPosition.value = position
            val photo = photoList[position]
            _currentPhoto.value = photo
            updateDateInfo(photo)
            
            // 加载当前位置的元数据（加载完成后会自动更新日期）
            loadMetadataForPhoto(photo.id)
        }
    }

    private fun updateDateInfo(photo: Photo) {
        _dateText.value = DateUtils.formatDate(photo.dateTaken)
        _infoText.value = DateUtils.formatTime(photo.dateTaken)
    }
    
    /**
     * 使用数据库元数据更新日期信息
     * 优先使用 EXIF 原始时间 (dateTakenOriginal)
     */
    private fun updateDateInfoFromMetadata(metadata: MediaMetadata) {
        // 优先级：EXIF DateTimeOriginal > dateTaken > photo.dateTaken
        val dateTaken = when {
            metadata.dateTakenOriginal != null && metadata.dateTakenOriginal > 0 -> metadata.dateTakenOriginal
            metadata.dateTaken > 0 -> metadata.dateTaken
            else -> return // 没有有效时间，不更新
        }
        
        _dateText.value = DateUtils.formatDate(dateTaken)
        _infoText.value = DateUtils.formatTime(dateTaken)
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
            
            // 更新元数据库中的收藏状态（异步）
            viewModelScope.launch {
                metadataRepository.updateFavorite(photo.id, _pendingFavoriteState)
            }
            
            val currentList = _photos.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == photo.id }
            if (index >= 0) {
                currentList[index] = updatedPhoto
                _photos.value = currentList
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
    
    /**
     * 从列表中移除指定位置的照片
     * @param position 要移除的照片位置
     * @return 是否还有剩余照片（如果返回 false 表示已删除最后一张，需要返回列表页）
     */
    fun removeCurrentPhoto(position: Int): Boolean {
        val currentList = _photos.value.toMutableList()
        
        if (position !in currentList.indices) return false
        
        // 移除照片
        currentList.removeAt(position)
        _photos.value = currentList
        
        // 如果列表为空，返回 false 表示需要退出
        if (currentList.isEmpty()) {
            return false
        }
        
        // 计算新位置
        val newPosition = if (position >= currentList.size) {
            // 如果删除的是最后一张，则移动到新的最后一张
            currentList.size - 1
        } else {
            // 否则保持当前位置（此时已经是下一张照片了）
            position
        }
        
        // 更新当前照片
        _currentPosition.value = newPosition
        _currentPhoto.value = currentList[newPosition]
        updateDateInfo(currentList[newPosition])
        
        return true
    }
    
    /**
     * 获取初始位置
     */
    fun getInitialPosition(): Int {
        return _photos.value.indexOfFirst { it.id == initialPhotoId }.takeIf { it >= 0 } ?: 0
    }
}