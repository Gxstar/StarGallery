# StarGallery

一个现代化的 Android 本地图库应用，采用 Kotlin + XML 开发。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.20 |
| 构建 | AGP | 9.2.1 |
| 最低版本 | Android 11 | API 30 |
| 架构 | MVVM + Repository + Room | - |
| 依赖注入 | Hilt (KSP) | 2.59.2 |
| 导航 | Navigation + SafeArgs | 2.9.7 |
| 图片加载 | Glide + RecyclerViewPreloader | 4.16.0 |
| 大图查看 | ZoomImage (子采样) | 1.4.0 |
| 本地数据库 | Room | 2.8.4 |
| 视频播放 | Media3 (ExoPlayer) | 1.9.1 |
| EXIF 提取 | metadata-extractor | 2.20.0 |
| 快速滚动 | FastScroller | 1.3.0 |
| 拖动多选 | drag-select-recyclerview | 2.4.0 |
| 生物识别 | Biometric | 1.1.0 |
| 协程 | Kotlinx Coroutines | 1.10.1 |
| 内存检测 | LeakCanary (Debug) | 2.14 |

## 功能特性

### 照片浏览（首页网格）
- **Room Flow 全量加载**：数据库变化自动推送，ViewModel 内存中过滤/排序/分组/搜索
- **日期分组展示**：按日/月/年自动分组，日期分隔符占整行
- **可调网格布局**：3–8 列动态切换，spanSizeLookup 控制分隔符占整行
- **Glide 动态预加载**：`RecyclerViewPreloader` 预加载量 = 列数 × 3（图片）+ 列数 × 4（布局），切换列数时自动重建
- **快速滚动条**：FastScroller 自定义样式，滚动时显示日期预览
- **实时刷新**：ContentObserver 监听媒体变化，Room Flow 自动推送列表
- **张数实时显示**：从 `photoListFlow` 派生，精确反映当前屏幕显示张数
- **实时搜索**：按文件名/文件夹名过滤

### EXIF 多选筛选
- **三维度多选**：相机品牌 / 相机型号 / 镜头型号各自独立多选（`Set<String>`）
- **级联自动推导**：选镜头→自动勾选对应型号+品牌，选型号→自动勾选对应品牌
- **维度间 AND + 维度内 OR**：照片须同时满足所有非空维度
- **未知设备选项**：覆盖无 EXIF 信息的照片
- **选项计数**：Room 聚合查询实时显示各选项可用数量

### 照片详情
- **ViewPager2 滑动翻页**：排序与网格列表完全一致
- **ZoomImageView 缩放**：大图（≥2000px）启用子采样分块加载
- **ExoPlayer 视频播放**：全局单例，跨页面保持播放状态
- **GIF 动图播放**：Glide 直接加载
- **HDR 检测**：三重检测（Ultra HDR / RGBA_F16 / ColorSpace）
- **照片风格解析**：7 大品牌（松下/索尼/佳能/尼康/富士/奥林巴斯/宾得）照片风格/胶片模拟
- **全屏切换 + 下滑关闭**
- **EXIF 全信息弹窗**：含地图 App 跳转（高德/腾讯/百度/谷歌），WGS84→GCJ02 坐标转换
- **PopupMenu 隐藏照片**

### RAW 格式识别
- 自动识别 DNG/ARW/CR2/CR3/NEF/ORF/RAF/RW2/PEF 等格式
- 同名 JPG+RAW 自动配对

### 批量操作（多选模式）
- **基于 photo ID 的选中追踪**：删除后位置不错乱
- **长按 + 拖动多选**：drag-select-recyclerview
- **批量操作**：收藏 / 隐藏 / 删除 / 移至回收站
- **Payload 精准刷新**：仅更新选择 UI，不重新加载图片

### 隐藏照片
- **生物识别认证**：指纹/设备密码保护隐藏相册
- **批量隐藏/恢复**

### 回收站
- 显示剩余过期天数
- 恢复 / 永久删除
- 全屏 ZoomImageView 预览

### 相册管理
- 按文件夹自动分组，封面预览，数量统计
- 独立（列数/排序/分组）设置，与首页互不干扰

## 权限说明

| Android 版本 | 所需权限 |
|-------------|----------|
| Android 14+ (API 34+) | READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION |
| Android 13 (API 33) | READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION |
| Android 11–12 (API 30–32) | READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION |
| 所有版本 | SET_WALLPAPER（普通权限，无需动态申请） |

## 项目结构

```
app/src/main/java/com/gxstar/stargallery/
├── MainActivity.kt              # 入口，SplashScreen
├── StarGalleryApp.kt            # @HiltAndroidApp
├── data/
│   ├── local/
│   │   ├── db/                  # Room (PhotoDao, PhotoEntity, AppDatabase v7)
│   │   ├── scanner/             # MediaScanner (全量/增量扫描)
│   │   ├── exif/                # ExifExtractor + PhotoStyleResolver (7品牌)
│   │   └── preferences/         # ScanPreferences (4 keys)
│   ├── model/                   # Photo (Parcelable), Album
│   └── repository/              # MediaRepository (MediaStore 操作)
├── di/                          # Hilt 模块 (AppModule, DatabaseModule, PreferenceModule)
├── ui/
│   ├── photos/                  # 首页网格 (PhotosFragment, PhotosViewModel)
│   │   ├── action/              # BatchActionHandler
│   │   ├── animation/           # PhotoItemAnimator
│   │   ├── filter/              # FilterBottomSheet (EXIF 三维多选)
│   │   ├── launcher/            # IntentSenderManager
│   │   ├── model/               # PhotoModel (PhotoItem + SeparatorItem)
│   │   ├── refresh/             # MediaChangeDetector (ContentObserver)
│   │   ├── scanner/             # ScanningProgressDialog, ScanViewModel
│   │   └── selection/           # PhotoSelectionManager
│   ├── albums/                  # 相册列表 + 详情 (AlbumDetailFragment)
│   ├── detail/                  # 照片详情 (ViewPager2, ZoomImage, ExoPlayer, HDR)
│   ├── trash/                   # 回收站 (TrashPhotoPreviewDialog)
│   ├── hidden/                  # 隐藏照片 (BiometricPrompt)
│   ├── about/                   # 6个子页面
│   ├── common/                  # BaseSelectionManager, PhotoGridViewHolder 等
│   └── util/                    # CoordinateUtils, DateUtils, SortUtils
```

## 关键设计

### 数据流
```
MediaStore → MediaScanner → Room DB → Flow → ViewModel combine
                                               → submitList() → RecyclerView
                                               → filteredPhotoCount → tvSubtitle
```

### 排序一致性
`SortUtils.sortPhotos()` 为网格和详情页提供统一三级排序：
```
normalizedDateTaken DESC → dateAdded DESC → id DESC
```

### 多选机制
`BaseSelectionManager` 使用 `_selectedPhotoIds: MutableSet<Long>` 追踪选中状态，
在需要 position 时通过 `findPositionByPhotoId()` 实时查找，
确保删除后选中位置不错乱。

### EXIF 筛选参数传递
筛选参数以 `\n` 分隔 `Set<String>` 编码通过 SafeArgs 传递给详情页，
详情页解码后使用与网格完全一致的过滤逻辑。

### 动态预加载
- 图片预加载：`currentSpanCount × 3`（RecyclerViewPreloader）
- 布局预取：`currentSpanCount × 4`（initialPrefetchItemCount）
- 切换列数时自动重建预加载机制

## 开发约定
- ViewBinding 替代 findViewById
- SafeArgs 进行 Fragment 参数传递
- 数据加载：Room Flow + ListAdapter，combine 合并状态
- 大图策略：小图直接加载，大图（≥2000px）启用 ZoomImageView 子采样
- ExoPlayer 单例：ExoPlayerManager 全局管理
- IntentSender 模式：MediaStore 操作需用户确认
- 隐藏照片：HiddenFragment 需 BiometricPrompt 认证
- 预加载量随列数动态调整（列数 × 3/4）

## 快速验证

```powershell
.\gradlew.bat assembleDebug          # 构建 Debug APK
.\gradlew.bat testDebugUnitTest       # 运行单元测试
```

*最后更新：2026-05-28*
