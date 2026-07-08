# StarGallery M3 化 · 阶段 B：M3 Token 化重构

- **日期**：2026-07-08
- **作者**：opencode 协作
- **状态**：草案，待用户审阅
- **关联**：完整 M3 化总览（阶段 A 已完成 → 当前阶段 B → 后续阶段 C）
- **前置条件**：阶段 A 完成（minSdk=31, DynamicColor 接入, 启动页改 DynamicColors.Light/Dark）

---

## 1. 背景

阶段 A 已把颜色源从硬编码切换为 Material You 动态颜色，但项目的视觉层仍深度依赖硬编码的 `@color/*` 资源（~120 处于 layout、~44 处于 drawable、~49 处于 icon）和 27 个自定义 `bg_*.xml` drawable。这些硬编码绕过了 M3 token 体系，导致：

- 换壁纸后主题色只影响部分 M3 组件，大量自定义 UI 元素仍保持固定颜色
- 暗色模式依赖手动维护两套 `colors.xml`，token 化后颜色自动跟随
- 未来换肤/无障碍对比度调整无法自动化

**本阶段目标**：把整个 UI 层的颜色引用从 `@color/*` 硬编码迁移为 `?attr/*` M3 token，重写所有自定义 drawable 为 shape + theme attr，为阶段 C（视觉重设计）提供干净的 token 基底。

---

## 2. 目标

- layout 中 ~120 处 `@color/*` 替换为对应的 `?attr/*` token
- 27 个 `bg_*.xml` 重写为 shape + theme attr，不再写死颜色
- 49 个 `ic_*.xml` 的 `fillColor` 改用 theme-aware 颜色
- 4 个 `themes.xml` 中的 10 条 hardcoded token 替换为 Material Theme Builder 生成的静态 scheme
- ~20 处硬编码 `android:textSize` 替换为 typography token
- 所有改动在亮/暗两种模式下视觉无回归

## 3. 非目标

- 不改任何功能布局（position、margin、padding、visibility）
- 不改 `exo_player_controller_view.xml`、`dialog_trash_photo_preview.xml`、`fragment_photo_detail.xml` 中特定硬编码（半透黑 overlay 场景，阶段 C 统一处理）
- 不改 Kotlin/Java 代码中的颜色逻辑（如 `Color.TRANSPARENT`、`Color.parseColor`），这些由阶段 C 审查
- 不引入新 UI 组件、不重构界面层级
- 不改 `values-night/colors.xml` 内容（由 Material Theme Builder 生成静态 scheme 覆盖）

---

## 4. 范围

### 4.1 B1：颜色 token 化（layout + drawable）

**布局文件（28 个 layout XML，~120 处替换）：**

| @color/* | 替换为 ?attr/* | 出现次数（约） | 备注 |
|---|---|---|---|
| `text_primary` | `colorOnSurface` | ~30 | 正文标题 |
| `text_secondary` | `colorOnSurfaceVariant` | ~25 | 副标题/说明 |
| `text_tertiary` | `colorOnSurfaceVariant` + 适当 alpha | ~5 | 次要说明 |
| `background` | `colorSurface` | ~12 | fragment root background |
| `background_white` | `colorSurfaceContainer` | ~10 | card/appbar 背景 |
| `background_card` | `colorSurfaceContainerHigh` | ~5 | 二级卡片 |
| `accent` | `colorPrimary` | ~5 | 选中/强调 |
| `divider` | `colorOutlineVariant` | ~5 | 分隔线 |
| `icon_normal` | `colorOnSurface` | ~35（drawable 中） | 图标默认色 |
| `icon_selected` | `colorPrimary` | ~2 | 图标选中色 |
| `icon_disabled` | `colorOnSurface` + alpha=0.38 | ~5（drawable 中） | 图标禁用色 |
| `white` | `colorOnPrimary` / `colorOnSurface` | ~15（特定场景） | 详情页 toolbar 文字等 |
| `black` | 保留 | ~3 | PhotoDetail 全屏背景 |
| `heart_red` | `colorError` | ~3 | 收藏心形 |
| `delete_red` | `colorError` | ~5 | 删除操作 |
| `selected_overlay` | `colorPrimary` + alpha | ~2 | 选中覆盖层 |
| `selected_border` | `colorPrimary` | ~2 | 选中描边 |
| `bottom_nav_bg` | `colorSurfaceContainer` + alpha | ~2 | 底部导航背景色 |
| `exif_progress_tint` | `colorPrimary` | ~1 | 进度条 |
| `photo_detail_bar_bg` | 保留 | ~2 | 详情页工具栏（半透） |
| `fastscroll_thumb` | `colorPrimary` | ~1 | 快速滚动 |
| `fastscroll_track` | `colorSurfaceVariant` | ~1 | 快速滚动轨 |
| `fastscroll_popup_bg` | 保留 | ~1 | 快滚动 popup |
| `fastscroll_popup_text` | 保留 | ~1 | 快滚动文字 |
| `tag_overlay` | `colorOnSurface` + alpha | ~2 | Tag |
| `tag_overlay_light` | 保留 | ~1 | Tag light |
| `expiration_tag_bg` | `colorOnSurface` + alpha | ~1 | 过期标签 |
| `raw_tag_bg` | `colorOnSurface` + alpha | ~1 | RAW 标签 |
| `raw_tag_text` | `colorOnSurfaceVariant` | ~1 | RAW 文字 |
| `play_button_bg` | 保留（半透） | ~1 | 播放按钮 |
| `video_controls_bg` | 保留（半透） | ~1 | 视频控制栏 |
| `dot_bg` | 保留（半透） | ~1 | 页面指示器 |
| `card_secondary_bg` | `colorSurfaceContainerHigh` + alpha | ~1 | 二级卡片 |
| `card_light_stroke` | `colorOutline` | ~1 | 卡片描边 |
| `delete_drag_handle` | `colorOnSurfaceVariant` | ~1 | 删除拖拽 |
| `selection_overlay` | `colorPrimary` + alpha | ~1 | 多选覆盖 |
| `exif_progress_tint` | `colorPrimary` | ~1 | EXIF 进度 |

**37 个独立颜色 key 映射到 ~16 个 M3 token。**

**保留不动的文件（阶段 C 处理）：**
- `fragment_photo_detail.xml`：`@color/photo_detail_bar_bg`（半透黑，全屏特化）
- `dialog_trash_photo_preview.xml`：`@color/black`、`@color/white`（全屏黑底）
- `exo_player_controller_view.xml`：`@color/white`（视频全屏黑底叠加）

**values-night/colors.xml 如何处理？**
- 现有的暗色颜色值保留不动（作为旧 fallback）
- token 化后 `?attr/*` 自动由主题提供暗色值
- 阶段 B3 会用 Material Theme Builder 生成的完整 scheme 覆盖 `Theme.StarGallery`，届时 `colors.xml` 中大部分 key 将成为 dead code

### 4.2 B2：Drawable 重写

**27 个 `bg_*.xml`** 当前结构分析：

| drawable | 当前（硬编码） | 重构方式 | 依赖的 theme attr |
|---|---|---|---|
| `bg_card.xml` | `solid @color/background_white` | shape + `?attr/colorSurfaceContainer` | `colorSurfaceContainer` |
| `bg_card_primary.xml` | — | shape + `?attr/colorSurface` | `colorSurface` |
| `bg_card_secondary.xml` | `solid @color/card_secondary_bg` | shape + `?attr/colorSurfaceContainerHigh` | `colorSurfaceContainerHigh` |
| `bg_card_light.xml` | `solid @color/background_white` + stroke | shape + `?attr/colorSurfaceContainer` + `?attr/colorOutline` | 同上 + `colorOutline` |
| `bg_bottom_nav.xml` | `solid @color/bottom_nav_bg` | shape + `?attr/colorSurfaceContainer` + alpha | `colorSurfaceContainer` |
| `bg_bottom_sheet.xml` | `solid @color/background_white` | shape + `?attr/colorSurfaceContainerLow` | `colorSurfaceContainerLow` |
| `bg_bottom_action.xml` | `solid @color/bottom_nav_bg` | shape + `?attr/colorSurfaceContainer` | `colorSurfaceContainer` |
| `bg_tag.xml` | `solid @color/tag_overlay` | shape + `?attr/colorOnSurface` + alpha | `colorOnSurface` |
| `bg_tag_gray.xml` | `solid @color/tag_overlay_light` | shape + `?attr/colorOnSurfaceVariant` + alpha | `colorOnSurfaceVariant` |
| `bg_tag_accent.xml` | hardcoded `#007AFF` | shape + `?attr/colorPrimaryContainer` | `colorPrimaryContainer` |
| `bg_tag_blue.xml` | hardcoded `#007AFF` | shape + `?attr/colorSecondaryContainer` | `colorSecondaryContainer` |
| `bg_tag_orange.xml` | hardcoded orange | shape + `?attr/colorTertiaryContainer` | `colorTertiaryContainer` |
| `bg_tag_purple.xml` | hardcoded purple | shape + `?attr/colorTertiaryContainer` | `colorTertiaryContainer` |
| `bg_tag_ios.xml` | hardcoded iOS blue | shape + `?attr/colorPrimaryContainer` | `colorPrimaryContainer` |
| `bg_tag_white.xml` | hardcoded white | shape + `?attr/colorSurface` | `colorSurface` |
| `bg_filter_row.xml` | `solid @color/background_card` | shape + `?attr/colorSurfaceContainerHigh` | `colorSurfaceContainerHigh` |
| `bg_filter_clear.xml` | `solid @color/background_card` | shape + `?attr/colorSurfaceContainerHigh` | `colorSurfaceContainerHigh` |
| `bg_dot.xml` | `solid @color/dot_bg` | 保留（半透） | — |
| `bg_dot_white.xml` | hardcoded white | 保留（作用于黑底） | — |
| `bg_dot_gray.xml` | hardcoded gray | 保留（作用于黑底） | — |
| `bg_play_button.xml` | `solid @color/play_button_bg` | 保留（半透） | — |
| `bg_video_controls.xml` | `solid @color/video_controls_bg` | 保留（半透） | — |
| `bg_album_gradient.xml` | hardcoded gradient | shape + `?attr/colorPrimaryContainer` 渐变 | `colorPrimaryContainer` |
| `bg_drag_handle.xml` | `solid @color/divider` | shape + `?attr/colorOutlineVariant` | `colorOutlineVariant` |
| `bg_hdr_tag.xml` | hardcoded 颜色 | shape + `?attr/colorTertiaryContainer` | `colorTertiaryContainer` |
| `bg_card.xml` | `solid @color/background_white` | shape + `?attr/colorSurfaceContainerLow` | `colorSurfaceContainerLow` |
| `fastscroll_thumb.xml` | hardcoded | shape + `?attr/colorPrimary` | `colorPrimary` |
| `fastscroll_track.xml` | hardcoded | shape + `?attr/colorSurfaceVariant` | `colorSurfaceVariant` |
| `bg_fastscroll_popup.xml` | `@color/fastscroll_popup_bg` | 保留 | — |
| `exif_progress_drawable.xml` | hardcoded | shape + `?attr/colorPrimary` | `colorPrimary` |
| `scrim_*.xml` | gradient | 保留（alpha 渐变） | — |

**26 个 drawable 需重写（17 个改颜色 + 9 个保留半透场景）。**

**49 个 `ic_*.xml`**：
- 35 个 `fillColor="@color/icon_normal"` → 删掉 `fillColor`，改用 `app:tint="@color/icon_normal"` 在调用方设置 — 但需保证所有引用点都设了 tint
- 替代方案：`fillColor="@color/icon_normal"` 保留，由阶段 B1 把 `@color/icon_normal` 映射到 `?attr/colorOnSurface`。**选择此方案**，避免更改所有调用方。
- `ic_filter_active.xml`、`ic_selected_filled.xml` `fillColor="@color/accent"` → `@color/icon_selected`（B1 映射到 `colorPrimary`）
- `ic_photo_placeholder.xml`、`ic_photo_error.xml` `fillColor="@color/icon_disabled"` → B1 映射

**策略**：icon 的 `fillColor` 保留 `@color/icon_normal` / `@color/icon_selected` / `@color/icon_disabled` 引用，在 B1 颜色 token 化中把这三个 color key 映射到对应的 M3 token。

### 4.3 B3：Surface 体系

**当前问题**：`Theme.StarGallery` 中 `colorSurface=@color/white`、`colorSurfaceVariant=@color/white`、所有 `colorSurfaceContainer*=@color/white`，在明亮模式下没有 surface container 层级（全白），导致卡片/页脚/导航栏缺少 M3 应有的 elevation tint。

**改动**：

1. **生成静态 M3 scheme**
   - 使用 `com.google.android.material.color.DynamicColors` 在 API 31+ 已接管，静态 scheme 仅用于 layout editor 预览和构建工具反射
   - 用 Material Theme Builder web 工具（https://m3.material.io/theme-builder）生成
   - 种子色：`colorPrimary=#FF000000`（当前黑色 seed），产物为完整 20+ token 的 light + dark XML
   - 若 web 工具不可用，改用 `io.github.toandv:material-color-utilities` Kotlin DSL 或社区脚本 `material-color-utilities` 生成
   - 输出文件：`res/values/colors_m3.xml`（light token）+ `res/values-night/colors_m3.xml`（dark token）

2. **静态 scheme 主题**
   - 新建 `values/themes_m3.xml`：`Theme.StarGallery.Static`，parent=`Theme.Material3.Light.NoActionBar`
   - 覆盖所有 20+ M3 color primary 和 surface token
   - `values-night/themes_m3.xml`：同步 dark scheme
   - 注意：DynamicColors 已启用时，此静态 scheme 作为 fallback 不被使用
   - **本阶段旧 theme.StarGallery 的 surface 属性改为**：
     ```xml
     <item name="colorSurface">@color/md_theme_light_surface</item>
     <item name="colorSurfaceVariant">@color/md_theme_light_surface_variant</item>
     <item name="colorOnSurface">@color/md_theme_light_on_surface</item>
     <item name="colorSurfaceContainer">@color/md_theme_light_surface_container</item>
     <item name="colorSurfaceContainerLow">@color/md_theme_light_surface_container_low</item>
     <item name="colorSurfaceContainerHigh">@color/md_theme_light_surface_container_high</item>
     <item name="colorSurfaceContainerHighest">@color/md_theme_light_surface_container_highest</item>
     ```

3. **`Theme.StarGallery` 中删除的 non-M3 属性**：
   - `colorPrimaryDark` — M3 无此 token，移除
   - `colorAccent` — M3 用 `colorPrimary` / `colorSecondary` 替代
   - 保留 `android:statusBarColor`、`android:navigationBarColor`、`android:windowLightStatusBar` 等窗口属性

### 4.4 B4：Typography token 化

**当前**：6 个 `TextAppearance.*` 已基于 M3
**需改**：layout 中 ~20 处 `android:textSize` + `android:textColor` 硬编码

| 样式名 | 对应 M3 | 替换 |
|---|---|---|
| `TextAppearance.Headline1` | `TextAppearance.Material3.HeadlineLarge` | 28sp ✅ 已有 |
| `TextAppearance.ListTitle` | `TextAppearance.Material3.TitleMedium` | 18sp ✅ 已有 |
| `TextAppearance.DateHeader` | `TextAppearance.Material3.TitleMedium` | 16sp ✅ 已有 |
| `TextAppearance.Subtitle` | `TextAppearance.Material3.BodyMedium` | 14sp ✅ 已有 |
| `TextAppearance.Caption` | `TextAppearance.Material3.LabelSmall` | 12sp ✅ 已有 |
| `BottomNavText` | `TextAppearance.Material3.LabelSmall` | 11sp ✅ 已有 |

**新增 typography token：**
- `TextAppearance.M3.BodySmall`（12sp）
- `TextAppearance.M3.LabelMedium`（12sp medium）

**layout 中硬编码替换**：约 20 处 `android:textSize=XXsp` + `android:textColor=@color/text_*` 需要替换为 style 引用。

### 4.5 B5：测试/回归

- `./gradlew.bat assembleDebug` 编译通过
- 28 个 fragment/layout 在亮/暗各检查一次
- 重点回归：底部导航（亮暗、横竖屏）、FilterBottomSheet（多选 chip）、PhotoDetail 全屏、Hidden 页安全认证、Trash 页过期标签、ExoPlayer 控制器
- 截图存档至 `docs/superpowers/artifacts/phase-b-YYYY-MM-DD/`

---

## 5. 验收标准

### 5.1 编译
- [ ] `./gradlew.bat assembleDebug` 编译通过
- [ ] 所有 `?attr/*` 引用在 compileSdk=36 下可解析

### 5.2 颜色
- [ ] layout 中 `@color/text_primary` / `@color/background` / `@color/accent` / `@color/divider` 引用已清零（排除保留的 `fragment_photo_detail.xml`、`dialog_trash_photo_preview.xml`、`exo_player_controller_view.xml`）
- [ ] drawable 中 `@color/tag_overlay` / `@color/card_*` / `@color/icon_normal` 引用已清零
- [ ] bg_*.xml 中 `solid android:color="@color/*"` 引用已清零（排除 `bg_dot*`、`bg_play_button`、`bg_video_controls`、`scrim_*`、`bg_fastscroll_popup`）
- [ ] 亮/暗模式文字对比度 >= 4.5:1（WCAG AA）

### 5.3 视觉
- [ ] 28 个 layout 亮暗各遍历一次，无颜色错位
- [ ] 底部导航栏背景色在亮暗下正确跟随
- [ ] BottomSheet 背景色正确
- [ ] Tag 颜色正常（accent/gray/blue/orange/purple/ios/white）
- [ ] 选中描边颜色正确
- [ ] 快速滚动条 thumb + track 颜色正确
- [ ] filter chip 选中/未选中状态颜色正确
- [ ] 收藏心形颜色正确
- [ ] 删除操作颜色正确（红色）

### 5.4 性能
- [ ] 冷启动到首页第一帧 < 500ms（基线对照，仅 attr 引用不 inflate 开销）

---

## 6. 测试策略

| 级别 | 内容 |
|---|---|
| 编译验证 | `assembleDebug` |
| 工具验证 | grep 搜索确认 `@color/text_*` 等旧 key 清零 |
| 设备回归 | 所有 fragment 亮/暗截图对比 |
| 对比度 | WCAG AA 检查（4.5:1） |

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| `?attr/*` 引用在 compileSdk=36 下无法解析 | 低 | 全部使用 M3 标准 token，compileSdk=36 含完整定义 |
| drawable 重构打破暗色模式 | 中 | 每改一个 drawable 即用 shape + theme attr 确认暗色值 |
| icon tint 丢失 | 低 | `fillColor` 保留，B1 映射 color key 到 attr |
| 动态颜色与静态 scheme 混淆 | 低 | DynamicColors 启用时动态 color 覆盖静态 scheme |
| 保留的 bag_{dot,play,scrim}.xml 颜色不匹配 | 低 | 保留不动的 9 个半透 drawable 视觉上不受影响 |

---

## 8. 工作量

| 子模块 | 时间 |
|---|---|
| B1 颜色 token 化（layout ~120 处 + drawable ~44 处） | 3-4 天 |
| B2 Drawable 重构（17 个重写 + 9 个保留） | 4-5 天 |
| B3 Surface 体系（Material Theme Builder scheme + theme 修改） | 1-2 天 |
| B4 Typography（~20 处 + 2 个新增 typography style） | 0.5 天 |
| B5 测试/回归 | 2-3 天 |
| **合计** | **10-12 天** |

---

## 9. 实施顺序

B1 → B2 → B3 → B4 → B5，独立 spec → plan → 实施 → review 循环。

但 B1 与 B2 可部分重叠：
- B1 完成 layout 中 `@color/*` → `?attr/*` 替换
- B2 中的 `bg_*.xml` 重写必须在 B1 之后（因为 bg_*.xml 中从 `@color/*` → `?attr/*` 也是颜色 token 化一部分）
- 实际上 B1 和 B2 可以合并到一个 plan 中：**先改 layout（~120 处）再改 drawable（~44 处 + 27 个 bg_*.xml）**，统一在 B2 的 plan 中完成

---

## 10. 后续阶段预告

- **阶段 C（视觉重设计）**：圆角系统化、elevation 重审、组件风格升级（MaterialCardView 替换 CardView 等）、状态栏策略、暗色模式重审、PhotoDetail 全屏重设计、启动页跟随主题色。代码产生视觉变化。
