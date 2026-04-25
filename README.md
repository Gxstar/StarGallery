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
| 分页加载 | Paging 3.4.0 |
| 快速滚动 | FastScroller |
| 拖动多选 | drag-select-recyclerview 2.4.0 |
| 权限管理 | PermissionX |
| 视频播放 | Media3 (ExoPlayer) 1.9.1 |
| EXIF 信息 | metadata-extractor 2.20.0 |
| 异步处理 | Coroutines + Flow |
| 内存检测 | LeakCanary (Debug) |

## 功能特性

### 照片浏览（首页网格列表）
- **Paging 3 分页加载**: PhotoPagingSource 从 MediaStore 分页获取数据，支持日期分组
- **日期分组展示**: 使用 `insertSeparators` 实现，按日/月/年自动分组，SeparatorItem 占整行显示日期标题
- **网格布局**: GridLayoutManager 支持 3-10 列自定义，动态计算单元格大小
- **Glide 预加载**: RecyclerViewPreloader 预加载图片，配合 ViewPreloadSizeProvider 提升滚动体验
- **快速滚动条定位**: FastScrollerBuilder 自定义样式，支持滚动时显示日期/位置预览
- **拖动多选模式**: PhotoSelectionManager + DragSelectHelper 实现长按选择、拖动连续选择
- **实时刷新**: MediaChangeDetector 通过 ContentObserver 监听媒体变化，PagingSource 自动刷新
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

### 批量选择与操作
- **长按进入选择模式**: PhotoSelectionManager 管理选择状态
- **拖动选择**: DragSelectHelper 处理连续选择逻辑
- **多选批量操作**: 收藏、删除、移至回收站等批量操作
- **全选功能**: 支持快速全选当前页面所有照片

### MediaStore API 使用
- **收藏/取消收藏**: `MediaStore.createFavoriteRequest()` - 返回 IntentSender，需用户确认
- **删除**: `MediaStore.createDeleteRequest()` - 逻辑删除，需用户确认
- **回收站操作**: `MediaStore.createTrashRequest()` - 移至回收站或恢复
- **直接切换**: `toggleFavoriteDirect()` - 无需确认的收藏状态切换（内部使用）

## 权限说明

| Android 版本 | 所需权限 |
|-------------|----------|
| Android 13+ (API 33+) | `READ_MEDIA_IMAGES` |
| Android 11-12 (API 30-32) | `READ_EXTERNAL_STORAGE` |
| Android 10 及以下 | 无特殊权限（使用 Storage Access Framework）|

## 项目结构

```
app/src/main/java/com/gxstar/stargallery/
├── data/
│   ├── model/              # 数据模型 (Photo, Album)
│   ├── paging/             # 分页数据源 (PhotoPagingSource)
│   ├── repository/         # 数据仓库 (MediaRepository)
│   └── local/
│       ├── scanner/        # 媒体扫描器
│       └── preferences/    # 扫描状态持久化
├── di/                     # Hilt 依赖注入模块
├── ui/
│   ├── albums/             # 相册列表和详情
│   ├── detail/             # 照片详情和大图查看
│   ├── photos/             # 照片网格展示
│   │   ├── action/         # 批量操作 (BatchActionHandler)
│   │   ├── animation/      # 列表动画 (PhotoItemAnimator)
│   │   ├── launcher/       # IntentSender 管理
│   │   ├── refresh/         # 媒体变化检测
│   │   └── selection/      # 选择状态管理
│   └── trash/              # 回收站管理
├── MainActivity.kt
└── StarGalleryApp.kt
```

## 关键架构点

### 首页照片网格实现
- **PhotosFragment**: 协调各管理器（SelectionManager、BatchActionHandler、IntentSenderManager），处理 UI 事件
- **PhotoPagingAdapter**: Paging 3 适配器，`insertSeparators` 插入日期分隔符，支持多列网格布局
- **PhotoSelectionManager**: 管理选择模式状态、idToPosition 映射，提供拖动选择入口
- **DragSelectHelper**: 处理拖动选择逻辑，支持 findCorrectPosition() 映射校准
- **BatchActionHandler**: 封装收藏/删除/移入回收站等批量操作
- **IntentSenderManager**: 管理 MediaStore IntentSender 回调流程
- **MediaChangeDetector**: ContentObserver 监听媒体变化，触发 Paging 数据刷新
- **GridLayoutManager**: 动态列数（3-10列），spanSizeLookup 控制 SeparatorItem 占整行

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

### MVVM + Clean Architecture
- **UI 层**: Fragment/ViewModel 处理用户交互、事件响应
- **Domain 层**: Repository 接口定义数据操作
- **Data 层**: MediaStore API、本地扫描器

## 性能优化

- **分页加载**: Paging 3 避免一次性加载大量数据，默认每页 50 项
- **Glide 预加载**: RecyclerViewPreloader 提前加载可见区域图片
- **大图子采样**: ZoomImageView 按需加载图像金字塔区域块（tiles）
- **ExoPlayer 单例**: 全局复用 ExoPlayer 实例，滑动时保持播放状态
- **异步处理**: 所有 IO 操作在 Dispatchers.IO 线程池执行
- **视图缓存**: RecyclerView ItemViewCache、GridLayoutManager 优化

## 开发约定

- **ViewBinding** 替代 findViewById，使用 `viewBinding.root` 访问根视图
- **SafeArgs** 进行 Fragment 参数传递，避免 Bundle 手动管理
- **IntentSender 流程**: MediaStore 收藏/删除/回收站操作需用户确认，必须设置回调处理结果
- **日期分组**: 使用 Paging 3 `insertSeparators` 实现，按日/月/年自动分组
- **RAW 配对**: 同名 JPG+RAW 文件自动合并显示，支持标签切换查看
- **大图加载策略**: 小图直接加载，大图（>=2000px）启用 ZoomImageView 子采样
- **ExoPlayer 单例**: 使用 ExoPlayerManager 全局管理，滑动切换时保持播放
- **协程作用域**: PhotoPageViewHolder 使用 viewHolderScope 管理异步任务生命周期

## Fragment 导航指南

修改导航需更新 `res/navigation/nav_graph.xml`：
1. 在 nav_graph.xml 中添加新的 Fragment 节点
2. 使用 SafeArgs 传递参数（如 photoId, albumId）
3. 在目标 Fragment 的 onNavigate() 中接收参数
4. 更新 AGENTS.md 中的导航说明

## 快速验证

修改 Kotlin 代码后运行 `gradlew.bat assembleDebug` 确认编译通过。

*最后更新：2026-04-25*
