# StarGallery M3 化 · 阶段 C：视觉重设计

- **日期**：2026-07-08
- **前置**：阶段 A (Dynamic Color) + 阶段 B (Token 化) 已完成

---

## 1. 目标

在 token 化的 M3 基底上，把 StarGallery 视觉风格从"小米相册硬编码"升级为"系统化 M3 外观"——统一圆角、托升、组件替换、暗色模式重审。

## 2. 范围

### C1：圆角系统化（1 天）

新增 `res/values/dimens.xml`：
```xml
<dimen name="shape_corner_xs">4dp</dimen>
<dimen name="shape_corner_sm">8dp</dimen>
<dimen name="shape_corner_md">12dp</dimen>
<dimen name="shape_corner_lg">16dp</dimen>
<dimen name="shape_corner_xl">28dp</dimen>
```

**替换硬编码 android:radius**（~20 处于 drawable 和 layout）：
| 场景 | 当前值 | 替换 |
|---|---|---|
| 卡片 (bg_card_*.xml, item_photo) | `12dp` | `@dimen/shape_corner_md` |
| BottomSheet | 自定义 | `@dimen/shape_corner_xl` top |
| BottomNavigation | 自定 | `@dimen/shape_corner_lg` |
| Tag (bg_tag_*.xml) | 多种值 | `@dimen/shape_corner_sm` |
| 按钮 (MaterialButton) | `?attr/cornerRadius` | 不换（M3 默认） |
| 搜索栏/FilterRow | `8dp` | `@dimen/shape_corner_sm` |
| 启动页 SplashScreen | 无 | `@dimen/shape_corner_lg` |

**不换**：`PhotoDetail` 全屏（无边角）、Trash 全屏、进度条、滑块。

### C2：Elevation 系统化（0.5 天）

替换硬编码 elevation 为 M3 层级：
| 场景 | 硬编码 | M3 层 |
|---|---|---|
| BottomNavigation | `8dp` | `?attr/elevationLevel3` |
| AppBarLayout | `4dp` | `?attr/elevationLevel2` |
| BottomSheet | `16dp` | `?attr/elevationLevel5` |
| 提示卡片 | `2dp` | `?attr/elevationLevel1` |
| FilterBottomSheet 内部 | `6dp` | `?attr/elevationLevel3` |

### C3：组件风格升级（2-3 天）

**MaterialCardView 替换 CardView**（`item_photo.xml`、`item_album.xml`）：
- `CardView` → `com.google.android.material.card.MaterialCardView`
- `app:cardCornerRadius` → `@dimen/shape_corner_md`
- 加 `app:cardElevation` + `app:strokeWidth` + `app:strokeColor`

**Tag → Chip 或保留**：
- 现有 7 个 `bg_tag_*.xml` 自定义 drawable
- 选项 A：保留 drawable（C 阶段不重写）
- 选项 B：改为 `MaterialChip` style + `chipBackgroundColor` token

**按钮风格**：
- 设置页退出按钮、删除对话框按钮等 → `style="@style/Widget.Material3.Button"` 系列

**BottomSheet 背景**：
- 用 `backgroundTint="?attr/colorSurfaceContainerLow"` 替换自定义 `bg_bottom_sheet.xml`

### C4：状态栏/导航栏策略（0.5 天）

- 确保 `edgeToEdge` + `WindowInsetsController` 在所有 fragment 一致
- 移除 per-fragment 硬编码 `window.statusBarColor`（若有）
- PhotoDetail 全屏时强制 `isAppearanceLightStatusBars=false`

### C5：暗色模式重审（1 天）

- 用 `md_theme_dark_*` 重新评估 `values-night/colors.xml` 剩余的值
- 保留的 9 个半透 drawable 在暗色下检查对比度
- Tag 颜色：确保 `colorOnSurface` 在暗色下的 alpha 可见

### C6：启动页跟随主题（0.5 天）

- `windowSplashScreenBackground` → `?attr/colorSurface`
- `windowSplashScreenAnimatedIcon` → 用 `?attr/colorPrimary` tint
- 确认亮/暗启动页背景正确跟随

## 3. 非目标

- 不改功能布局（position/margin/padding/visibility）
- 不改 Kotlin 逻辑代码
- 不改 `ExoPlayer` 布局或控件
- 不改 `PhotoDetail` 全屏黑底行为

## 4. 验收

- [ ] 所有卡片圆角统一 12dp (`shape_corner_md`)
- [ ] BottomSheet 顶部圆角 28dp
- [ ] 硬编码 elevation 清零
- [ ] MaterialCardView 替换 CardView
- [ ] 状态栏亮/暗跟随正确
- [ ] 暗色模式 surface tonal lift 有效
- [ ] 启动页背景跟随主题色
