# StarGallery 项目上下文

> 本文档为 AI 助手提供项目背景信息，帮助快速理解和开发 StarGallery 项目。

---

## 项目概述

**StarGallery** 是一个现代化的 Android 本地图库应用，采用 Kotlin + XML 开发。应用专注于提供流畅的照片浏览、管理和查看体验，支持大图缩放、视频播放、批量选择、RAW 格式识别等功能。

| 属性 | 值 |
|------|-----|
| 项目名称 | StarGallery |
| 类型 | Android 本地图库应用 |
| 开发语言 | Kotlin + XML |
| 包名 | `com.gxstar.stargallery` |
| 最低 SDK | Android 11 (API 30) |
| 目标 SDK | Android 14 (API 34) |
| 编译 SDK | Android 14 (API 34) |
| 架构模式 | MVVM + Clean Architecture |
| 依赖注入 | Hilt |

---

## 技术栈

### 核心框架
| 技术 | 版本 | 用途 |
|------|------|------|
| **Kotlin** | 1.9.24 | 开发语言 |
| **Android Gradle Plugin** | 8.5.0 | 构建工具 |
| **Hilt** | 2.51.1 | 依赖注入 |
| **Navigation Component** | 2.8.0 | Fragment 导航 |
| **SafeArgs** | 2.8.0 | 类型安全导航参数 |
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
| **ExifInterface** | 1.3.7 | EXIF 信息读取 |

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
├── data/                      # 数据层
│   ├── model/                 # 数据模型
│   │   ├── Photo.kt          # 照片数据类（含 RAW 识别）
│   │   └── Album.kt          # 相册数据类
│   ├── paging/                # Paging 3 数据源
│   │   └── PhotoPagingSource.kt
│   └── repository/            # 数据仓库
│       └── MediaRepository.kt # MediaStore 操作封装
├── di/                        # Hilt 依赖注入模块
│   ├── AppModule.kt
│   └── PreferenceModule.kt
├── ui/                        # UI 层
│   ├── albums/                # 相册功能
│   ├── common/                # 公共组件
│   ├── detail/                # 照片详情
│   ├── photos/                # 照片网格
│   ├── trash/                 # 回收站
│   └── util/                  # 工具类
├── MainActivity.kt            # 主 Activity
└── StarGalleryApp.kt          # Application 类
```

---

## 功能特性

### 已实现
- **照片浏览**: 按日期分组、网格布局 (3-8 列)、快速滚动、拖动多选
- **照片详情**: 高清大图查看、手势缩放、视频播放、EXIF 信息
- **相册管理**: 相册列表、相册详情、封面预览
- **批量操作**: 长按选择、拖动多选、全选、批量分享/删除
- **收藏功能**: MediaStore API 实现，与系统相册同步
- **回收站**: 逻辑删除、恢复、彻底删除
- **RAW 支持**: DNG/ARW/CR2/CR3/NEF 等格式识别

---

## 导航结构

```
PhotosFragment (照片列表)
├── → PhotoDetailFragment (照片详情)
│       └── photoId: Long, sortType: Int
└── → TrashFragment (回收站)

AlbumsFragment (相册列表)
└── → AlbumDetailFragment (相册详情)
        ├── bucketId: Long
        ├── albumName: String
        └── → PhotoDetailFragment (照片详情)
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

---

## 关键文件速查

### 入口文件
| 文件 | 路径 | 说明 |
|------|------|------|
| AndroidManifest.xml | `app/src/main/AndroidManifest.xml` | 权限声明、Activity 注册 |
| StarGalleryApp.kt | `app/src/main/java/.../StarGalleryApp.kt` | Hilt 入口 |
| MainActivity.kt | `app/src/main/java/.../MainActivity.kt` | 主 Activity |

### 构建配置
| 文件 | 路径 | 说明 |
|------|------|------|
| 根 build.gradle.kts | `build.gradle.kts` | 全局插件 |
| app build.gradle.kts | `app/build.gradle.kts` | 应用依赖 |
| libs.versions.toml | `gradle/libs.versions.toml` | 版本目录 |

### 数据层
| 文件 | 说明 |
|------|------|
| `data/model/Photo.kt` | 照片数据类（含 RAW 识别逻辑） |
| `data/model/Album.kt` | 相册数据类 |
| `data/repository/MediaRepository.kt` | MediaStore 操作封装 |
| `data/paging/PhotoPagingSource.kt` | Paging 3 数据源 |

### UI 层
| 目录 | 文件 | 说明 |
|------|------|------|
| photos/ | PhotosFragment.kt | 照片网格（选择模式、拖动多选） |
| photos/ | PhotosViewModel.kt | 照片列表 ViewModel |
| photos/ | PhotoPagingAdapter.kt | Paging 适配器 |
| detail/ | PhotoDetailFragment.kt | 照片详情页 |
| detail/ | PhotoDetailViewModel.kt | 详情 ViewModel |
| detail/ | EdgeSubsamplingScaleImageView.kt | 自定义大图控件 |
| albums/ | AlbumsFragment.kt | 相册列表 |
| albums/ | AlbumDetailFragment.kt | 相册详情 |
| trash/ | TrashFragment.kt | 回收站 |
| common/ | DeleteOptionsBottomSheet.kt | 删除选项弹窗 |

### 资源文件
| 文件 | 说明 |
|------|------|
| `res/navigation/nav_graph.xml` | 导航路由定义 |
| `res/menu/bottom_nav_menu.xml` | 底部导航菜单 |
| `res/values/themes.xml` | 应用主题 |
| `res/values/colors.xml` | 颜色定义 |

---

## 权限配置

| 权限 | 适用版本 | 用途 |
|------|---------|------|
| `READ_MEDIA_IMAGES` | Android 13+ | 读取图片 |
| `READ_MEDIA_VIDEO` | Android 13+ | 读取视频 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ | 部分访问 |
| `READ_EXTERNAL_STORAGE` | Android 11-12 | 读取存储 |

---

## 开发约定

### 架构原则
- **MVVM 模式**: UI 逻辑在 ViewModel 中处理
- **Repository 模式**: MediaStore 操作统一封装
- **依赖注入**: Hilt 管理组件生命周期
- **响应式编程**: Flow + Paging 3

### 代码规范
- 使用 ViewBinding 替代 findViewById
- 使用 SafeArgs 进行 Fragment 参数传递
- 使用 Coroutines 处理异步操作
- 遵循 Material Design 设计规范

### 性能优化
- Paging 3 分页加载，支持大数据量
- Glide 加载缩略图，减少内存占用
- SubsamplingScaleImageView 分块加载大图
- LeakCanary 检测内存泄漏

---

## MediaStore API

```kotlin
// 收藏/取消收藏
MediaStore.createFavoriteRequest(contentResolver, uris, isFavorite)

// 删除照片
MediaStore.createDeleteRequest(contentResolver, uris)

// 查询照片
contentResolver.query(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    projection, selection, selectionArgs, sortOrder
)
```

---

## 常见问题

### 添加新 Fragment
1. 在 `ui/` 下创建新包
2. 创建 Fragment 和 ViewModel
3. 更新 `res/navigation/nav_graph.xml`
4. 更新 `res/menu/bottom_nav_menu.xml`（如需底部导航）

### 修改照片查询逻辑
修改 `data/repository/MediaRepository.kt`

### 修改主题颜色
修改 `res/values/colors.xml` 和 `res/values/themes.xml`

---

*最后更新: 2026-04-02*
