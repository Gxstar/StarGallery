package com.gxstar.stargallery.ui.compose.detail

import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _currentPhoto = MutableStateFlow<Photo?>(null)
    val currentPhoto: StateFlow<Photo?> = _currentPhoto.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentSortType: Int = 0
    private var currentBucketId: Long = -1L

    fun loadPhotos(initialPhotoId: Long, sortType: Int, bucketId: Long) {
        currentSortType = sortType
        currentBucketId = bucketId

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val sortTypeEnum = if (sortType == 0) {
                    MediaRepository.SortType.DATE_TAKEN
                } else {
                    MediaRepository.SortType.DATE_ADDED
                }

                val loadedPhotos = if (bucketId == -1L) {
                    // Load from all photos
                    mediaRepository.getAllMedia(sortTypeEnum)
                } else {
                    // Load from specific album
                    mediaRepository.getPhotosByBucket(bucketId, sortTypeEnum)
                }

                _photos.value = loadedPhotos

                // Find the initial photo
                val initialPhoto = loadedPhotos.find { it.id == initialPhotoId }
                _currentPhoto.value = initialPhoto ?: loadedPhotos.firstOrNull()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setCurrentPhoto(photo: Photo) {
        _currentPhoto.value = photo
    }

    fun refreshCurrentPhoto() {
        viewModelScope.launch {
            _currentPhoto.value?.let { currentPhoto ->
                val updatedPhoto = mediaRepository.getPhotoById(currentPhoto.id)
                if (updatedPhoto != null) {
                    _currentPhoto.value = updatedPhoto
                    // Also update in the list
                    _photos.value = _photos.value.map {
                        if (it.id == updatedPhoto.id) updatedPhoto else it
                    }
                }
            }
        }
    }

    fun toggleFavorite(onNeedsIntentSender: (IntentSender) -> Unit) {
        viewModelScope.launch {
            _currentPhoto.value?.let { photo ->
                val intentSender = mediaRepository.toggleFavorite(photo)
                if (intentSender != null) {
                    onNeedsIntentSender(intentSender)
                } else {
                    // Direct toggle succeeded
                    refreshCurrentPhoto()
                }
            }
        }
    }

    fun trashCurrentPhoto(onNeedsIntentSender: (IntentSender) -> Unit) {
        viewModelScope.launch {
            _currentPhoto.value?.let { photo ->
                val intentSender = mediaRepository.trashPhoto(photo)
                if (intentSender != null) {
                    onNeedsIntentSender(intentSender)
                }
            }
        }
    }

    fun removeCurrentPhoto() {
        val removed = _currentPhoto.value ?: return
        val currentList = _photos.value.toMutableList()
        val removedIndex = currentList.indexOfFirst { it.id == removed.id }

        if (removedIndex >= 0) {
            currentList.removeAt(removedIndex)
            _photos.value = currentList

            // Set the next photo as current
            if (currentList.isNotEmpty()) {
                val nextIndex = if (removedIndex >= currentList.size) {
                    currentList.size - 1
                } else {
                    removedIndex
                }
                _currentPhoto.value = currentList[nextIndex]
            } else {
                _currentPhoto.value = null
            }
        }
    }
}
