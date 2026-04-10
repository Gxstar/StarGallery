package com.gxstar.stargallery.ui.compose.photos

import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.paging.MediaStorePagingSource
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
class PhotosViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 60
        private const val PREFETCH_DISTANCE = 30
    }

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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCounts()
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

    fun loadCounts() {
        viewModelScope.launch {
            _photoCount.value = mediaRepository.getPhotoCount()
        }
        viewModelScope.launch {
            _favoriteCount.value = mediaRepository.getFavoriteCount()
        }
    }

    /**
     * 基础照片数据流
     */
    private val basePhotoPagingFlow: Flow<PagingData<PhotoModel.PhotoItem>> = _currentSortType
        .flatMapLatest { sortType ->
            Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    enablePlaceholders = false,
                    initialLoadSize = PAGE_SIZE * 3,
                    prefetchDistance = PREFETCH_DISTANCE
                ),
                pagingSourceFactory = {
                    MediaStorePagingSource(
                        contentResolver = context.contentResolver,
                        sortType = sortType
                    )
                }
            ).flow
                .map { pagingData ->
                    pagingData.map { photo -> PhotoModel.PhotoItem(photo) }
                }
                .cachedIn(viewModelScope)
        }

    /**
     * 带日期分组和收藏筛选的照片数据流
     */
    val photoPagingFlow: Flow<PagingData<PhotoModel>> = combine(
        basePhotoPagingFlow,
        _currentSortType,
        _currentGroupType,
        _showFavoritesOnly
    ) { pagingData, sortType, groupType, showFavoritesOnly ->
        val filteredData = if (showFavoritesOnly) {
            pagingData.filter { it.photo.isFavorite }
        } else {
            pagingData
        }

        filteredData.insertSeparators { before, after ->
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
        loadCounts()
    }

    fun getCurrentPhotoCount(): Int {
        return if (_showFavoritesOnly.value) {
            _favoriteCount.value
        } else {
            _photoCount.value
        }
    }

    fun setFavorite(photos: List<Photo>, isFavorite: Boolean): IntentSender? {
        return mediaRepository.setFavorite(photos, isFavorite)
    }

    fun toggleMixedFavorite(photos: List<Photo>): IntentSender? {
        val hasFavorite = photos.any { !it.isFavorite }
        val isFavorite = hasFavorite
        return mediaRepository.setFavorite(photos, isFavorite)
    }

    fun trashPhotos(photos: List<Photo>): IntentSender? {
        return mediaRepository.trashPhotos(photos)
    }

    fun deletePhotos(photos: List<Photo>): IntentSender? {
        return mediaRepository.deletePhotos(photos)
    }
}
