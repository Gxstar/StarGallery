package com.gxstar.stargallery.ui.photos

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
import com.gxstar.stargallery.data.paging.PhotoPagingSource
import com.gxstar.stargallery.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    private var cachedPagingFlow: Flow<PagingData<PhotoModel>>? = null
    private var cachedSortType: MediaRepository.SortType? = null

    private val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

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
     * 使用Paging 3官方推荐的insertSeparators方法
     */
    fun getPhotoPagingFlow(): Flow<PagingData<PhotoModel>> {
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
            ).flow
                .map { pagingData -> 
                    // 先将Photo转换为PhotoModel.PhotoItem
                    pagingData.map { photo -> 
                        PhotoModel.PhotoItem(photo) 
                    }
                }
                .map { pagingData ->
                    // 使用insertSeparators插入日期分隔符
                    pagingData.insertSeparators { before: PhotoModel.PhotoItem?, after: PhotoModel.PhotoItem? ->
                        if (after == null) {
                            // 到达列表末尾，不需要分隔符
                            null
                        } else if (before == null) {
                            // 列表开头，显示第一个日期分隔符
                            PhotoModel.SeparatorItem(getDateText(after.photo))
                        } else {
                            // 比较前后两个item的日期，如果不同则插入分隔符
                            val beforeDate = getDateText(before.photo)
                            val afterDate = getDateText(after.photo)
                            if (beforeDate != afterDate) {
                                PhotoModel.SeparatorItem(afterDate)
                            } else {
                                null
                            }
                        }
                    }
                }
                .cachedIn(viewModelScope)
        }
        return cachedPagingFlow!!
    }

    /**
     * 获取照片的显示日期文本
     * 必须与排序逻辑完全一致：
     * - DATE_TAKEN排序：COALESCE(NULLIF(DATE_TAKEN, 0), DATE_ADDED * 1000)
     * - DATE_MODIFIED排序：DATE_MODIFIED * 1000
     */
    private fun getDateText(photo: Photo): String {
        // 计算时间戳，与排序逻辑完全一致
        val timestampMillis = when (_currentSortType.value) {
            MediaRepository.SortType.DATE_TAKEN -> {
                // 优先DATE_TAKEN，为0时用DATE_ADDED * 1000
                if (photo.dateTaken > 0) photo.dateTaken 
                else photo.dateAdded * 1000L
            }
            MediaRepository.SortType.DATE_MODIFIED -> photo.dateModified * 1000L
        }
        
        val date = Date(timestampMillis)
        val dateStr = dateFormat.format(date)
        
        // 获取今天和昨天的日期字符串
        val calendar = Calendar.getInstance()
        val todayStr = dateFormat.format(calendar.time)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = dateFormat.format(calendar.time)
        
        return when (dateStr) {
            todayStr -> "今天"
            yesterdayStr -> "昨天"
            else -> dateStr
        }
    }

    fun refresh() {
        loadPhotoCount()
    }
}
