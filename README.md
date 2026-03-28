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
| 分页加载 | Paging 3 |
| 列表分组 | Groupie |
| 权限管理 | PermissionX |
| 视频播放 | Media3 (ExoPlayer) |
| 异步处理 | Coroutines + Flow |

## 功能特性

### 照片浏览
- 按日期分组展示照片
- 网格布局，支持 3-8 列自定义
- 快速滚动条定位

### 照片详情
- 高清大图查看
- 支持缩放操作
- 查看照片详细信息（拍摄时间、尺寸等）

### 批量选择
- 长按进入选择模式
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
│   ├── model/          # 数据模型
│   └── repository/     # 数据仓库
├── di/                 # Hilt 依赖注入模块
├── ui/
│   ├── albums/         # 相册列表
│   ├── detail/         # 照片详情
│   └── photos/         # 照片网格
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
