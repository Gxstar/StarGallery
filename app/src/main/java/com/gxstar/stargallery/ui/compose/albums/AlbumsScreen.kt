package com.gxstar.stargallery.ui.compose.albums

import android.Manifest
import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.gxstar.stargallery.data.model.Album
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme
import com.permissionx.guolindev.PermissionX

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel = hiltViewModel(),
    onNavigateToAlbumDetail: (Long, String) -> Unit
) {
    val albums by viewModel.albums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val activity = context.findActivity() as? androidx.fragment.app.FragmentActivity
        if (activity != null) {
            PermissionX.init(activity)
                .permissions(*permissions)
                .request { _, _, _ ->
                    viewModel.loadAlbums()
                }
        }
    }

    StarGalleryTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("相册", fontSize = 20.sp) }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(bottom = 80.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (albums.isEmpty()) {
                    EmptyAlbumsState()
                } else {
                    AlbumsGrid(
                        albums = albums,
                        onAlbumClick = { album ->
                            onNavigateToAlbumDetail(album.id, album.name)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumsGrid(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumItem(
                album = album,
                onClick = { onAlbumClick(album) }
            )
        }
    }
}

@Composable
private fun AlbumItem(
    album: Album,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            ) {
                if (album.coverUri != null) {
                    AsyncImage(
                        model = album.coverUri,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.photoCount} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyAlbumsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "没有相册",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? {
    return when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
