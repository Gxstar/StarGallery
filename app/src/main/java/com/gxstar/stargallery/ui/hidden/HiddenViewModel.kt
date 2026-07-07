package com.gxstar.stargallery.ui.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.model.Photo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HiddenViewModel @Inject constructor(
    private val photoDao: PhotoDao
) : ViewModel() {

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            photoDao.getAllPhotosFlow().collect { entities ->
                _photos.value = entities.filter { it.isHidden }.map {
                    Photo(
                        id = it.id,
                        uri = android.net.Uri.parse(it.uri),
                        dateTaken = it.dateTaken,
                        dateModified = it.dateModified,
                        dateAdded = it.dateAdded,
                        mimeType = it.mimeType,
                        width = it.width,
                        height = it.height,
                        size = it.size,
                        bucketId = it.bucketId,
                        bucketName = it.bucketName,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        orientation = it.orientation,
                        isFavorite = it.isFavorite,
                        isHidden = it.isHidden,
                        isHdr = it.isHdr,
                        thumbnailPath = it.thumbnailPath
                    )
                }
            }
        }
    }

    fun getPhotoCount(): Int = _photos.value.size

    fun unhidePhotos(photoIds: List<Long>) {
        viewModelScope.launch {
            photoDao.updateHiddenBatch(photoIds, false)
        }
    }
}
