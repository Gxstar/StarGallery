# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本代码仓库中工作时提供指导。

## 构建命令 (Windows)

```bash
gradlew.bat assembleDebug   # Debug 构建
gradlew.bat installDebug   # 构建并安装
gradlew.bat clean          # 清理
gradlew.bat test           # 运行单元测试
```

## 项目概述

StarGallery 是一款 Android 本地图库应用，采用 Kotlin + XML 开发，架构为 MVVM + 清洁架构。从 MediaStore 读取本地照片/视频，支持拖动多选、RAW 格式和日期分组等功能。

## 架构

### 分层结构
- **`data/`** - 数据层：模型、分页数据源、仓库（MediaRepository）
- **`di/`** - Hilt 依赖注入模块
- **`ui/`** - UI 层：Fragment、ViewModel、适配器
  - `photos/` - 照片网格，支持分页、拖动选择、选中管理
  - `albums/` - 相册列表和详情
  - `detail/` - 全屏照片查看器，带滑动翻页
  - `trash/` - 回收站功能

### 核心组件
- **MediaRepository** - 所有 MediaStore 操作（照片、相册、收藏、回收站）的单一数据源
- **PhotoPagingSource** - 照片网格的 Paging 3 数据源
- **PhotoSelectionManager** - 管理拖动选择的状态
- **IntentSenderManager** - 处理 MediaStore IntentSender 流程（收藏/删除/移入回收站）

### 导航 (SafeArgs)
- `photosFragment` (起始页) → `photoDetailFragment`
- `albumsFragment` → `albumDetailFragment` → `photoDetailFragment`
- `photosFragment` → `trashFragment`

参数通过生成的 `*Directions` 类传递（如 `PhotosFragmentDirections`、`AlbumDetailFragmentDirections`）。

## MediaStore 操作

所有破坏性操作都使用 `IntentSender` 流程，需要用户确认：
- `MediaStore.createFavoriteRequest()` - 添加/取消收藏
- `MediaStore.createDeleteRequest()` - 永久删除
- `MediaStore.createTrashRequest()` - 移入/恢复回收站

`MediaRepository` 返回 `IntentSender?`，由 Fragment/ViewModel 传递给 `Activity.startIntentSenderForResult()`，并设置回调处理结果。

## Paging 3 日期分隔符

`PhotoPagingAdapter` 使用 `insertSeparators` 添加日期标题。`SeparatorItem` 占满整行宽度。数据加载见 `PhotoPagingSource`，分隔符逻辑见 `PhotoPagingAdapter`。

## RAW 照片处理

同一 bucket 中具有相同 `displayName`（不含扩展名）的照片会合并：
- 主照片（JPG/HEIF 等）获得 `pairedRawId` 指向 RAW 文件
- 仅 RAW 照片显示 "RAW" 标签
- 配对照片显示 "JPG+RAW" 格式标签

## 拖动多选

长按进入选择模式。`DragSelectHelper` 追踪拖动状态。`PhotoSelectionManager` 管理 `idToPosition` 映射。当位置可能发生变化时（如列表更新后），使用 `findCorrectPosition()` 进行校准。

## 核心依赖库
| 库 | 版本 |
|----|------|
| Kotlin | 2.3.20 |
| AGP | 9.1.1 |
| Paging 3 | 3.4.2 |
| Glide | 4.16.0 |
| Hilt | 2.59.2 |
| Media3 (ExoPlayer) | 1.9.1 |
| ZoomImage | 1.4.0 |
| Coil | 2.7.0 |
| drag-select-recyclerview | 2.4.0 |

## 现代图片格式支持

应用使用双图片加载器架构：
- **Glide** - 照片网格缩略图
- **Coil** - 支持 AVIF、HEIC、GIF、HDR 等现代格式
- **ZoomImage** - 大图查看和缩放（替代已废弃的 SubsamplingScaleImageView）
