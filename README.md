# StarGallery

一个现代化的 Android 本地图库应用，采用 Kotlin + XML 开发。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.3.20 |
| 最低版本 | Android 11 (API 30) |
| 架构 | MVVM + Repository Pattern |
| 依赖注入 | Hilt 2.59.2 |
| 导航 | Navigation Component + SafeArgs |
| 图片加载 | Glide 4.16.0 |
| 大图查看 | SubsamplingScaleImageView 3.10.0 |
| 手势视图 | GestureViews |
| 分页加载 | Paging 3.4.0 |
| 快速滚动 | RecyclerView FastScroll |
| 拖动多选 | drag-select-recyclerview 2.4.0 |
| 权限管理 | PermissionX |
| 视频播放 | Media3 (ExoPlayer) 1.10.0 |
| EXIF 信息 | ExifInterface + Metadata Extractor |
| 异步处理 | Coroutines + Flow |
| 内存检测 | LeakCanary (Debug) |

## 功能特性

### 照片浏览
- **日期分组展示**: 使用 Paging 3 `insertSeparators` 实现，按日/月/年自动分组，SeparatorItem 占整行显示日期标题
- **网格布局**: 支持 3-8 列自定义，GridSpacingItemDecoration 实现间距效果
- **快速滚动条定位**: FastScrollThumb/Track 自定义样式
- **拖动多选模式**: drag-select-recyclerview 实现长按选择、拖动连续选择

### RAW 格式识别与配对
- **自动识别**: DNG、ARW、CR2、CR3、NEF、ORF、RAF、RW2、PEF 等 RAW 格式
- **JPG+RAW 配对**: 同名文件自动关联，显示 "JPG+RAW" 标签
- **单独分组**: RAW 文件在相册中独立展示，便于专业摄影管理

### 照片详情
- **高清大图查看**: SubsamplingScaleImageView 分块加载，支持缩放操作
- **详细信息**: 拍摄时间、尺寸、EXIF 信息（通过 Metadata Extractor 实时解析）
- **视频播放**: Media3/ExoPlayer 集成，支持边看边滑动浏览
- **标签设置**: TagsSettingsBottomSheet 支持自定义标签管理

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
│   └── trash/              # 回收站管理
├── MainActivity.kt
└── StarGalleryApp.kt
```

## 关键架构点

### Paging 3 + Date Headers
- `PhotoPagingSource` 从 MediaStore 分页加载数据
- `PhotoPagingAdapter` 使用 `insertSeparators` 插入日期分隔符
- SeparatorItem 占整行，显示当前日期（日/月/年分组）

### 拖动多选实现
- `DragSelectHelper`: 管理选择状态、处理拖动事件
- `PhotoSelectionManager`: 封装业务逻辑，提供 startDragSelection() 等接口
- **Bug 修复**: idToPosition 映射校准 - 在 PhotoSelectionManager.startDragSelection() 和 DragSelectHelper 中添加 findCorrectPosition() 方法，确保选择圆圈显示正确位置

### MediaStore API 流程
1. Repository 层返回 IntentSender（而非直接执行）
2. UI 层通过 registerReceiver() 监听结果
3. 用户确认后回调处理成功/失败
4. 支持批量操作和单张照片操作

### MVVM + Repository Pattern
- **UI 层**: Fragment/ViewModel 处理用户交互、事件响应
- **Repository 层**: MediaRepository 封装所有数据访问逻辑
- **Data 层**: MediaStore API、本地扫描器

## RAW 配对实现

同名 JPG+RAW 文件自动关联：
1. 解析文件名提取基础名（不含扩展名）
2. 检查是否存在对应的 RAW 文件
3. 在 PhotoPagingAdapter 中渲染 "JPG+RAW" 标签
4. 点击可切换查看配对的另一张照片

## Fragment 导航

使用 SafeArgs，参数通过 `PhotosFragmentDirections` 传递。修改导航需更新 `res/navigation/nav_graph.xml`。

## 性能优化

- **分页加载**: Paging 3 避免一次性加载大量数据，默认每页 50 项
- **图片缓存**: Glide 使用 LruCache 和磁盘缓存
- **大图分块**: ZoomImageView 按需加载图像金字塔
- **异步处理**: 所有 IO 操作在 Dispatchers.IO 线程池执行

## 开发约定

- **ViewBinding** 替代 findViewById，使用 `viewBinding.root` 访问根视图
- **SafeArgs** 进行 Fragment 参数传递，避免 Bundle 手动管理
- **IntentSender 流程**: MediaStore 收藏/删除/回收站操作需用户确认，必须设置回调处理结果
- **日期分组**: 使用 Paging 3 `insertSeparators` 实现，按日/月/年自动分组
- **RAW 配对**: 同名 JPG+RAW 文件自动合并显示，支持标签切换查看

## Fragment 导航指南

修改导航需更新 `res/navigation/nav_graph.xml`：
1. 在 nav_graph.xml 中添加新的 Fragment 节点
2. 使用 SafeArgs 传递参数（如 photoId, albumId）
3. 在目标 Fragment 的 onNavigate() 中接收参数
4. 更新 AGENTS.md 中的导航说明

## 快速验证

修改 Kotlin 代码后运行 `gradlew.bat assembleDebug` 确认编译通过。

*最后更新：2026-04-14*
