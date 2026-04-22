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

    // 从导航参数获取初始照片和排序方式
    private val initialPhoto: Photo? = savedStateHandle["initialPhoto"]
    private val initialPhotoId: Long = savedStateHandle["photoId"] ?: -1L
    private val sortTypeValue: Int = savedStateHandle["sortType"] ?: 0
    private val bucketId: Long = savedStateHandle["bucketId"] ?: -1L

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

    // 用户是否已经手动滑动过位置（用于防止后台加载完成后重置位置）
    private var _userHasMovedPosition = false

    init {
        // 立即显示初始照片，不等待全部加载
        initialPhoto?.let { photo ->
            _currentPhoto.value = photo
            _photos.value = listOf(photo)
            updateDateInfo(photo)
        }
        // 后台渐进加载所有照片，加载完成后自动刷新列表
        loadPhotosInBackground()
    }

    private fun loadPhotosInBackground() {
        viewModelScope.launch {
            val allPhotos = if (bucketId != -1L) {
                mediaRepository.getPhotosByBucket(bucketId, sortType)
            } else {
                mediaRepository.getAllMedia(sortType)
            }

            if (allPhotos.isNotEmpty()) {
                val initialPos = allPhotos.indexOfFirst { it.id == initialPhotoId }.takeIf { it >= 0 } ?: 0
                // 仅在用户未主动滑动过的情况下才更新位置，否则保持用户在详情页的手动滑动位置
                _photos.value = allPhotos
                if (!_userHasMovedPosition) {
                    _currentPosition.value = initialPos
                    _currentPhoto.value = allPhotos[initialPos]
                    updateDateInfo(allPhotos[initialPos])
                }
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
            _userHasMovedPosition = true
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
