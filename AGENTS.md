# StarGallery — 项目指南

## 强制规则

**所有思考与回复必须使用中文。**

## 构建

```powershell
.\gradlew.bat assembleDebug          # Debug APK
.\gradlew.bat testDebugUnitTest       # 单元测试
```

单模块 `:app`，版本目录 `gradle/libs.versions.toml`。依赖通过腾讯镜像加速（`settings.gradle.kts`）。
Kotlin 2.3.20，AGP 9.2.1，minSdk 30，compileSdk 36，Java 21。

## 架构要点

- **MVVM + Repository + Room + Hilt + Navigation**
- 入口：`MainActivity` → `nav_graph.xml` → `photosFragment` (start)
- Hilt 用 **KSP**（非 kapt）：`ksp(libs.hilt.compiler)`
- Navigation 用 **SafeArgs**，参数定义在 `nav_graph.xml`，禁止手动 Bundle
- **ViewBinding** 替代 findViewById，使用 `viewBinding.root` 访问根视图

## 数据加载模式（非 Paging 3）

**项目已从 Paging 3 迁移到 Room Flow + ListAdapter 模式：**

- `PhotoDao.getAllPhotosFlow()` 返回 `Flow<List<PhotoEntity>>`，Room 自动监听表变化推送更新
- `PhotosViewModel.photoListFlow` 通过 `combine` 合并排序、收藏过滤、EXIF 过滤、分组等状态，在内存中排序/过滤/插入 `SeparatorItem`
- `PhotoListAdapter` 继承 `ListAdapter<PhotoModel, RecyclerView.ViewHolder>`，使用 `submitList()` 提交数据
- 数据收集使用 `lifecycleScope.launch` + `repeatOnLifecycle(STARTED)` 组合

## 删除/收藏后的刷新机制

- **Room 删除** → `invalidationTracker` 自动使 Flow 失效 → `getAllPhotosFlow()` 自动推送新列表 → 无需手动刷新
- **收藏切换** → `viewModel.updateFavorite()` 更新 Room → Flow 自动推送
- **隐藏操作** → `viewModel.updateHidden()` 更新 Room → Flow 自动推送
- **ContentObserver 防抖**：`MediaChangeDetector` 有 500ms debounce + `shouldSkipRefresh`（1秒抑制窗口），`lastExplicitRefreshTime` 被设置后 1 秒内跳过回调

## MediaStore 操作 (IntentSender 模式)

1. `Repository` 返回 `IntentSender`（非直接执行）
2. UI 通过 `ActivityResultContracts.StartIntentSenderForResult` 启动
3. 用户确认后回调处理成功/失败
4. 用于：收藏、删除、回收站、恢复
5. `IntentSenderManager` 统一管理 favoriteLauncher / trashLauncher / deleteLauncher

## 图片加载

- **Glide** 缩略图/GIF + `RecyclerViewPreloader` 预加载
- **ZoomImageView**（ZoomImage 1.4.0）大图子采样（>=2000px 启用）
- **ExoPlayerManager**：全局单例，页面切换保持播放状态

## RecyclerView 网格

- `GridLayoutManager` 3-8 列可配置，`spanSizeLookup` 控制 SeparatorItem 占整行
- `setHasFixedSize(true)` + `setItemViewCacheSize(8)`
- `supportsChangeAnimations = false` 避免变更动画残影
- 扫描时 `itemAnimator = null` 防快速刷新乱跳，扫描完成后恢复

## 导航图

```
photosFragment (start)
  → photoDetailFragment    (action_photosFragment_to_photoDetailFragment)
  → trashFragment          (action_photosFragment_to_trashFragment)
  → hiddenFragment         (action_photosFragment_to_hiddenFragment)  [需生物识别]
  → aboutFragment          (action_photosFragment_to_aboutFragment)

albumsFragment
  → albumDetailFragment    (action_albumsFragment_to_albumDetailFragment)
  → photoDetailFragment

albumDetailFragment
  → photoDetailFragment    (action_albumDetailFragment_to_photoDetailFragment)

hiddenFragment
  → photoDetailFragment    (action_hiddenFragment_to_photoDetailFragment)

aboutFragment
  → privacyPolicyFragment, permissionsFragment, thirdPartyLibrariesFragment, contactFragment, licenseFragment
```

## 关键目录

```
app/src/main/java/com/gxstar/stargallery/
├── data/
│   ├── model/              # Photo, Album
│   ├── repository/         # MediaRepository
│   └── local/
│       ├── db/             # PhotoDao, PhotoEntity, AppDatabase (Room)
│       ├── scanner/        # MediaScanner (全量/增量扫描)
│       ├── exif/           # ExifExtractor
│       └── preferences/    # ScanPreferences
├── di/                     # Hilt 模块 (AppModule, DatabaseModule, PreferenceModule)
├── ui/
│   ├── photos/             # 首页网格 (PhotosFragment, PhotosViewModel, PhotoListAdapter)
│   │   ├── action/         # BatchActionHandler
│   │   ├── animation/      # PhotoItemAnimator
│   │   ├── filter/         # FilterBottomSheet (EXIF 筛选)
│   │   ├── launcher/       # IntentSenderManager
│   │   ├── model/          # PhotoModel (PhotoItem, SeparatorItem)
│   │   ├── refresh/        # MediaChangeDetector (ContentObserver)
│   │   ├── scanner/        # ScanningProgressDialog, ScanViewModel
│   │   └── selection/      # PhotoSelectionManager
│   ├── albums/             # 相册列表和详情
│   ├── detail/             # 照片详情 (ViewPager2 + ZoomImageView + ExoPlayer)
│   ├── trash/              # 回收站
│   ├── hidden/             # 隐藏照片 (需生物识别认证)
│   ├── about/              # 关于页面组
│   └── common/             # 共享组件 (BaseSelectionManager, GridSpanCalculator, PhotoGridViewHolder)
├── MainActivity.kt
└── StarGalleryApp.kt
```

## 内存泄漏

- Debug 构建含 LeakCanary
- `lifecycleScope.launch` 的 Flow 收集需确保 ViewModel `onCleared` 时清理
- Fragment `onDestroyView` 中需清理 RecyclerView 引用（adapter/layoutManager/itemAnimator 置 null）

## 测试

- 仅有占位单元测试 `app/src/test/.../ExampleUnitTest.kt`
- 无集成测试、无 UI 测试

## 特殊功能

- **RAW 格式识别**：DNG/ARW/CR2/CR3/NEF 等，同名 JPG+RAW 自动配对
- **EXIF 筛选**：按相机品牌/型号/镜头型号过滤，底部弹窗选择
- **隐藏照片**：`HiddenFragment` 进入需 BiometricPrompt 认证（指纹/设备密码）
- **拖动多选**：`drag-select-recyclerview` 库，各页面有独立 SelectionManager
- **快速滚动条**：FastScroller，滚动时显示日期分隔符预览
