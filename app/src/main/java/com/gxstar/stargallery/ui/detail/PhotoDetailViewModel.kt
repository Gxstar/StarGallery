package com.gxstar.stargallery.ui.detail

import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val photoId: Long = savedStateHandle["photoId"] ?: -1L

    private val _photo = MutableStateFlow<Photo?>(null)
    val photo: StateFlow<Photo?> = _photo.asStateFlow()

    private val _dateText = MutableStateFlow("")
    val dateText: StateFlow<String> = _dateText.asStateFlow()

    private val _infoText = MutableStateFlow("")
    val infoText: StateFlow<String> = _infoText.asStateFlow()

    init {
        loadPhoto()
    }

    private fun loadPhoto() {
        viewModelScope.launch {
            val photo = mediaRepository.getPhotoById(photoId)
            _photo.value = photo
            photo?.let {
                updateDateInfo(it)
            }
        }
    }

    private fun updateDateInfo(photo: Photo) {
        val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
        
        _dateText.value = dateFormat.format(Date(photo.dateTaken))
        _infoText.value = timeFormat.format(Date(photo.dateTaken))
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            _photo.value?.let { photo ->
                val success = mediaRepository.toggleFavoriteDirect(photo)
                if (success) {
                    _photo.value = photo.copy(isFavorite = !photo.isFavorite)
                }
            }
        }
    }

    fun deletePhoto(onResult: (IntentSender?) -> Unit) {
        _photo.value?.let { photo ->
            val intentSender = mediaRepository.deletePhoto(photo)
            onResult(intentSender)
        }
    }
}