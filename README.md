# StarGallery

一个现代化的 Android 本地图库应用，采用 Kotlin + XML 开发。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.3.20 |
| 最低版本 | Android 11 (API 30) |
| 架构 | MVVM + Repository Pattern + Clean Architecture |
| 依赖注入 | Hilt 2.59.2 |
| 导航 | Navigation Component + SafeArgs |
| 图片加载 | Glide 4.16.0 |
| 大图查看 | ZoomImage 1.4.0 (子采样+缩放) |
| 本地数据库 | Room 2.8.4 |
| 快速滚动 | FastScroller |
| 拖动多选 | drag-select-recyclerview 2.4.0 |
| 权限管理 | ActivityResultContracts |
| 视频播放 | Media3 (ExoPlayer) 1.9.1 |
| EXIF 信息 | metadata-extractor 2.20.0 |
| 异步处理 | Coroutines + Flow |
| 内存检测 | LeakCanary (Debug) |

## 功能特性

### 照片浏览（首页网格列表）
- **Room Flow 全量加载**: PhotoDao.getAllPhotosFlow() 返回 Flow<List<PhotoEntity>>，Room 自动监听表变化推送更新，在 ViewModel 中全量加载到内存进行排序/过滤/插入日期分隔符
- **日期分组展示**: 按日/月/年自动分组，SeparatorItem 占整行显示日期标题
- **网格布局**: GridLayoutManager 支持 3-8 列自定义，动态计算单元格大小
- **Glide 预加载**: RecyclerViewPreloader 预加载图片，配合 ViewPreloadSizeProvider 提升滚动体验
- **快速滚动条定位**: FastScrollerBuilder 自定义样式，支持滚动时显示日期预览
- **拖动多选模式**: PhotoSelectionManager + DragSelectHelper 实现长按选择、拖动连续选择
- **实时刷新**: MediaChangeDetector 通过 ContentObserver 监听媒体变化，触发增量扫描同步到 Room，Room Flow 自动推送更新
- **状态恢复**: 配置变更时保存/恢复滚动位置，支持搜索、排序、分组筛选

### RAW 格式识别与配对
- **自动识别**: DNG、ARW、CR2、CR3、NEF、ORF、RAF、RW2、PEF 等 RAW 格式
- **JPG+RAW 配对**: 同名文件自动关联，显示 "JPG+RAW" 标签
- **单独分组**: RAW 文件在相册中独立展示，便于专业摄影管理

### 照片详情（图片查看器）
- **ViewPager2 滑动翻页**: PhotoPagerAdapter 管理照片列表，支持滑动切换和删除后平滑动画
- **ZoomImageView 缩放查看**: 小图（<2000px）直接加载原图，大图启用子采样分块加载高清区域
- **HDR 显示支持**: 自动检测 Ultra HDR (Gainmap)、HEIC/AVIF HDR、RGBA_F16 格式，启用窗口 HDR 色彩模式
- **ExoPlayer 视频播放**: Media3/ExoPlayer 集成，滑动切换时保持视频播放状态，支持控制器
- **GIF 动图播放**: Glide 直接加载 GIF，流畅播放
- **EXIF 标签显示**: 异步读取相机品牌（Panasonic/Nikon/Canon/Sony/Fujifilm）、PhotoStyle，动态渲染标签
- **滑动返回**: 下拉拖动 + alpha 渐变实现滑动返回效果
- **全屏模式切换**: 单击切换全屏，WindowInsetsController 隐藏/显示系统栏

### 相册管理
- **自动分组**: 按 BUCKET_ID 自动识别系统相册（相机、下载等）
- **封面预览**: 每个相册显示最新照片作为封面
- **数量统计**: 实时计算相册内照片数量

### 隐藏照片
- **生物识别认证**: HiddenFragment 进入需 BiometricPrompt 认证（指纹/设备密码）
- **隐藏/恢复**: 支持批量隐藏照片，隐藏后仅在 HiddenFragment 中可见
- **Room Flow 同步**: 隐藏操作直接写入 Room，Flow 自动推送列表更新

### EXIF 筛选
- **按相机品牌/型号/镜头型号过滤**: FilterBottomSheet 弹窗选择筛选条件
- **实时统计**: Room 聚合查询各选项的可用数量，UI 动态更新
- **多筛选组合**: 支持同时筛选品牌 + 型号 + 镜头

### RAW 格式识别与配对
- **自动识别**: DNG、ARW、CR2、CR3、NEF、ORF、RAF、RW2、PEF 等 RAW 格式
- **JPG+RAW 配对**: 同名文件自动关联，显示 "JPG+RAW" 标签
- **单独分组**: RAW 文件在相册中独立展示，便于专业摄影管理

### 批量选择与操作
- **长按进入选择模式**: PhotoSelectionManager 管理选择状态
- **拖动选择**: DragSelectHelper 处理连续选择逻辑
- **多选批量操作**: 收藏、删除、移至回收站等批量操作
- **全选功能**: 支持快速全选当前页面所有照片

### 回收站
- **移至回收站**: 使用 `MediaStore.createTrashRequest()` 将照片移到回收站
- **恢复**: 从回收站恢复照片到原位置
- **永久删除**: 使用 `MediaStore.createDeleteRequest()` 直接从设备删除
- **批量操作**: 支持多选后批量恢复或删除

## 权限说明

| Android 版本 | 所需权限 |
|-------------|----------|
| Android 14+ (API 34+) | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` |
| Android 13 (API 33) | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` |
| Android 11-12 (API 30-32) | `READ_EXTERNAL_STORAGE` |

## 项目结构

```
app/src/main/java/com/gxstar/stargallery/
├── data/
│   ├── model/              # 数据模型 (Photo, Album)
│   ├── repository/         # 数据仓库 (MediaRepository)
│   └── local/
│       ├── db/             # Room 数据库 (PhotoDao, PhotoEntity, AppDatabase)
│       ├── scanner/        # MediaScanner (全量/增量扫描, EXIF 提取)
│       ├── exif/           # ExifExtractor (EXIF 元数据读取)
│       └── preferences/    # ScanPreferences (扫描状态持久化)
├── di/                     # Hilt 依赖注入模块 (AppModule, DatabaseModule, PreferenceModule)
├── ui/
│   ├── albums/             # 相册列表和详情 (AlbumsFragment, AlbumDetailFragment)
│   ├── detail/             # 照片详情查看器 (ViewPager2 + ZoomImageView + ExoPlayer)
│   ├── photos/             # 首页照片网格
│   │   ├── action/         # 批量操作 (BatchActionHandler)
│   │   ├── animation/      # 列表动画 (PhotoItemAnimator)
│   │   ├── filter/         # EXIF 筛选弹窗 (FilterBottomSheet)
│   │   ├── launcher/       # IntentSender 统一管理 (IntentSenderManager)
│   │   ├── model/          # UI 模型 (PhotoModel: PhotoItem + SeparatorItem)
│   │   ├── refresh/        # 媒体变化检测 (MediaChangeDetector - ContentObserver)
│   │   ├── scanner/        # 扫描进度弹窗 (ScanningProgressDialog, ScanViewModel)
│   │   └── selection/      # 选择状态管理 (PhotoSelectionManager)
│   ├── hidden/             # 隐藏照片 (需生物识别认证)
│   ├── trash/              # 回收站管理
│   ├── about/              # 关于页面组 (隐私/权限/第三方库/联系我们/许可)
│   └── common/             # 共享组件 (BaseSelectionManager, GridSpanCalculator, PhotoGridViewHolder)
├── MainActivity.kt
└── StarGalleryApp.kt
```

## 关键架构点

### 首页照片网格实现
- **PhotosFragment**: 协调各管理器（SelectionManager、BatchActionHandler、IntentSenderManager），处理 UI 事件
- **PhotoListAdapter**: ListAdapter<PhotoModel>，DiffUtil 智能更新，PhotoItem/SeparatorItem 双类型
- **PhotoSelectionManager**: 管理选择模式状态、idToPosition 映射，提供拖动选择入口
- **DragSelectHelper**: 处理拖动选择逻辑，支持 findCorrectPosition() 映射校准
- **BatchActionHandler**: 封装收藏/删除/移入回收站等批量操作
- **IntentSenderManager**: 统一管理 favoriteLauncher / trashLauncher / deleteLauncher
- **MediaChangeDetector**: ContentObserver 监听媒体变化，触发增量扫描同步到 Room，Room Flow 自动推送
- **GridLayoutManager**: 动态列数（3-8列），spanSizeLookup 控制 SeparatorItem 占整行
- **PhotoViewModel.photoListFlow**: combine 合并排序/收藏过滤/EXIF 过滤/分组，在内存中处理

### 图片详情页实现
- **PhotoDetailFragment**: 全屏图片查看器，ViewPager2 管理页面切换
- **PhotoPagerAdapter**: RecyclerView 适配器，DiffUtil 智能更新，支持删除动画
- **PhotoPageViewHolder**: 单页内容加载器，ZoomImageView/ExoPlayer/Glide 分发媒体类型
- **ZoomImageView**: 子采样加载大图（>2000px），先缩略图预览再高清区域加载
- **ExoPlayerManager**: 全局单例 ExoPlayer，滑动切换时保持播放状态
- **HDR 检测**: Bitmap ColorSpace/BT.2020/PQ/HLG 检测，Android 14+ Ultra HDR Gainmap 检测

### IntentSender 流程
1. Repository 层返回 IntentSender（而非直接执行）
2. UI 层通过 `ActivityResultContracts.StartIntentSenderForResult` 启动
3. 用户确认后回调处理成功/失败
4. 支持批量操作和单张照片操作
5. IntentSenderManager 统一管理三种操作的回调

## 性能优化

- **Glide 预加载**: RecyclerViewPreloader 提前加载可见区域图片
- **大图子采样**: ZoomImageView 按需加载图像金字塔区域块（tiles），>=2000px 启用
- **ExoPlayer 单例**: 全局复用 ExoPlayer 实例，滑动时保持播放状态
- **异步处理**: 所有 IO 操作在 Dispatchers.IO 线程池执行
- **视图缓存**: RecyclerView ItemViewCache、GridLayoutManager 优化
- **扫描动画控制**: 扫描时 itemAnimator = null 防快速刷新乱跳

## 开发约定

- **ViewBinding** 替代 findViewById，使用 `viewBinding.root` 访问根视图
- **SafeArgs** 进行 Fragment 参数传递，避免 Bundle 手动管理
- **数据加载**: Room Flow + ListAdapter，`combine` 合并排序/过滤/分组在内存中处理
- **日期分组**: 按日/月/年自动分组，SeparatorItem 占整行显示日期标题
- **RAW 配对**: 同名 JPG+RAW 文件自动合并显示，支持标签切换查看
- **大图加载策略**: 小图直接加载，大图（>=2000px）启用 ZoomImageView 子采样
- **ExoPlayer 单例**: 使用 ExoPlayerManager 全局管理，滑动切换时保持播放
- **IntentSender 流程**: MediaStore 收藏/删除/回收站操作需用户确认，必须设置回调
- **隐藏照片**: HiddenFragment 需 BiometricPrompt 认证才能进入

## 快速验证

修改 Kotlin 代码后运行 `.\gradlew.bat assembleDebug` 确认编译通过。

*最后更新：2026-05-14*
