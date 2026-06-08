# StarGallery — 项目指南

## 强制规则

**所有思考与回复必须使用中文。**

## 构建

```powershell
.\gradlew.bat assembleDebug          # Debug APK
.\gradlew.bat testDebugUnitTest       # 单元测试
```

单模块 `:app`，版本目录 `gradle/libs.versions.toml`。依赖通过腾讯镜像加速（`settings.gradle.kts`）。
Kotlin 2.3.20，AGP 9.2.1，minSdk 30，compileSdk 36，targetSdk 35，Java 21。

## 架构要点

- **MVVM + Repository + Room + Hilt + Navigation**
- 入口：`MainActivity` → `nav_graph.xml` → `photosFragment` (start)
- Hilt 用 **KSP**（非 kapt）：`ksp(libs.hilt.compiler)`
- Navigation 用 **SafeArgs**，参数定义在 `nav_graph.xml`，禁止手动 Bundle
- **ViewBinding** 替代 findViewById，使用 `viewBinding.root` 访问根视图
- 插件：`com.android.application`、`navigation.safeargs`、`ksp`、`hilt`、`kotlin.parcelize`

## 数据加载模式（非 Paging 3）

**项目已从 Paging 3 迁移到 Room Flow + ListAdapter 模式：**

- `PhotoDao.getAllPhotosFlow()` 返回 `Flow<List<PhotoEntity>>`，Room 自动监听表变化推送更新
- `PhotosViewModel.photoListFlow` 通过 `combine` 合并排序、收藏过滤、EXIF 过滤、分组、搜索等状态，在内存中排序/过滤/插入 `SeparatorItem`
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
- **动态预加载数量**：`currentSpanCount * 3`（`RecyclerViewPreloader` 预加载图片数）、`currentSpanCount * 4`（`GridLayoutManager.initialPrefetchItemCount` 布局预取数），切换列数时自动重建预加载器
- **ZoomImageView**（ZoomImage 1.4.0）大图子采样（>=2000px 启用）
- **ExoPlayerManager**：全局单例，跨页面保持播放状态

## RecyclerView 网格

- `GridLayoutManager` 3-8 列可配置（`GridSpanCalculator` 支持 3-10 列，UI 限制 3-8），`spanSizeLookup` 控制 SeparatorItem 占整行
- `setHasFixedSize(true)` + `setItemViewCacheSize(8)`
- `supportsChangeAnimations = false` 避免变更动画残影
- `PhotoItemAnimator` 删除动画：缩小+渐隐（仅 TYPE_PHOTO）
- 扫描时 `itemAnimator = null` 防快速刷新乱跳，扫描完成后恢复

## 网格列数偏好（横竖屏独立设置）

- 工具类 `ui/common/GridSpanPreferences.kt`：封装竖横屏双 key 读写 + 解析规则
- 解析规则（当前方向有值优先；都没有再走 `fallback`）：
  1) 当前方向有存值 → 用
  2) 否则另一方向有存值 → 用另一方向（用户没在当前方向设过，暂复用）
  3) 都没有 → 用 `fallback`（按屏宽计算的最佳值）
- 写入：用户主动改列数时 `save(prefix, isLandscape, value)`，按**当前方向**写入对应 key
- 旋转（`onConfigurationChanged`）调用 resolver 取数，**不写**偏好
- **首页** prefix = `"span_count"`（旧 `span_count` 单 key 已弃用）
- **相册详情** prefix = `"album_span_count"`（独立键，互不影响）
- AlbumDetailFragment 复用同一套 resolver 逻辑，与首页行为完全一致（见"相册管理"一节）

## 搜索功能

- `btnSearch` 进入搜索模式，`etSearch` + TextWatcher + IME_ACTION_SEARCH
- `viewModel.setSearchQuery(query)` 在 `photoListFlow` combine 链中过滤 `displayName` 和 `bucketName`
- 搜索工具栏显示 `tvSearchSubtitle`：`"搜索：查询词 — N张"`

## 照片详情页

- **ViewPager2 + PhotoPagerAdapter** 滑动翻页，DiffUtil 处理列表变化
- **顶部工具栏**（AutoHide）：btnBack / tvDate / tvInfo / **btnMore（PopupMenu → 隐藏）**
- **底部工具栏**：btnSend（分享）/ btnFavorite（收藏）/ btnDelete（删除→DeleteOptionsBottomSheet）/ btnInfo（PhotoInfoBottomSheet）
- **全屏切换**：单点切换，Alpha 动画 200ms，WindowInsetsController 隐藏系统栏
- **下滑关闭**：垂直滑动 > 200px 触发 navigateUp()，透明度跟随滑动距离
- **PhotoPageViewHolder**：ZoomImageView 子采样 + Glide 加载 + ExoPlayer 视频 + GIF + HDR 检测
- **400ms 加载延迟**：`PhotoDetailViewModel.loadPhotosInBackground()` 等待导航动画完成，避免掉帧
- **底部工具栏 systemBars inset**：与 `TrashPhotoPreviewDialog` 同样的 `ViewCompat.setOnApplyWindowInsetsListener` 处理 `systemBars.bottom`，保证按钮在系统导航栏之上

## HDR 检测（PhotoPageViewHolder）

三重检测逻辑：
1. Android 14+ `Bitmap.hasGainmap()` → Ultra HDR
2. `Bitmap.Config.RGBA_F16` → 高位深 HDR
3. `ColorSpace` 名称检测（BT2020, BT2020_HLG, BT2020_PQ 等）+ `isWideGamut`

## EXIF 处理

- **ExifExtractor**（326 行）：提取 20+ EXIF 字段，按优先级解析最佳日期（DateTimeOriginal → DateTimeDigitized → IFD0 DateTime → dateTaken）
- **PhotoStyleResolver**（446 行）：7 大品牌照片风格/色彩模式/胶片模拟映射
  - 松下（Panasonic）：TAG_PHOTO_STYLE，19 种（Standard/Vivid/Cinelike/V-Log 等）
  - 索尼（Sony）：TAG_COLOR_MODE，25+ 种（Standard/Vivid/FL/IN/SH 等）
  - 佳能（Canon）：TAG_PHOTO_EFFECT + PictureStyle，18 种（含 R 系列 Fine Detail）
  - 尼康（Nikon）：PictureControl，从 MakerNote 描述字符串提取
  - 富士（Fujifilm）：TAG_FILM_MODE，12 种胶片模拟（Provia/Velvia/Classic Chrome/Eterna/Classic Negative/Nostalgic Neg 等）
  - 奥林巴斯（Olympus）：TagPictureMode，18 种（含 Art Filter）
  - 宾得（Pentax）：ImageTone + PictureMode，20+ 种
- **PhotoInfoBottomSheet**（433 行）：Hilt 注入 PhotoDao，显示全量 EXIF 信息，地图 App 跳转（高德/腾讯/百度/谷歌），坐标 WGS84→GCJ02 转换
- **CoordinateUtils**：WGS84→GCJ02 坐标转换，用于中国地图显示

## PhotoGridViewHolder 配置系统

```kotlin
data class ViewHolderConfig(
    val fixedSize: Boolean = false,
    val itemSize: Int = 0,
    val useClickProcessing: Boolean = true,
    val showFavorite: Boolean = true,
    val showVideoIndicator: Boolean = true,
    val showFormatTag: Boolean = true,
    val showExpirationTag: Boolean = false  // 回收站专用
)
```
- Photos/Albums 默认显示所有状态
- Trash：`showExpirationTag=true`，显示剩余天数
- Hidden：全关

## PhotoListAdapter

- `ListAdapter<PhotoModel, RecyclerView.ViewHolder>`
- 2 种 ViewType：`TYPE_HEADER(0)` → HeaderViewHolder（日期分隔符），`TYPE_PHOTO(1)` → PhotoGridViewHolder
- DiffUtil：`areItemsTheSame` PhotoItem → id，SeparatorItem → dateText；`areContentsTheSame` → equals

## 扫描机制

- **MediaScanner**（737 行）：全量扫描 + 增量扫描 + 精确同步
- **全量扫描**：查询 MediaStore → 批量写入 Room → 删除孤立记录 → 后台 EXIF 提取
- **增量扫描**：按 lastScanTime 查询变更 → 写入 Room → 双向同步（清理孤立 + 补充缺失记录）→ EXIF 提取
- **双向同步**：增量扫描同时删除 MediaStore 中已消失的记录，恢复从回收站还原的记录
- **EXIF 批量提取**：`EXIF_BATCH_SIZE = 20`，跳过已有 EXIF 数据，失败 5 秒后重试
- **ScanState**（SharedFlow）：`Idle` / `Scanning(current, total, progress)` / `Completed` / `Error`
- **ScanningProgressDialog**：DialogFragment，底部卡片进度条，含完成动画，1.5 秒自动关闭
- **ScanViewModel**：ActivityViewModels，管理扫描生命周期
- 初始化触发：`PhotosViewModel.init` 检查 `!scanPreferences.isScanCompleted` → 执行全量扫描

## ScanPreferences 键

| 键 | 类型 | 默认值 | 说明 |
|----|------|--------|------|
| `scan_completed` | Boolean | false | 首次扫描是否完成 |
| `last_scan_time` | Long | 0L | 最后扫描时间（秒） |
| `last_media_count` | Int | 0 | 最后扫描媒体数量 |
| `incremental_since_deletion_check` | Int | 0 | 上次删除检查后的增量扫描次数 |

删除检查：每 50 次增量扫描执行一次（`DELETION_CHECK_INTERVAL = 50`）

## 回收站

- `TrashFragment`：GridLayoutManager + TrashAdapter（ListAdapter<Photo>），TrashSelectionManager
- 恢复操作：`mediaRepository.restorePhotos()`（IntentSender）
- 永久删除：`mediaRepository.deletePhotos()`（IntentSender）
- `TrashPhotoPreviewDialog`：全屏 ZoomImageView 预览，内联恢复/删除按钮
- **`TrashPhotoPreviewDialog` 底部按钮 systemBars inset**：通过 `ViewCompat.setOnApplyWindowInsetsListener(bottomBar)` 把 `systemBars.bottom + 16dp` 写到 paddingBottom，按钮始终在系统导航栏之上
- `dateExpiration` 回退规则：`DATE_EXPIRES > 0 → expires*1000`，否则 `(dateModified+30天)*1000`

## 隐藏照片

- `HiddenFragment`：BiometricPrompt 认证（BIOMETRIC_STRONG + DEVICE_CREDENTIAL），认证失败自动返回
- 数据源：Room Flow（`photoDao.getAllPhotosFlow()` → filter `isHidden`）
- 恢复操作：`photoDao.updateHiddenBatch(ids, false)`（无需 IntentSender，直接写 Room）

## 相册管理

- `AlbumsFragment`：内置 AlbumAdapter（ListAdapter<Album>），3 列 Grid，Glide 封面
- `AlbumDetailFragment`：
  - 复用 `fragment_photos.xml` 布局
  - 复用 `PhotoGridViewHolder` + `AlbumDetailAdapter`（与 `PhotoListAdapter` 结构完全一致，共享 `PhotoGridViewHolder`）
  - **网格列数偏好** prefix = `"album_span_count"`，与首页各自独立，**通过 `GridSpanPreferences` 复用同一套解析/写入规则**（竖横屏独立 + 旋转不写偏好 + resolver 跨方向 fallback）
  - 排序/分组：独立的 `KEY_SORT_TYPE_ALBUM` / `KEY_GROUP_TYPE_ALBUM` 键
  - 数据源：MediaStore 直接查询（非 Room），手动排序/分组

## 导航图

```
photosFragment (start)
  → photoDetailFragment    (slide_in_right/out_left)
  → trashFragment
  → hiddenFragment         [需生物识别]
  → aboutFragment

albumsFragment
  → albumDetailFragment
  → photoDetailFragment    (slide)

albumDetailFragment
  → photoDetailFragment    (slide)

hiddenFragment
  → photoDetailFragment    (slide)

aboutFragment
  → privacyPolicyFragment, permissionsFragment, thirdPartyLibrariesFragment, contactFragment, licenseFragment
```

`photoDetailFragment` 参数：`initialPhoto`（Photo?）、`photoId`（long）、`sortType`（int）、`bucketId`（long, default=-1）、`favoritesOnly`（boolean, default=false）、`filterCameraMake`（string?）、`filterCameraModel`（string?）、`filterLensModel`（string?）

EXIF 筛选参数以 `\n` 分隔 `Set<String>` 编码传递，详情页解码后复用一致过滤逻辑。

## 底部导航（BottomNavigationView 自适应宽度）

- `MainActivity.applyBottomNavWidth()`：根据 `resources.displayMetrics.widthPixels` 计算目标宽度 `min(屏宽 - 2*32dp, 480dp)`，居中显示
- 解决横屏/大屏设备上固定 `64dp * 2` margin 导致悬浮胶囊过宽的问题
- 调用时机：`onCreate` 末尾 + `onConfigurationChanged`
- 底部 systemBars insets：与目标宽度叠加使用，通过 `ViewCompat.setOnApplyWindowInsetsListener` 把 `systemBars.bottom` 加到 `bottomMargin`，避开系统导航栏
- XML 中 `bottom_nav` 用 `wrap_content` + 居中约束 + 32dp marginStart/End（代码动态覆盖实际值）

## 横屏适配（layout-land/）

- `AndroidManifest.xml:44` `configChanges="orientation|screenSize|screenLayout"` → 旋转时 **Activity/Fragment 不重建**，仅触发 `onConfigurationChanged`
- 关键问题：**FastScroller（1.3.0）通过匿名 ItemDecoration 的 `onDraw` 触发 onPreDraw**；原 `updateSpanCount` 用 `while itemDecorationCount > 0 { removeItemDecorationAt(0) }` 会把该匿名装饰一并清掉，导致 thumb 位置永久卡在旧 width（"thumb 出现在中间"）
- 修复：保存 `gridSpacingItemDecoration` 引用，`updateSpanCount` 只 `removeItemDecoration(this)` 后重建
- 关键问题：**DragSelectTouchListener 抢占 `OnItemTouchListener`**，导致 FastScroller 的 thumb 触摸被吞；解决：竖横屏 RecyclerView 都用 `me.zhanghai.android.fastscroll.FixOnItemTouchListenerRecyclerView`（库 1.3.0 官方修正类，统一 RV 类型以兼容 ViewBinding）
- 关键问题：**横屏 layout 用 `ConstraintLayout` 直挂 RV**（不依赖 `appbar_scrolling_view_behavior`），让 RV width 在旋转后立即撑满新宽度，FastScroller 的 onPreDraw 用 `mView.getWidth()` 自动贴齐屏幕右边缘
- 关键问题：FastScroller thumb 与悬浮 BottomNav 在底部重叠 → builder 加 `setPadding(0, 0, 0, 80dp)` 让 thumb 跳过 BottomNav 区
- `layout-land/fragment_photos.xml` + `layout-land/activity_main.xml` 资源存在
- 旋转重建（`onConfigurationChanged`）必须同步调用：
  - `applyBottomNavWidth()`（MainActivity）
  - `GridSpanPreferences.resolveForOrientation(...)` + `applySpanCount`（Photos/AlbumDetail）

## 关键目录

```
app/src/main/java/com/gxstar/stargallery/
├── MainActivity.kt                  # applyBottomNavWidth + insets + 旋转
├── StarGalleryApp.kt
├── data/
│   ├── local/
│   │   ├── db/             # PhotoDao, PhotoEntity, AppDatabase (Room, v7)
│   │   ├── scanner/        # MediaScanner (全量/增量扫描)
│   │   ├── exif/           # ExifExtractor, PhotoStyleResolver (7品牌)
│   │   └── preferences/    # ScanPreferences (4 keys)
│   ├── model/              # Photo (Parcelable, 25+ fields), Album
│   └── repository/         # MediaRepository (MediaStore 操作)
├── di/                     # Hilt 模块 (AppModule, DatabaseModule, PreferenceModule)
├── ui/
│   ├── photos/             # 首页网格 (PhotosFragment, PhotosViewModel)
│   │   ├── action/         # BatchActionHandler (6种操作)
│   │   ├── animation/      # PhotoItemAnimator (DefaultItemAnimator)
│   │   ├── filter/         # FilterBottomSheet (EXIF 三维多选)
│   │   ├── launcher/       # IntentSenderManager (3个 Launcher)
│   │   ├── model/          # PhotoModel (PhotoItem + SeparatorItem)
│   │   ├── refresh/        # MediaChangeDetector (ContentObserver)
│   │   ├── scanner/        # ScanningProgressDialog, ScanViewModel
│   │   ├── selection/      # PhotoSelectionManager
│   │   ├── GridSpacingItemDecoration.kt
│   │   ├── PhotoListAdapter.kt
│   │   └── PhotoPreloadModelProvider.kt
│   ├── albums/             # 相册列表 (AlbumsFragment) + 详情 (AlbumDetailFragment)
│   ├── detail/             # 照片详情 (ViewPager2 + ZoomImageView + ExoPlayer + HDR)
│   │   ├── ExoPlayerManager.kt        # 单例
│   │   ├── PhotoDetailFragment.kt
│   │   ├── PhotoDetailViewModel.kt
│   │   ├── PhotoInfoBottomSheet.kt    # (EXIF + 地图)
│   │   ├── PhotoPagerAdapter.kt       # DiffUtil
│   │   └── PhotoPageViewHolder.kt     # (缩放/视频/GIF/HDR)
│   ├── trash/              # 回收站 (TrashFragment, TrashPhotoPreviewDialog)
│   ├── hidden/             # 隐藏照片 (HiddenFragment, 需生物识别)
│   ├── about/              # 6个子页面
│   ├── common/             # 共享组件
│   │   ├── BaseSelectionManager.kt
│   │   ├── DeleteOptionsBottomSheet.kt
│   │   ├── Extensions.kt
│   │   ├── GridSpanCalculator.kt      # 屏宽 → 最佳列数
│   │   ├── GridSpanPreferences.kt     # 竖横屏独立列数偏好（首页 + AlbumDetail 共用）
│   │   ├── PhotoGridViewHolder.kt     # (含 ViewHolderConfig)
│   │   └── PhotoSelectionTracker.kt
│   └── util/               # CoordinateUtils, DateUtils, SortUtils
```

## 内存泄漏

- Debug 构建含 LeakCanary
- `lifecycleScope.launch` 的 Flow 收集需确保 ViewModel `onCleared` 时清理
- Fragment `onDestroyView` 中需清理 RecyclerView 引用（adapter/layoutManager/itemAnimator/gridSpacingItemDecoration 置 null），ExoPlayerManager.release()
- `BaseSelectionManager.clear()` 清理所有引用

## 测试

- 仅有占位单元测试 `app/src/test/.../ExampleUnitTest.kt`
- 无集成测试、无 UI 测试

## 特殊功能

- **RAW 格式识别**：DNG/ARW/CR2/CR3/NEF/ORF/RAF/RW2/PEF 等，同名 JPG+RAW 自动配对
- **EXIF 多选筛选**：按相机品牌/型号/镜头型号多选过滤，级联自动推导，维度间 AND + 维度内 OR
- **排序一致性**：`SortUtils.sortPhotos()` 三级排序，网格与详情页完全统一
- **张数实时显示**：`filteredPhotoCount` 从 `photoListFlow` 派生，始终精确反映当前屏幕显示数量
- **隐藏照片**：`HiddenFragment` 进入需 BiometricPrompt 认证（指纹/设备密码）
- **拖动多选**：`drag-select-recyclerview` 库 + 基于 photo ID 的选中追踪，删除后位置不错乱
- **快速滚动条**：FastScroller（1.3.0），竖横屏都用 `FixOnItemTouchListenerRecyclerView` 解决与 DragSelect 的触摸冲突；横屏用 `layout-land/fragment_photos.xml` + `setPadding(0,0,0,80)` 让 thumb 贴齐屏幕右边缘
- **搜索**：按文件名/文件夹名实时过滤
- **HDR 检测**：三重检测（Ultra HDR / RGBA_F16 / ColorSpace）
- **照片风格解析**：7 大相机品牌照片风格/胶片模拟映射
- **坐标转换**：WGS84→GCJ02，适配中国地图
- **动态预加载**：预加载量随列数自动调整（列数 × 3/4）
- **列数偏好竖横屏独立**：首页与相册详情各自独立；当前方向未设时复用另一方向值；都没设时按屏宽计算
- **底部导航自适应宽度**：根据屏宽在 32dp~480dp 区间内自适应，避免横屏悬浮胶囊过宽
