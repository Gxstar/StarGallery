# M3 视觉重设计实施计划

**Goal:** 统一圆角/elevation、升级组件、状态栏策略、暗色模式重审。

**Architecture:** C1 圆角 → C2 elevation → C3 组件升级 → C4 状态栏 + C6 启动页 → C5 暗色模式。

**Tech Stack:** Material Components 1.12.0, minSdk=31

## File Structure

### 创建
- `res/values/dimens.xml` — 圆角/elevation token
- `res/values-night-v31/` — 夜暗色调 surface token 检查

### 修改
- 27 `bg_*.xml` — `android:radius` → `@dimen/shape_corner_*`
- `item_photo.xml` — CardView → MaterialCardView
- `item_album.xml` — CardView → MaterialCardView
- Layout `fragment_*.xml` — elevation 值替换、状态栏去除
- `values-v31/themes.xml` — 启动页背景
- `values-night-v31/themes.xml` — 暗色启动页

---

## Task 1：C1 圆角系统化

**Files:**
- Create: `res/values/dimens.xml` — 5 个 `shape_corner_*`
- Modify: ~20 `bg_*.xml` / `item_*.xml` / layout — `android:radius` → `@dimen/*`

Replace hardcoded radius values across drawable and layout files. Key replacements:
- `bg_card*.xml`: `8dp` or `12dp` → `@dimen/shape_corner_md`
- `bg_tag*.xml`: `4dp` → `@dimen/shape_corner_sm`
- `bg_bottom_sheet.xml`: top corners → `@dimen/shape_corner_xl`
- `bg_filter_row.xml`: → `@dimen/shape_corner_sm`
- `fastscroll_track.xml`: → `@dimen/shape_corner_xs`

- [ ] Create `dimens.xml` with 5 corner tokens
- [ ] Replace radius in bg_*.xml drawables
- [ ] Replace radius in item_*.xml layouts
- [ ] `.\gradlew.bat assembleDebug` ✅
- [ ] Commit

---

## Task 2：C2 elevation 系统化

**Files:**
- Modify: `layout/*.xml` — elevation values

- [ ] Replace `android:elevation` hardcoded values with `@dimen/elevation_*`
- [ ] Add surface tint elevation overlays (API 31+ `themeOverlay`)
- [ ] `.\gradlew.bat assembleDebug` ✅
- [ ] Commit

---

## Task 3：C3 组件升级

- [ ] `item_photo.xml`: CardView → MaterialCardView
- [ ] `item_album.xml`: CardView → MaterialCardView
- [ ] Add MaterialCardView stroke/background styling
- [ ] Button styles normalization
- [ ] `.\gradlew.bat assembleDebug` ✅
- [ ] Commit

---

## Task 4：C4 + C6 状态栏 + 启动页

- [ ] Review per-fragment status bar overrides
- [ ] EdgeToEdge consistency check
- [ ] `windowSplashScreenBackground` → `?attr/colorSurface`
- [ ] `.\gradlew.bat assembleDebug` ✅
- [ ] Commit

---

## Task 5：C5 暗色模式重审

- [ ] Review values-night/colors.xml remaining values
- [ ] Check retained drawable contrast in dark mode
- [ ] Final `.\gradlew.bat assembleDebug` ✅
- [ ] Commit

---

## Self-Review

- Spec coverage: C1-C6 all have tasks ✅
- No placeholders ✅
- Scope: visual only, no functional changes ✅
