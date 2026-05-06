# StarGallery 依赖库使用文档

本文档描述 App 使用的功能及对应的第三方库，基于代码实现整理。

---

## 1. Glide (图片加载)

**功能：** 缩略图网格显示、全屏图片查看、图片预加载

**关键文件：**
- [PhotoListAdapter.kt](app/src/main/java/com/gxstar/stargallery/ui/photos/PhotoListAdapter.kt)
- [PhotoPageViewHolder.kt](app/src/main/java/com/gxstar/stargallery/ui/detail/PhotoPageViewHolder.kt)

**使用方式：**
```kotlin
Glide.with(context)
    .load(photo.uri)
    .placeholder(R.drawable.ic_photo_placeholder)
    .centerCrop()
    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
    .into(binding.ivPhoto)
```

---

## 2. ZoomImage (大图缩放)

**功能：** 支持捏合缩放、平铺加载高分辨率照片

**关键文件：**
- [PhotoPageViewHolder.kt](app/src/main/java/com/gxstar/stargallery/ui/detail/PhotoPageViewHolder.kt)

**使用方式：**
```kotlin
binding.ivPhoto.setImage(ImageSource.uri(photo.uri), ImageOptions())
```

---

## 3. Hilt (依赖注入)

**功能：** ViewModel 注入、单例管理、Repository 模式

**关键文件：**
- [StarGalleryApp.kt](app/src/main/java/com/gxstar/stargallery/StarGalleryApp.kt)
- [AppModule.kt](app/src/main/java/com/gxstar/stargallery/di/AppModule.kt)
- [DatabaseModule.kt](app/src/main/java/com/gxstar/stargallery/di/DatabaseModule.kt)

**使用方式：**
```kotlin
@HiltAndroidApp
class StarGalleryApp : Application()

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val photoDao: PhotoDao,
    private val mediaScanner: MediaScanner
) : ViewModel()
```

---

## 4. Navigation Component (导航)

**功能：** BottomNavigation、Fragment 跳转、SafeArgs 类型安全传参

**关键文件：**
- [MainActivity.kt](app/src/main/java/com/gxstar/stargallery/MainActivity.kt)
- 所有 Fragment

**使用方式：**
```kotlin
val action = AlbumsFragmentDirections.actionAlbumsFragmentToAlbumDetailFragment(album.id, album.name)
findNavController().navigate(action)
```

---

## 5. Paging 3 (分页)

**功能：** 照片列表分页加载、日期分组

**关键文件：**
- [PhotosViewModel.kt](app/src/main/java/com/gxstar/stargallery/ui/photos/PhotosViewModel.kt)
- [PhotoListAdapter.kt](app/src/main/java/com/gxstar/stargallery/ui/photos/PhotoListAdapter.kt)
- [RoomPagingSource.kt](app/src/main/java/com/gxstar/stargallery/data/paging/RoomPagingSource.kt)

**使用方式：**
```kotlin
Pager(config = PagingConfig(pageSize = 30, prefetchDistance = 10),
    pagingSourceFactory = { RoomPagingSource(...) }
).flow
```

---

## 6. Room (数据库)

**功能：** 照片元数据缓存、收藏状态持久化

**关键文件：**
- [AppDatabase.kt](app/src/main/java/com/gxstar/stargallery/data/local/db/AppDatabase.kt)
- [PhotoDao.kt](app/src/main/java/com/gxstar/stargallery/data/local/db/PhotoDao.kt)
- [PhotoEntity.kt](app/src/main/java/com/gxstar/stargallery/data/local/db/PhotoEntity.kt)

---

## 7. ActivityResultContracts (权限管理)

**功能：** 运行时权限请求（媒体访问）

**关键文件：**
- [AlbumsFragment.kt](app/src/main/java/com/gxstar/stargallery/ui/albums/AlbumsFragment.kt)
- [PhotosFragment.kt](app/src/main/java/com/gxstar/stargallery/ui/photos/PhotosFragment.kt)

**使用方式：**
```kotlin
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { results ->
    if (results.values.all { it }) {
        viewModel.loadAlbums()
    }
}

permissionLauncher.launch(arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO
))
```

---

## 8. metadata-extractor (EXIF 元数据)

**功能：** 提取相机型号、ISO、光圈等拍摄信息

**关键文件：**
- [ExifExtractor.kt](app/src/main/java/com/gxstar/stargallery/data/local/exif/ExifExtractor.kt)

**使用方式：**
```kotlin
val metadata = ImageMetadataReader.readMetadata(stream)
val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
```

---

## 9. Media3/ExoPlayer (视频播放)

**功能：** 照片详情页内嵌视频播放

**关键文件：**
- [ExoPlayerManager.kt](app/src/main/java/com/gxstar/stargallery/ui/detail/ExoPlayerManager.kt)

**使用方式：**
```kotlin
ExoPlayer.Builder(context).build().apply {
    repeatMode = Player.REPEAT_MODE_ONE
}
```

---

## 10. recyclerview-selection (选择模式)

**功能：** 长按多选、批量操作

**关键文件：**
- [PhotoListAdapter.kt](app/src/main/java/com/gxstar/stargallery/ui/photos/PhotoListAdapter.kt)
- [PhotoSelectionTracker.kt](app/src/main/java/com/gxstar/stargallery/ui/common/PhotoSelectionTracker.kt)

---

## 11. LeakCanary (内存泄漏检测)

**功能：** Debug 模式自动检测内存泄漏

**集成方式：**
```kotlin
debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
```

---

## 12. kotlinx-coroutines (异步操作)

**功能：** Flow 响应式数据、withContext IO 切换、协程同步

**使用方式：**
```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    viewModel.photoPagingFlow.collectLatest { pagingData -> }
}

suspend fun extractExif(uri: Uri) = withContext(Dispatchers.IO) { }
```

---

## 附录：依赖汇总表

| 库 | 版本 | 用途 |
|---|------|------|
| Glide | 4.16.0 | 图片加载 |
| ZoomImage | 1.4.0 | 大图缩放 |
| Hilt | 2.59.2 | 依赖注入 |
| Navigation | 2.9.7 | 导航 |
| Paging 3 | 3.4.2 | 分页 |
| Room | 2.8.4 | 数据库 |
| ActivityResultContracts | AndroidX | 权限 |
| metadata-extractor | 2.20.0 | EXIF |
| Media3 | 1.9.1 | 视频播放 |
| recyclerview-selection | 1.2.0 | 选择 |
| LeakCanary | 2.14 | 泄漏检测 |
| Coroutines | 1.10.1 | 异步 |

---

## 未使用但已声明的库

- **Groupie** - 已注释不用，改用 Paging 3 insertSeparators 实现分组
