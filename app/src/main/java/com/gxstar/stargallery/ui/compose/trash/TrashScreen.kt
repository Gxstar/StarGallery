package com.gxstar.stargallery.ui.compose.trash

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val photos by viewModel.photos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, R.string.restored, Toast.LENGTH_SHORT).show()
            viewModel.loadTrashedPhotos()
            selectedIds = emptySet()
            isSelectionMode = false
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, R.string.deleted, Toast.LENGTH_SHORT).show()
            viewModel.loadTrashedPhotos()
            selectedIds = emptySet()
            isSelectionMode = false
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }

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
                            IconButton(onClick = {
                                showRestoreDialog = true
                            }) {
                                Icon(Icons.Default.Restore, contentDescription = "Restore")
                            }
                            IconButton(onClick = {
                                showDeleteDialog = true
                            }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Delete")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                } else {
                    TopAppBar(
                        title = { Text("回收站") },
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
                    .padding(bottom = 80.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (photos.isEmpty()) {
                    EmptyTrashState()
                } else {
                    TrashGrid(
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
            }
        }

        if (showRestoreDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreDialog = false },
                title = { Text("恢复照片") },
                text = { Text("确定要恢复选中的 ${selectedIds.size} 项吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        val selectedPhotos = photos.filter { selectedIds.contains(it.id) }
                        viewModel.restorePhotos(selectedPhotos) { intentSender ->
                            restoreLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        }
                        showRestoreDialog = false
                    }) {
                        Text("恢复")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("永久删除") },
                text = { Text("确定要永久删除选中的 ${selectedIds.size} 项吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        val selectedPhotos = photos.filter { selectedIds.contains(it.id) }
                        viewModel.deletePhotos(selectedPhotos) { intentSender ->
                            deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        }
                        showDeleteDialog = false
                    }) {
                        Text("删除", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashGrid(
    photos: List<Photo>,
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
        items(photos, key = { it.id }) { photo ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .combinedClickable(
                        onClick = { onPhotoClick(photo) },
                        onLongClick = { onPhotoLongClick(photo) }
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

                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (selectedIds.contains(photo.id)) Color.Blue.copy(alpha = 0.3f)
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
                                if (selectedIds.contains(photo.id)) Color.Blue
                                else Color.White.copy(alpha = 0.7f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedIds.contains(photo.id)) {
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
    }
}

@Composable
private fun EmptyTrashState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "回收站是空的",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
