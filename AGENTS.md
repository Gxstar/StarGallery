# StarGallery 项目上下文

> 本文档为 AI 助手提供项目背景信息，帮助快速理解和开发 StarGallery 项目。

---

## 项目概述

**StarGallery** 是一个现代化的 Android 本地图库应用，采用 Kotlin + XML 开发。应用专注于提供流畅的照片浏览、管理和查看体验，支持大图缩放、视频播放、批量选择、RAW 格式识别与配对、系统级回收站等功能。

| 属性 | 值 |
|------|-----|
| 项目名称 | StarGallery |
| 类型 | Android 本地图库应用 |
| 开发语言 | Kotlin + XML |
| 包名 | `com.gxstar.stargallery` |
| 最低 SDK | Android 11 (API 30) |
| 目标 SDK | Android 14 (API 34) |
| 编译 SDK | Android 14 (API 34) |
| Java 版本 | 17 |
| 架构模式 | MVVM + Repository 模式 |
| 依赖注入 | Hilt |

---

## 技术栈

### 核心框架
| 技术 | 版本 | 用途 |
|------|------|------|
| **Kotlin** | 2.3.20 | 开发语言 |
| **Android Gradle Plugin** | 9.1.0 | 构建工具 |
| **KSP** | 2.3.6 | Kotlin 符号处理 |
| **Hilt** | 2.59.2 | 依赖注入 |
| **Navigation Component** | 2.9.7 | Fragment 导航 |
| **SafeArgs** | 2.9.7 | 类型安全导航参数 |
| **Paging 3** | 3.2.1 | 分页加载 |
| **Coroutines** | 1.8.1 | 异步处理 |
| **ViewBinding** | - | 视图绑定 |

### 媒体处理
| 技术 | 版本 | 用途 |
|------|------|------|
| **Glide** | 4.16.0 | 缩略图加载与缓存 |
| **SubsamplingScaleImageView** | 3.10.0 | 高清大图分块显示 |
| **GestureViews** | 2.8.3 | 手势缩放支持 |
| **Media3 (ExoPlayer)** | 1.4.1 | 视频播放 |
| **ExifInterface** | 1.3.7 | EXIF 基础信息读取 |
| **Metadata Extractor** | 2.19.0 | 详细 EXIF 信息解析 |

### UI 组件
| 技术 | 版本 | 用途 |
|------|------|------|
| **Material Design** | 1.11.0 | UI 组件库 |
| **RecyclerView FastScroll** | 1.3.0 | 快速滚动条 |
| **drag-select-recyclerview** | 2.4.0 | 拖动多选 |

### 工具
| 技术 | 版本 | 用途 |
|------|------|------|
| **PermissionX** | 1.7.1 | 运行时权限管理 |
| **LeakCanary** | 2.14 | 内存泄漏检测 (Debug) |

---

## 项目结构

```
app/src/main/java/com/gxstar/stargallery/
├── data/                          # 数据层
│   ├── model/                     # 数据模型
│   │   ├── Photo.kt              # 照片数据类（含 RAW 识别、格式标签）
│   │   └── Album.kt              # 相册数据类
│   ├── paging/                    # Paging 3 数据源
│   │   ├── PhotoPagingSource.kt
│   │   └── MediaStorePagingSource.kt
│   └── repository/                # 数据仓库
│       └── MediaRepository.kt     # MediaStore 操作封装
├── di/                            # Hilt 依赖注入模块
│   ├── AppModule.kt
│   └── PreferenceModule.kt
├── ui/                            # UI 层
│   ├── albums/                    # 相册功能
│   │   ├── AlbumsFragment.kt
│   │   ├── AlbumsViewModel.kt
│   │   ├── AlbumDetailFragment.kt
│   │   └── AlbumDetailViewModel.kt
│   ├── common/                    # 公共组件
│   │   ├── DeleteOptionsBottomSheet.kt
│   │   └── DragSelectHelper.kt
│   ├── detail/                    # 照片详情
│   │   ├── PhotoDetailFragment.kt
│   │   ├── PhotoDetailViewModel.kt
│   │   ├── PhotoInfoBottomSheet.kt    # 图片详细信息弹窗
│   │   ├── PhotoPagerAdapter.kt       # 图片 ViewPager 适配器
│   │   ├── PhotoPageViewHolder.kt     # 图片页面 ViewHolder
│   │   ├── EdgeSubsamplingScaleImageView.kt  # 自定义大图控件
│   │   └── ExoPlayerManager.kt        # 视频播放管理器
│   ├── photos/                    # 照片网格
│   │   ├── PhotosFragment.kt
│   │   ├── PhotosViewModel.kt
│   │   ├── PhotoPagingAdapter.kt
│   │   ├── PhotoPreloadModelProvider.kt
│   │   ├── GridSpacingItemDecoration.kt
│   │   ├── action/                # 批量操作
│   │   │   └── BatchActionHandler.kt
│   │   ├── animation/             # 动画效果
│   │   │   └── PhotoItemAnimator.kt
│   │   ├── launcher/              # Intent 启动器
│   │   │   └── IntentSenderManager.kt
│   │   ├── refresh/               # 媒体刷新检测
│   │   │   └── MediaChangeDetector.kt
│   │   └── selection/             # 选择管理
│   │       └── PhotoSelectionManager.kt
│   ├── trash/                     # 回收站
│   │   ├── TrashFragment.kt
│   │   ├── TrashViewModel.kt
│   │   └── TrashPhotoPreviewDialog.kt
│   └── util/                      # 工具类
│       └── DateUtils.kt
├── MainActivity.kt                # 主 Activity（Edge-to-Edge）
└── StarGalleryApp.kt              # Application 类
```

---

## 功能特性

### 已实现

#### 照片浏览
- **网格布局**: 支持 3-8 列动态切换
- **日期分组**: 按日、月、年分组显示
- **快速滚动**: 支持 RecyclerView FastScroll
- **拖动多选**: 支持长按后拖动批量选择
- **选择模式**: 批量选择、全选、取消选择
- **自动刷新**: 检测媒体库变化自动刷新

#### 照片详情
- **高清大图**: 使用 SubsamplingScaleImageView 分块加载
- **手势操作**: 双击缩放、捏合缩放、拖动平移
- **视频播放**: 使用 ExoPlayer 播放视频
- **EXIF 信息**: 显示详细的拍摄参数（机型、镜头、光圈、ISO、快门等）
- **格式标签**: 显示 JPG+RAW、RAW 等格式标识

#### 相册管理
- **相册列表**: 按照片数量排序
- **相册详情**: 进入相册查看照片
- **智能相册**: 收藏、视频等系统相册

#### 批量操作
- **收藏/取消收藏**: 使用 MediaStore API，与系统相册同步
- **删除选项**: 移至回收站或永久删除
- **批量分享**: 支持多选分享

#### RAW 支持
- **格式识别**: DNG、ARW、CR2、CR3、NEF、ORF、RW2、RAF、SRW
- **同名合并**: 自动合并同名的 JPG+RAW 文件，优先显示 JPG
- **格式标签**: UI 显示 "JPG+RAW" 或 "RAW" 标识

#### 回收站
- **系统级回收站**: 使用 Android 11+ 系统回收站 API
- **恢复功能**: 从回收站恢复照片
- **永久删除**: 彻底删除回收站中的照片
- **预览功能**: 支持回收站照片预览

#### 排序与分组
- **排序方式**: 拍摄时间、创建时间
- **分组方式**: 按日、按月、按年分组

---

## 导航结构

```
PhotosFragment (照片列表)
├── → PhotoDetailFragment (照片详情)
│       └── photoId: Long, sortType: Int
└── → TrashFragment (回收站)

AlbumsFragment (相册列表)
├── → AlbumDetailFragment (相册详情)
│       ├── bucketId: Long
│       ├── albumName: String
│       └── → PhotoDetailFragment (照片详情)

PhotoDetailFragment (照片详情)
├── 左右滑动切换照片
├── 显示 EXIF 信息 BottomSheet
└── 支持收藏、删除、分享操作
```

---

## 构建命令

```bash
# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 版本
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug

# 清理项目
./gradlew clean

# 单元测试
./gradlew test
```

**Windows 环境**: 使用 `gradlew.bat` 替代 `./gradlew`

---

## 关键文件速查

### 入口文件
| 文件 | 路径 | 说明 |
|------|------|------|
| AndroidManifest.xml | `app/src/main/AndroidManifest.xml` | 权限声明、Activity 注册 |
| StarGalleryApp.kt | `app/src/main/java/.../StarGalleryApp.kt` | Hilt 入口 |
| MainActivity.kt | `app/src/main/java/.../MainActivity.kt` | 主 Activity（Edge-to-Edge） |

### 构建配置
| 文件 | 路径 | 说明 |
|------|------|------|
| 根 build.gradle.kts | `build.gradle.kts` | 全局插件配置 |
| app build.gradle.kts | `app/build.gradle.kts` | 应用依赖 |
| libs.versions.toml | `gradle/libs.versions.toml` | 版本目录 |

### 数据层
| 文件 | 说明 |
|------|------|
| `data/model/Photo.kt` | 照片数据类（含 RAW 识别、同名合并逻辑） |
| `data/model/Album.kt` | 相册数据类 |
| `data/repository/MediaRepository.kt` | MediaStore 操作封装（查询、收藏、删除、回收站） |
| `data/paging/PhotoPagingSource.kt` | Paging 3 数据源 |
| `data/paging/MediaStorePagingSource.kt` | MediaStore 专用分页源 |

### UI 层
| 目录 | 文件 | 说明 |
|------|------|------|
| photos/ | PhotosFragment.kt | 照片网格（选择模式、拖动多选、分组） |
| photos/ | PhotosViewModel.kt | 照片列表 ViewModel |
| photos/ | PhotoPagingAdapter.kt | Paging 适配器（含日期分隔符） |
| photos/ | PhotoSelectionManager.kt | 选择状态管理 |
| photos/ | BatchActionHandler.kt | 批量操作处理 |
| photos/ | MediaChangeDetector.kt | 媒体库变化检测 |
| detail/ | PhotoDetailFragment.kt | 照片详情页（ViewPager2） |
| detail/ | PhotoDetailViewModel.kt | 详情 ViewModel |
| detail/ | PhotoInfoBottomSheet.kt | EXIF 信息底部弹窗 |
| detail/ | ExoPlayerManager.kt | 视频播放管理 |
| detail/ | EdgeSubsamplingScaleImageView.kt | 自定义大图控件（Edge-to-Edge） |
| albums/ | AlbumsFragment.kt | 相册列表 |
| albums/ | AlbumDetailFragment.kt | 相册详情 |
| trash/ | TrashFragment.kt | 回收站 |
| trash/ | TrashViewModel.kt | 回收站 ViewModel |
| common/ | DeleteOptionsBottomSheet.kt | 删除选项弹窗 |
| common/ | DragSelectHelper.kt | 拖动多选辅助类 |

### 资源文件
| 文件 | 说明 |
|------|------|
| `res/navigation/nav_graph.xml` | 导航路由定义 |
| `res/menu/bottom_nav_menu.xml` | 底部导航菜单 |
| `res/values/themes.xml` | 应用主题（Edge-to-Edge） |
| `res/values/colors.xml` | 颜色定义 |
| `res/values/strings.xml` | 字符串资源 |

---

## 权限配置

| 权限 | 适用版本 | 用途 |
|------|---------|------|
| `READ_MEDIA_IMAGES` | Android 13+ | 读取图片 |
| `READ_MEDIA_VIDEO` | Android 13+ | 读取视频 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ | 部分访问 |
| `READ_EXTERNAL_STORAGE` | Android 11-12 | 读取存储（maxSdkVersion=32） |

---

## 开发约定

### 架构原则
- **MVVM 模式**: UI 逻辑在 ViewModel 中处理，使用 Flow 响应式编程
- **Repository 模式**: MediaStore 操作统一封装在 MediaRepository
- **依赖注入**: Hilt 管理组件生命周期
- **分页加载**: Paging 3 处理大数据量照片列表

### 代码规范
- 使用 ViewBinding 替代 findViewById
- 使用 SafeArgs 进行 Fragment 参数传递
- 使用 Coroutines + Flow 处理异步操作
- 遵循 Material Design 3 设计规范
- 支持 Edge-to-Edge 沉浸式体验

### 性能优化
- Paging 3 分页加载，支持大数据量
- Glide 加载缩略图，支持预加载
- SubsamplingScaleImageView 分块加载大图
- LeakCanary 检测内存泄漏（Debug）
- 同名照片合并减少重复显示

---

## MediaStore API 使用

```kotlin
// 收藏/取消收藏 - 返回 IntentSender 供用户确认
MediaStore.createFavoriteRequest(contentResolver, uris, isFavorite)

// 删除照片 - 返回 IntentSender 供用户确认
MediaStore.createDeleteRequest(contentResolver, uris)

// 移至回收站 - 返回 IntentSender 供用户确认（Android 11+）
MediaStore.createTrashRequest(contentResolver, uris, true)

// 从回收站恢复 - 返回 IntentSender 供用户确认
MediaStore.createTrashRequest(contentResolver, uris, false)

// 查询照片（排除回收站）
val bundle = Bundle().apply {
    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
}
contentResolver.query(uri, projection, bundle, null)

// 查询回收站
val bundle = Bundle().apply {
    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, 
        "${MediaStore.MediaColumns.IS_TRASHED} = 1")
}
```

---

## 新增功能说明

### RAW 照片配对显示
应用支持自动识别并合并同名的 JPG+RAW 文件对：
1. 扫描时按文件名（不含扩展名）分组
2. 优先显示普通格式照片（JPG/HEIF/PNG）
3. 在 Photo 对象中记录 `pairedRawId` 关联 RAW 文件
4. UI 显示格式标签如 "JPG+RAW"

### 自动刷新机制
- 使用 `MediaChangeDetector` 检测媒体库变化
- 比较最新媒体时间戳判断是否有新照片
- 支持拍摄时间和创建时间两种排序的检测

### Edge-to-Edge UI
- 使用 `enableEdgeToEdge()` 实现沉浸式状态栏
- 适配刘海屏（Display Cutout）
- 底部导航栏动态避让系统导航栏

### 详细 EXIF 信息
使用 metadata-extractor 库解析详细拍摄信息：
- 相机机型、镜头型号
- 光圈、ISO、快门速度、焦距
- 拍摄日期时间

---

## 常见问题

### 添加新 Fragment
1. 在 `ui/` 下创建新包
2. 创建 Fragment 和 ViewModel
3. 更新 `res/navigation/nav_graph.xml`
4. 如需底部导航入口，更新 `res/menu/bottom_nav_menu.xml`

### 修改照片查询逻辑
修改 `data/repository/MediaRepository.kt` 中的查询方法

### 修改主题颜色
修改 `res/values/colors.xml` 和 `res/values/themes.xml`

### 添加新的 RAW 格式支持
在 `data/model/Photo.kt` 的 `isRaw` 属性中添加新的 MIME_TYPE 判断

---

*最后更新: 2026-04-07*