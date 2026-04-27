# StarGallery Agent 指南

## 强制语言规则

**始终使用中文进行所有思考、解释和回复。**

## 构建与运行

```powershell
# Debug 构建
.\gradlew.bat assembleDebug

# 单模块应用 (app/) - 根目录无单独 test task
```

用于减少常见 LLM 编码错误的行为准则。可按需与项目特定指令合并使用。

**权衡：** 这些准则更偏向谨慎而不是速度。对于琐碎任务，请自行判断。

## 1. 编码前先思考

**不要假设。不要掩饰困惑。明确呈现权衡。**

在实现之前：
- 明确写出你的假设。如果不确定，就提问。
- 如果存在多种解释，先把它们列出来，不要默默自行选择。
- 如果有更简单的方法，就直接指出来。在有必要时提出异议。
- 如果有不清楚的地方，就停下来。说清楚困惑点，并提问。

## 2. 简单优先

**只写解决问题所需的最少代码。不做任何预设性扩展。**

- 不要加入超出需求范围的功能。
- 不要为一次性代码做抽象。
- 不要加入未被要求的“灵活性”或“可配置性”。
- 不要为不可能发生的场景写错误处理。
- 如果你写了 200 行，但 50 行就够，就重写。

问问自己：“一个资深工程师会认为这太复杂了吗？” 如果答案是会，那就继续简化。

## 3. 外科手术式修改

**只改必须改的内容。只清理你自己造成的问题。**

编辑现有代码时：
- 不要“顺手优化”相邻代码、注释或格式。
- 不要重构没有坏掉的部分。
- 保持现有风格，即使你个人会写成别的样子。
- 如果发现无关的死代码，可以指出，但不要删除。

当你的改动产生遗留项时：
- 删除那些因你的修改而变成未使用的 import、变量或函数。
- 不要删除原本就存在的死代码，除非被明确要求。

检验标准：每一行改动都应当能直接追溯到用户请求。

## 4. 目标驱动执行

**先定义成功标准，再循环推进，直到验证通过。**

把任务转换成可验证的目标：
- “添加校验” → “先为非法输入写测试，再让测试通过”
- “修复这个 bug” → “先写能复现它的测试，再让测试通过”
- “重构 X” → “确保改动前后测试都通过”

对于多步骤任务，先给出简短计划：
```
1. [步骤] → 验证：[检查项]
2. [步骤] → 验证：[检查项]
3. [步骤] → 验证：[检查项]
```

强有力的成功标准能让你独立闭环推进。弱成功标准（“把它弄好”）则会不断需要额外澄清。

---

**如果这些准则正在发挥作用，你会看到：** diff 中不必要的改动更少，因为过度复杂而返工的次数更少，而且澄清性问题会出现在实现之前，而不是出错之后。

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