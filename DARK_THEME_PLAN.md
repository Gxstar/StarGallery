# StarGallery 暗色主题实施方案

> 生成日期：2026-06-03
> 状态：待执行
> 预计文件变更：**60+ 个文件**

---

## 目录

1. [架构概述](#1-架构概述)
2. [P0：基础设施（6 个文件）](#2-p0基础设施)
3. [P1：主题切换 UI（4 个文件）](#3-p1主题切换-ui)
4. [P2：布局适配（20+ 个文件）](#4-p2布局适配)
5. [P3：Drawable 硬编码颜色清理（20+ 个文件）](#5-p3drawable-硬编码颜色清理)
6. [P4：Kotlin 代码适配（5 个文件）](#6-p4kotlin-代码适配)
7. [P5：图标 Vector 适配（12 个文件）](#7-p5图标-vector-适配)
8. [P6：构建验证](#8-p6构建验证)
9. [风险与注意事项](#9-风险与注意事项)
10. [附录：完整颜色映射表](#10-附录完整颜色映射表)

---

## 1. 架构概述

### 原理

使用 Android 的 `values-night/` 资源限定符 + `AppCompatDelegate.setDefaultNightMode()`。**不需要手动切换样式/颜色**，Android 资源系统自动根据当前夜间模式选择 `values-night/` 下的资源。

### 主题模式定义

| 模式 | `AppCompatDelegate` 常量 | 说明 |
|------|--------------------------|------|
| 跟随系统 | `MODE_NIGHT_FOLLOW_SYSTEM` | 默认值，跟随系统设置 |
| 浅色 | `MODE_NIGHT_NO` | 始终浅色 |
| 深色 | `MODE_NIGHT_YES` | 始终深色 |

### 初始化流程

```
App启动
  └→ StarGalleryApp.onCreate()
       ├→ 从 SharedPreferences 读取 theme_mode（int，默认 MODE_NIGHT_FOLLOW_SYSTEM）
       ├→ AppCompatDelegate.setDefaultNightMode(mode)
       └→ 后续所有 Activity 自动使用对应主题资源

用户切换主题
  └→ PhotosFragment.showThemeDialog()
       ├→ 保存新 mode 到 SharedPreferences
       ├→ AppCompatDelegate.setDefaultNightMode(newMode)
       └→ activity?.recreate()   // 重建 Activity 使新主题生效
```

### 文件清单总览

| 路径 | 操作 | 阶段 |
|------|------|------|
| `res/values-night/colors.xml` | **新建** | P0 |
| `res/values-night/themes.xml` | **新建** | P0 |
| `res/values-night-v31/themes.xml` | **新建** | P0 |
| `res/values/strings.xml` | 修改（追加 4 行） | P0 |
| `StarGalleryApp.kt` | 修改 | P0 |
| `res/menu/menu_photos.xml` | 修改（追加 1 个 item） | P1 |
| `PhotosFragment.kt` | 修改（追加 showThemeDialog） | P1 |
| `res/values/themes.xml` | 修改（底部弹窗主题） | P1 |
| 22 个布局文件 | 修改（硬编码→资源引用） | P2 |
| `res/values/colors.xml` | 修改（新增约 10 个颜色） | P2 |
| 17 个 drawable XML | 修改（硬编码→资源引用） | P3 |
| `res/values-night/` 对应颜色 | 需追加暗色值 | P3 |
| 5 个 Kotlin 文件 | 修改 | P4 |
| 10 个图标 vector XML | 修改 | P5 |
| 2 个 PNG fallback 图标 drawable | **新建** | P5 |

---

## 2. P0：基础设施

### 2.1 新建 `app/src/main/res/values-night/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 主色调 -->
    <color name="primary">#FFFFFFFF</color>
    <color name="primary_dark">#FFE1E1E1</color>
    <color name="accent">#FF0A84FF</color>

    <!-- 背景色 -->
    <color name="background">#FF0D0D0D</color>
    <color name="background_white">#FF1C1C1E</color>
    <color name="background_card">#FF2C2C2E</color>

    <!-- 文字颜色 -->
    <color name="text_primary">#FFF5F5F5</color>
    <color name="text_secondary">#FF8E8E93</color>
    <color name="text_tertiary">#FF636366</color>
    <color name="text_hint">#FF48484A</color>

    <!-- 图标颜色 -->
    <color name="icon_normal">#FFE5E5E5</color>
    <color name="icon_selected">#FF0A84FF</color>
    <color name="icon_disabled">#FF48484A</color>

    <!-- 分隔线 -->
    <color name="divider">#FF38383A</color>

    <!-- 选中状态 -->
    <color name="selected_overlay">#1A0A84FF</color>
    <color name="selected_border">#FF0A84FF</color>

    <!-- 快速滚动条 -->
    <color name="fastscroll_thumb">#FF0A84FF</color>
    <color name="fastscroll_track">#FF38383A</color>

    <!-- 基础颜色 -->
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
    <color name="transparent">#00000000</color>

    <!-- 语义色 -->
    <color name="heart_red">#FFFF3B30</color>
    <color name="delete_red">#FFFF453A</color>

    <!-- ===== 新增：从 P2 硬编码提取的资源 ===== -->

    <!-- 底部导航栏背景 (原 #E6FFFFFF → 暗 #E61C1C1E) -->
    <color name="bottom_nav_bg">#E61C1C1E</color>

    <!-- 快速滚动弹窗背景 (原 #E6000000 → 暗 #E6F5F5F5) -->
    <color name="fastscroll_popup_bg">#E6F5F5F5</color>

    <!-- 快速滚动弹窗文字 (原 #FFFFFF → 暗 #000000) -->
    <color name="fastscroll_popup_text">#FF000000</color>

    <!-- 照片详情工具栏背景 (原 #F2FFFFFF → 暗 #F21C1C1E) -->
    <color name="photo_detail_bar_bg">#F21C1C1E</color>

    <!-- 半透明标签 (原 #1A000000 → 暗 #1AFFFFFF) -->
    <color name="tag_overlay">#1AFFFFFF</color>

    <!-- 极浅透明标签 (原 #0D000000 → 暗 #0DFFFFFF) -->
    <color name="tag_overlay_light">#0DFFFFFF</color>

    <!-- 选择遮罩 (原 #40000000 → 暗 #40FFFFFF) -->
    <color name="selection_overlay">#40FFFFFF</color>

    <!-- 过期标签背景 (原 #99000000 → 暗 #99FFFFFF) -->
    <color name="expiration_tag_bg">#99FFFFFF</color>

    <!-- RAW 标签背景 (原 #80000000 → 暗 #80FFFFFF) -->
    <color name="raw_tag_bg">#80FFFFFF</color>

    <!-- RAW 标签文字 (原 #CCCCCC → 暗 #CCCCCC 保持不变即可) -->
    <color name="raw_tag_text">#FFCCCCCC</color>

    <!-- 播放按钮背景 (原 #80000000 → 暗 #80FFFFFF) -->
    <color name="play_button_bg">#80FFFFFF</color>

    <!-- 视频控件背景 (原 #60000000 → 暗 #60FFFFFF) -->
    <color name="video_controls_bg">#60FFFFFF</color>

    <!-- 圆点半透明 (原 #80000000 → 暗 #80FFFFFF) -->
    <color name="dot_bg">#80FFFFFF</color>

    <!-- 浅色卡片背景 (原 #F2F2F7 → 暗 #2C2C2E) -->
    <color name="card_secondary_bg">#2C2C2E</color>

    <!-- EXIF 进度条 (原 #FF64B5F6 → 暗 #FF64B5F6 保持不变) -->
    <color name="exif_progress_tint">#FF64B5F6</color>

    <!-- 浅色卡片边框 (原 #E5E5EA → 暗 #48484A) -->
    <color name="card_light_stroke">#FF48484A</color>

    <!-- 删除选项顶部横条 (原 #E0E0E0 → 暗 #48484A) -->
    <color name="delete_drag_handle">#FF48484A</color>
</resources>
```

### 2.2 新建 `app/src/main/res/values-night/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.StarGallery" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorPrimaryDark">@color/primary_dark</item>
        <item name="colorAccent">@color/accent</item>
        <item name="android:windowBackground">@color/background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="colorSurface">@color/background_white</item>
        <item name="colorSurfaceVariant">@color/background_white</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorSurfaceContainer">@color/background_white</item>
        <item name="colorSurfaceContainerHigh">@color/background_white</item>
        <item name="colorSurfaceContainerHighest">@color/background_white</item>
    </style>

    <!-- 底部弹窗主题：去掉 .Light 后缀，让 Material3 自动适配 -->
    <!-- 注意：此样式与 values/themes.xml 中的同名样式形成覆盖关系 -->
    <style name="CustomBottomSheetDialogTheme" parent="Theme.Material3.BottomSheetDialog">
        <item name="bottomSheetStyle">@style/CustomBottomSheetStyle</item>
    </style>

    <style name="CustomBottomSheetStyle" parent="Widget.Material3.BottomSheet.Modal">
        <item name="backgroundTint">@color/transparent</item>
    </style>

    <!-- 文字样式 - 与亮色主题相同，通过 colors-night 自动切换颜色 -->
    <style name="TextAppearance.Headline1" parent="TextAppearance.Material3.HeadlineLarge">
        <item name="android:textSize">28sp</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:fontFamily">sans-serif-medium</item>
    </style>

    <style name="TextAppearance.ListTitle" parent="TextAppearance.Material3.TitleMedium">
        <item name="android:textSize">18sp</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:fontFamily">sans-serif-medium</item>
    </style>

    <style name="TextAppearance.DateHeader" parent="TextAppearance.Material3.TitleMedium">
        <item name="android:textSize">16sp</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:fontFamily">sans-serif-medium</item>
    </style>

    <style name="TextAppearance.Subtitle" parent="TextAppearance.Material3.BodyMedium">
        <item name="android:textSize">14sp</item>
        <item name="android:textColor">@color/text_secondary</item>
    </style>

    <style name="TextAppearance.Caption" parent="TextAppearance.Material3.LabelSmall">
        <item name="android:textSize">12sp</item>
        <item name="android:textColor">@color/text_tertiary</item>
    </style>

    <style name="BottomNavText" parent="TextAppearance.Material3.LabelSmall">
        <item name="android:textSize">11sp</item>
    </style>

    <style name="DialogAnimation">
        <item name="android:windowEnterAnimation">@android:anim/fade_in</item>
        <item name="android:windowExitAnimation">@android:anim/fade_out</item>
    </style>
</resources>
```

### 2.3 新建 `app/src/main/res/values-night-v31/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 暗色启动画面主题 -->
    <style name="Theme.App.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/background_white</item>
        <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher</item>
        <item name="android:windowSplashScreenAnimationDuration">500</item>
        <item name="postSplashScreenTheme">@style/Theme.StarGallery</item>
    </style>
</resources>
```

### 2.4 修改 `app/src/main/res/values/strings.xml`

在文件末尾（`</resources>` 之前）追加：

```xml
<!-- 主题切换 -->
<string name="theme">主题模式</string>
<string name="theme_system">跟随系统</string>
<string name="theme_light">浅色模式</string>
<string name="theme_dark">深色模式</string>
```

### 2.5 修改 `app/src/main/java/com/gxstar/stargallery/StarGalleryApp.kt`

**原内容：**
```kotlin
package com.gxstar.stargallery

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StarGalleryApp : Application()
```

**修改为：**
```kotlin
package com.gxstar.stargallery

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StarGalleryApp : Application() {

    companion object {
        const val PREFS_NAME = "stargallery_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_THEME_MODE = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    override fun onCreate() {
        super.onCreate()
        applyThemeFromPreferences()
    }

    private fun applyThemeFromPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
```

> **说明**：在 `Application.onCreate()` 中调用 `setDefaultNightMode()` 确保所有 Activity 启动前已设置正确的夜间模式。`shared_prefs` 的 key 使用 `stargallery_prefs`，与 `PreferenceModule` 提供的实例一致。

### 2.6 修改 `app/src/main/res/values/themes.xml`

**修改目标**：将 `CustomBottomSheetDialogTheme` 的 parent 从 `.Light` 改为不带后缀的通用版本。

**找到第 64 行：**
```xml
<style name="CustomBottomSheetDialogTheme" parent="Theme.Material3.Light.BottomSheetDialog">
```

**改为：**
```xml
<style name="CustomBottomSheetDialogTheme" parent="Theme.Material3.BottomSheetDialog">
```

> **说明**：去掉 `.Light` 后缀后，Material3 会根据当前 Activity 的主题自动映射到 Light 或 Dark 子主题。亮色模式下行为完全不变。

---

## 3. P1：主题切换 UI

### 3.1 修改 `app/src/main/res/menu/menu_photos.xml`

在 `action_columns` 和 `action_trash` 之间插入新条目：

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/action_select"   android:title="@string/select" />
    <item android:id="@+id/action_sort"     android:title="@string/sort_by" />
    <item android:id="@+id/action_group"    android:title="@string/group_by" />
    <item android:id="@+id/action_columns"  android:title="@string/columns" />
    <item android:id="@+id/action_theme"    android:title="@string/theme" />
    <item android:id="@+id/action_trash"    android:title="@string/trash_title" />
    <item android:id="@+id/action_hidden"   android:title="@string/hidden_title" />
    <item android:id="@+id/action_about"    android:title="@string/about" />
</menu>
```

### 3.2 修改 `app/src/main/java/com/gxstar/stargallery/ui/photos/PhotosFragment.kt`

#### 3.2.1 在 `showPopupMenu` 的 `when` 分支中添加（约第 727 行，`action_columns` 分支之后）：

```kotlin
R.id.action_theme -> {
    showThemeDialog()
    true
}
```

#### 3.2.2 在类中添加新方法（建议放在 `showColumnsDialog()` 方法附近）：

```kotlin
/**
 * 显示主题切换对话框
 */
private fun showThemeDialog() {
    val currentMode = sharedPreferences.getInt(
        StarGalleryApp.KEY_THEME_MODE,
        StarGalleryApp.DEFAULT_THEME_MODE
    )
    val checkedIndex = when (currentMode) {
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> 0
        AppCompatDelegate.MODE_NIGHT_NO -> 1
        AppCompatDelegate.MODE_NIGHT_YES -> 2
        else -> 0
    }

    val items = arrayOf(
        getString(R.string.theme_system),
        getString(R.string.theme_light),
        getString(R.string.theme_dark)
    )

    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.theme)
        .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
            val mode = when (which) {
                0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            sharedPreferences.edit()
                .putInt(StarGalleryApp.KEY_THEME_MODE, mode)
                .apply()
            AppCompatDelegate.setDefaultNightMode(mode)
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
```

#### 3.2.3 添加必要的 import（文件顶部）：

```kotlin
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gxstar.stargallery.StarGalleryApp
```

> **注意**：`sharedPreferences` 已经在类中通过 `@Inject lateinit var sharedPreferences: SharedPreferences` 注入。`requireContext()` 返回 Activity 上下文，`MaterialAlertDialogBuilder` 能正确继承主题。

---

## 4. P2：布局适配

### 原则

- **`android:background="@color/white"`** → **`android:background="?attr/colorSurface"`**
- **`android:background="@color/background"`** → 保持不变（已在 `values-night/colors.xml` 中映射暗色值）
- **`android:textColor="@color/text_*"`** → 保持不变（同上）
- **`android:background="@color/background_card"`** → 保持不变（同上）

### 4.1 List：所有需要改 `@color/white` → `?attr/colorSurface` 的布局文件

| # | 文件路径 | 行 | 上下文 |
|---|---------|----|--------|
| 1 | `res/layout/fragment_photos.xml` | `android:background="@color/white"` | CoordinatorLayout 根布局 |
| 2 | `res/layout/fragment_photos.xml` | `android:background="@color/white"` | AppBarLayout |
| 3 | `res/layout/fragment_photos.xml` | `android:background="@color/white"` | normalToolbar 的 ConstraintLayout |
| 4 | `res/layout/fragment_photos.xml` | `android:background="@color/white"` | selectionToolbar 的 ConstraintLayout |
| 5 | `res/layout/fragment_photos.xml` | `android:background="@color/white"` | searchToolbar 的 ConstraintLayout |
| 6 | `res/layout/fragment_albums.xml` | 同上模式（CoordinatorLayout + AppBarLayout + Toolbar） | 约 3 处 |
| 7 | `res/layout/fragment_trash.xml` | 同上 | 约 3 处 |
| 8 | `res/layout/fragment_hidden.xml` | 同上 | 约 3 处 |
| 9 | `res/layout/fragment_photo_detail.xml` | `android:background="@color/white"` | rootContainer FrameLayout |
| 10 | `res/layout/activity_main.xml` | `android:background="@color/white"` | rootLayout ConstraintLayout |
| 11 | `res/layout/item_photo.xml` | `android:background="@color/white"` | 照片列表项 ConstraintLayout |
| 12 | `res/layout/item_photo_with_header.xml` | 如有 `@color/white` | 带日期头的照片项 |
| 13 | `res/layout/item_date_header.xml` | 如有 `@color/white` | 日期分隔符 |
| 14 | `res/layout/item_album.xml` | 如有 `@color/white` | 相册列表项 |
| 15 | `res/layout/layout_scanning_progress.xml` | `app:cardBackgroundColor="@color/white"` | 扫描进度卡片 |

> **批量替换方法**：在 IDE 中使用正则搜索 `background="@color/white"`，逐文件确认后替换。

### 4.2 特殊案例：`fragment_photo_detail.xml` 的毛玻璃酒吧

**当前（第 19、90 行）：**
```xml
android:background="#F2FFFFFF"
```

**修改为：**
```xml
android:background="@color/photo_detail_bar_bg"
```

> 这个颜色需要在 `values/colors.xml` 中新增（亮色 `#F2FFFFFF`），并在 `values-night/colors.xml` 中设为暗色 `#F21C1C1E`。

### 4.3 `fragment_photos.xml` 的 `progressTint`

**当前（第 117 行）：**
```xml
android:progressTint="#FF64B5F6"
```

**修改为：**
```xml
android:progressTint="@color/exif_progress_tint"
```

> 需要在 `values/colors.xml` 中新增该颜色资源（亮暗色统一为 `#FF64B5F6`，因为这个蓝色在两种背景下都清晰可见）。

### 4.4 修改 `app/src/main/res/values/colors.xml`

在文件末尾（`</resources>` 之前）追加新增颜色：

```xml
<!-- ===== 新增：暗色主题适配 ===== -->
<color name="bottom_nav_bg">#E6FFFFFF</color>
<color name="fastscroll_popup_bg">#E6000000</color>
<color name="fastscroll_popup_text">#FFFFFFFF</color>
<color name="photo_detail_bar_bg">#F2FFFFFF</color>
<color name="tag_overlay">#1A000000</color>
<color name="tag_overlay_light">#0D000000</color>
<color name="selection_overlay">#40000000</color>
<color name="expiration_tag_bg">#99000000</color>
<color name="raw_tag_bg">#80000000</color>
<color name="raw_tag_text">#FFCCCCCC</color>
<color name="play_button_bg">#80000000</color>
<color name="video_controls_bg">#60000000</color>
<color name="dot_bg">#80000000</color>
<color name="card_secondary_bg">#F2F2F7</color>
<color name="exif_progress_tint">#FF64B5F6</color>
<color name="card_light_stroke">#FFE5E5EA</color>
<color name="delete_drag_handle">#FFE0E0E0</color>
```

---

## 5. P3：Drawable 硬编码颜色清理

### 5.1 bg_bottom_nav.xml

**当前：**
```xml
<solid android:color="#E6FFFFFF" />
```
**改为：**
```xml
<solid android:color="@color/bottom_nav_bg" />
```

### 5.2 bg_fastscroll_popup.xml

**当前：**
```xml
<solid android:color="#E6000000" />
```
**改为：**
```xml
<solid android:color="@color/fastscroll_popup_bg" />
```

### 5.3 bg_card_light.xml（如果有硬编码）

检查文件中是否包含硬编码 `#FFFFFF` 或 `#E5E5EA`。

**查找并替换：**
- `#FFFFFF` → `@color/background_white`
- `#E5E5EA` → `@color/card_light_stroke`

### 5.4 bg_card_secondary.xml

**当前（如果文件内容为）：**
```xml
<solid android:color="#F2F2F7" />
```
**改为：**
```xml
<solid android:color="@color/card_secondary_bg" />
```

### 5.5 bg_tag.xml

**当前：**
```xml
<solid android:color="#1A000000" />
```
**改为：**
```xml
<solid android:color="@color/tag_overlay" />
```

### 5.6 bg_tag_gray.xml

**当前：**
```xml
<solid android:color="#0D000000" />
```
**改为：**
```xml
<solid android:color="@color/tag_overlay_light" />
```

### 5.7 bg_play_button.xml

**当前：**
```xml
<solid android:color="#80000000" />
```
**改为：**
```xml
<solid android:color="@color/play_button_bg" />
```

### 5.8 bg_video_controls.xml

**当前：**
```xml
<solid android:color="#60000000" />
```
**改为：**
```xml
<solid android:color="@color/video_controls_bg" />
```

### 5.9 bg_dot.xml

**当前：**
```xml
<solid android:color="#80000000" />
```
**改为：**
```xml
<solid android:color="@color/dot_bg" />
```

### 5.10 bg_bottom_action.xml

**当前：**
```xml
<solid android:color="#E6FFFFFF" />
```
**改为：**
```xml
<solid android:color="@color/bottom_nav_bg" />
```

### 5.11 bg_drag_handle.xml

检查是否有硬编码（当前使用 `@color/divider`，应已 OK）。

### 5.12 dialog_delete_options.xml 中的硬编码

查找 `#E0E0E0`（顶部横条颜色），如果有，改为：
```xml
<solid android:color="@color/delete_drag_handle" />
```

### 5.13 exif_progress_drawable.xml

查找硬编码 `#1A007AFF` 或 `#FF64B5F6`，改为 `@color/accent` 或 `@color/exif_progress_tint`。

### 5.14 不需要修改的 drawable

以下文件**不需要修改**，因为它们使用的颜色在亮/暗模式下均适用：

| 文件 | 原因 |
|------|------|
| `scrim_top.xml` | 黑色渐变遮罩，暗色下同样适用 |
| `scrim_bottom.xml` | 同上 |
| `gradient_bottom_to_top.xml` | 同上 |
| `bg_album_gradient.xml` | 同上 |
| `bg_card_primary.xml` | `#2C2C2E` 本来就是暗色卡片，保持不变 |
| `bg_dot_white.xml` | `#80FFFFFF` 用于深色背景上的白色圆点，暗色下同样需要白色 |
| `bg_tag_white.xml` | 白色标签在暗色下也应该保持白色 |
| `bg_tag_blue.xml` | 明确语义色 |
| `bg_tag_accent.xml` | 明确语义色 |
| `bg_tag_orange.xml` | 明确语义色（松下 LUT2） |
| `bg_tag_purple.xml` | 明确语义色（松下 LUT1） |
| `bg_tag_ios.xml` | `#E5E5EA` — 如果用于深色元素上的浅灰背景，保持；如果有问题再调 |

---

## 6. P4：Kotlin 代码适配

### 6.1 PhotosFragment.kt — 快速滚动弹窗文字

**当前（约第 262 行）：**
```kotlin
popupView.setTextColor(0xFFFFFFFF.toInt())
```

**改为：**
```kotlin
popupView.setTextColor(
    ContextCompat.getColor(requireContext(), R.color.fastscroll_popup_text)
)
```

### 6.2 PhotoDetailFragment.kt — 全屏切换

**当前（约第 293-317 行）：**
```kotlin
private fun toggleFullscreen() {
    isFullscreen = !isFullscreen
    val controller = WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView)
    
    if (isFullscreen) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        binding.rootContainer.setBackgroundColor(Color.BLACK)
        fadeView(binding.topBar, false)
        fadeView(binding.bottomBar, false)
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
        binding.rootContainer.setBackgroundColor(Color.WHITE)
        fadeView(binding.topBar, true)
        fadeView(binding.bottomBar, true)
    }
    updateSystemBarIcons(!isFullscreen)
}

private fun updateSystemBarIcons(lightBars: Boolean) {
    val window = requireActivity().window
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}
```

**修改为：**
```kotlin
private fun toggleFullscreen() {
    isFullscreen = !isFullscreen
    val controller = WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView)
    
    if (isFullscreen) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        binding.rootContainer.setBackgroundColor(Color.BLACK)
        fadeView(binding.topBar, false)
        fadeView(binding.bottomBar, false)
        updateSystemBarIcons(lightBars = false)  // 全屏：浅色图标
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
        // 退出全屏：恢复为主题表面色而非硬编码白色
        val typedValue = TypedValue()
        requireActivity().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        binding.rootContainer.setBackgroundColor(typedValue.data)
        fadeView(binding.topBar, true)
        fadeView(binding.bottomBar, true)
        // 系统栏图标根据当前主题决定
        val isLightTheme = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        updateSystemBarIcons(lightBars = isLightTheme)
    }
}

private fun updateSystemBarIcons(lightBars: Boolean) {
    val window = requireActivity().window
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}
```

> **关键**：`requireActivity().theme.resolveAttribute()` 从当前主题中解析 `colorSurface` 的实际值，无需手动维护亮/暗色映射。全屏模式下始终用纯黑背景 + 浅色图标（这是设计意图）。
>
> **额外导入**：需要在文件顶部添加 `import android.util.TypedValue`

### 6.3 FilterBottomSheet.kt

检查第 185-221 行的 Chip 颜色设置，将硬编码的 `Color.WHITE` 改为：

```kotlin
// 原：Chip 背景
chip.chipBackgroundColor = ColorStateList.valueOf(Color.WHITE)
// 改为：
val surfaceColor = TypedValue().also {
    requireContext().theme.resolveAttribute(
        com.google.android.material.R.attr.colorSurface, it, true
    )
}.data
chip.chipBackgroundColor = ColorStateList.valueOf(surfaceColor)
```

类似地，所有文字颜色如果使用了 `Color.BLACK` 或 `ContextCompat.getColor(android.R.color.black)`，需要改为 `@color/text_primary`。

### 6.4 PhotoInfoBottomSheet.kt

检查第 303-312 行 Badge 颜色。这些是品牌/语义色（iOS 蓝、橙色、绿色、紫色等），在暗色下可以保持或微调。

**建议保持不变**，因为这些是摄影格式标签的语义色，在暗色背景下同样清晰。

但如果 Badge 的文字颜色硬编码为 `Color.WHITE` 或 `Color.BLACK`，需要改为 `@color/text_primary`。

### 6.5 ExoPlayer 控制器

`exo_player_controller_view.xml` 中的 `#B3FFFFFF` 和 `#80FFFFFF` 是视频播放器控件颜色（半透明白色叠加），在暗色下同样适用，不需要修改。

---

## 7. P5：图标 Vector 适配

### 7.1 需要改的图标 Vector（硬编码 → @color 引用）

> 这些文件中的 `android:fillColor="#FF666666"` 等硬编码需要使用 IDE 精确搜索和替换。

| 文件 | 硬编码颜色 | 改为 |
|------|-----------|------|
| `ic_photos_normal.xml` | `#FF666666` | `@color/icon_normal` |
| `ic_albums_normal.xml` | `#FF666666` | `@color/icon_normal` |
| `ic_more.xml` | 检查 | 如有 → `@color/icon_normal` |
| `ic_search.xml` | 检查 | 如有 → `@color/icon_normal` |
| `ic_filter.xml` | 检查 | 如有 → `@color/icon_normal` |
| `ic_back.xml` | 检查 | 如有 → `@color/icon_normal` |
| `ic_close.xml` | 检查 | 如有 → `@color/icon_normal` |
| `ic_send.xml` | 检查 | 如有 → `@color/icon_normal` |
| `ic_photo_placeholder.xml` | 检查 | 如有 → `@color/icon_normal` 或 `@color/text_tertiary` |
| `ic_photo_error.xml` | 检查 | 同上 |

> 如果图标 Vector 中已经使用了 `@color` 引用或系统颜色（如 `?attr/colorControlNormal`），则无需修改。

### 7.2 不需要改的图标

| 文件 | 原因 |
|------|------|
| `ic_photos_selected.xml` | 已通过 `bottom_nav_color.xml` selector 控制颜色 |
| `ic_albums_selected.xml` | 同上 |
| `ic_selected.xml` | 白色描边，用于选中圈，暗色下同样需要 |
| `ic_selected_filled.xml` | 如使用 `@color/accent` 则 OK；如硬编码 `#FF007AFF` 则改为 `@color/accent` |
| `ic_favorite.xml` / `ic_favorite_filled.xml` | 收藏红心，保持红色语义不变 |
| `ic_delete.xml` | 删除图标，语义色保持不变 |

---

## 8. P6：构建验证

### 8.1 编译检查

```powershell
.\gradlew.bat assembleDebug
```

预期零编译错误。

### 8.2 手动验证清单

| 场景 | 验证点 |
|------|--------|
| 默认（跟随系统）+ 系统浅色 | 所有页面白色背景、黑色文字、透明状态栏（亮色图标） |
| 手动选择浅色 | 同上 |
| 手动选择深色 | 所有页面暗色背景（`#1C1C1E`）、浅色文字（`#F5F5F5`）、状态栏暗色背景+浅色图标 |
| 跟随系统 + 系统深色 | 同上 |
| 深色下打开照片详情 | 工具栏暗色毛玻璃（`#F21C1C1E`）、正常模式背景暗色 |
| 深色下全屏切换 | 全屏→纯黑背景；退出全屏→恢复暗色表面 |
| 深色下搜索 | 搜索框 `background_2C2C2E`、输入文字浅色 |
| 深色下底部弹窗 | 弹窗暗色背景、文字浅色 |
| 深色下相框/回收站/隐藏/关于 | 所有子页面背景暗色 |
| 启动画面 | 深色主题下启动画面暗色背景 |
| 主题切换后选择状态 | 如有选中照片不崩溃（可降级为丢失选择状态） |

### 8.3 常见问题排查

| 症状 | 可能原因 |
|------|---------|
| 某页面文字看不到（白色文字在白底上） | 该页面的 `textColor` 硬编码了 `#000000`，未改为 `@color/text_primary` |
| 底部导航栏消失/颜色不对 | `bg_bottom_nav.xml` 未改为 `@color/bottom_nav_bg` |
| 快速滚动弹窗文字不可见 | `PhotosFragment.kt` 的 `setTextColor` 未改为资源引用 |
| 暗色下图标不可见 | 图标 Vector 硬编码了深色 `fillColor`，未改为 `@color/icon_normal` |
| 详情页退出全屏闪白 | `PhotoDetailFragment.kt` 仍硬编码 `Color.WHITE` |
| 启动画面白色 | 缺少 `values-night-v31/themes.xml` |
| 底部弹窗白色 | `CustomBottomSheetDialogTheme` 未改为 `.BottomSheetDialog` |

---

## 9. 风险与注意事项

### 9.1 `activity?.recreate()` 的行为

- Activity 重建时，ViewModel 作用域为 Activity 的实例会保留状态
- Fragment 会被销毁并重建，其 `lateinit var` 属性会在 `onCreateView` 中重新初始化
- Navigation 状态由 `NavController` 通过 `savedInstanceState` 恢复，理论上不应丢失当前目标
- **选择模式是已知的降级风险**：用户在选图时切换主题，选择状态会丢失，但这是边缘场景

### 9.2 默认值兼容性

- `StarGalleryApp.DEFAULT_THEME_MODE = MODE_NIGHT_FOLLOW_SYSTEM`
- 升级用户（从旧版无主题设置升上来）的 SharedPreferences 中不存在 `theme_mode` key，`getInt` 返回默认值 `MODE_NIGHT_FOLLOW_SYSTEM`
- 这意味着升级后，所有用户默认"跟随系统"，保持与系统设置一致
- 用户必须在菜单中手动选择才能锁定浅色或深色

### 9.3 系统栏变化

- 暗色主题下 `windowLightStatusBar = false`，系统状态栏图标变为白色/浅色
- `enableEdgeToEdge` 使用 `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)`，两个参数都透明，不受影响
- 但如果未来有人添加 scrim（半透明遮罩），必须确保使用 `SystemBarStyle.auto(lightScrim, darkScrim)` 传入**不同的**值

### 9.4 WebView / 第三方组件

- 项目当前不包含 WebView
- Metadata-extractor 库不受主题影响
- Glide、ZoomImage、ExoPlayer 不受主题影响
- FastScroller 的弹出窗口通过 `bg_fastscroll_popup.xml` 已适配

---

## 10. 附录：完整颜色映射表

| 亮色值 | 暗色值 | 资源名 | 用途 |
|--------|--------|--------|------|
| `#FF000000` | `#FFFFFFFF` | `primary` | 主色 |
| `#FF000000` | `#FFE1E1E1` | `primary_dark` | 深主色 |
| `#FF007AFF` | `#FF0A84FF` | `accent` | 强调色（iOS 蓝暗色下微调亮） |
| `#FFFAFAFA` | `#FF0D0D0D` | `background` | 窗口背景 |
| `#FFFFFFFF` | `#FF1C1C1E` | `background_white` | 表面/白底 |
| `#FFF5F5F5` | `#FF2C2C2E` | `background_card` | 卡片背景 |
| `#FF000000` | `#FFF5F5F5` | `text_primary` | 主文字 |
| `#FF666666` | `#FF8E8E93` | `text_secondary` | 副文字 |
| `#FF999999` | `#FF636366` | `text_tertiary` | 三级文字 |
| `#FFCCCCCC` | `#FF48484A` | `text_hint` | 提示文字 |
| `#FF222222` | `#FFE5E5E5` | `icon_normal` | 常规图标 |
| `#FF007AFF` | `#FF0A84FF` | `icon_selected` | 选中图标 |
| `#FFCCCCCC` | `#FF48484A` | `icon_disabled` | 禁用图标 |
| `#FFE5E5E5` | `#FF38383A` | `divider` | 分隔线 |
| `#33FFFFFF` | `#1A0A84FF` | `selected_overlay` | 选中遮罩 |
| `#FF007AFF` | `#FF0A84FF` | `selected_border` | 选中边框 |
| `#FF007AFF` | `#FF0A84FF` | `fastscroll_thumb` | 滚动条拇指 |
| `#FFDDDDDD` | `#FF38383A` | `fastscroll_track` | 滚动条轨道 |
| `#E6FFFFFF` | `#E61C1C1E` | `bottom_nav_bg` | 底部导航悬浮背景 |
| `#E6000000` | `#E6F5F5F5` | `fastscroll_popup_bg` | 快速滚动弹窗背景 |
| `#FFFFFFFF` | `#FF000000` | `fastscroll_popup_text` | 快速滚动弹窗文字 |
| `#F2FFFFFF` | `#F21C1C1E` | `photo_detail_bar_bg` | 详情页工具栏毛玻璃 |
| `#1A000000` | `#1AFFFFFF` | `tag_overlay` | 半透明标签底 |
| `#0D000000` | `#0DFFFFFF` | `tag_overlay_light` | 极浅透明标签底 |
| `#40000000` | `#40FFFFFF` | `selection_overlay` | 选择遮罩 |
| `#99000000` | `#99FFFFFF` | `expiration_tag_bg` | 过期标签背景 |
| `#80000000` | `#80FFFFFF` | `raw_tag_bg` | RAW 标签背景 |
| `#60000000` | `#60FFFFFF` | `video_controls_bg` | 视频控件背景 |
| `#80000000` | `#80FFFFFF` | `dot_bg` | 圆点半透明 |
| `#80000000` | `#80FFFFFF` | `play_button_bg` | 播放按钮背景 |
| `#F2F2F7` | `#2C2C2E` | `card_secondary_bg` | 浅色卡片背景 |
| `#E5E5EA` | `#FF48484A` | `card_light_stroke` | 浅色卡片边框 |
| `#E0E0E0` | `#FF48484A` | `delete_drag_handle` | 删除弹窗横条 |
| `#FF64B5F6` | `#FF64B5F6` | `exif_progress_tint` | EXIF 进度条（亮暗相同） |

> **表格中所有"新增"颜色**都已同时定义在 `values/colors.xml`（亮色值）和 `values-night/colors.xml`（暗色值）中。
