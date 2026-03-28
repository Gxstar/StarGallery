package com.gxstar.stargallery.ui.photos

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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _photoGroups = MutableStateFlow<List<PhotoGroup>>(emptyList())
    val photoGroups: StateFlow<List<PhotoGroup>> = _photoGroups.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPhotos()
    }

    fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val allPhotos = mediaRepository.getAllPhotos()
                _photoGroups.value = groupPhotosByDate(allPhotos)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun groupPhotosByDate(photos: List<Photo>): List<PhotoGroup> {
        val groups = mutableMapOf<String, MutableList<Photo>>()
        val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        val calendar = Calendar.getInstance()
        val today = calendar.time
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = calendar.time

        val todayStr = formatRelativeDate(today, today)
        val yesterdayStr = formatRelativeDate(yesterday, today)

        for (photo in photos) {
            val date = Date(photo.dateTaken)
            val dateStr = dateFormat.format(date)
            
            val displayDate = when (dateStr) {
                todayStr -> "今天"
                yesterdayStr -> "昨天"
                else -> dateStr
            }
            
            if (!groups.containsKey(displayDate)) {
                groups[displayDate] = mutableListOf()
            }
            groups[displayDate]?.add(photo)
        }

        return groups.map { (date, photos) -> PhotoGroup(date, photos) }
    }

    private fun formatRelativeDate(date: Date, reference: Date): String {
        val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        return dateFormat.format(date)
    }
}