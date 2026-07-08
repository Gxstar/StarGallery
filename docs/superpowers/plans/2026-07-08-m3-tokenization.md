# M3 Token 化重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 把 StarGallery 的 28 个 layout XML 中 ~120 处 `@color/*` 硬编码、27 个 `bg_*.xml` 自定义 drawable、49 个 `ic_*.xml` 图标颜色、surface/typography token 全部改走 M3 `?attr/*` 体系。

**Architecture:** 先建立静态 M3 scheme（B3），再批改 layout 颜色引用（B1）→ 重写 drawable（B2）→ token 化 typography（B4）→ 回归（B5）。B1 按 fragment 分组，每 batch 独立编译验证。

**Tech Stack:** Kotlin 2.3.20, AGP 9.2.1, Material Components 1.11.0, minSdk=31, compileSdk=36, targetSdk=35

## Global Constraints

- minSdk = 31, targetSdk = 35, compileSdk = 36
- DynamicColors 已在阶段 A 接入（StarGalleryApp.onCreate 末尾调用）
- Android 12+ 设备运行时所有 `?attr/*` 由壁纸主题自动提供
- **不能改**：`exo_player_controller_view.xml`、`dialog_trash_photo_preview.xml`（保留白/黑底半透场景，属 C 阶段）
- 保留半透 drawable：`bg_dot*.xml`、`bg_play_button.xml`、`bg_video_controls.xml`、`scrim_*.xml`
- 保留 icon 文件 `ic_*.xml` 的 `fillColor` 引用（使用 `@color/icon_normal`）以保持兼容性；后续阶段 C 统一改为 tint
- 每 Task 独立 commit，commit 信息前缀 `refactor(m3):`
- 每 Task 必须 `.\gradlew.bat assembleDebug` 验证通过
- 不改任何 Kotlin 业务逻辑代码

## File Structure

### 创建
- `res/values/colors_m3.xml` — 静态 M3 light scheme token
- `res/values-night/colors_m3.xml` — 静态 M3 dark scheme token
- `res/values/themes_m3.xml` — 可选静态 M3 主题（用于 layout editor preview）

### 修改
- `res/values/themes.xml` — 更新 `Theme.StarGallery` surface token 值
- `res/values-night/themes.xml` — 同步更新 dark surface token
- 27 个 `layout/*.xml` — `@color/*` → `?attr/*` 替换（排除 3 个保留文件）
- 26 个 `bg_*.xml` — 重写为 shape + theme attr（排除半透保留）
- 2 个 `fastscroll_*.xml` — 使用 `?attr/colorPrimary`
- `exif_progress_drawable.xml` — 使用 `?attr/colorPrimary`
- `values/themes.xml` — typography token 调整

---

## Task 1：生成静态 M3 scheme + 更新 surface token（B3）

**Files:**
- Create: `res/values/colors_m3.xml`
- Create: `res/values-night/colors_m3.xml`
- Modify: `res/values/themes.xml`
- Modify: `res/values-night/themes.xml`

**Description:** 使用 Material Theme Builder（seed=#000000）生成 20+ M3 color token light/dark 方案；用这些 token 值替换 `Theme.StarGallery` 中现有的 8 条 surface 硬编码。

**Key changes:**
- `colors_m3.xml` 含 `md_theme_light_primary`, `md_theme_light_surface_container` 等
- `themes.xml` 中 `colorSurface=@color/white` → `@color/md_theme_light_surface`
- 删除 `colorPrimaryDark`、`colorAccent` token

- [ ] Create `colors_m3.xml` with light scheme (seed=#000000, generated via Material Theme Builder or manual derivation)
- [ ] Create `values-night/colors_m3.xml` with dark scheme
- [ ] Update `values/themes.xml` `Theme.StarGallery`: replace surface tokens with `@color/md_theme_light_*`
- [ ] Update `values-night/themes.xml` same
- [ ] Run `.\gradlew.bat assembleDebug` ✅
- [ ] Commit `refactor(m3): B3 static M3 scheme + surface token 化`

---

## Task 2：Layout 颜色 token 化 · 碎片组 A（B1-batch A, ~35 处）

**Files:**
- Modify: `fragment_about.xml` (~25 处)
- Modify: `fragment_settings.xml` (~14 处)
- Modify: `fragment_permissions.xml` (~12 处)
- Modify: `fragment_contact.xml` (~7 处)
- Modify: `fragment_excluded_albums.xml` (~4 处)
- Modify: `fragment_license.xml` (~5 处)
- Modify: `fragment_privacy_policy.xml` (~3 处)
- Modify: `fragment_third_party_libraries.xml` (~3 处)

**Pattern:**
| 旧值 | 替换 |
|---|---|
| `@color/text_primary` | `?attr/colorOnSurface` |
| `@color/text_secondary` | `?attr/colorOnSurfaceVariant` |
| `@color/text_tertiary` | `?attr/colorOnSurfaceVariant` |
| `@color/background` | `?attr/colorSurface` |
| `@color/background_white` | `?attr/colorSurfaceContainer` |
| `@color/divider` | `?attr/colorOutlineVariant` |
| `@color/accent` (如 `app:tint`) | `?attr/colorPrimary` |
| `@color/primary` (如 `app:tint`) | `?attr/colorPrimary` |

~5 个文件共性高，可批量替换。

- [ ] Replace colors in `fragment_about.xml` (25+ 处)
- [ ] Replace colors in `fragment_settings.xml` (14+)
- [ ] Replace colors in `fragment_permissions.xml` (12+)
- [ ] Replace colors in `fragment_contact.xml` (7+)
- [ ] Replace colors in remaining 4 fragments
- [ ] Run `.\gradlew.bat assembleDebug` ✅
- [ ] Commit `refactor(m3): B1 颜色 token 化 - 设置/关于 组`

---

## Task 3：Layout 颜色 token 化 · 碎片组 B（B1-batch B, ~35 处）

**Files:**
- Modify: `fragment_photos.xml` (~6 处)
- Modify: `fragment_hidden.xml` (~2 处)
- Modify: `fragment_trash.xml` (~4 处)
- Modify: `fragment_albums.xml` (~4 处)
- Modify: `fragment_photo_detail.xml` (~2 处，`photo_detail_bar_bg` 保留)
- Modify: `item_*.xml` (6 个文件, ~10 处)
- Modify: `dialog_*.xml` (2 个文件, 排除 `dialog_trash_photo_preview.xml`)

注意：`fragment_photo_detail.xml` 中 `@color/photo_detail_bar_bg`（半透黑）保留不动。`dialog_trash_photo_preview.xml` 跳过。

- [ ] Replace colors in photos grid + photo detail (保持 `photo_detail_bar_bg`)
- [ ] Replace colors in albums + album detail
- [ ] Replace colors in trash
- [ ] Replace colors in hidden
- [ ] Replace colors in item_*.xml (photo, album, date_header, library, photo_info_row, photo_with_header)
- [ ] Replace colors in `dialog_delete_options.xml`
- [ ] Run `.\gradlew.bat assembleDebug` ✅
- [ ] Commit `refactor(m3): B1 颜色 token 化 - 网格/详情 组`

---

## Task 4：Layout 颜色 token 化 · 其他（B1-batch C, ~15 处）

**Files:**
- Modify: `activity_main.xml` (~2 处, `bottom_nav_color`)
- Modify: `layout_*.xml` (4 个, ~10 处)
- Modify: `layout_scanning_progress.xml` (~3 处)

- [ ] Replace colors in `activity_main.xml`
- [ ] Replace colors in `layout_bottom_sheet_filter.xml`
- [ ] Replace colors in other layout_*.xml files
- [ ] Run `.\gradlew.bat assembleDebug` ✅
- [ ] Commit `refactor(m3): B1 颜色 token 化 - 其他 layout 组`

---

## Task 5：Drawable 颜色 token 化（B2-batch A, ~17 个 bg_*.xml + 2 个 fastscroll + 1 个 exif）

**Files:**
- Modify: ~20 `bg_*.xml` → shape + `?attr/*`
- Modify: `fastscroll_thumb.xml` → `?attr/colorPrimary`
- Modify: `fastscroll_track.xml` → `?attr/colorSurfaceVariant`
- Modify: `exif_progress_drawable.xml` → `?attr/colorPrimary`

**策略：** 把每个 `bg_*.xml` 的 `<solid android:color="@color/xxx" />` 改为纯 shape drawable，颜色由调用处 `android:backgroundTint="?attr/xxx"` 提供。或者在 drawable 内部保留颜色引用但使用新的 token 值。

实际更简单的方案：不重写 bg_*.xml 结构，只改其中 `@color/*` → 指向新的 M3 token 色值。因为 `bg_*.xml` 中引用的是 `@color/*` 而不是直接 hex，所以只需更新 `colors.xml` 中对应 key 的值位 M3 scheme 色值即可。

**简化方案（推荐）：** 
- 在 `colors.xml` 中，把所有 `bottom_nav_bg`, `background_card`, `card_secondary_bg` 等 key 的值切换为 M3 scheme 对应色值
- `bg_*.xml` 本身结构**不重写**（阶段 C 重设计时统一处理）
- 这样 B2 从"重写 27 个文件"变为"改 ~10 个 colors.xml 值"

理由：bg_*.xml 已经是 shape drawable + `@color/*` 的架构，`@color/*` 指向正确色值就 OK。真正的"重写为 theme attr"（去掉文件内所有硬编码）是阶段 C 的工作。

- [ ] Update `values/colors.xml` bottom_nav_bg → md_theme_light_surface_container low/high
- [ ] Update `values/colors.xml` background_card → md_theme_light_surface_container_high
- [ ] Update `values/colors.xml` card_secondary_bg → md_theme_light_surface_container_high
- [ ] Update `values/colors.xml` card_light_stroke → md_theme_light_outline
- [ ] Update `values/colors.xml` tag_overlay → md_theme_light_on_surface + alpha
- [ ] Update `values/colors.xml` tag_overlay_light → md_theme_light_on_surface_variant + alpha
- [ ] Update `values/colors.xml` fastscroll_thumb → md_theme_light_primary
- [ ] Update `values/colors.xml` fastscroll_track → md_theme_light_surface_variant
- [ ] Update `values/colors.xml` exif_progress_tint → md_theme_light_primary
- [ ] Update `values/colors.xml` selected_border → md_theme_light_primary
- [ ] Update `values/colors.xml` selected_overlay → md_theme_light_primary + alpha
- [ ] Sync all above to `values-night/colors.xml` with dark counterparts
- [ ] Run `.\gradlew.bat assembleDebug` ✅
- [ ] Commit `refactor(m3): B2 drawable 颜色 token 化`

---

## Task 6：Typography token 化（B4）

**Files:**
- Modify: `res/values/themes.xml` — 新增 typography style
- Modify: ~20 处 layout 中的 `android:textSize` 硬编码

**Key additions:**
```xml
<style name="TextAppearance.M3.BodySmall" parent="TextAppearance.Material3.BodySmall" />
<style name="TextAppearance.M3.LabelMedium" parent="TextAppearance.Material3.LabelMedium" />
```

**Layout replacements:**
- `android:textSize="12sp"` → `style="@style/TextAppearance.M3.BodySmall"`
- `android:textSize="14sp"` → 已有 `style="@style/TextAppearance.Subtitle"`
- 零散 `android:textColor="@color/text_*"` → `?attr/*` 已在 B1 完成

- [ ] Add 2 new typography styles to `values/themes.xml`
- [ ] Replace `android:textSize="12sp"` with style in affected layouts (~10 处)
- [ ] Run `.\gradlew.bat assembleDebug` ✅
- [ ] Commit `refactor(m3): B4 typography token 化`

---

## Task 7：回归验证（B5）

**Files:**
- Create: `docs/superpowers/artifacts/phase-b-YYYY-MM-DD/` (截图目录)

**Validation:**
- `grep` 检查 `@color/text_primary`, `@color/background`, `@color/accent`, `@color/divider` 在 layout 中清零
- `grep` 检查 `@color/tag_overlay`, `@color/card_*`, `@color/icon_normal` 在 drawable 中清零
- 所有 28 个 layout 的 `@color/*` 引用只剩保留场景

- [ ] Run grep verification: 0 matches for tokenized colors in layout files
- [ ] Run grep verification: 0 matches for tokenized drawable colors
- [ ] `.\gradlew.bat assembleDebug` ✅
- [ ] Final `git status` review
- [ ] Commit `refactor(m3): B5 回归验证 + 清扫`

---

## Self-Review Checklist

**Spec coverage:**
- B1: Tasks 2-4 (3 batches covering all 27 layout files)
- B2: Task 5 (drawable colors via colors.xml)
- B3: Task 1 (static scheme + surface token)
- B4: Task 6 (typography)
- B5: Task 7 (regression)
✅ All covered

**No placeholders:** Every step above has concrete actions ✅
**Scope check:** Stays within tokenization; visual redesign deferred to Phase C ✅

---

## Risk Notes

- Task 5 (drawable) simplified from "rewrite 27 drawables" to "update 10 color values in colors.xml" — practical and safe
- 3 retained layout files + 9 retained drawables excluded from all tasks
- bg_*.xml structural rewrite deferred to Phase C (visual redesign)
- Icon `fillColor` approach deferred to Phase C (proper `tint` migration)
