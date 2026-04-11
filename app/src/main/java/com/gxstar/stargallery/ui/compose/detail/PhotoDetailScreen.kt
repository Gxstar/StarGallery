package com.gxstar.stargallery.ui.compose.detail

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.rememberZoomableImageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    initialPhotoId: Long,
    sortType: Int = 0,
    bucketId: Long = -1L,
    viewModel: PhotoDetailViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val photos by viewModel.photos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentPhoto by viewModel.currentPhoto.collectAsState()

    var isFullscreen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
            viewModel.removeCurrentPhoto()
            if (photos.isEmpty()) {
                onNavigateBack()
            }
        }
    }

    val favoriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.refreshCurrentPhoto()
        }
    }

    LaunchedEffect(initialPhotoId, sortType, bucketId) {
        viewModel.loadPhotos(initialPhotoId, sortType, bucketId)
    }

    val pagerState = rememberPagerState(initialPage = 0) { photos.size }

    LaunchedEffect(photos, initialPhotoId) {
        if (photos.isNotEmpty()) {
            val initialIndex = photos.indexOfFirst { it.id == initialPhotoId }.coerceAtLeast(0)
            if (pagerState.currentPage != initialIndex) {
                pagerState.scrollToPage(initialIndex)
            }
        }
    }

    StarGalleryTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            } else if (photos.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { photos.getOrNull(it)?.id ?: it }
                ) { page ->
                    val photo = photos[page]
                    viewModel.setCurrentPhoto(photo)

                    // 创建独立的缩放状态
                    val zoomableState = rememberZoomableState()
                    
                    // 当页面不在当前显示时重置缩放
                    if (pagerState.settledPage != page) {
                        LaunchedEffect(Unit) {
                            zoomableState.resetZoom()
                        }
                    }

                    ZoomablePhotoImage(
                        photo = photo,
                        zoomableState = zoomableState,
                        onTap = { isFullscreen = !isFullscreen }
                    )
                }

                AnimatedVisibility(
                    visible = !isFullscreen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    TopAppBar(
                        title = { Text("${pagerState.currentPage + 1} / ${photos.size}") },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            currentPhoto?.let { photo ->
                                IconButton(onClick = {
                                    viewModel.toggleFavorite { intentSender ->
                                        favoriteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                    }
                                }) {
                                    Icon(
                                        if (photo.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorite",
                                        tint = if (photo.isFavorite) Color.Red else Color.White
                                    )
                                }
                                IconButton(onClick = {
                                    showInfo = !showInfo
                                }) {
                                    Icon(Icons.Default.Info, contentDescription = "Info")
                                }
                                IconButton(onClick = {
                                    viewModel.trashCurrentPhoto { intentSender ->
                                        trashLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.7f),
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                            actionIconContentColor = Color.White
                        )
                    )
                }

                AnimatedVisibility(
                    visible = showInfo && !isFullscreen && currentPhoto != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    PhotoInfoPanel(
                        photo = currentPhoto!!,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhotoImage(
    photo: Photo,
    zoomableState: ZoomableState,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    
    // 构建 ImageRequest 确保加载完整大图
    val imageRequest = remember(photo.uri) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .build()
    }
    
    // 创建 ZoomableImageState
    val zoomableImageState = rememberZoomableImageState(zoomableState)
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (photo.isVideo) {
            Text(
                "Video: ${photo.mimeType}",
                color = Color.White
            )
        } else {
            ZoomableAsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                state = zoomableImageState,
                onClick = { onTap() }
            )
        }
    }
}

@Composable
private fun PhotoInfoPanel(
    photo: Photo,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "类型: ${photo.mimeType}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "${photo.width} x ${photo.height}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = "大小: ${formatFileSize(photo.size)}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}