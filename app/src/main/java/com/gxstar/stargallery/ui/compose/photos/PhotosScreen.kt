package com.gxstar.stargallery.ui.compose.photos

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme
import com.gxstar.stargallery.ui.photos.GroupType
import com.gxstar.stargallery.ui.photos.PhotoModel
import com.permissionx.guolindev.PermissionX

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel = hiltViewModel(),
    onNavigateToDetail: (Long, Int) -> Unit,
    onNavigateToAlbums: () -> Unit,
    onNavigateToTrash: () -> Unit,
    currentTab: Int = 0,
    onTabChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val photos = viewModel.photoPagingFlow.collectAsLazyPagingItems()

    val currentSortType by viewModel.currentSortType.collectAsState()
    val currentGroupType by viewModel.currentGroupType.collectAsState()
    val showFavoritesOnly by viewModel.showFavoritesOnly.collectAsState()
    val photoCount by viewModel.photoCount.collectAsState()
    val favoriteCount by viewModel.favoriteCount.collectAsState()

    var showSortDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }

    // Selection state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    // IntentSender launchers
    val favoriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.refresh()
            selectedIds = emptySet()
            isSelectionMode = false
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
            viewModel.refresh()
            selectedIds = emptySet()
            isSelectionMode = false
        }
    }

    // Permission launcher
    LaunchedEffect(Unit) {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        PermissionX.init(context as androidx.fragment.app.FragmentActivity)
            .permissions(*permissions)
            .request { _, _, _ ->
                viewModel.refresh()
            }
    }

    StarGalleryTheme {
        Scaffold(
            topBar = {
                if (isSelectionMode) {
                    SelectionTopBar(
                        selectedCount = selectedIds.size,
                        onBack = {
                            selectedIds = emptySet()
                            isSelectionMode = false
                        },
                        onShare = {
                            val selectedPhotos = getSelectedPhotos(photos, selectedIds)
                            sharePhotos(context, selectedPhotos)
                            selectedIds = emptySet()
                            isSelectionMode = false
                        },
                        onFavorite = {
                            val selectedPhotos = getSelectedPhotos(photos, selectedIds)
                            val hasFavorite = selectedPhotos.any { !it.isFavorite }
                            val hasUnfavorite = selectedPhotos.any { it.isFavorite }

                            val intentSender = when {
                                hasFavorite && hasUnfavorite -> viewModel.toggleMixedFavorite(selectedPhotos)
                                hasFavorite -> viewModel.setFavorite(selectedPhotos, true)
                                hasUnfavorite -> viewModel.setFavorite(selectedPhotos, false)
                                else -> null
                            }

                            intentSender?.let {
                                favoriteLauncher.launch(IntentSenderRequest.Builder(it).build())
                            } ?: run {
                                viewModel.refresh()
                                selectedIds = emptySet()
                                isSelectionMode = false
                            }
                        },
                        onDelete = {
                            val selectedPhotos = getSelectedPhotos(photos, selectedIds)
                            viewModel.trashPhotos(selectedPhotos)?.let { intentSender ->
                                trashLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            } ?: run {
                                viewModel.refresh()
                                selectedIds = emptySet()
                                isSelectionMode = false
                            }
                        }
                    )
                } else {
                    PhotosTopBar(
                        subtitle = if (showFavoritesOnly) {
                            context.getString(R.string.favorite_count, favoriteCount)
                        } else {
                            context.getString(R.string.photo_count, photoCount)
                        },
                        showFavoritesOnly = showFavoritesOnly,
                        onFilterClick = { viewModel.toggleFavoritesOnly() },
                        onMoreClick = { showSortDialog = true }
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Image, contentDescription = "Photos") },
                        label = { Text("照片") },
                        selected = currentTab == 0,
                        onClick = { onTabChange(0) }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Image, contentDescription = "Albums") },
                        label = { Text("相册") },
                        selected = currentTab == 1,
                        onClick = {
                            onTabChange(1)
                            onNavigateToAlbums()
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Delete, contentDescription = "Trash") },
                        label = { Text("回收站") },
                        selected = currentTab == 2,
                        onClick = {
                            onTabChange(2)
                            onNavigateToTrash()
                        }
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
                                val sortTypeValue = if (currentSortType == MediaRepository.SortType.DATE_TAKEN) 0 else 1
                                onNavigateToDetail(photo.id, sortTypeValue)
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

        if (showSortDialog) {
            SortDialog(
                currentSortType = currentSortType,
                onDismiss = { showSortDialog = false },
                onSelect = { sortType ->
                    viewModel.setSortType(sortType)
                    showSortDialog = false
                }
            )
        }

        if (showGroupDialog) {
            GroupDialog(
                currentGroupType = currentGroupType,
                onDismiss = { showGroupDialog = false },
                onSelect = { groupType ->
                    viewModel.setGroupType(groupType)
                    showGroupDialog = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosTopBar(
    subtitle: String,
    showFavoritesOnly: Boolean,
    onFilterClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text("照片", fontSize = 20.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            IconButton(onClick = onFilterClick) {
                Icon(
                    if (showFavoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Filter"
                )
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("排序") },
                    onClick = { /* Show sort dialog */ }
                )
                DropdownMenuItem(
                    text = { Text("分组") },
                    onClick = { /* Show group dialog */ }
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount 已选择") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            IconButton(onClick = onFavorite) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
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
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
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
                    PhotoGridItem(
                        photo = item.photo,
                        isSelected = selectedIds.contains(item.photo.id),
                        isSelectionMode = isSelectionMode,
                        onClick = { onPhotoClick(item.photo) },
                        onLongClick = { onPhotoLongClick(item.photo) }
                    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridItem(
    photo: Photo,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (photo.isVideo) {
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

        if (photo.isFavorite && !isSelectionMode) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "Favorite",
                tint = Color.Red,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
            )
        }

        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) Color.Blue.copy(alpha = 0.3f) else Color.Transparent
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) Color.Blue else Color.White.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
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

@Composable
private fun EmptyState() {
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
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "没有照片",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SortDialog(
    currentSortType: MediaRepository.SortType,
    onDismiss: () -> Unit,
    onSelect: (MediaRepository.SortType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序方式") },
        text = {
            Column {
                MediaRepository.SortType.entries.forEach { sortType ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sortType == currentSortType,
                            onClick = { onSelect(sortType) }
                        )
                        Text(
                            when (sortType) {
                                MediaRepository.SortType.DATE_TAKEN -> "按拍摄时间"
                                MediaRepository.SortType.DATE_ADDED -> "按添加时间"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun GroupDialog(
    currentGroupType: GroupType,
    onDismiss: () -> Unit,
    onSelect: (GroupType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组方式") },
        text = {
            Column {
                GroupType.entries.forEach { groupType ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = groupType == currentGroupType,
                            onClick = { onSelect(groupType) }
                        )
                        Text(
                            when (groupType) {
                                GroupType.DAY -> "按天"
                                GroupType.MONTH -> "按月"
                                GroupType.YEAR -> "按年"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun getSelectedPhotos(
    photos: androidx.paging.compose.LazyPagingItems<PhotoModel>,
    selectedIds: Set<Long>
): List<Photo> {
    val result = mutableListOf<Photo>()
    for (i in 0 until photos.itemCount) {
        val item = photos.peek(i)
        if (item is PhotoModel.PhotoItem && selectedIds.contains(item.photo.id)) {
            result.add(item.photo)
        }
    }
    return result
}

private fun sharePhotos(context: android.content.Context, photos: List<Photo>) {
    if (photos.isEmpty()) return

    val uris = photos.map { it.uri }
    val shareIntent = android.content.Intent().apply {
        action = android.content.Intent.ACTION_SEND_MULTIPLE
        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
        type = "image/*"
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(shareIntent, "分享照片"))
}
