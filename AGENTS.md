# StarGallery Agent 指南

## 强制语言规则

**始终使用中文进行所有思考、解释和回复。**

## 构建与运行

```powershell
# Debug 构建
.\gradlew.bat assembleDebug

# 单模块应用 (app/) - 根目录无单独 test task
```

## 关键架构

- **MVVM + Repository + Clean Architecture** - UI/ViewModel/Repository/DataSource 分层
- **单模块**: 仅 `:app`
- **入口点**: `MainActivity` → `nav_graph.xml` → `photosFragment` (startDestination)

## 不显而易见的约定

### 依赖注入 (Hilt)
- 使用 **KSP**（而非 kapt）进行注解处理：`ksp(libs.hilt.compiler)`
- 错误示例: `.kapt(libs.hilt.compiler)`

### 导航 (SafeArgs)
- 所有 Fragment 参数使用 SafeArgs 生成的类（如 `PhotosFragmentDirections`、`PhotoDetailFragmentArgs`）
- 参数定义在 `res/navigation/nav_graph.xml` - 禁止手动创建 Bundle

### MediaStore 操作 (IntentSender 模式)
1. Repository 返回 `IntentSender`（而非直接执行）
2. UI 调用 `ActivityResultContracts.StartIntentSenderForResult`
3. 用户确认后回调处理成功/失败
4. 用于: 收藏、删除、回收站操作

### 图片加载
- **Glide** 用于缩略图和 GIF
- **ZoomImageView**（非 SubsamplingScaleImageView）用于大图（>=2000px）子采样加载
- **ExoPlayerManager**: 全局单例 ExoPlayer，页面切换时保持播放状态

### Paging 3 日期分组
- 使用 `insertSeparators` 插入 `SeparatorItem` 实现日期分隔
- `PhotoPagingSource` 来自 MediaStore，每页 50 条

## 关键特性

1. **HDR 检测**: 检查 Bitmap ColorSpace/BT.2020/PQ/HLG 及 Android 14+ Ultra HDR Gainmap
2. **RAW+JPG 配对**: 同名不同扩展名自动关联，显示 "JPG+RAW" 标签
3. **网格列数**: 通过 `GridLayoutManager` + `spanSizeLookup` 动态支持 3-10 列
4. **MediaChangeDetector**: ContentObserver 触发 PagingSource 刷新

## 依赖与仓库

- **腾讯镜像** 用于 Gradle 插件和依赖（配置于 `settings.gradle.kts`）
- **Maven Central** 也配置为优先仓库
- KSP 版本: 2.3.6, Kotlin: 2.3.20, AGP: 9.2.0

## Fragment 导航流程

```
photosFragment → photoDetailFragment (action_photosFragment_to_photoDetailFragment)
               → trashFragment (action_photosFragment_to_trashFragment)
               → aboutFragment (action_photosFragment_to_aboutFragment)

albumsFragment → albumDetailFragment (action_albumsFragment_to_albumDetailFragment)
albumDetailFragment → photoDetailFragment (action_albumDetailFragment_to_photoDetailFragment)

aboutFragment → privacyPolicyFragment, permissionsFragment, thirdPartyLibrariesFragment, contactFragment, licenseFragment
```

## 测试

- Debug 构建包含 LeakCanary 用于内存泄漏检测
- 示例单元测试位于 `app/src/test/java/com/gxstar/stargallery/ExampleUnitTest.kt`