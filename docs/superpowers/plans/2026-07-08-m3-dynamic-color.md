# M3 Dynamic Color 接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** StarGallery Android 12+ 设备的颜色源从硬编码切换为 Material You 动态颜色（壁纸派生），为后续阶段 B（Token 化）和阶段 C（视觉重设计）铺路。

**Architecture:** 提升 `minSdk` 至 31（消除 Android 11 静态 fallback 分支），用 Material Components 的 `DynamicColors.applyToActivitiesIfAvailable(Application)` 标准 API 在 `Application.onCreate` 阶段接入动态颜色。本阶段不修改任何 layout、不重写任何 drawable、不动 `Theme.StarGallery` 现有 token —— 阶段 B 才做。

**Tech Stack:** Kotlin 2.3.20, AGP 9.2.1, Material Components 1.11.0, Gradle (KTS), Hilt 2.59.2, minSdk 30→31, compileSdk 36, targetSdk 35, Java 21.

## Global Constraints

- minSdk = **31**（commit 后所有任务以此为基线）
- targetSdk = 35, compileSdk = 36
- Java 21 / Kotlin 2.3.20
- 不新增任何依赖（`com.google.android.material:material:1.11.0` 已含 `DynamicColors`）
- 不修改任何 `layout/*.xml`、不重写任何 `drawable/*.xml`
- 不动 `values-v31/themes.xml`、`values-night-v31/themes.xml`
- 不动 `values-night/colors.xml` 内容
- 不动 `Theme.StarGallery` 中现有 10 条硬编码 token
- 每个 Task 完成后必须 commit；commit 信息使用 `chore(m3): ...` 或 `feat(m3): ...` 前缀
- 暗色/亮色模式：跟随 `AppCompatDelegate.setDefaultNightMode`，由 `StarGalleryApp.applyThemeFromPreferences()` 决定
- 调用顺序硬约束：`localeManager.applyLocale()` → `applyThemeFromPreferences()` → `DynamicColors.applyToActivitiesIfAvailable(this)`，缺一不可，顺序错会导致 scheme 应用错乱

## File Structure

本次改动涉及 4 个文件，**均为修改**：

| 文件 | 改动类型 | 职责 |
|---|---|---|
| `app/build.gradle.kts:19` | 数值改 1 行 | 提升 minSdk |
| `app/src/main/res/values/themes.xml:5` | parent 改 1 行 | 启动页跟随 light dynamic |
| `app/src/main/res/values-night/themes.xml:5` | parent 改 1 行 | 启动页跟随 dark dynamic |
| `app/src/main/java/com/gxstar/stargallery/StarGalleryApp.kt` | 加 1 个 import + 1 行调用 | Application 级别应用 dynamic color |

新建文件：
- `docs/superpowers/artifacts/phase-a-2026-07-08/`（设备回归截图归档目录）

---

## Task 1：提升 minSdk 到 31

**Files:**
- Modify: `app/build.gradle.kts:19`

**Interfaces:**
- Consumes: 无
- Produces: `minSdk = 31`，作为后续 Task 的构建基线

- [ ] **Step 1: 读 `app/build.gradle.kts` 确认当前值**

```bash
cat app/build.gradle.kts | head -25
```

预期输出含 `minSdk = 30`（第 19 行附近）。

- [ ] **Step 2: 改 `minSdk` 数值**

用 `edit` 工具将 `app/build.gradle.kts:19` 的 `minSdk = 30` 改为 `minSdk = 31`。

改前：
```kotlin
        minSdk = 30
```
改后：
```kotlin
        minSdk = 31
```

- [ ] **Step 3: 验证编译通过**

```bash
.\gradlew.bat assembleDebug
```

预期：`BUILD SUCCESSFUL`，耗时与基线一致（首次可能 1-3 分钟）。如有 Android 11 相关 lint 警告可忽略；如有 deprecation 警告，确认非本 Task 引入。

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(m3): 提升 minSdk 到 31 (阶段 A 第 1 步)"
```

---

## Task 2：Theme.App.Starting 接入 DynamicColors Light/Dark

**Files:**
- Modify: `app/src/main/res/values/themes.xml:5`
- Modify: `app/src/main/res/values-night/themes.xml:5`

**Interfaces:**
- Consumes: Task 1 产出的 `minSdk = 31` 构建基线
- Produces: 启动页主题父类变为 `Theme.Material3.DynamicColors.Light` / `Dark`，避免闪屏与主页颜色断层

- [ ] **Step 1: 读 `values/themes.xml:5` 确认当前 parent**

```bash
sed -n '1,10p' app/src/main/res/values/themes.xml
```

预期第 5 行：
```xml
    <style name="Theme.App.Starting" parent="Theme.StarGallery" />
```

- [ ] **Step 2: 改 `values/themes.xml:5`**

用 `edit` 工具将 `parent="Theme.StarGallery"` 改为 `parent="Theme.Material3.DynamicColors.Light"`。

改后：
```xml
    <style name="Theme.App.Starting" parent="Theme.Material3.DynamicColors.Light" />
```

- [ ] **Step 3: 读 `values-night/themes.xml:5` 确认当前 parent**

```bash
sed -n '1,10p' app/src/main/res/values-night/themes.xml
```

预期找到 `Theme.App.Starting` 一行。

- [ ] **Step 4: 改 `values-night/themes.xml` 中 `Theme.App.Starting` parent**

注意：`values-night/themes.xml` 中**没有** `Theme.App.Starting` 的定义（它只在 `values/themes.xml:5` 定义），night 模式下的启动页走 `values-night-v31/themes.xml:3` 的 `Theme.SplashScreen` 子类。所以本 Task 在 `values-night/themes.xml` **没有改动**。

确认方法：用 `grep` 工具搜索 `values-night/themes.xml`，无 `Theme.App.Starting` 即为预期。

```bash
grep "Theme.App.Starting" app/src/main/res/values-night/themes.xml
```

预期：无输出（exit code 1）。

- [ ] **Step 5: 验证编译通过**

```bash
.\gradlew.bat assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/themes.xml
git commit -m "feat(m3): 启动页接入 Theme.Material3.DynamicColors.Light (阶段 A 第 2 步)"
```

---

## Task 3：StarGalleryApp 接入 DynamicColors

**Files:**
- Modify: `app/src/main/java/com/gxstar/stargallery/StarGalleryApp.kt`

**Interfaces:**
- Consumes: Task 1 的 `minSdk = 31`；Task 2 的启动页 DynamicColors parent
- Produces: Application.onCreate 中按硬约束顺序调用 `DynamicColors.applyToActivitiesIfAvailable(this)`

- [ ] **Step 1: 读 StarGalleryApp.kt 确认现状**

```bash
cat app/src/main/java/com/gxstar/stargallery/StarGalleryApp.kt
```

预期当前 `onCreate`（第 22-26 行）：
```kotlin
    override fun onCreate() {
        super.onCreate()
        localeManager.applyLocale()
        applyThemeFromPreferences()
    }
```

- [ ] **Step 2: 加 import**

在 `import` 区（已有 import 之间）加入 `DynamicColors` 的 import。

用 `edit` 工具在 `import com.gxstar.stargallery.util.LocaleManager` 这一行**之后**插入：

```kotlin
import com.google.android.material.color.DynamicColors
```

- [ ] **Step 3: 在 `onCreate` 末尾追加 DynamicColors 调用**

用 `edit` 工具将 `onCreate` 改为：

```kotlin
    override fun onCreate() {
        super.onCreate()
        localeManager.applyLocale()
        applyThemeFromPreferences()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
```

**调用顺序硬约束**（已固化在 spec 第 4.3 节）：`localeManager.applyLocale()` → `applyThemeFromPreferences()` → `DynamicColors.applyToActivitiesIfAvailable(this)`，不可调换。

- [ ] **Step 4: 验证编译通过**

```bash
.\gradlew.bat assembleDebug
```

预期：`BUILD SUCCESSFUL`，无 `unresolved reference: DynamicColors` 错误。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gxstar/stargallery/StarGalleryApp.kt
git commit -m "feat(m3): StarGalleryApp 接入 DynamicColors (阶段 A 第 3 步)"
```

---

## Task 4：设备回归 + 截图归档

**Files:**
- Create: `docs/superpowers/artifacts/phase-a-2026-07-08/`（截图归档目录）
- Create: `docs/superpowers/artifacts/phase-a-2026-07-08/regression-checklist.md`（验收 checklist）
- Create: `docs/superpowers/artifacts/phase-a-2026-07-08/README.md`（归档说明）

**Interfaces:**
- Consumes: Task 1-3 全部产出
- Produces: 设备回归证据 + spec 第 5 节所有验收点的签字

> **重要前提**：本 Task 需要真实 Android 12+ / Android 14+ 设备（adb-connected 真机或模拟器）。如果没有，停止 Task 4 并通知用户"需要真机才能完成验收"。

- [ ] **Step 1: 准备设备**

确认至少 1 台 Android 12+ 真机或模拟器已 adb 连接：

```bash
adb devices
```

预期：列表中至少有 1 台 `device`（非 `unauthorized` / `offline`），API ≥ 31。

- [ ] **Step 2: 安装 Debug APK**

```bash
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

预期：`Success`。

- [ ] **Step 3: 启动 App + 第一次截图（亮色）**

```bash
adb shell am start -n com.gxstar.stargallery/.MainActivity
```

手动操作：等首屏加载完成（首页 Photos 列表），用手机截图工具（`adb exec-out screencap -p > light-home.png` 或手机自带截图）保存到 `docs/superpowers/artifacts/phase-a-2026-07-08/light-home.png`。

- [ ] **Step 4: 16 fragment 亮色截图**

按以下顺序，逐个 fragment 截图（每进入一个 fragment，等图片加载完成，按设备截图）：

| # | Fragment | 路径 |
|---|---|---|
| 1 | Photos（首页） | `light-01-photos.png` |
| 2 | Albums（首页） | `light-02-albums.png` |
| 3 | AlbumDetail（任一相册） | `light-03-album-detail.png` |
| 4 | PhotoDetail（任一照片） | `light-04-photo-detail.png` |
| 5 | Trash | `light-05-trash.png` |
| 6 | Hidden（先认证） | `light-06-hidden.png` |
| 7 | About | `light-07-about.png` |
| 8 | Privacy Policy | `light-08-privacy.png` |
| 9 | Permissions | `light-09-permissions.png` |
| 10 | Third Party Libraries | `light-10-third-party.png` |
| 11 | Contact | `light-11-contact.png` |
| 12 | License | `light-12-license.png` |
| 13 | Settings | `light-13-settings.png` |
| 14 | Excluded Albums | `light-14-excluded-albums.png` |
| 15 | BottomSheet Filter | `light-15-filter.png` |
| 16 | BottomSheet DeleteOptions | `light-16-delete-options.png` |

BottomSheet 通过点击 Photos 页右上 filter 图标 / 多选删除入口触发。

- [ ] **Step 5: 切换暗色模式 + 重复 Step 3-4**

```bash
adb shell "cmd uimode night yes"
```

等 1-2 秒，重复 Step 3（首页）+ Step 4（16 fragment），文件名 `dark-*.png`。

切回亮色：
```bash
adb shell "cmd uimode night no"
```

- [ ] **Step 6: 验证 Dynamic Color 联动**

操作：
1. 在系统设置 → 壁纸 → 换一张**主色调与默认明显不同**的壁纸（如蓝色 / 绿色 / 红色）
2. 等 5 秒，回到 App
3. 截图：`dynamic-after-wallpaper-change.png`

预期：首页 AppBar、BottomNav、Tag 颜色随壁纸主色变化。

- [ ] **Step 7: 验证启动闪屏无颜色跳变**

操作：
1. `adb shell am force-stop com.gxstar.stargallery`
2. 立即启动：`adb shell am start -n com.gxstar.stargallery/.MainActivity`
3. 录屏 5 秒（`adb shell screenrecord /sdcard/boot.mp4` & `adb pull`）
4. 播放录屏，肉眼检查闪屏 → 主页之间无明显颜色跳变

- [ ] **Step 8: 写验收 checklist**

创建 `docs/superpowers/artifacts/phase-a-2026-07-08/regression-checklist.md`，逐项复制 spec 第 5 节验收点并打勾（无设备无法打的标 `N/A` + 备注）：

```markdown
# 阶段 A 设备回归验收

**日期**：2026-07-08
**设备**：<填写设备型号 + API>
**测试人**：<填写>

## 5.1 编译/构建
- [ ] `./gradlew.bat assembleDebug` 通过
- [ ] 无新增依赖

## 5.2 行为
- [ ] 换壁纸后 16 fragment 颜色联动
- [ ] 切暗色模式立即跟随
- [ ] 启动闪屏无颜色跳变

## 5.3 视觉回归
- [ ] 16 fragment 亮 + 暗 32 张截图无错位
- [ ] 暗色模式无黑底白字/白底黑字误用
- [ ] PhotoDetail 全屏仍为黑底
- [ ] 隐藏照片认证页可读
- [ ] 回收站 Tag 对比度 >= 4.5:1
- [ ] 启动闪屏图标亮暗都清晰

## 5.4 性能
- [ ] 冷启动到首页 < 500ms（基线对照）
- [ ] 切暗色模式无掉帧
```

- [ ] **Step 9: 写归档说明**

创建 `docs/superpowers/artifacts/phase-a-2026-07-08/README.md`：

```markdown
# 阶段 A 设备回归归档

**对应 spec**：`../../specs/2026-07-08-m3-dynamic-color-design.md`
**对应 plan**：`../../plans/2026-07-08-m3-dynamic-color.md`
**日期**：2026-07-08

## 截图清单

### 亮色（16 fragment）
- light-01-photos.png
- light-02-albums.png
- ...

### 暗色（16 fragment）
- dark-01-photos.png
- ...

### 联动验证
- dynamic-after-wallpaper-change.png

### 闪屏录屏
- boot.mp4

## 验收 checklist
- regression-checklist.md

## 结论
- 阶段 A 是否通过：<是/否 + 备注>
```

- [ ] **Step 10: 提交截图 + checklist（不进 git 大文件，按需）**

如果截图 < 1MB 且用户希望归档入仓：

```bash
git add docs/superpowers/artifacts/phase-a-2026-07-08/
git commit -m "docs(m3): 阶段 A 设备回归截图 + 验收 checklist"
```

如果截图过大（> 1MB），告知用户"截图超过建议大小，未入仓；保留在本地 `docs/superpowers/artifacts/phase-a-2026-07-08/`，spec 验收以本机记录为准"，仅 commit `regression-checklist.md` 和 `README.md`：

```bash
git add docs/superpowers/artifacts/phase-a-2026-07-08/regression-checklist.md docs/superpowers/artifacts/phase-a-2026-07-08/README.md
git commit -m "docs(m3): 阶段 A 验收 checklist + 归档说明"
```

---

## Self-Review

### 1. Spec coverage

| Spec 章节 | 对应 Task |
|---|---|
| 4.1 minSdk 提升 | Task 1 |
| 4.2 主题 parent 调整 | Task 2 |
| 4.3 Dynamic Color 接入 | Task 3 |
| 4.4 保留主题属性 | 全程（不改动） |
| 5.1 编译/构建 | Task 1/2/3 各 Step 3 / Step 5 验证；Task 4 Step 2 部署 |
| 5.2 行为 | Task 4 Step 6-7 |
| 5.3 视觉回归 | Task 4 Step 3-5 |
| 5.4 性能 | Task 4 Step 8 checklist 包含 |

✅ 所有 spec 需求都有 Task 覆盖。

### 2. Placeholder scan

- 无 "TBD" / "TODO" / "implement later"
- 无 "Add appropriate error handling" 类模糊指令
- 每个 Step 有具体命令或代码
- 无 "Similar to Task N" 引用（每个 Task 的代码完整呈现）

✅ 干净。

### 3. Type consistency

- `DynamicColors.applyToActivitiesIfAvailable(this)` 一致使用（Task 3 Step 3 + spec 4.3）
- `minSdk = 31` 一致（Task 1 + 全局约束）
- 调用顺序在 Task 3 Step 3 + 全局约束 + spec 4.3 三处描述一致

✅ 一致。

---

## 风险与回滚

- **回滚单个 Task**：`git revert <commit>` 即可，每个 Task 独立 commit
- **回滚整个阶段**：`git revert <Task 4 之前的 3 个 commit>`
- **最坏情况**：若 DynamicColors 接入导致启动崩溃，回滚 Task 3 即可恢复（Task 1+2 仍可保留，minSdk 提升和启动页 parent 是无害的）

---

## 实施完成后

阶段 A 完成通知用户，等待用户确认后进入阶段 B brainstorm 流程。**本 plan 不预先规定 B/C 阶段细节。**
