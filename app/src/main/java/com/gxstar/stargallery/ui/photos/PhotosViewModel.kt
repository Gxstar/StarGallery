package com.gxstar.stargallery.ui.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.gxstar.stargallery.data.paging.PhotoPagingSource
import com.gxstar.stargallery.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 60
    }

    private val _currentSortType = MutableStateFlow(MediaRepository.SortType.DATE_TAKEN)
    val currentSortType: StateFlow<MediaRepository.SortType> = _currentSortType.asStateFlow()

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount.asStateFlow()

    // 缓存的 Paging Flow，只创建一次
    private var cachedPagingFlow: Flow<PagingData<PhotoWithHeader>>? = null
    private var cachedSortType: MediaRepository.SortType? = null

    init {
        loadPhotoCount()
    }

    fun setSortType(sortType: MediaRepository.SortType) {
        if (_currentSortType.value != sortType) {
            _currentSortType.value = sortType
            // 清除缓存，下次获取时重新创建
            cachedPagingFlow = null
            cachedSortType = null
        }
    }

    fun loadPhotoCount() {
        viewModelScope.launch {
            _photoCount.value = mediaRepository.getPhotoCount()
        }
    }

    /**
     * 获取带日期分组的Paging Flow
     * 使用缓存机制，确保同一个排序类型下返回同一个 Flow
     */
    fun getPhotoPagingFlow(): Flow<PagingData<PhotoWithHeader>> {
        // 如果排序类型改变或没有缓存，创建新的 Flow
        if (cachedPagingFlow == null || cachedSortType != _currentSortType.value) {
            cachedSortType = _currentSortType.value
            cachedPagingFlow = Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    enablePlaceholders = false,
                    initialLoadSize = PAGE_SIZE * 2,
                    prefetchDistance = 30
                ),
                pagingSourceFactory = {
                    PhotoPagingSource(context, _currentSortType.value)
                }
            ).flow.cachedIn(viewModelScope)
        }
        return cachedPagingFlow!!
    }

    fun refresh() {
        loadPhotoCount()
    }
}