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

## EXIF 筛选（多选模式）

- 三个维度：相机品牌、相机型号、镜头型号，均为 `Set<String>` 支持**多选**
- **维度间 AND 关系**：三个维度的 `filter` 链式调用，照片须同时满足所有非空维度
- **维度内 OR 关系**：使用 `Set.contains()` 检查，匹配任一选中项即可
- **级联筛选**：`recomputeEffective()` 自动推导 —— 选镜头→自动勾选对应型号+品牌，选型号→自动勾选对应品牌
- **显式/有效分离**：`_explicitCameraMake`（用户操作） vs `_filterCameraMake`（含级联推导结果），后者才是实际过滤条件
- `FilterBottomSheet`：ChipGroup `isSingleSelection = false`，每项 chip 独立开关。主视图值显示：单选→值名，多选→"已选 N 项"，未选→"—"
- 选项列表由 `buildFilterOptions()` 生成，含"未知设备"（key=""）选项，不含"全部"选项（由"清除筛选"按钮替代）

## 排序机制

- `SortUtils.sortPhotos()` 统一三级排序（用于网格列表 + 详情页）：
  - `DATE_TAKEN`：`normalizedDateTaken DESC → dateAdded DESC → id DESC`
  - `DATE_ADDED`：`dateAdded DESC → id DESC`
- `normalizedDateTaken` fallback 链：`dateTaken > 0 ? dateTaken : (dateModified > 0 ? dateModified*1000 : dateAdded*1000)`
- 详情页 `PhotoDetailViewModel.loadPhotosInBackground()` 在全部/过滤模式下均使用 Room + `SortUtils.sortPhotos()`，保证与网格排序**完全一致**

## 张数显示

- `filteredPhotoCount`：从 `photoListFlow` 派生，实时统计 `PhotoItem` 个数 → 精确反映当前列表显示张数
- `photoCount` / `favoriteCount` / `hiddenCount`：Room SQL 全局查询，用于 FilterBottomSheet 选项计数等
- `tvSubtitle` 始终显示 `filteredPhotoCount`，无论默认、收藏、EXIF 筛选模式

## 删除/收藏后的刷新机制

- **Room 删除** → `invalidationTracker` 自动使 Flow 失效 → `getAllPhotosFlow()` 自动推送新列表 → 无需手动刷新
- **收藏切换** → `viewModel.updateFavorite()` 更新 Room → Flow 自动推送
- **隐藏操作** → `viewModel.updateHidden()` 更新 Room → Flow 自动推送
- **ContentObserver 防抖**：`MediaChangeDetector` 有 500ms debounce + `shouldSkipRefresh`（1秒抑制窗口），`lastExplicitRefreshTime` 被设置后 1 秒内跳过回调

## 多选机制（BaseSelectionManager）

- 抽象基类 `BaseSelectionManager`，子类实现 `getItemCount()`、`getPhotoAtPosition()`、`notifyItemChanged()`、`isPositionSelectable()`
- **基于 photo ID 的选中追踪**：`_selectedPhotoIds: MutableSet<Long>`，不依赖 position（避免列表变动后选中错位）
- 需要 position 时通过 `findPositionByPhotoId()` 遍历当前列表实时定位
- `DragSelectTouchListener` 实现拖动多选，通过 `isSelected(index)` / `setSelected(index, selected)` 与底层 ID 集合交互
- **Payload 精准刷新**：`PAYLOAD_SELECTION_CHANGED` 仅更新选择 UI（`updateSelectionState()`），不重新加载图片
- `PhotoGridViewHolder` 长按使用 `bindingAdapterPosition`（RecyclerView 实时维护），而非 `bind()` 传入的旧 position

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

`photoDetailFragment` 参数：`initialPhoto`、`photoId`、`sortType`、`bucketId`、`favoritesOnly`、`filterCameraMake`、`filterCameraModel`、`filterLensModel`

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
│   │   ├── filter/         # FilterBottomSheet (EXIF 多选筛选)
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
- `BaseSelectionManager.clear()` 清理所有引用

## 测试

- 仅有占位单元测试 `app/src/test/.../ExampleUnitTest.kt`
- 无集成测试、无 UI 测试

## 特殊功能

- **RAW 格式识别**：DNG/ARW/CR2/CR3/NEF 等，同名 JPG+RAW 自动配对
- **EXIF 多选筛选**：按相机品牌/型号/镜头型号多选过滤，级联自动推导，维度间 AND + 维度内 OR
- **排序一致性**：`SortUtils.sortPhotos()` 三级排序，网格与详情页完全统一
- **张数实时显示**：`filteredPhotoCount` 从 `photoListFlow` 派生，始终精确反映当前屏幕显示数量
- **隐藏照片**：`HiddenFragment` 进入需 BiometricPrompt 认证（指纹/设备密码）
- **拖动多选**：`drag-select-recyclerview` 库 + 基于 photo ID 的选中追踪，删除后位置不错乱
- **快速滚动条**：FastScroller，滚动时显示日期分隔符预览
