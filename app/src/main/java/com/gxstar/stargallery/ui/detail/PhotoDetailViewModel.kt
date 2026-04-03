package com.gxstar.stargallery.ui.detail

import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 从导航参数获取初始照片ID和排序方式
    private val initialPhotoId: Long = savedStateHandle["photoId"] ?: -1L
    private val sortTypeValue: Int = savedStateHandle["sortType"] ?: 0
    
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
            
            // 加载所有媒体（图片+视频）
            val allPhotos = mediaRepository.getAllMedia(sortType)
            _photos.value = allPhotos
            
            // 找到初始照片的位置
            val initialPosition = allPhotos.indexOfFirst { it.id == initialPhotoId }
            if (initialPosition >= 0) {
                _currentPosition.value = initialPosition
                val photo = allPhotos[initialPosition]
                _currentPhoto.value = photo
                updateDateInfo(photo)
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
            _currentPosition.value = position
            val photo = photoList[position]
            _currentPhoto.value = photo
            updateDateInfo(photo)
        }
    }

    private fun updateDateInfo(photo: Photo) {
        _dateText.value = DateUtils.formatDate(photo.dateTaken)
        _infoText.value = DateUtils.formatTime(photo.dateTaken)
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            _currentPhoto.value?.let { photo ->
                val success = mediaRepository.toggleFavoriteDirect(photo)
                if (success) {
                    val updatedPhoto = photo.copy(isFavorite = !photo.isFavorite)
                    _currentPhoto.value = updatedPhoto
                    
                    // 更新列表中的照片
                    val currentList = _photos.value.toMutableList()
                    val index = currentList.indexOfFirst { it.id == photo.id }
                    if (index >= 0) {
                        currentList[index] = updatedPhoto
                        _photos.value = currentList
                    }
                }
            }
        }
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
