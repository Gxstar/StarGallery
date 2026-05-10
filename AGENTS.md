# StarGallery — 项目指南

## 强制规则

**所有思考与回复必须使用中文。**

## 构建

```powershell
.\gradlew.bat assembleDebug          # Debug APK
.\gradlew.bat testDebugUnitTest       # 单元测试
```

单模块 `:app`，版本目录 `gradle/libs.versions.toml`。依赖通过腾讯镜像加速（`settings.gradle.kts`）。

## 架构要点

- **MVVM + Repository + Room + Hilt + Navigation + Paging 3**
- 入口：`MainActivity` → `nav_graph.xml` → `photosFragment` (start)
- Hilt 用 **KSP**（非 kapt）：`ksp(libs.hilt.compiler)`
- Navigation 用 **SafeArgs**，参数定义在 `nav_graph.xml`，禁止手动 Bundle
- 所有 Fragment 参数使用 SafeArgs 生成类（`PhotosFragmentDirections` 等）

## Paging 3 关键约定

这些是反复踩坑后确认的方案：

| 坑 | 正确做法 |
|----|---------|
| PagingData 收集随生命周期重启 | 用 `lifecycleScope.launch` 而非 `repeatOnLifecycle(STARTED)`，防止返回时重复 `submitData` |
| PagingData 跨生命周期 | `photoPagingFlow` 末尾加 `.cachedIn(viewModelScope)` |
| `insertSeparators` 假分隔头 | `enablePlaceholders = false`，否则占位符间隙处会错误插入多余 SeparatorItem |
| 删除后列表跳动 | `photoAdapter?.refresh()` 后设置 `lastExplicitRefreshTime = now` 抑制 ContentObserver 延迟 500ms 的重复刷新 |
| ViewModel 泄漏 | `pagingSourceFactory = { dao.pagingXxx() }` 中 `dao` 必须提取为局部变量，勿用 `photoDao`（隐式捕获 `this`） |

**`gridSpacingItemDecoration` 的 `position < spanCount` 判断**未考虑 SeparatorItem 占位，会导致首行照片间距错误。用 `rowStart = position - spanIndex` 做 header 感知判断。

### 删除/收藏后的数据刷新机制

- **Room 删除** → `invalidationTracker` 自动失效 PagingSource → 无需手动调用
- **收藏切换** → `_refreshTrigger = now()` → `flatMapLatest` 重建 Pager
- `photoAdapter?.refresh()` 仅用于触发 DiffUtil 动画（删除等），之后必须设置 `lastExplicitRefreshTime` 防止 ContentObserver 500ms 后重复触发

### ContentObserver 防抖

`MediaChangeDetector` 有 500ms debounce + `shouldSkipRefresh`（1秒抑制窗口）：
- `lastExplicitRefreshTime` 被设置后 1 秒内跳过 ContentObserver 回调
- 在 IntentSender 回调中设置此属性防止双刷新

## MediaStore 操作 (IntentSender 模式)

1. `Repository` 返回 `IntentSender`（非直接执行）
2. UI 通过 `ActivityResultContracts.StartIntentSenderForResult` 启动
3. 用户确认后回调处理成功/失败
4. 用于：收藏、删除、回收站

## 图片加载

- **Glide** 缩略图/GIF，**ZoomImageView**（非 SubsamplingScaleImageView）大图子采样
- **ExoPlayerManager**：全局单例，页面切换保持播放状态

## RecyclerView 网格

- `GridLayoutManager` 3-10 列动态切换，`spanSizeLookup` 控制 SeparatorItem 占整行
- `setHasFixedSize(true)` + `setItemViewCacheSize(8)`
- `supportsChangeAnimations = false` 避免变更动画残影
- 扫描时 `itemAnimator = null` 防快速刷新乱跳

## 内存泄漏

- Debug 构建含 LeakCanary，关注点：
  - `pagingSourceFactory` lambda 不能隐式捕获 `this`（ViewModel）
  - `lifecycleScope.launch` 的 PagingData 收集不会在 `onStop` 时取消，需确保 ViewModel `onCleared` 时清理
- 修改后运行 `assembleDebug` + LeakCanary 检查

## 测试

- 仅有占位单元测试 `app/src/test/.../ExampleUnitTest.kt`
- 无集成测试、无 UI 测试

## 导航图

```
photosFragment → photoDetailFragment    (action_photosFragment_to_photoDetailFragment)
               → trashFragment
               → aboutFragment
albumsFragment  → albumDetailFragment   (action_albumsFragment_to_albumDetailFragment)
albumDetailFragment → photoDetailFragment
aboutFragment  → privacyPolicyFragment, permissionsFragment, thirdPartyLibrariesFragment, contactFragment, licenseFragment
```