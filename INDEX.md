# StarGallery 项目代码索引

> 本文档用于帮助 AI 工具快速定位 StarGallery 项目中各功能对应的文件。

---

## 项目概览

| 属性 | 值 |
|------|-----|
| 项目名称 | StarGallery |
| 类型 | Android 本地图册应用 |
| 语言 | Kotlin + XML |
| 最低 API | Android 11 (API 30) |
| 架构 | MVVM + Clean Architecture |
| 包名 | `com.gxstar.stargallery` |

---

## 核心文件速查表

### 入口文件

| 文件 | 路径 | 功能 |
|------|------|------|
| AndroidManifest.xml | `app/src/main/AndroidManifest.xml` | 应用清单：权限声明、Activity 注册 |
| StarGalleryApp.kt | `app/src/main/java/.../StarGalleryApp.kt` | Application 类，Hilt 初始化入口 |
| MainActivity.kt | `app/src/main/java/.../MainActivity.kt` | 主 Activity，承载 Fragment 容器和底部导航 |

### 构建配置

| 文件 | 路径 | 功能 |
|------|------|------|
| 根 build.gradle.kts | `build.gradle.kts` | 全局插件声明 (Android, Kotlin, Hilt, SafeArgs) |
| app build.gradle.kts | `app/build.gradle.kts` | 应用级依赖、编译配置 |
| settings.gradle.kts | `settings.gradle.kts` | 仓库配置 (阿里云镜像、JitPack)、模块包含 |
| gradle.properties | `gradle.properties` | Gradle 全局属性配置 |
| proguard-rules.pro | `app/proguard-rules.pro` | 代码混淆规则 |

---

## 数据层 (data/)

### 数据模型 (data/model/)

| 文件 | 路径 | 功能 |
|------|------|------|
| Photo.kt | `app/src/main/java/.../data/model/Photo.kt` | 照片数据类：URI、缩略图、日期、大小、是否收藏等字段 |
| Album.kt | `app/src/main/java/.../data/model/Album.kt` | 相册数据类：相册名、封面、照片数量等字段 |

### 分页数据源 (data/paging/)

| 文件 | 路径 | 功能 |
|------|------|------|
| PhotoPagingSource.kt | `app/src/main/java/.../data/paging/PhotoPagingSource.kt` | Paging 3 数据源，从 MediaStore 分页查询照片 |

### 数据仓库 (data/repository/)

| 文件 | 路径 | 功能 |
|------|------|------|
| MediaRepository.kt | `app/src/main/java/.../data/repository/MediaRepository.kt` | 核心仓库类：照片查询、相册查询、收藏、删除、恢复等 MediaStore 操作 |

---

## 依赖注入 (di/)

| 文件 | 路径 | 功能 |
|------|------|------|
| AppModule.kt | `app/src/main/java/.../di/AppModule.kt` | Hilt 模块：提供 Repository、Context 等单例依赖 |
| PreferenceModule.kt | `app/src/main/java/.../di/PreferenceModule.kt` | Hilt 模块：提供 SharedPreferences/Preferences 相关依赖 |

---

## UI 层 (ui/)

### 照片浏览 (ui/photos/)

| 文件 | 路径 | 功能 |
|------|------|------|
| PhotosFragment.kt | `app/src/main/java/.../ui/photos/PhotosFragment.kt` | 照片网格列表 Fragment：展示所有照片、分组、多选、快速滚动 |
| PhotosViewModel.kt | `app/src/main/java/.../ui/photos/PhotosViewModel.kt` | 照片列表 ViewModel：管理 Paging 数据流、选择状态 |
| PhotoPagingAdapter.kt | `app/src/main/java/.../ui/photos/PhotoPagingAdapter.kt` | Paging 3 适配器：照片网格项的绑定与显示 |
| PhotoPreloadModelProvider.kt | `app/src/main/java/.../ui/photos/PhotoPreloadModelProvider.kt` | Glide 预加载模型：优化滑动时的图片加载体验 |
| GridSpacingItemDecoration.kt | `app/src/main/java/.../ui/photos/GridSpacingItemDecoration.kt` | RecyclerView 网格间距装饰器 |

### 照片详情 (ui/detail/)

| 文件 | 路径 | 功能 |
|------|------|------|
| PhotoDetailFragment.kt | `app/src/main/java/.../ui/detail/PhotoDetailFragment.kt` | 照片详情 Fragment：大图查看、手势缩放、视频播放、信息展示 |
| PhotoDetailViewModel.kt | `app/src/main/java/.../ui/detail/PhotoDetailViewModel.kt` | 详情 ViewModel：收藏状态切换、删除操作 |
| PhotoPagerAdapter.kt | `app/src/main/java/.../ui/detail/PhotoPagerAdapter.kt` | ViewPager2 适配器：支持左右滑动切换照片 |
| PhotoPageViewHolder.kt | `app/src/main/java/.../ui/detail/PhotoPageViewHolder.kt` | 单页 ViewHolder：管理 SubsamplingScaleImageView 或视频播放器 |
| EdgeSubsamplingScaleImageView.kt | `app/src/main/java/.../ui/detail/EdgeSubsamplingScaleImageView.kt` | 自定义大图查看控件：支持边缘检测和分块加载 |

### 相册管理 (ui/albums/)

| 文件 | 路径 | 功能 |
|------|------|------|
| AlbumsFragment.kt | `app/src/main/java/.../ui/albums/AlbumsFragment.kt` | 相册列表 Fragment：展示所有相册及封面 |
| AlbumsViewModel.kt | `app/src/main/java/.../ui/albums/AlbumsViewModel.kt` | 相册列表 ViewModel：加载相册数据 |
| AlbumDetailFragment.kt | `app/src/main/java/.../ui/albums/AlbumDetailFragment.kt` | 相册详情 Fragment：展示指定相册内的所有照片 |
| AlbumDetailViewModel.kt | `app/src/main/java/.../ui/albums/AlbumDetailViewModel.kt` | 相册详情 ViewModel：加载相册内照片列表 |

### 回收站 (ui/trash/)

| 文件 | 路径 | 功能 |
|------|------|------|
| TrashFragment.kt | `app/src/main/java/.../ui/trash/TrashFragment.kt` | 回收站 Fragment：展示已删除照片、恢复/彻底删除操作 |
| TrashViewModel.kt | `app/src/main/java/.../ui/trash/TrashViewModel.kt` | 回收站 ViewModel：管理回收站数据和操作 |

### 公共组件 (ui/common/)

| 文件 | 路径 | 功能 |
|------|------|------|
| DeleteOptionsBottomSheet.kt | `app/src/main/java/.../ui/common/DeleteOptionsBottomSheet.kt` | 删除选项底部弹窗：提供"移到回收站"和"彻底删除"选项 |

### 工具类 (ui/util/)

| 文件 | 路径 | 功能 |
|------|------|------|
| DateUtils.kt | `app/src/main/java/.../ui/util/DateUtils.kt` | 日期工具类：日期格式化、日期分组逻辑 |

---

## 资源文件 (res/)

### 布局文件 (res/layout/)

| 文件 | 功能 |
|------|------|
| activity_main.xml | 主 Activity 布局：包含 NavHostFragment 和底部导航栏 |
| fragment_photos.xml | 照片列表 Fragment 布局：RecyclerView + 快速滚动条 |
| fragment_photo_detail.xml | 照片详情 Fragment 布局：ViewPager2 + 信息面板 |
| fragment_albums.xml | 相册列表 Fragment 布局：RecyclerView |
| fragment_trash.xml | 回收站 Fragment 布局 |
| item_photo.xml | 单张照片网格项布局 |
| item_photo_with_header.xml | 带日期头的照片项布局 |
| item_album.xml | 相册列表项布局 |
| item_photo_page.xml | 详情页单页布局（大图查看） |
| item_date_header.xml | 日期分组头布局 |
| dialog_columns.xml | 列数选择对话框布局 |
| dialog_delete_options.xml | 删除选项对话框布局 |

### 菜单文件 (res/menu/)

| 文件 | 功能 |
|------|------|
| bottom_nav_menu.xml | 底部导航菜单项：照片、相册、回收站 |
| menu_photos.xml | 照片列表工具栏菜单 |

### 导航文件 (res/navigation/)

| 文件 | 功能 |
|------|------|
| nav_graph.xml | Navigation 导航图：定义 Fragment 间跳转路由和 SafeArgs 参数 |

### 可绘制资源 (res/drawable/)

| 文件 | 功能 |
|------|------|
| ic_photos.xml / ic_photos_normal.xml / ic_photos_selected.xml | 照片 Tab 图标 |
| ic_albums.xml / ic_albums_normal.xml / ic_albums_selected.xml | 相册 Tab 图标 |
| ic_trash.xml | 回收站 Tab 图标 |
| ic_favorite.xml / ic_favorite_filled.xml / ic_favorite_small.xml | 收藏图标 |
| ic_delete.xml / ic_delete_forever.xml | 删除图标 |
| ic_restore.xml | 恢复图标 |
| ic_back.xml | 返回图标 |
| ic_check_white.xml / ic_select_all.xml / ic_selected.xml / ic_selected_filled.xml | 选择相关图标 |
| ic_send.xml | 分享图标 |
| ic_search.xml / ic_filter.xml | 搜索/筛选图标 |
| ic_edit.xml / ic_add.xml / ic_more.xml | 编辑/添加/更多图标 |
| ic_video_indicator.xml | 视频标识图标 |
| ic_launcher_background.xml / ic_launcher_foreground.xml | 应用启动图标 |
| bg_album_gradient.xml | 相册渐变背景 |
| bg_bottom_action.xml / bg_bottom_nav.xml / bg_bottom_nav_item.xml / bg_bottom_sheet.xml | 底部 UI 背景 |
| bg_raw_tag.xml | 原始标签背景 |
| fastscroll_thumb.xml / fastscroll_thumb_material.xml | 快速滚动条滑块 |
| fastscroll_track.xml / fastscroll_track_material.xml | 快速滚动条轨道 |

### 颜色资源 (res/color/)

| 文件 | 功能 |
|------|------|
| bottom_nav_color.xml | 底部导航项选中/未选中状态颜色 |

### 值资源 (res/values/)

| 文件 | 功能 |
|------|------|
| strings.xml | 所有字符串资源（多语言支持） |
| colors.xml | 全局颜色定义 |
| dimens.xml | 尺寸资源（间距、字体大小等） |
| themes.xml | 应用主题和样式定义 |

### XML 配置 (res/xml/)

| 文件 | 功能 |
|------|------|
| backup_rules.xml | 数据备份规则 |
| data_extraction_rules.xml | 数据提取规则 |

### 启动图标 (res/mipmap-*/)

| 目录 | 功能 |
|------|------|
| mipmap-hdpi/ 到 mipmap-xxxhdpi/ | 各密度启动图标 (.webp) |
| mipmap-anydpi/ | 自适应启动图标 (XML) |

---

## 功能定位指南

### 按功能查找文件

| 功能需求 | 相关文件 |
|---------|---------|
| **修改照片网格布局** | `ui/photos/PhotosFragment.kt`, `item_photo.xml`, `GridSpacingItemDecoration.kt` |
| **修改照片大图查看** | `ui/detail/PhotoDetailFragment.kt`, `EdgeSubsamplingScaleImageView.kt`, `item_photo_page.xml` |
| **修改相册列表** | `ui/albums/AlbumsFragment.kt`, `AlbumsViewModel.kt`, `item_album.xml` |
| **修改照片查询逻辑** | `data/repository/MediaRepository.kt`, `data/paging/PhotoPagingSource.kt` |
| **修改收藏功能** | `data/repository/MediaRepository.kt`, `ui/detail/PhotoDetailViewModel.kt` |
| **修改删除功能** | `data/repository/MediaRepository.kt`, `ui/common/DeleteOptionsBottomSheet.kt`, `ui/trash/` |
| **修改底部导航** | `activity_main.xml`, `bottom_nav_menu.xml`, `MainActivity.kt` |
| **修改导航路由** | `res/navigation/nav_graph.xml` |
| **添加新 Fragment** | 在 `ui/` 下创建新包，更新 `nav_graph.xml` 和 `bottom_nav_menu.xml` |
| **修改权限** | `AndroidManifest.xml` |
| **修改依赖** | `app/build.gradle.kts` |
| **修改主题/颜色** | `res/values/themes.xml`, `res/values/colors.xml` |
| **修改字符串** | `res/values/strings.xml` |

---

## 架构关系图

```
MainActivity.kt
    │
    ├── NavHost (nav_graph.xml)
    │       │
    │       ├── PhotosFragment ──── PhotosViewModel ──── MediaRepository ──── MediaStore
    │       │       │                      │                      │
    │       │       └── PhotoPagingAdapter ── PhotoPagingSource ────┘
    │       │
    │       ├── AlbumsFragment ──── AlbumsViewModel ──── MediaRepository
    │       │       │
    │       │       └── AlbumDetailFragment ──── AlbumDetailViewModel ──── MediaRepository
    │       │
    │       ├── PhotoDetailFragment ──── PhotoDetailViewModel ──── MediaRepository
    │       │       │
    │       │       └── PhotoPagerAdapter ──── PhotoPageViewHolder
    │       │
    │       └── TrashFragment ──── TrashViewModel ──── MediaRepository
    │
    └── BottomNavigationView (bottom_nav_menu.xml)
```

---

## 技术栈速查

| 技术 | 用途 |
|------|------|
| Hilt | 依赖注入 |
| Navigation Component + SafeArgs | Fragment 导航 |
| Paging 3 | 照片分页加载 |
| Glide | 图片加载与缓存 |
| SubsamplingScaleImageView | 大图分块显示 |
| Media3 (ExoPlayer) | 视频播放 |
| PermissionX | 运行时权限管理 |
| Coroutines + Flow | 异步处理 |
| Groupie | 复杂列表分组 |
| drag-select-recyclerview | 拖动多选 |
| RecyclerView FastScroll | 快速滚动条 |

---

## 权限说明

| 权限 | 适用版本 | 用途 |
|------|---------|------|
| `READ_MEDIA_IMAGES` | Android 13+ | 读取图片 |
| `READ_MEDIA_VIDEO` | Android 13+ | 读取视频 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ | 用户选择部分访问 |
| `READ_EXTERNAL_STORAGE` | Android 11-12 | 读取存储 (maxSdkVersion=32) |
