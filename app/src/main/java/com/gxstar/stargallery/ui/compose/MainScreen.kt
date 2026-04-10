package com.gxstar.stargallery.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gxstar.stargallery.ui.compose.albums.AlbumDetailScreen
import com.gxstar.stargallery.ui.compose.albums.AlbumsScreen
import com.gxstar.stargallery.ui.compose.detail.PhotoDetailScreen
import com.gxstar.stargallery.ui.compose.photos.PhotosScreen
import com.gxstar.stargallery.ui.compose.trash.TrashScreen
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme

object NavRoutes {
    const val PHOTOS = "photos"
    const val ALBUMS = "albums"
    const val ALBUM_DETAIL = "album_detail/{albumId}/{albumName}"
    const val PHOTO_DETAIL = "photo_detail/{photoId}/{sortType}/{bucketId}"
    const val TRASH = "trash"

    fun albumDetail(albumId: Long, albumName: String) = "album_detail/$albumId/$albumName"
    fun photoDetail(photoId: Long, sortType: Int = 0, bucketId: Long = -1L) = "photo_detail/$photoId/$sortType/$bucketId"
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Determine if bottom bar should be shown (hide on detail screens and trash)
    val showBottomBar = currentRoute in listOf(NavRoutes.PHOTOS, NavRoutes.ALBUMS)

    // Determine current tab based on route
    val currentTab = when (currentRoute) {
        NavRoutes.PHOTOS -> 0
        NavRoutes.ALBUMS -> 1
        else -> 0
    }

    StarGalleryTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // Main content
            NavHost(
                navController = navController,
                startDestination = NavRoutes.PHOTOS
            ) {
                // Photos tab
                composable(NavRoutes.PHOTOS) {
                    PhotosScreen(
                        onNavigateToDetail = { photoId, sortType ->
                            navController.navigate(NavRoutes.photoDetail(photoId, sortType))
                        },
                        onNavigateToTrash = {
                            navController.navigate(NavRoutes.TRASH)
                        }
                    )
                }

                // Albums list
                composable(NavRoutes.ALBUMS) {
                    AlbumsScreen(
                        onNavigateToAlbumDetail = { albumId, albumName ->
                            navController.navigate(NavRoutes.albumDetail(albumId, albumName))
                        }
                    )
                }

                // Album detail
                composable(
                    route = NavRoutes.ALBUM_DETAIL,
                    arguments = listOf(
                        navArgument("albumId") { type = NavType.LongType },
                        navArgument("albumName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val albumId = backStackEntry.arguments?.getLong("albumId") ?: -1L
                    val albumName = backStackEntry.arguments?.getString("albumName") ?: ""

                    AlbumDetailScreen(
                        albumId = albumId,
                        albumName = albumName,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToDetail = { photoId, sortType ->
                            navController.navigate(NavRoutes.photoDetail(photoId, sortType, albumId))
                        }
                    )
                }

                // Photo detail (full screen viewer)
                composable(
                    route = NavRoutes.PHOTO_DETAIL,
                    arguments = listOf(
                        navArgument("photoId") { type = NavType.LongType },
                        navArgument("sortType") { type = NavType.IntType; defaultValue = 0 },
                        navArgument("bucketId") { type = NavType.LongType; defaultValue = -1L }
                    )
                ) { backStackEntry ->
                    val photoId = backStackEntry.arguments?.getLong("photoId") ?: -1L
                    val sortType = backStackEntry.arguments?.getInt("sortType") ?: 0
                    val bucketId = backStackEntry.arguments?.getLong("bucketId") ?: -1L

                    PhotoDetailScreen(
                        initialPhotoId = photoId,
                        sortType = sortType,
                        bucketId = bucketId,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // Trash
                composable(NavRoutes.TRASH) {
                    TrashScreen(
                        onNavigateBack = {
                            navController.navigate(NavRoutes.PHOTOS) {
                                popUpTo(NavRoutes.PHOTOS) { inclusive = true }
                            }
                        }
                    )
                }
            }

            // Floating bottom navigation bar
            if (showBottomBar) {
                FloatingBottomNavigationBar(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        val route = when (tab) {
                            0 -> NavRoutes.PHOTOS
                            1 -> NavRoutes.ALBUMS
                            else -> NavRoutes.PHOTOS
                        }
                        navController.navigate(route) {
                            popUpTo(NavRoutes.PHOTOS) { inclusive = true }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun FloatingBottomNavigationBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier
            .padding(bottom = 18.dp, start = 20.dp, end = 20.dp)
            .clip(RoundedCornerShape(20.dp)),
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp).copy(alpha = 0.9f),
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0.dp)
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Image, contentDescription = "照片") },
            label = { Text("照片") },
            selected = currentTab == 0,
            onClick = { onTabSelected(0) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "相册") },
            label = { Text("相册") },
            selected = currentTab == 1,
            onClick = { onTabSelected(1) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        )
    }
}