package com.gxstar.stargallery.ui.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
    var currentTab by rememberSaveable { mutableIntStateOf(0) }

    StarGalleryTheme {
        Scaffold { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = NavRoutes.PHOTOS,
                modifier = Modifier.padding(paddingValues)
            ) {
                // Photos tab
                composable(NavRoutes.PHOTOS) {
                    PhotosScreen(
                        onNavigateToDetail = { photoId, sortType ->
                            navController.navigate(NavRoutes.photoDetail(photoId, sortType))
                        },
                        onNavigateToAlbums = {
                            currentTab = 1
                            navController.navigate(NavRoutes.ALBUMS) {
                                popUpTo(NavRoutes.PHOTOS) { inclusive = false }
                            }
                        },
                        onNavigateToTrash = {
                            currentTab = 2
                            navController.navigate(NavRoutes.TRASH) {
                                popUpTo(NavRoutes.PHOTOS) { inclusive = false }
                            }
                        },
                        currentTab = currentTab,
                        onTabChange = { tab ->
                            currentTab = tab
                            when (tab) {
                                0 -> navController.navigate(NavRoutes.PHOTOS) {
                                    popUpTo(NavRoutes.PHOTOS) { inclusive = true }
                                }
                                1 -> navController.navigate(NavRoutes.ALBUMS) {
                                    popUpTo(NavRoutes.PHOTOS) { inclusive = true }
                                }
                                2 -> navController.navigate(NavRoutes.TRASH) {
                                    popUpTo(NavRoutes.PHOTOS) { inclusive = true }
                                }
                            }
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
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
