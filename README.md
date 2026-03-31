# StarGallery

一个现代化的 Android 本地图库应用，采用 Kotlin + XML 开发。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| 最低版本 | Android 11 (API 30) |
| 架构 | MVVM + Clean Architecture |
| 依赖注入 | Hilt |
| 导航 | Navigation Component + SafeArgs |
| 图片加载 | Glide |
| 大图查看 | SubsamplingScaleImageView |
| 手势视图 | GestureViews |
| 分页加载 | Paging 3 |
| 列表分组 | Groupie |
| 权限管理 | PermissionX |
| 视频播放 | Media3 (ExoPlayer) |
| 快速滚动 | RecyclerView FastScroll |
| 拖动多选 | drag-select-recyclerview |
| EXIF 信息 | androidx.exifinterface |
| 异步处理 | Coroutines + Flow |

## 功能特性

### 照片浏览
- 按日期分组展示照片
- 网格布局，支持 3-8 列自定义
- 快速滚动条定位
- 支持拖动多选模式

### 照片详情
- 高清大图查看
- 支持缩放操作
- 查看照片详细信息（拍摄时间、尺寸等）
- 支持视频播放

### 相册管理
- 按相册分类展示
- 查看相册内所有照片
- 相册封面预览

### 批量选择
- 长按进入选择模式
- 拖动选择连续照片
- 多选照片进行批量操作
- 支持全选功能

### 收藏功能
- 切换收藏状态
- 使用系统 MediaStore API 实现，与系统相册同步

### 分享与删除
- 批量分享照片
- 使用系统 API 进行删除操作，需要用户确认

## 权限说明

- **Android 13+**: `READ_MEDIA_IMAGES` 读取图片权限
- **Android 11-12**: `READ_EXTERNAL_STORAGE` 存储读取权限

## 项目结构

```
app/src/main/java/com/gxstar/stargallery/
├── data/
│   ├── model/          # 数据模型 (Album, Photo)
│   ├── paging/         # 分页数据源 (PhotoPagingSource)
│   └── repository/    # 数据仓库 (MediaRepository)
├── di/                 # Hilt 依赖注入模块
├── ui/
│   ├── albums/         # 相册列表和详情
│   ├── detail/         # 照片详情和大图查看
│   └── photos/         # 照片网格展示
├── MainActivity.kt
└── StarGalleryApp.kt
```

## 构建

```bash
./gradlew assembleDebug
```

## 开发说明

### MediaStore API 使用

应用使用系统 MediaStore API 进行媒体操作：
- `MediaStore.createFavoriteRequest()` - 收藏/取消收藏
- `MediaStore.createDeleteRequest()` - 删除照片

这些操作需要用户确认，符合 Android 11+ 分区存储规范。

### 性能优化

- 使用 Paging 3 进行分页加载，避免一次性加载大量数据
- 使用 Glide 加载缩略图，减少内存占用
- 使用 SubsamplingScaleImageView 分块加载大图

### 拖动多选

应用使用 `drag-select-recyclerview` 库实现拖动多选功能：
- 长按进入选择模式
- 按住拖动可连续选择多个照片
- 松开手指结束选择
