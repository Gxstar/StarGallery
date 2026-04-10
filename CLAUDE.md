# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本代码仓库中工作时提供指导。

## 构建命令 (Windows)

```bash
gradlew.bat assembleDebug   # Debug 构建
gradlew.bat installDebug   # 构建并安装
gradlew.bat clean          # 清理
```

## 项目概述

StarGallery 是一款 Android 本地图库应用，采用 **Kotlin + Jetpack Compose** 开发，架构为 MVVM + 清洁架构。从 MediaStore 读取本地照片/视频，支持日期分组、收藏筛选和底部导航等功能。

## 架构

### 分层结构
- **`data/`** - 数据层：模型、分页数据源、仓库（MediaRepository）
- **`di/`** - Hilt 依赖注入模块
- **`ui/`** - UI 层（Compose）：Screen、ViewModel
  - `compose/photos/` - 照片网格，支持分页、日期分组、选择模式
  - `compose/albums/` - 相册列表和详情
  - `compose/detail/` - 全屏照片查看器，带滑动翻页
  - `compose/trash/` - 回收站功能

### 核心组件
- **MediaRepository** - 所有 MediaStore 操作（照片、相册、收藏、回收站）的单一数据源
- **MediaStorePagingSource** - 照片网格的 Paging 3 数据源
- **PhotoModel** - `sealed class`，包含 `PhotoItem` 和 `SeparatorItem`（日期分隔符）
- **GroupType** - 日期分组枚举（DAY / MONTH / YEAR）

### 导航 (Compose Navigation)
- `photos` (起始页) → `photo_detail`
- `albums` → `album_detail` → `photo_detail`
- `photos` → `trash`

路由通过 `NavRoutes` object 定义，参数通过 `navArgument` 传递。

## MediaStore 操作

所有破坏性操作都使用 `IntentSender` 流程，需要用户确认：
- `MediaStore.createFavoriteRequest()` - 添加/取消收藏
- `MediaStore.createDeleteRequest()` - 永久删除
- `MediaStore.createTrashRequest()` - 移入/恢复回收站

`MediaRepository` 返回 `IntentSender?`，由 Screen/ViewModel 通过 `ActivityResultContracts.StartIntentSenderForResult()` 发起请求。

## Paging 3 + 日期分隔符

`PhotosViewModel` 使用 `combine` 组合 `basePhotoPagingFlow` 和筛选/分组状态，通过 `insertSeparators` 添加日期标题。`MediaStorePagingSource` 使用 Android 10+ 的 Bundle 分页参数高效查询。

## 多选功能

长按进入选择模式。Screen 内自行管理 `selectedIds: Set<Long>` 和 `isSelectionMode: Boolean` 状态，支持分享、收藏、移入回收站操作。

## 核心依赖库
| 库 | 版本 |
|----|------|
| Kotlin | 2.3.20 |
| AGP | 9.1.0 |
| Compose BOM | 2024.06.00 |
| Compose | 1.7.0 |
| Paging 3 | 3.4.0 |
| Coil | 2.7.0 |
| Hilt | 2.59.2 |
| Media3 (ExoPlayer) | 1.10.0 |
| Navigation Compose | 2.9.7 |
