package com.gxstar.stargallery.ui.albums

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.GroupType
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.util.DateUtils
import com.gxstar.stargallery.ui.util.SortUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _albumId = MutableStateFlow(-1L)
    val albumId: StateFlow<Long> = _albumId.asStateFlow()

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _photoList = MutableStateFlow<List<PhotoModel>>(emptyList())
    val photoListFlow: StateFlow<List<PhotoModel>> = _photoList.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                _photos,
                _currentSortType,
                _currentGroupType
            ) { photos, sortType, groupType ->
                buildPhotoModelList(photos, sortType, groupType)
            }.collect { list ->
                _photoList.value = list
            }
        }
    }

    private fun buildPhotoModelList(
        photos: List<Photo>,
        sortType: MediaRepository.SortType,
        groupType: GroupType
    ): List<PhotoModel> {
        if (photos.isEmpty()) return emptyList()

        val sortedPhotos = SortUtils.sortPhotos(photos, sortType)
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

    fun setAlbumId(albumId: Long) {
        if (_albumId.value != albumId) {
            _albumId.value = albumId
            loadPhotos()
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

    fun loadPhotos() {
        val currentAlbumId = _albumId.value
        if (currentAlbumId == -1L) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val photos = mediaRepository.getPhotosByBucket(currentAlbumId)
                _photos.value = photos
                _photoCount.value = photos.size
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        loadPhotos()
    }
}