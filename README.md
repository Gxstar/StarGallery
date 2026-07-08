# StarGallery

一个现代化的 Android 本地图库应用，采用 Kotlin 开发，Material Design 设计风格。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.20 |
| 最低版本 | Android 11 | API 30 |
| 架构 | MVVM + Repository + Room + Hilt | - |
| 导航 | Navigation + SafeArgs | 2.9.7 |
| 图片加载 | Glide + 预加载 | 4.16.0 |
| 大图查看 | ZoomImage（子采样缩放） | 1.4.0 |
| 视频播放 | Media3 ExoPlayer | 1.9.1 |
| EXIF 提取 | metadata-extractor | 2.20.0 |
| 数据库 | Room | 2.8.4 |
| 快速滚动 | FastScroller | 1.3.0 |
| 拖动多选 | drag-select-recyclerview | 2.4.0 |
| 生物识别 | Biometric | 1.1.0 |

## 功能特性

### 照片浏览
- **网格布局**：3–8 列可调，竖横屏独立保存列数偏好
- **日期分组**：可按日/月/年自动分组，日期分隔符醒目展示
- **快速滚动**：FastScroller 自定义样式，滚动时显示日期预览
- **实时搜索**：按文件名或文件夹名实时过滤
- **张数统计**：屏幕右上角实时显示当前展示的照片数量
- **实时刷新**：相册内容变化后自动更新，无需手动刷新

### EXIF 多选筛选
- 按**相机品牌**、**相机型号**、**镜头型号**三个维度多选过滤
- 维度间 AND、维度内 OR 逻辑
- **级联自动推导**：选镜头自动勾选对应型号和品牌
- 支持"未知设备"选项，覆盖无 EXIF 信息的照片
- 各选项实时显示可用数量

### 照片详情
- **ViewPager2** 左右滑动翻页，排序与网格列表完全一致
- **大图缩放**：ZoomImageView 子采样分块加载，流畅查看高分辨率图片
- **视频播放**：ExoPlayer 支持，跨页面保持播放状态
- **GIF 动图**：Glide 直接加载播放
- **HDR 检测**：自动识别 Ultra HDR 和广色域照片
- **全屏切换** + **下滑关闭**
- **EXIF 全信息弹窗**：查看拍摄参数、GPS 位置，支持跳转地图 App
- **照片风格解析**：7 大品牌（索尼/佳能/尼康/富士/松下/奥林巴斯/宾得）照片风格/胶片模拟

### RAW 格式识别
- 自动识别 DNG、ARW、CR2/CR3、NEF、ORF、RAF、RW2、PEF 等格式
- 同名 JPG+RAW 自动配对显示

### 批量操作
- 长按进入多选模式，**拖动**连续选择多张
- 支持批量：收藏 / 隐藏 / 删除 / 移至回收站
- 选中状态基于照片 ID 追踪，删除后不会错乱

### 回收站
- 删除的照片进入回收站，显示剩余天数
- 支持恢复和永久删除
- 全屏预览回收站内的照片

### 隐藏照片
- **生物识别保护**（指纹/设备密码），认证失败自动退出
- 隐藏的照片在首页不可见，需通过专门入口查看

### 相册管理
- 按文件夹自动分组，封面预览，显示照片数量
- 相册详情独立设置（列数/排序/分组），与首页互不干扰

### 设置
- **语言切换**：系统跟随 / 简体中文 / English，实时生效无需重启
- **排除相册**：在设置中选择不想显示的相册，立即生效
- **HDR 标签开关**：控制列表中 HDR 标识的显示

### 更多
- **AVIF 支持**：Android 12+ 支持 AVIF 格式解码和缩放浏览
- **缩略图缓存**：本地缓存 512px 缩略图，滚动更流畅
- **快速预览**：详情页导航前预缓存网格列表，打开即可滑动

## 权限说明

| Android 版本 | 所需权限 |
|-------------|----------|
| Android 14+ | `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED`、`ACCESS_MEDIA_LOCATION` |
| Android 13 | `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`ACCESS_MEDIA_LOCATION` |
| Android 11–12 | `READ_EXTERNAL_STORAGE`、`ACCESS_MEDIA_LOCATION` |
| 所有版本 | `SET_WALLPAPER`（普通权限，无需动态申请） |

## 项目结构

```
app/src/main/java/com/gxstar/stargallery/
├── MainActivity.kt
├── StarGalleryApp.kt
├── data/                   # 数据层
│   ├── local/db            # Room 数据库 + DAO
│   ├── local/scanner       # 媒体扫描器
│   ├── local/exif          # EXIF 提取器 + 照片风格解析
│   ├── local/preferences   # 扫描偏好设置
│   ├── local/ThumbnailManager.kt  # 缩略图缓存
│   ├── model/              # 数据模型
│   └── repository/         # 仓库层
├── di/                     # Hilt 依赖注入模块
├── util/                   # 全局工具（语言/排除相册/HDR开关）
├── ui/
│   ├── photos/             # 首页网格（列表/适配器/筛选/多选/扫描）
│   ├── albums/             # 相册列表 + 相册详情
│   ├── detail/             # 照片详情（ViewPager2/视频/HDR/EXIF弹窗）
│   ├── settings/           # 设置页（语言/排除相册/HDR开关）
│   ├── trash/              # 回收站
│   ├── hidden/             # 隐藏照片（生物识别）
│   ├── about/              # 关于页面（隐私/权限/许可等）
│   ├── common/             # 共享组件（选择管理器/网格列数/ViewHolder）
│   └── util/               # 工具类（坐标转换/日期/排序）
```

## 关键设计

### 数据流
```
MediaStore → 扫描 → Room DB → Flow → ViewModel 过滤/排序/分组 → RecyclerView
```

### 排序一致性
网格列表和详情页使用统一的排序算法：拍摄时间倒序 → 添加时间倒序 → ID 倒序。

### 多选机制
选中状态基于照片 ID（而非列表位置），删除或列表变化后不会错乱。

### 横竖屏适配
旋转时不重建 Activity，列数偏好横竖屏独立保存，FastScroller 在横屏下自动贴齐屏幕右侧。

## 构建

```powershell
.\gradlew.bat assembleDebug          # 构建 Debug APK
.\gradlew.bat testDebugUnitTest       # 运行单元测试
```

## 开发说明

- **ViewBinding** 替代 findViewById
- **SafeArgs** 进行 Fragment 参数传递
- **Room Flow + ListAdapter** 数据加载模式
- **Hilt KSP** 依赖注入（非 kapt）
- 支持亮色/暗色主题，中英文双语

*最后更新：2026-07-08*
