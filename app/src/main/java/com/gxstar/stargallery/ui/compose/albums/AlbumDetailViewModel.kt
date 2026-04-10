package com.gxstar.stargallery.ui.compose.albums

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.paging.InMemoryPhotoPagingSource
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.GroupType
import com.gxstar.stargallery.ui.photos.PhotoModel
import com.gxstar.stargallery.ui.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 60
    }

    private val _albumId = MutableStateFlow(-1L)
    val albumId: StateFlow<Long> = _albumId.asStateFlow()

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _currentGroupType = MutableStateFlow(GroupType.DAY)
    val currentGroupType: StateFlow<GroupType> = _currentGroupType.asStateFlow()

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount.asStateFlow()

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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

    private fun createPagerFlow(photos: List<Photo>, sortType: MediaRepository.SortType): Flow<PagingData<PhotoModel.PhotoItem>> {
        val sortedPhotos = if (photos.isEmpty()) {
            emptyList()
        } else {
            when (sortType) {
                MediaRepository.SortType.DATE_TAKEN -> {
                    photos.sortedByDescending { photo ->
                        if (photo.dateTaken > 0) photo.dateTaken else (photo.dateAdded * 1000L)
                    }
                }
                MediaRepository.SortType.DATE_ADDED -> {
                    photos.sortedByDescending { it.dateAdded }
                }
            }
        }

        val totalSize = sortedPhotos.size

        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = if (totalSize > 0) totalSize else PAGE_SIZE * 2,
                prefetchDistance = 30
            ),
            pagingSourceFactory = {
                InMemoryPhotoPagingSource(sortedPhotos)
            }
        ).flow
            .map { pagingData ->
                pagingData.map { photo -> PhotoModel.PhotoItem(photo) }
            }
            .cachedIn(viewModelScope)
    }

    private val basePhotoPagingFlow: Flow<PagingData<PhotoModel.PhotoItem>> = combine(
        _photos,
        _currentSortType
    ) { photos: List<Photo>, sortType: MediaRepository.SortType ->
        Pair(photos, sortType)
    }.flatMapLatest { (photos: List<Photo>, sortType: MediaRepository.SortType) ->
        createPagerFlow(photos, sortType)
    }

    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        basePhotoPagingFlow,
        _currentSortType,
        _currentGroupType
    ) { pagingData, sortType, groupType ->
        pagingData.insertSeparators { before, after ->
            if (after == null) {
                null
            } else if (before == null) {
                PhotoModel.SeparatorItem(DateUtils.formatDateText(context, after.photo, sortType, groupType))
            } else {
                val beforeDate = DateUtils.formatDateText(context, before.photo, sortType, groupType)
                val afterDate = DateUtils.formatDateText(context, after.photo, sortType, groupType)
                if (beforeDate != afterDate) {
                    PhotoModel.SeparatorItem(afterDate)
                } else {
                    null
                }
            }
        }
    }.cachedIn(viewModelScope)

    fun refresh() {
        loadPhotos()
    }
}
