# StarGallery M3 化 · 阶段 A：Dynamic Color 接入

- **日期**：2026-07-08
- **作者**：opencode 协作
- **状态**：草案，待用户审阅
- **关联**：完整 M3 化总览（阶段 A + B + C）已与用户达成共识，本 spec 只覆盖阶段 A

---

## 1. 背景

StarGallery 当前主题已是 `Theme.Material3.Light.NoActionBar` / `Theme.Material3.Dark.NoActionBar`，但颜色源仍写死在 `colors.xml`（黑白 + iOS 蓝 `#007AFF` 的"小米相册"硬编码风格）。完整 M3 化拆为 3 个独立 spec 顺序实施，本文件是第 1 阶段：把 Android 12+ 设备的颜色源切换为 **Material You 动态颜色（从壁纸派生）**，为后续 token 化和视觉重设计铺路。

**本阶段不改任何视觉布局、不改组件结构、不改 layout XML。**

---

## 2. 目标

- Android 12+（API 31+）设备的颜色源从硬编码切换为 Material You 动态颜色
- 暗色模式自动跟随系统
- 启动闪屏无明显色调闪烁
- 现有 16 个 fragment 视觉无错位（仅颜色源变化）

## 3. 非目标

- 不替换任何硬编码颜色为 `?attr/*` token（由阶段 B 处理）
- 不重写任何 `bg_*.xml` drawable（由阶段 B 处理）
- 不调整 `Theme.StarGallery` 的 surface / typography 配置（由阶段 B 处理）
- 不动 `values-night/colors.xml` 内容（由阶段 B 处理）
- 不为 Android 11- 提供 fallback（minSdk 提升到 31 后无需处理）

---

## 4. 范围

### 4.1 minSdk 提升

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts:19` | `minSdk = 30` → `minSdk = 31` |

**理由**：Material You Dynamic Colors 需要 API 31+。当前 `minSdk = 30` 下要为 30 单独维护静态 fallback，与"按你推荐的来吧"原则冲突。代码内所有 `Build.VERSION_CODES` 检查（已检索 12 处）均为 `>= TIRAMISU(33)` 或 `>= S(31)` 或 `>= P(28)` 或 `>= UPSIDE_DOWN_CAKE(34)`，没有任何 API 30/29 独有特性依赖。

**代价**：放弃 Android 11 用户（2024 年 <3% 市占，且持续下降）。AGENTS.md 当前 `targetSdk = 35`，提升 minSdk 不影响 Play Store 兼容性。

### 4.2 主题 parent 调整

| 文件 | 改动 |
|---|---|
| `app/src/main/res/values/themes.xml:5` | `Theme.App.Starting parent="Theme.StarGallery"` 改用 `Theme.Material3.DynamicColors.Light` 包裹，避免启动闪屏与主屏颜色断层 |
| `app/src/main/res/values-night/themes.xml` | 同步 `Theme.Material3.DynamicColors.Dark` 包裹 |

**values-v31/values-night-v31 不动**：
- 这两个文件中的 `Theme.App.Starting` 已是 `Theme.SplashScreen` 子类
- 其 `postSplashScreenTheme` 已指向 `Theme.StarGallery`（`values-v31/themes.xml:13`、`values-night-v31/themes.xml:7`）
- 启动闪屏的 `windowSplashScreenBackground` 保持现状（白色/background_white），不引入闪屏色调变化，避免色调跳变
- 启动后由 `postSplashScreenTheme` 跳转到 `Theme.StarGallery`，再由 `DynamicColors.applyToActivitiesIfAvailable` 在 MainActivity `onCreate` 中应用动态色

### 4.3 Dynamic Color 接入

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/gxstar/stargallery/MainActivity.kt` | `onCreate` 中在 `super.onCreate(savedInstanceState)` 之后、`LocaleManager.applyLocale()` 之后调用 `DynamicColors.applyToActivitiesIfAvailable(this)` |

**API 选型**：`DynamicColors.applyToActivitiesIfAvailable(Application)` 是 Material Components 1.11.0 提供的标准入口。它会：
- 检测系统是否支持动态颜色（API 31+）
- 不支持时静默跳过
- 自动应用 light + dark scheme，运行时跟随系统 `Configuration.UI_MODE_NIGHT_MASK`

**放置位置**：`super.onCreate` 之后即可调用，不依赖任何 View 创建，因此对所有 fragment 一视同仁。`LocaleManager.applyLocale()` 必须先于 DynamicColors 调用，因为 locale 切换会触发 Activity 重建，DynamicColors 需要在重建后重新应用。

### 4.4 关于保留主题属性

`Theme.StarGallery` 当前包含以下非 M3 token 属性，本阶段**保留不动**：

```xml
<item name="colorPrimary">@color/primary</item>
<item name="colorPrimaryDark">@color/primary_dark</item>
<item name="colorAccent">@color/accent</item>
<item name="android:windowBackground">@color/white</item>
<item name="colorSurface">@color/white</item>
<item name="colorSurfaceVariant">@color/white</item>
<item name="colorOnSurface">@color/text_primary</item>
<item name="colorSurfaceContainer">@color/white</item>
<item name="colorSurfaceContainerHigh">@color/white</item>
<item name="colorSurfaceContainerHighest">@color/white</item>
```

这些属性在 `DynamicColors.applyToActivitiesIfAvailable` 调用时会被动态颜色覆盖（Android 12+ 设备），Android 11- 设备（已无）继续走静态值。**阶段 B 会用 Material Theme Builder 重新生成这些 token 的静态 fallback。**

---

## 5. 验收标准

### 5.1 编译/构建
- [ ] `./gradlew.bat assembleDebug` 在 minSdk=31 下编译通过，无警告
- [ ] 无新增依赖（Material Components 1.11.0 已含 `DynamicColors`）

### 5.2 行为
- [ ] Android 12+ 真机：系统换壁纸后，应用内所有使用 M3 token 的位置（MaterialButton、BottomNavigationView、ChipGroup、AppBar 背景、Tag 容器、选中描边、状态栏/导航栏 inset 派生等）随之变化
- [ ] Android 12+ 真机：系统切换暗色模式，应用立即跟随（无需重启）
- [ ] 启动闪屏：从白屏 → 主页之间无明显颜色跳变（500ms 内不引起用户注意）

### 5.3 视觉回归
- [ ] 16 个 fragment 在 1 台 Android 12+ 设备 + 1 台 Android 14+ 设备上各运行 1 次，无错位
- [ ] 暗色模式：所有页面在两种主题下均无黑底白字/白底黑字误用
- [ ] PhotoDetail 全屏：仍为黑底，状态栏/导航栏图标正确（白）
- [ ] 隐藏照片认证页：暗色模式下指纹提示框可读
- [ ] 回收站页：Tag 颜色在亮/暗下都对比度 >= 4.5:1
- [ ] 启动闪屏：图标在亮/暗模式下都清晰可见

### 5.4 性能
- [ ] 冷启动到首页第一帧 < 500ms（基线对照）
- [ ] 切换暗色模式无掉帧

---

## 6. 测试策略

| 级别 | 内容 |
|---|---|
| 单元测试 | 无（本次仅主题/manifest 改动） |
| 设备回归 | Android 12 真机 1 台 + Android 14 真机 1 台（覆盖 minSdk 31 和当前主流） |
| 截图对比 | 阶段 A 完成后，对 16 个 fragment 亮/暗各拍 1 张截图，存入 `docs/superpowers/artifacts/phase-a-YYYY-MM-DD/` |
| 原型对比 | 阶段 A 开始前，用 Material Theme Builder 生成 1 张"假想效果"截图，给用户预览（实施前确认） |

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 启动闪屏颜色跳变 | 低 | 启动页 `windowSplashScreenBackground` 保持现状白/灰，不引入动态色 |
| PhotoDetail 全屏与 Dynamic Color 冲突 | 低 | PhotoDetail 主题不受影响（窗口级覆写） |
| ExoPlayer controller 颜色不协调 | 低 | controller 内部硬编码白图标 + 半透黑遮罩，与动态色无关 |
| minSdk 提升失去 Android 11 用户 | 低 | 2024 年 <3% 市占，且持续下降 |
| Dynamic Color 与现有 `colorAccent` (#007AFF) 不一致 | 预期 | 这是 Material You 的设计意图，用户已确认接受 |

---

## 8. 工作量

| 子任务 | 时间 |
|---|---|
| 改 `build.gradle.kts` minSdk | 5 分钟 |
| 改 `MainActivity.kt` 接入 DynamicColors | 5 分钟 |
| Material Theme Builder 假想效果截图（给用户 review） | 1 小时 |
| 设备回归测试（2 台 × 16 fragment × 2 主题） | 3 小时 |
| 截图归档 | 30 分钟 |
| **合计** | **0.5 天** |

---

## 9. 实施步骤

按 writing-plans skill 阶段细化，本 spec 不展开。

执行顺序：
1. 改 `app/build.gradle.kts:19` `minSdk = 31`
2. 改 `MainActivity.kt` `onCreate` 加入 `DynamicColors.applyToActivitiesIfAvailable(this)`
3. `./gradlew.bat assembleDebug` 验证
4. 设备回归（Android 12 + Android 14 真机）
5. 截图归档
6. 提交（用户确认后）

---

## 10. 后续阶段预告

- **阶段 B（M3 Token 化重构，10-12 天）**：把硬编码 `@color/*` 和 80+ 自定义 `bg_*.xml` 全部走 `?attr/*` token；用 Material Theme Builder 生成静态 fallback scheme
- **阶段 C（视觉重设计，4-6 天）**：圆角系统化、elevation 重审、组件风格升级、状态栏策略、暗色模式重审、启动页跟随主题

A 阶段完成、B 阶段开始前，会再次走完整的 brainstorm → spec → plan 流程，本 spec 不预先规定后续阶段细节。
