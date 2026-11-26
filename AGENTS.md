# StarGallery — 项目指南

## 强制规则

**所有思考与回复必须使用中文。**

## 构建

```powershell
.\gradlew.bat assembleDebug          # Debug APK
.\gradlew.bat testDebugUnitTest       # 单元测试
```

单模块 `:app`，版本目录 `gradle/libs.versions.toml`。依赖通过腾讯镜像加速（`settings.gradle.kts`）。
Kotlin 2.4.10，AGP 9.3.1，KSP 2.3.10，minSdk 35，compileSdk 37，targetSdk 36，Java 21。

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
- `PhotosViewModel.photoListFlow` 通过 `combine` 合并排序、收藏过滤、EXIF 过滤、**排除相册**、分组、搜索等状态，在内存中排序/过滤/插入 `SeparatorItem`
- `PhotoListAdapter` 继承 `ListAdapter<PhotoModel, RecyclerView.ViewHolder>`，使用 `submitList()` 提交数据
- 数据收集使用 `lifecycleScope.launch` + `repeatOnLifecycle(STARTED)` 组合

## EXIF 筛选（注册表驱动）

- **注册表驱动**：`FilterDimensions.ALL`（`filter/FilterState.kt`）定义全部维度（当前：相机品牌/相机型号/镜头型号），每个维度含 `valueOf: (PhotoEntity) -> String?`。**新增维度唯一改动点是注册表**，面板行、选项列表、计数、chip 条、过滤管道自动生效；连续量（光圈/快门/ISO/焦段）在 `valueOf` 内直接分档成区间字符串，避免 chip 爆炸
- **单一数据源**：`PhotosViewModel._filterState: MutableStateFlow<FilterState>`（favoritesOnly + searchQuery + selections），取代旧的 6 个逐维度 StateFlow + `recomputeEffective()` 级联写回。`FilterState` 不可变，变更返回新实例供 `distinctUntilChanged` 去重
- **维度间 AND、维度内 OR**：`FilterState.matches(entity)` 统一判断
- **faceted 选项统计**：`filterOptions` 在内存中对候选集统计，`matchesExcept(排除自身维度)` —— 选了品牌后型号列表只剩该品牌型号，计数 = "选了还剩几张"；统计只在该面板订阅时计算（`WhileSubscribed(0)`），关闭即停
- **层级联动**：父维度变化后 `schedulePrune` 在后台线程 `pruneUnavailable` 裁剪子维度失效选择（异步收敛），**不再级联写回**，避免 chip 视觉与实际脱钩
- **生效条件 chip 条**：`activeConditions`（收藏/搜索/维度/排除相册）驱动顶栏 chip 条，每条可单独移除；维度 chip 点主体直达该维度列表
- **详情页同步（新增维度强制检查项）**：`PhotosFragment.navigateToDetail` 通过 SafeArgs 硬编码传 cameraMake/cameraModel/lensModel 三维，`PhotoDetailViewModel` 只解析这 3 个。**新增维度时必须同步改造详情页**，否则详情页翻页集合与网格不一致。当前未新增维度时不受影响
- `FilterBottomSheet`：ChipGroup `singleSelection=false`，每项 chip 独立开关，选中态一律以 `filterState.selectionOf()` 为唯一依据（`syncChipChecks`）。主视图值显示：单选→值名，多选→"已选 N 项"，未选→"—"；`createChip` 的 ColorStateList 必须用 `state_checked`（不是 `state_selected`）

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

## 媒体权限链路（Android 14+ 部分访问）

- **Manifest 声明** `READ_MEDIA_VISUAL_USER_SELECTED`：系统按标准模型授权，避免旧兼容路径的 `REVOKED_COMPAT`（名义 granted、实际受限）
- **`util/MediaAccessHelper.kt`**：三态判定 NONE / PARTIAL / FULL
  - FULL = `READ_MEDIA_IMAGES granted`；PARTIAL = IMAGES 未授予但 `VISUAL_USER_SELECTED granted`；其余 NONE
  - `REQUIRED_PERMISSIONS` 统一申请数组（含 VISUAL_USER_SELECTED）
- **PhotosFragment**：`checkPermissions` 用 `hasAnyAccess` 判断是否弹框（部分授权不重复弹）；权限回调/onResume 检测级别变化 → 触发补扫；PARTIAL 时显示顶部提示条引导去系统设置
- **部分授权提示条**：`fragment_photos.xml`（竖/横屏）的 `partialAccessBanner` + strings（中英）
- **权限升级补扫**：FULL 可用时 `PhotosViewModel.onFullAccessAvailable()` → `reconcileMediaStore()` 补回缺失媒体（修复"部分→完整后永久只显示选中几张"）

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
- 子类：`PhotoSelectionManager`、`AlbumSelectionManager`、`TrashSelectionManager`、`HiddenSelectionManager`

## MediaStore 操作 (IntentSender 模式)

1. `Repository` 返回 `IntentSender`（非直接执行）
2. UI 通过 `ActivityResultContracts.StartIntentSenderForResult` 启动
3. 用户确认后回调处理成功/失败
4. 用于：收藏、删除、回收站、恢复
5. `IntentSenderManager` 统一管理 favoriteLauncher / trashLauncher / deleteLauncher

## 图片加载

- **Glide** 缩略图/GIF + `RecyclerViewPreloader` 预加载
- **动态预加载数量**：`currentSpanCount * 3`（`RecyclerViewPreloader` 预加载图片数）、`currentSpanCount * 4`（`GridLayoutManager.initialPrefetchItemCount` 布局预取数），切换列数时自动重建预加载器
- **ZoomImageView**（ZoomImage 1.6.0）大图子采样（>=2000px 启用）
- **ExoPlayerManager**：全局单例，跨页面保持播放状态

## 缩略图缓存（ThumbnailManager）

- `data/local/ThumbnailManager.kt`：通过 `ContentResolver.loadThumbnail()` 生成 512px JPEG 缩略图
- 缓存目录：`cache/thumbnails/{photoId}.jpg`，首次生成后 Glide 优先加载本地缓存文件
- 支持：批量删除、孤儿清理（`cleanupOrphanedThumbnails()`）、数量统计
- RAW 格式跳过缩略图生成（`image/x-*`）

## RecyclerView 网格

- 分组头（`item_date_header`）：日期 + 右侧「N张」小字（`SeparatorItem.count`，基线对齐 12sp text_secondary），首页/相册详情两处共用；缩略图角标（HDR/RAW/过期）统一用 `bg_thumb_tag`（4dp 圆角 + 60% 黑）
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
- **顶/底栏是六块独立浮动玻璃**（2026-11 按 iOS 26/27 相册重做，不再有整幅贴边长条）：
  - 顶部 `topOverlay`（ConstraintLayout）：`glassBack`（46dp 正圆，左 18dp）+ `glassDateCapsule`（46dp 高胶囊，含 tvDate/tvInfo 双行）+ `glassMore`（46dp 正圆，右 18dp）
  - 底部 `bottomOverlay`（纵向 LinearLayout）：`filmstrip`（44dp 高横向 RecyclerView）+ `bottomBar` 行（`glassShare` 46dp 圆 / `glassActions` 46dp 三键胶囊 / `glassDelete` 46dp 圆）
  - 材质（2026-11 二次修正后的可用配方）：`glassMaterial=regular`（**不要用 clear**，clear 自带压暗层，压白底就是灰）+ `blurAmount="0.08"`（**0~1 的比例值，不是像素**，默认 0.0625，传大数会把底糊成一团灰）+ `glassTintStrength=0.5` + 白纱**次层** `@drawable/bg_detail_glass_shell`（`detail_glass_fill` 80% 白 `#CCFFFFFF`，浅色下实测 RGB≈246，35%/58%/70% 依次被用户判为「偏灰」 + `detail_glass_stroke` 0.5dp 描边，radius 100dp 自动裁圆/胶囊）
  - **`backdropSource` 必须手动指到 `rootContainer`**（`setupGlassOverlays()` 里代码设置）：库默认捕获「直接父容器」，玻璃块挂在透明的 topOverlay/bottomBar 里时拍到的是空面板，玻璃永远是一块死灰——这是「玻璃变灰实心」的根因
  - `adaptiveTint=true`（浅色背景自动变白变实、深色照片上提亮，仿 iOS 自适应材质）+ `enableEdgeHighlight=true` / `edgeHighlightOpacity=0.5f`；**`enableShadow` 必须关**——库的阴影按 View 矩形边界画灰色块，圆形/胶囊按钮外圈会露出一圈方形灰边（2026-11 实机验证）
  - 每块玻璃都必须在 `setupGlassOverlays()` 里 `enableDynamicBackground=true`，漏一块翻页时那块会「卡住」不跟随
  - 已知取舍：玻璃明暗跟随底片颜色，压在深色照片上时黑字对比度会下降（首页导航仍是 clear + 80% 厚白纱的另一套配方，两处不要互相抄参数）
- **底栏不再有文字标签**（iOS 是纯图标）：图标 24dp、`android:tint="@color/nav_item_active"`；收藏态实心红心在 `updateFavoriteIcon()` 单独回染 `@color/heart_red`
- **inset 只加在容器上**：`topOverlay.paddingTop = systemBars.top`、`bottomOverlay.paddingBottom = 12dp + systemBars.bottom`。绝不要把 inset 塞进玻璃内部 padding，否则圆角与外形失配
- **胶片条 `DetailFilmstripAdapter`**：单项高 44dp、宽按宽高比（clamp 0.62~1.55）自适应，`orientation` 90/270 交换宽高；当前项只用 2dp 描边定位；缩略图优先 `thumbnailPath` 缓存文件、否则 content uri；点击 `setCurrentItem`，翻页回调里 `syncFilmstripToPager()` 换描边并把当前项滚到中央
- **玻璃上的次级文字**（tvInfo 时间）用 `detail_glass_text_secondary`（亮 #3C3C3E / 暗 #D1D1D6）+ bold——普通 `text_secondary` 压在透底玻璃上会融掉（2026-11 实机教训）
- **全屏切换**：单点切换，Alpha 动画 200ms，淡入淡出的是 `topOverlay` / `bottomOverlay` 两个容器；退出全屏后补一次 `syncFilmstripToPager()`（全屏期间胶片条 GONE、宽度 0，居中会被跳过）
- **下滑关闭**：垂直滑动 > 200px 触发 navigateUp()，透明度跟随滑动距离
- **PhotoPageViewHolder**：ZoomImageView 子采样 + Glide 加载 + ExoPlayer 视频 + GIF + HDR 检测
- **400ms 加载延迟**：`PhotoDetailViewModel.loadPhotosInBackground()` 等待导航动画完成，避免掉帧
- **底部工具栏 systemBars inset**：与 `TrashPhotoPreviewDialog` 同样的 `ViewCompat.setOnApplyWindowInsetsListener` 处理 `systemBars.bottom`，保证按钮在系统导航栏之上

## 详情页列表缓存（PhotoDetailListCache）

- `ui/detail/PhotoDetailListCache.kt`：@Singleton，导航跳转前快照当前网格可见列表
- `put(photos)` 写入 → `take(expectedPhotoId)` 读取后立即清空
- 详情页 `PhotoDetailViewModel` 初始化时优先读取缓存，避免重新 Room 查询 + 过滤 + 排序
- `take()` 验证 `expectedPhotoId` 存在性，不匹配则回退 Room 查询

## AVIF 子采样解码（AvifRegionDecoder）

- `ui/detail/AvifRegionDecoder.kt`：ZoomImage 的 `RegionDecoder` 扩展，Android 12+ 支持
- AVIF 不支持 `BitmapRegionDecoder` → 采用 `ImageDecoder` 全图缩放 + 裁切策略
- `Factory` 注册模式识别 `image/avif` MIME，仅 Android 12+ 启用

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
    val showExpirationTag: Boolean = false,
    val showHdrTag: Boolean = true
)
```
- Photos/Albums 默认显示所有状态（含 HDR 标签）
- Trash：`showExpirationTag=true`，显示剩余天数
- Hidden：全关（`showHdrTag=false`）

## PhotoListAdapter

- `ListAdapter<PhotoModel, RecyclerView.ViewHolder>`
- 2 种 ViewType：`TYPE_HEADER(0)` → HeaderViewHolder（日期分隔符），`TYPE_PHOTO(1)` → PhotoGridViewHolder
- DiffUtil：`areItemsTheSame` PhotoItem → id，SeparatorItem → dateText；`areContentsTheSame` → equals

## 扫描机制

- **MediaScanner**：全量扫描 + 增量扫描 + 精确同步 + 数量对账（约 1100 行）
- **全量扫描**：查询 MediaStore → 批量写入 Room → 删除孤立记录 → 后台 EXIF 提取
- **增量扫描**：按 lastScanTime 查询变更 → 写入 Room → 双向同步（清理孤立 + 补充缺失记录）→ EXIF 提取
  - 时间戳仅在全流程成功后推进到「最大 dateModified+1」，异常不推进（避免漏扫），边界秒不重扫
- **双向同步**：差集用「含回收站」的 MediaStore ID 集合，移入回收站的照片不算永久删除（保留本地状态）
- **数量对账**（`reconcileMediaStore`）：对比 MediaStore 与 Room 数量，缺则分批补回（每批 200）；权限升级为完整访问后自动触发，不再只靠「每 50 次增量」的删除检查
- **EXIF 批量提取**：`EXIF_BATCH_SIZE = 20`，跳过已有 EXIF 数据，失败 5 秒后重试
- **ScanState**（SharedFlow）：`Idle` / `Scanning(current, total, progress)` / `Completed` / `Error`
- 初始化触发：`PhotosViewModel.init` 先判权限（`MediaAccessHelper.hasAnyAccess`），无权限不扫描；再按 `!scanPreferences.isScanCompleted` → 全量扫描，或 `isCompleted=true 但 isExifExtractionCompleted=false` → 恢复 EXIF 提取

## ScanPreferences 键

| 键 | 类型 | 默认值 | 说明 |
|----|------|--------|------|
| `scan_completed` | Boolean | false | 首次扫描是否完成 |
| `exif_extraction_completed` | Boolean | false | EXIF 提取是否完成（进程被杀后恢复用） |
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
- `HiddenAdapter`：继承 `ListAdapter<Photo, PhotoGridViewHolder>`，`setHasStableIds(true)`
- `HiddenSelectionManager`：继承 `BaseSelectionManager`
- ViewHolderConfig：全关（`showFavorite=false`、`showVideoIndicator=false`、`showFormatTag=false`、`showExpirationTag=false`、`showHdrTag=false`）

## 设置页面

- `ui/settings/SettingsFragment.kt`：Hilt 注入，三个设置项：
  - **语言选择**：系统/中文/英文三档，单选对话框切换
  - **排除相册**：点击进入 ExcludedAlbumsFragment
  - **HDR 显示**：MaterialSwitch 实时控制 HDR 标签全局开关

### 语言切换（LocaleManager）

- `util/LocaleManager.kt`：@Singleton，SharedPreferences 持久化语言选择
- 通过 `AppCompatDelegate.setApplicationLocales()` 全局设置，触发 Activity 重建
- 三档：`system`（跟随系统）/ `zh`（简体中文）/ `en`（English）
- `StateFlow<String>` 实时监听当前语言
- `StarGalleryApp.onCreate()` 调用 `localeManager.applyLocale()` 冷启动恢复

### 排除相册（ExcludedAlbumManager）

- `util/ExcludedAlbumManager.kt`：@Singleton，SharedPreferences 存储 `Set<String>(bucketId)` → `StateFlow<Set<Long>>`
- 方法：`isExcluded()`、`setExcluded()`、`setExcludedBatch()`、`setAllExcluded()`
- `PhotosViewModel.baseFilteredList` 通过 `combine` 联动排除 → Room Flow 自动推送更新
- `ExcludedAlbumsFragment`：动态加载相册列表，多选弹窗管理排除项，`MaterialAlertDialogBuilder` + `setMultiChoiceItems`
- 隔离扫描：MediaScanner 增量/全量扫描时检查 exclude 状态

### HDR 显示开关（HdrDisplayManager）

- `util/HdrDisplayManager.kt`：@Singleton，SharedPreferences 存储 HDR 标签显示状态（默认 true）
- SettingsFragment 内 MaterialSwitch 实时切换
- PhotoGridViewHolder 通过 `config.showHdrTag` 控制显示

## 相册管理

- `AlbumsFragment`：内置 AlbumAdapter（ListAdapter<Album>），3 列 Grid，Glide 封面
- `AlbumDetailFragment`：
  - 复用 `fragment_photos.xml` 布局
  - 复用 `PhotoGridViewHolder` + `AlbumDetailAdapter`（与 `PhotoListAdapter` 结构完全一致，共享 `PhotoGridViewHolder`）
  - **网格列数偏好** prefix = `"album_span_count"`，与首页各自独立，**通过 `GridSpanPreferences` 复用同一套解析/写入规则**（竖横屏独立 + 旋转不写偏好 + resolver 跨方向 fallback）
  - 排序/分组：独立的 `KEY_SORT_TYPE_ALBUM` / `KEY_GROUP_TYPE_ALBUM` 键
  - 数据源：MediaStore 直接查询（非 Room），手动排序/分组
- `AlbumSelectionManager`：继承 `BaseSelectionManager`，只选择 `PhotoItem`（跳过 `SeparatorItem`）

## 导航图

```
photosFragment (start)
  → photoDetailFragment    (slide_in_right/out_left)
  → trashFragment
  → hiddenFragment         [需生物识别]
  → aboutFragment
  → settingsFragment

albumsFragment
  → albumDetailFragment

albumDetailFragment
  → photoDetailFragment    (slide)

hiddenFragment
  → photoDetailFragment    (slide)

aboutFragment
  → privacyPolicyFragment, permissionsFragment, thirdPartyLibrariesFragment, contactFragment, licenseFragment

settingsFragment
  → excludedAlbumsFragment
```

`photoDetailFragment` 参数：`initialPhoto`（Photo?）、`photoId`（long）、`sortType`（int）、`bucketId`（long, default=-1）、`favoritesOnly`（boolean, default=false）、`filterCameraMake`（string?）、`filterCameraModel`（string?）、`filterLensModel`（string?）

EXIF 筛选参数以 `\n` 分隔 `Set<String>` 编码传递，详情页解码后复用一致过滤逻辑。

## 底部导航（浮动液态玻璃紧凑胶囊）

视觉基准是系统相册（HyperOS）的底部胶囊，但**用户最终选了"更透"而不是"更白"**。下列都是硬规则（每条都踩过坑）：

- **尺寸定死在 XML**：`bottom_nav_glass` = `176dp × 56dp`、`cornerRadius=28dp`、左右居中约束、`marginBottom=12dp`。宽度 = 4dp padding + 2 × 84dp 导航项 + 4dp padding。容器宽度**不再由代码按屏宽计算**（旧的 `applyBottomNavWidth()` 已删除，它会覆盖 XML 宽度导致胶囊永远满宽）
- **材质与详情页六块玻璃同一配方（2026-11 统一，勿再分开调参）**：`glassMaterial=regular` + `blurAmount="0.08"`（**0~1 的比例值，不是像素**；旧结论「regular 糊成灰/不透」是 blurAmount 越界 + 背景采样失败叠加造成的误判，`clear` 自带压暗层、压白底反而发灰）+ `glassTintStrength=0.5` + `adaptiveTint=true`（浅底变白实/深底提亮）
- **白纱只有一层**：内层导航项容器挂 `@drawable/bg_detail_glass_shell`（80% 白 `detail_glass_fill` `#CCFFFFFF` / 夜 `#801C1C1E` + 0.5dp `detail_glass_stroke` 描边，radius 100dp 自动裁成 28dp 药丸），容器 background 只能挂全透明的 `bg_nav_outline`；铺满整只胶囊、**不要 inset**（inset 会做出"内部白块 + 外圈亮边"的割裂感）
- **代码侧三件套（`setupGlassNavBar()`）**：`enableDynamicBackground=true`、`backdropSource = rootLayout`（不指到内容根容器就会拍到空面板 → 死灰）、`enableShadow=false`（库的阴影是矩形灰块）、`enableEdgeHighlight=true` + `edgeHighlightOpacity=0.5f`
- **选中镜片**：`bg_nav_item_pill` 用**半透明黑**（`@color/glass_indicator_bg` day `#29000000` / night `#29FFFFFF`，约 16%），四边内缩 3dp、圆角 24dp。半透明黑随底色自适应，固定实心灰会和白纱同色而消失
- **前景色**：`applyNavForegroundColors()` 取 `nav_item_active`（day `#000000`）/ `nav_item_idle`（day `#3A3A40`）day/night 资源，不按背景明暗动态切换；文字不要加 shadow
- **底部 insets**：`ViewCompat.setOnApplyWindowInsetsListener` 把 `systemBars.bottom` 加到 `bottomMargin`，基础值 12dp
- **列表留白**：`FloatingNavMetrics`（`NAV_HEIGHT_DP=56` / `NAV_BOTTOM_GAP_DP=12` / `CONTENT_BREATHING_DP=12`）是唯一换算口径
- **底部导航可见性**：`NavController.addOnDestinationChangedListener` 监听目标变化，仅 photosFragment / albumsFragment 显示
- **顶层 tab 切换动画（2026-11 三次修正）**：统一入口 `navigateToTab()`，手机点击、平板 rail、左右滑动三条入口共用。
  - 前进（图库→相册）走 nav action `action_photosFragment_to_albumsFragment`，动画定义在 nav_graph（enter `slide_in_right` / exit `slide_out_left` / popEnter `slide_in_left` / popExit `slide_out_right`，各 300ms）
  - **关键坑：`navigate(actionId)` 必须传 null 的 NavOptions**。Navigation 2.9.8 `NavController.android.kt:854` 是 `if (finalNavOptions == null) finalNavOptions = navAction.navOptions` —— 传入任何非 null NavOptions（哪怕只是 `setLaunchSingleTop(true)`）都会整段覆盖 action 动画，前进和返回的动画一起丢失。`setLaunchSingleTop` 在这里本来也无意义（两个不同 destination）。
  - 返回（相册→图库）用 `popBackStack(R.id.photosFragment, false)`。`FragmentNavigator.popBackStack()` 本身不带动画参数，返回动画 100% 依赖前进时 `setCustomAnimations()` 写入 back stack 的 popEnter/popExit —— 所以上一条不成立时返回必然硬切。**不要用「navigate + setPopUpTo」代替**：那样取不到正确方向的 pop 动画。
  - **左右滑手势**：`setupSwipeNavigation()` 只创建 GestureDetector，喂事件放在 **Activity 的 `dispatchTouchEvent()`**（`if (::swipeDetector.isInitialized) swipeDetector.onTouchEvent(ev)`，然后照常 `super`）。**不要挂在 nav host 容器的 OnTouchListener 上**——它是 ViewGroup，子 View（RecyclerView）消费 ACTION_DOWN 后父容器永远收不到后续事件，手势完全不触发（2026-11 实机踩坑：日志无任何事件）。onFling 里先判断当前 destination 只在两个首页 tab 生效，水平位移 > 120dp 且 > 垂直位移、水平速度 > 120dp/s 才切换。左滑进相册、右滑回图库。这是 fling 触发整页 slide，不是跟手拖拽；Activity 层只喂不消费，网格滚动与详情页 ViewPager2 不受影响。
  - **rail 不能用 `setupWithNavController`**——它走默认 NavOptions，同样是无动画硬切；已改为 `setOnItemSelectedListener` 手动接 `navigateToTab`，选中态在 `onDestinationChangedListener` 里 `menu.findItem(id).isChecked = true` 同步（程序化 setChecked 不回调监听器，无循环风险）
- **tab 切换不重载（2026-11）**：`PhotosViewModel` / `AlbumsViewModel` 改用 `by activityViewModels()`（Activity 作用域）。Fragment 作用域时切走即销毁、回来自带新 VM 重查 Room + 重建 5900 条，是「回图库像重新加载」的根因
- **图库滚动位置跨 view 保持**：`PhotosViewModel.lastGridPosition/lastGridOffset`，`onDestroyView` 写入、首次提交列表后 `restoreScrollPending` 一次性 `scrollToPositionWithOffset`。不恢复的话回图库跳顶部，主观上同样像「重新加载」

## 横屏适配（layout-land/）

- `AndroidManifest.xml:44` `configChanges="orientation|screenSize|screenLayout"` → 旋转时 **Activity/Fragment 不重建**，仅触发 `onConfigurationChanged`
- 关键问题：**FastScroller（1.3.0）通过匿名 ItemDecoration 的 `onDraw` 触发 onPreDraw**；原 `updateSpanCount` 用 `while itemDecorationCount > 0 { removeItemDecorationAt(0) }` 会把该匿名装饰一并清掉，导致 thumb 位置永久卡在旧 width（"thumb 出现在中间"）
- 修复：保存 `gridSpacingItemDecoration` 引用，`updateSpanCount` 只 `removeItemDecoration(this)` 后重建
- 关键问题：**DragSelectTouchListener 抢占 `OnItemTouchListener`**，导致 FastScroller 的 thumb 触摸被吞；解决：竖横屏 RecyclerView 都用 `me.zhanghai.android.fastscroll.FixOnItemTouchListenerRecyclerView`（库 1.3.0 官方修正类，统一 RV 类型以兼容 ViewBinding）
- 关键问题：**横屏 layout 用 `ConstraintLayout` 直挂 RV**（不依赖 `appbar_scrolling_view_behavior`），让 RV width 在旋转后立即撑满新宽度，FastScroller 的 onPreDraw 用 `mView.getWidth()` 自动贴齐屏幕右边缘
- 关键问题：FastScroller thumb 与悬浮 BottomNav 在底部重叠 → builder 加 `setPadding(0, 0, 0, 80dp)` 让 thumb 跳过 BottomNav 区
- `layout-land/fragment_photos.xml` + `layout-land/activity_main.xml` 资源存在
- 旋转重建（`onConfigurationChanged`）必须同步调用：
  - `bottomNavGlass.requestApplyInsets()`（MainActivity，宽度已由 XML 定死）
  - `GridSpanPreferences.resolveForOrientation(...)` + `applySpanCount`（Photos/AlbumDetail）

## 关键目录

```
app/src/main/java/com/gxstar/stargallery/
├── MainActivity.kt                  # applyBottomNavWidth + insets + 旋转
├── StarGalleryApp.kt                # @HiltAndroidApp + LocaleManager.applyLocale()
├── data/
│   ├── local/
│   │   ├── db/             # PhotoDao, PhotoEntity, AppDatabase (Room, v7)
│   │   ├── scanner/        # MediaScanner (全量/增量扫描)
│   │   ├── exif/           # ExifExtractor, PhotoStyleResolver (7品牌)
│   │   ├── preferences/    # ScanPreferences (5 keys)
│   │   └── ThumbnailManager.kt     # 512px JPEG 缩略图缓存
│   ├── model/              # Photo (Parcelable, 25+ fields), Album
│   └── repository/         # MediaRepository (MediaStore 操作)
├── di/                     # Hilt 模块 (AppModule, DatabaseModule, PreferenceModule)
├── util/                   # 工具类（根级）
│   ├── ExcludedAlbumManager.kt     # 排除相册管理 (StateFlow)
│   ├── HdrDisplayManager.kt        # HDR 显示开关
│   └── LocaleManager.kt            # 语言切换 (system/zh/en)
├── ui/
│   ├── photos/             # 首页网格 (PhotosFragment, PhotosViewModel)
│   │   ├── action/         # BatchActionHandler (6种操作)
│   │   ├── animation/      # PhotoItemAnimator (DefaultItemAnimator)
│   │   ├── filter/         # FilterBottomSheet (EXIF 三维多选)
│   │   ├── launcher/       # IntentSenderManager (3个 Launcher)
│   │   ├── model/          # PhotoModel (PhotoItem + SeparatorItem)
│   │   ├── refresh/        # MediaChangeDetector (ContentObserver)
│   │   ├── scanner/        # （原 ScanViewModel/ScanningProgressDialog 死代码已删除）
│   │   ├── selection/      # PhotoSelectionManager
│   │   ├── GridSpacingItemDecoration.kt
│   │   ├── PhotoListAdapter.kt
│   │   └── PhotoPreloadModelProvider.kt
│   ├── albums/             # 相册列表 (AlbumsFragment) + 详情 (AlbumDetailFragment, AlbumDetailAdapter, AlbumSelectionManager)
│   ├── detail/             # 照片详情 (ViewPager2 + ZoomImageView + ExoPlayer + HDR)
│   │   ├── ExoPlayerManager.kt        # 单例
│   │   ├── PhotoDetailFragment.kt
│   │   ├── PhotoDetailViewModel.kt
│   │   ├── PhotoDetailListCache.kt    # 列表快照缓存
│   │   ├── PhotoInfoBottomSheet.kt    # (EXIF + 地图)
│   │   ├── PhotoPagerAdapter.kt       # DiffUtil
│   │   ├── PhotoPageViewHolder.kt     # (缩放/视频/GIF/HDR)
│   │   └── AvifRegionDecoder.kt       # AVIF 子采样 (Android 12+)
│   ├── settings/           # 设置页
│   │   ├── SettingsFragment.kt         # 语言 + 排除相册 + HDR 开关
│   │   └── ExcludedAlbumsFragment.kt  # 排除相册管理
│   ├── trash/              # 回收站 (TrashFragment, TrashPhotoPreviewDialog)
│   ├── hidden/             # 隐藏照片 (HiddenFragment, HiddenAdapter, HiddenSelectionManager, HiddenViewModel)
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

- **RAW 格式识别**：DNG/ARW/CR2/CR3/NEF/ORF/RAF/RW2/PEF 等（仅识别与标记，同名 JPG+RAW 自动配对尚未实现）
- **EXIF 多选筛选**：按相机品牌/型号/镜头型号多选过滤，级联自动推导，维度间 AND + 维度内 OR
- **排序一致性**：`SortUtils.sortPhotos()` 三级排序，网格与详情页完全统一
- **张数实时显示**：`filteredPhotoCount` 从 `photoListFlow` 派生，始终精确反映当前屏幕显示数量
- **隐藏照片**：`HiddenFragment` 进入需 BiometricPrompt 认证（指纹/设备密码）
- **拖动多选**：`drag-select-recyclerview` 库 + 基于 photo ID 的选中追踪，删除后位置不错乱
- **快速滚动条**：FastScroller（1.3.0），竖横屏都用 `FixOnItemTouchListenerRecyclerView` 解决与 DragSelect 的触摸冲突；横屏用 `layout-land/fragment_photos.xml` + `setPadding(0,0,0,80)` 让 thumb 贴齐屏幕右边缘
- **搜索**：按文件名/文件夹名实时过滤
- **HDR 检测**：三重检测（Ultra HDR / RGBA_F16 / ColorSpace）
- **照片风格解析**：7 大相机品牌照片风格/胶片模拟映射
- **语言切换**：系统/中文/英文三档，`AppCompatDelegate.setApplicationLocales()` 全局持久化，无需重启
- **排除相册**：在设置中排除指定相册，`baseFilteredList` 自动过滤，Room Flow 实时同步
- **HDR 显示开关**：设置中控制 HDR 标签全局显示，不影响检测逻辑
- **AVIF 支持**：Android 12+ 子采样解码，ZoomImageView 集成，全图缩放 + 裁剪策略
- **缩略图缓存**：`ThumbnailManager` 本地缓存 512px JPEG，减少 Glide 重复加载
- **坐标转换**：WGS84→GCJ02，适配中国地图
- **动态预加载**：预加载量随列数自动调整（列数 × 3/4）
- **列数偏好竖横屏独立**：首页与相册详情各自独立；当前方向未设时复用另一方向值；都没设时按屏宽计算
- **底部导航自适应宽度**：根据屏宽在 32dp~480dp 区间内自适应，避免横屏悬浮胶囊过宽
