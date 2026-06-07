package com.gxstar.stargallery.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.model.Album
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.util.ExcludedAlbumManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val excludedAlbumManager: ExcludedAlbumManager
) : ViewModel() {

    private val _rawAlbums = MutableStateFlow<List<Album>>(emptyList())

    val albums: StateFlow<List<Album>> = combine(
        _rawAlbums,
        excludedAlbumManager.excludedBucketIds
    ) { albums, excludedIds ->
        albums.map { it.copy(isExcluded = it.id in excludedIds) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadAlbums() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val allAlbums = mediaRepository.getAlbums()
                _rawAlbums.value = allAlbums
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
