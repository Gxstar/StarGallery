package com.gxstar.stargallery.ui.compose.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme
import com.gxstar.stargallery.ui.photos.PhotoModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    albumName: String,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long, Int) -> Unit
) {
    val photos = viewModel.photoPagingFlow.collectAsLazyPagingItems()
    val photoCount by viewModel.photoCount.collectAsState()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(albumId) {
        viewModel.setAlbumId(albumId)
    }

    StarGalleryTheme {
        Scaffold(
            topBar = {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("${selectedIds.size} 已选择") },
                        navigationIcon = {
                            IconButton(onClick = {
                                selectedIds = emptySet()
                                isSelectionMode = false
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { /* Share */ }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }
                            IconButton(onClick = { /* Favorite */ }) {
                                Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite")
                            }
                            IconButton(onClick = { /* Delete */ }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(albumName, fontSize = 18.sp)
                                Text("$photoCount 张照片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (photos.itemCount == 0) {
                    EmptyState()
                } else {
                    PhotoGrid(
                        photos = photos,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onPhotoClick = { photo ->
                            if (isSelectionMode) {
                                selectedIds = if (selectedIds.contains(photo.id)) {
                                    selectedIds - photo.id
                                } else {
                                    selectedIds + photo.id
                                }
                                if (selectedIds.isEmpty()) {
                                    isSelectionMode = false
                                }
                            } else {
                                onNavigateToDetail(photo.id, 0)
                            }
                        },
                        onPhotoLongClick = { photo ->
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds = setOf(photo.id)
                            }
                        }
                    )
                }

                if (photos.loadState.refresh is androidx.paging.LoadState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    photos: androidx.paging.compose.LazyPagingItems<PhotoModel>,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onPhotoClick: (Photo) -> Unit,
    onPhotoLongClick: (Photo) -> Unit
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            count = photos.itemCount,
            key = { index -> photos.peek(index)?.hashCode() ?: index }
        ) { index ->
            val item = photos.peek(index)
            when (item) {
                is PhotoModel.SeparatorItem -> {
                    Text(
                        text = item.dateText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                is PhotoModel.PhotoItem -> {
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .combinedClickable(
                                onClick = { onPhotoClick(item.photo) },
                                onLongClick = { onPhotoLongClick(item.photo) }
                            )
                    ) {
                        AsyncImage(
                            model = item.photo.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (item.photo.isVideo) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .padding(2.dp)
                            ) {
                                Text(
                                    "▶",
                                    color = Color.White,
                                    fontSize = 8.sp
                                )
                            }
                        }

                        if (isSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        if (selectedIds.contains(item.photo.id)) Color.Blue.copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                            )

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selectedIds.contains(item.photo.id)) Color.Blue
                                        else Color.White.copy(alpha = 0.7f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedIds.contains(item.photo.id)) {
                                    Text(
                                        "✓",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
                null -> {
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "相册为空",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
