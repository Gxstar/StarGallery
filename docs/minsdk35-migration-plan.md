# StarGallery minSdk 提升改造方案

> 目标：消除因 minSdk 落后带来的大量 `Build.VERSION.SDK_INT` 判断分支与 callback，获得更干净的原生新格式（AVIF / HEIF / JXL / 高位深 HDR）支持链路。
> 适用版本基线：minSdk 30 / targetSdk 35 / compileSdk 37，Kotlin 2.4.10，AGP 9.3.0

---

## 一、结论摘要（TL;DR）

1. **方向正确，但两个前提需要修正**：
   - **JXL 永远不会被 Android 原生支持**。官方媒体格式表（Android 开发者文档）只有 BMP/GIF/JPEG/PNG/WebP/HEIF/AVIF，**JXL 从未出现、也没有计划加入**。JXL 必须永远依赖 jxl-coder 第三方库，且它是纯软件解码（libjxl），没有硬件加速。**"为了 JXL 原生支持而提 minSdk" 不成立**——JXL 的痛点（MediaStore 不识别、无系统缩略图）与 minSdk 无关。
   - **高位深 AVIF / HEIF 在 API 34（Android 14）就已原生齐备**：AVIF 解码自 API 34 起为平台强制要求（所有设备必带），10-bit / HDR 显示（`Bitmap.hasGainmap()`、`COLOR_MODE_HDR`）也是 API 34。**API 35（Android 15）的增量只有 ISO 21496-1 gain map（Ultra HDR 1.1，与 iOS 18 Adaptive HDR 跨平台互通）和 HDR headroom 控制（`setDesiredHdrHeadroom`）**。

2. **minSdk 决策（已定稿）**：**minSdk = 35（Android 15）**。
   - **JXL 单独处理**：保留 jxl-coder（jxl-coder + jxl-coder-glide 均不动），其余格式（JPEG / HEIF / AVIF / WebP / PNG / GIF）全部走系统原生解码（`ImageDecoder` / `BitmapFactory` / `BitmapRegionDecoder`）。
   - **HDR 正确展示**：通过 `Bitmap.hasGainmap()` + `bitmap.colorSpace.isWideGamut` 在解码后按位图实际属性决定窗口色彩模式（HDR / WIDE / DEFAULT），并修复"AVIF / HEIF 高位深被 HDR 探测门槛挡在门外"的存量缺陷（详见第七、八章）。
   - 覆盖 41.0% 设备，作为新格式优先的相册工具可接受；代价是丢失约 46% 用户，上架文案与商店支持政策需同步说明。

3. **紧急事项（优先级最高）**：Play 商店要求 **2026-08-31 起新应用与更新 targetSdk 必须 ≥ 36**（Android 16）。距今仅 20 天，`targetSdk` 需从 35 升到 36（compileSdk 37 已支持），否则无法发布更新。

4. **改造收益预估**：删除 15 处 `SDK_INT` 分支（7 个文件）→ 预计清理为 0 处；移除 `avif-coder` 依赖（AVIF/HEIF 走原生）；`AvifRegionDecoder` 重构为 `ImageDecoder` 实现（去掉一个 native 库 + 一个 Glide 集成）。

---

## 二、事实澄清：Android 原生图片格式支持矩阵

| 格式 | 原生解码 | 高位深 / HDR | 说明 |
|---|---|---|---|
| JPEG | API 1 | Ultra HDR（gain map）API 34；ISO 21496-1 API 35 | API 35 起同时编码 v1 + 21496-1 元数据 |
| HEIF / HEIC | API 26 | 10-bit 显示 API 34+；gain map（21496-1）API 35 | `BitmapRegionDecoder` 原生支持区域解码 |
| **AVIF** | API 31（ImageDecoder） | **API 34 起编解码为平台强制要求**；10-bit / HDR API 34+ | **不**支持 `BitmapRegionDecoder`，大图子采样需自定义（保留 AvifRegionDecoder） |
| WebP / PNG / GIF / BMP | 原生 | — | — |
| **JXL** | **永不原生** | — | 仅 jxl-coder（awxkee，libjxl 软件解码）；MediaStore 不识别，无系统缩略图 |

### 关键推论
- **AVIF 不需要 avif-coder**：minSdk ≥ 31 后 `ImageDecoder` / `BitmapFactory` 原生解码 AVIF；minSdk ≥ 34 后强制存在。Glide 5 默认解码链（BitmapFactory）即可覆盖。
- **HEIF 不需要 avif-coder**：API 26+ 原生，且 `BitmapRegionDecoder`（ZoomImage 内置）直接支持区域解码，甚至不用 AvifRegionDecoder 参与。
- **JXL 必须保留 jxl-coder**：这是唯一的第三方依赖，与 minSdk 无关。
- **"硬件加速"的真实含义**：AVIF/HEIF 解码在支持硬解的设备上，平台解码路径（`ImageDecoder`）会自动走硬件/高效软件解码器；JXL 没有硬件加速。提 minSdk 只能让你**用上**平台路径，并不能凭空造出 JXL 硬件解码。

---

## 三、minSdk 档位与覆盖率成本

| 候选 minSdk | Android 版本 | 设备覆盖率（2026-08） | 相比 minSdk 30 丢失用户 | 原生收益 |
|---|---|---|---|---|
| 30（现状） | Android 11 | 86.9% | — | — |
| 33 | Android 13 | 68.9% | ~18% | 权限模型统一（READ_MEDIA_*） |
| **34** | Android 14 | **54.5%** | ~32% | **AVIF 强制原生 + 10-bit HDR + gainmap + COLOR_MODE_HDR，分支全删** |
| 35 | Android 15 | 41.0% | ~46% | + ISO 21496-1 gain map + HDR headroom |
| 36 | Android 16 | 22.3% | — | **只适合做 targetSdk，不建议做 minSdk** |

> 注：Android 16 覆盖率已达 22.3%，且 Play 强制 36+，未来 1~2 年 34/35 覆盖面会快速上升。

---

## 四、分阶段执行计划

### 阶段 0：紧急 — targetSdk 升 36（本周内）

- `app/build.gradle.kts`：`targetSdk = 36`
- 验证点：targetSdk 35 → 36 无破坏性行为变更；edge-to-edge 已在 35 强制，36 无新增项。
- 单独 commit，不与其他改动混在一起。

### 阶段 1：minSdk 提升 + 编译修复

```kotlin
// app/build.gradle.kts
minSdk = 35   // 或 34（推荐），二选一，见第一节
```

- 预期编译错误：0 ~ 2 个（`NewApi` lint 报错会一次性列出所有需要处理的新 API 调用点，正好作为清理清单核对）。
- 跑 `./gradlew assembleDebug` 与 `lintDebug` 建立基线。

### 阶段 2：分支清理（15 处 → 0 处）

| # | 文件 | 行 | 现状 | 清理动作 |
|---|---|---|---|---|
| 1 | `MainActivity.kt` | 45 | `SDK_INT >= P` 才设 cutout | 删除判断，直接 `window.attributes.layoutInDisplayCutoutMode = ...` |
| 2 | `MediaScanner.kt` | 599 | `detectUltraHdr` 守卫 `< UPSIDE_DOWN_CAKE return false` | 删除守卫（minSdk ≥ 34 恒不成立） |
| 3 | `MediaScanner.kt` | 618 | `fixLegacyHdrLabels` 同款守卫 | 删除守卫 |
| 4 | `AvifRegionDecoder.kt` | 145 | `checkSupport` 要求 `>= S` | 删除版本判断，`mimeType == "image/avif"` 即接受 |
| 5 | `PhotoPageViewHolder.kt` | 265 | `shouldProbeHdr` 前置 `>= UPSIDE_DOWN_CAKE` | 删除，直接 `photo.isUltraHdr && hdrDisplayEnabled()` |
| 6 | `PhotoPageViewHolder.kt` | 392 | `hasGainmap = SDK_INT>=34 && bitmap.hasGainmap()` | 直接 `bitmap.hasGainmap()` |
| 7 | `PhotoPageViewHolder.kt` | 520 | `colorModeForBitmap` 内 `>=34` 分支 | 直接 `bitmap.hasGainmap() -> HDR` |
| 8 | `PhotoPageViewHolder.kt` | 542 | `applyWindowColorMode` 内 `>=34` 包裹 | 删除 if，直接写 `window.colorMode` |
| 9 | `PhotoPageViewHolder.kt` | 559 | `resetWindowColorMode` 内 `>=34` 包裹 | 同上 |
| 10 | `PhotosFragment.kt` | 768 | 权限三档 `when`（34/33/30） | 合并为单档（见下） |
| 11 | `AlbumsFragment.kt` | 58 | 权限三档 `if/else` | 同上 |
| 12 | `AlbumDetailFragment.kt` | 219 | 权限三档 `if/else` | 同上 |
| 13 | `AndroidManifest.xml` | 11 | `READ_EXTERNAL_STORAGE` + `maxSdkVersion=32` | 整行删除；`tools:targetApi="33"` 改 35/36 |

**权限合并建议**：三个 Fragment 的权限逻辑提取为工具类（如 `PermissionUtils.requestMediaPermissions()`），统一为：

```kotlin
arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    // 可选：ACCESS_MEDIA_LOCATION（EXIF 坐标，PhotosFragment 需要）
    Manifest.permission.ACCESS_MEDIA_LOCATION
)
```

> ⚠️ **关于 `READ_MEDIA_VISUAL_USER_SELECTED` 的语义**（Android 14+）：一旦声明且用户选择"仅部分照片"，`READ_MEDIA_IMAGES` 会被系统降级为只读所选子集，相册 App 会出现"照片变少"的困惑体验。当前代码三处都在请求它。**建议从请求列表中移除**，让用户只能"全部允许 / 拒绝"，全量读取行为更可控。若保留，必须处理"部分授权"降级状态。

### 阶段 3：依赖与解码链重构

1. **移除 `avif-coder` + `avif-coder-glide`**（`gradle/libs.versions.toml` + `build.gradle.kts`）：
   - AVIF / HEIF 原生解码已覆盖（API 31 / 26，API 34 强制）。
   - 少一个 native 库 → APK 更小、16KB page size 对齐风险更低。
2. **`AvifRegionDecoder` 重构为 `ImageDecoder` 实现**（替换 `HeifCoder`）：
   - `decodeImageInfo()`：`ImageDecoder.createSource` + 只解码一次拿尺寸（或 `BitmapFactory.Options.inJustDecodeBounds`）。
   - `decodeRegion()`：`ImageDecoder.decodeBitmap` + `decoder.setTargetSize(finalWidth, finalHeight)` 全图缩放解码，再按 `region` 裁切（与现策略一致，去掉 `HeifCoder` 依赖）。
   - 注意：AVIF 仍**不支持** `BitmapRegionDecoder`，此自定义 RegionDecoder 必须保留（这是平台限制，不是 minSdk 问题）。
3. **`ThumbnailManager`**：非 JXL 分支注释中"AVIF/HEIC 由第三方解码器处理"的描述更新为原生路径；代码本身无需改（Glide 默认解码链即可）。
4. **保留 `jxl-coder-glide` + `jxl-coder`**：JXL 唯一解码路径，不动。
5. **评估可移除项（收益小，可选）**：
   - `coreLibraryDesugaring`：Android 14/15 内置 OpenJDK 17，desugar 基本无作用；若 zoomimage 1.6.0 不强要求可删除（注释声称"要求"，需实测编译）。
   - `androidx.core:core-splashscreen`：API 31+ 原生 SplashScreen，兼容层变 no-op；删除需把 `Theme.App.Starting` 从 compat 属性迁移为原生 `windowSplashScreen*` 属性。
6. **`StarGalleryGlideModule` 暂不动**：注释中"刻意不开 `setUriImageDecoderEnabled` 以免绕过 jxl-coder"是当前设计约束；若想进一步让 AVIF/HEIF 走 ImageDecoder，需验证不干扰 JXL 注册的 ResourceDecoder，属于可选优化。

### 阶段 4：JXL 路径整理（保持 jxl-coder）

- `PhotoPageViewHolder.loadJxlDirect` 保留（MediaStore 对 JXL 无缩略图、Glide content:// 路径失败，这是平台限制，与 minSdk 无关）。
- 窗口色彩模式判断删除后，16-bit JXL 的 `WIDE_COLOR_GAMUT` 切换逻辑更干净（`colorModeForBitmap` 直接判断 `bitmap.colorSpace.isWideGamut`）。
- `TrashPhotoPreviewDialog.kt` 的 `needSubsampling` 逻辑无需改动（无 SDK_INT 分支）。

### 阶段 4b：HDR 展示链路改造（本次定稿核心）

现状缺陷（源码走查确认）：

- `Photo.isUltraHdr` 的实现只是 `mimeType == "image/jpeg"`；`PhotoPageViewHolder.shouldProbeHdr` 只认这个条件 → **AVIF 10-bit / HEIF gain map（API 35 原生支持 ISO 21496-1）永远不会进入 HDR 探测路径**，高位深 HDR 效果丢失。
- `MediaScanner.detectUltraHdr()` 扫描时已做字节级探测并写入 `Photo.isHdr` 字段，但详情页**没有使用该字段**，而是用 MIME 判断 + 每张 JPEG 再解码 200×200 探测一次（性能浪费 + 结果可能不一致）。

改造动作：

1. `PhotoPageViewHolder.loadImage` 中 HDR 候选条件改为：
   ```kotlin
   val isHdrCandidate = photo.isUltraHdr || photo.isAvif || photo.isHeic
   val shouldProbeHdr = (photo.isHdr || isHdrCandidate) && hdrDisplayEnabled()
   ```
2. `checkHdrAndLoad` 的探测结果从"仅 `hasGainmap`"扩展为"`hasGainmap || colorSpace.isWideGamut`"，使 10-bit AVIF（RGBA_F16 + BT.2020/P3）与 HEIF gain map 都能正确分流到 HDR / WIDE 窗口模式。
3. 高位深内容（HDR / WIDE 候选）**只走 `ImageDecoder` 直接解码路径**（保留 gainmap 与 F16 位深），不经过 Glide（Glide Downsampler 会降级到 ARGB_8888 并可能丢 gainmap，见第八章 P0-2）。
4. 若 `photo.isHdr` 已为 true（扫描期命中），可跳过小图探测直接走 HDR 解码；否则用小图探测兜底。

### 阶段 5：回归测试矩阵

| 测试项 | 样本 | 预期 |
|---|---|---|
| 列表加载 | AVIF(8/10-bit)、HEIC、JXL(8/16-bit)、Ultra HDR JPEG、GIF、RAW | 缩略图正常，滚动流畅 |
| 详情页子采样 | >2000px JPEG / AVIF / HEIC | 平滑缩放，无 OOM |
| HDR 显示 | Ultra HDR JPEG、10-bit AVIF、16-bit JXL | `COLOR_MODE_HDR` / `WIDE_COLOR_GAMUT` 正确切换 |
| 缩略图生成 | 全格式混合库 | `ThumbnailManager` 缓存正常 |
| 媒体操作 | 删除 / 收藏 / 回收站 / 恢复（IntentSender） | 回调正常 |
| EXIF 面板 | 含 GPS / 相机型号照片 | 地图跳转、WGS84→GCJ02 正常 |
| 权限 | Android 14/15 首次启动 | "全部允许"流程正确 |
| 构建 | `assembleDebug` / `lintDebug` / `testDebugUnitTest` | 全绿，NewApi 为 0 |

---

## 五、风险与回退

| 风险 | 等级 | 对策 |
|---|---|---|
| minSdk 34/35 丢失用户（32%/46%） | 高 | 明确产品定位；若求覆盖面可退 33（但 33 删不干净 HDR 分支） |
| `READ_MEDIA_VISUAL_USER_SELECTED` 部分授权语义 | 中 | 移除该权限或处理降级状态 |
| avif-coder 移除后 ImageDecoder 大图解码性能 | 中 | `MAX_DECODE_DIM=4096` 已限制；用真机 48MP 样张验证 |
| jxl-coder native 库 16KB page 对齐 | 低 | 2.6.1 已支持；在 Android 15 模拟器（16KB 开发者选项）验证 |
| desugaring / splashscreen 移除导致编译失败 | 低 | 单独 commit，失败即回退该 commit |

**回退策略**：每个阶段独立 commit（建议顺序：`targetSdk36` → `minsdk35` → `branch-cleanup` → `remove-avif-coder` → `optional-cleanup`），任意阶段可单独 `git revert`。

---

## 六、Commit 拆分建议

1. `chore: bump targetSdk to 36 (Play requirement by 2026-08-31)`
2. `chore: raise minSdk to 34/35`
3. `refactor: remove all Build.VERSION.SDK_INT branches (15 sites)`
4. `refactor: drop avif-coder, rewrite AvifRegionDecoder with ImageDecoder`
5. `chore: unify media permission flow (drop READ_MEDIA_VISUAL_USER_SELECTED)`
6. `chore(optional): remove desugaring / core-splashscreen`

---

## 七、解码展示链路设计（定稿：原生解码 + JXL 单独 + HDR 保留）

```
loadImage(photo)
│
├─ photo.isJxl ──────────────────────────→ jxl-coder 直接解码字节流
│      （MediaStore 对 JXL 无缩略图/识别，必须独立路径）
│      decodeSampled(DEFAULT) → 16-bit→RGBA_F16 / 8-bit→ARGB_8888
│      → colorModeForBitmap → HDR/WIDE/DEFAULT（删除 SDK_INT 门槛）
│
├─ HDR 候选（isUltraHdr || isAvif || isHeic）且 (isHdr || 探测命中)
│      → ImageDecoder 直接解码（保留 gainmap / RGBA_F16）
│      → hasGainmap → COLOR_MODE_HDR
│      → colorSpace.isWideGamut → COLOR_MODE_WIDE_COLOR_GAMUT
│      → 否则 → DEFAULT（回退 SDR 子采样路径）
│
├─ 大图（maxDim ≥ 2000）且非 HDR
│      → Glide + AvifRegionDecoder（ImageDecoder 实现，全图缩放+裁切）
│      → BitmapRegionDecoder 不认 AVIF，此自定义解码器必须保留
│
└─ 小图（maxDim < 2000）且非 HDR
       → ImageDecoder / Glide 原生全量解码
```

统一出口：`colorModeForBitmap(bitmap)` 按位图**实际属性**（gainmap / 色域 / 位深）决定窗口模式，不依赖格式假设。

## 八、解码展示阶段坑点清单

### P0 — 不改则 HDR 目标落空

| # | 坑 | 说明 | 对策 |
|---|---|---|---|
| P0-1 | **HDR 探测只认 JPEG** | `shouldProbeHdr = photo.isUltraHdr(=image/jpeg)`，AVIF 10-bit / HEIF gain map 永远进不了 HDR 路径 | 探测条件扩为 `isUltraHdr \|\| isAvif \|\| isHeic`；探测结果按 `hasGainmap \|\| isWideGamut` 判断 |
| P0-2 | **Glide 路径丢高位深** | Downsampler 默认 `ARGB_8888`，10-bit AVIF/HEIF 被降 8-bit；采样缩放可能丢 gainmap | 高位深/HDR 内容只走 `ImageDecoder` 直接路径，不经 Glide |
| P0-3 | **`Photo.isHdr` 字段闲置** | 扫描期 `detectUltraHdr()` 已字节探测并落库，详情页却用 MIME 判断 + 每张 JPEG 重复解码探测（浪费且不一致） | 优先用 `photo.isHdr`；未标记的才用小图探测兜底 |

### P1 — 质量与性能

| # | 坑 | 说明 | 对策 |
|---|---|---|---|
| P1-1 | **JXL 16-bit 大图内存峰值** | RGBA_F16 = 8B/px，4096 长边 ≈ 89~134MB/张，OOM 风险 | F16 路径解码上限降至 2048~2560（屏显足够），或按设备内存分级 |
| P1-2 | **ViewPager2 相邻页 colorMode 竞争** | 预加载页 bind 后若恢复 `lastAppliedColorMode`，会把当前页 HDR 模式切走 → 翻页闪烁 | 真机连续快速翻页验证；必要时加"当前可见页优先"仲裁 |
| P1-3 | **colorMode 切换触发 surface 重建** | 每次 `window.colorMode` 变更都有代价 | 已用 hdrHandler 延迟；同页重复 bind（如收藏切换）避免重复切换 |
| P1-4 | **AvifRegionDecoder 按 sampleSize 严格缓存** | 连续缩放时 sampleSize 变化即重新全图解码 → 卡顿 | 加容差缓存（`\|Δsample\| ≤ 2` 复用）；或预生成两三级缩放 |
| P1-5 | **Android 15 HDR headroom** | HDR 图与 SDR UI 混排时 SDR 变暗/过曝 | 接入 `Window.setDesiredHdrHeadroom`（全屏查看时调大） |

### P2 — 边界与已知限制

| # | 坑 | 说明 | 对策 |
|---|---|---|---|
| P2-1 | SDR 设备上无谓的 HDR 探测 | 每张 JPEG 先解码 200×200 探测是浪费 | 先查 `Display.getHdrCapabilities()`，无 HDR 屏跳过 |
| P2-2 | HEIF 动图 / AVIF 序列 | `ImageDecoder` 只解首帧 | 明确产品策略（显示首帧；后续可接第三方动图解码） |
| P2-3 | EXIF 面板字段缺失 | metadata-extractor 对 AVIF/JXL 支持有限 | 缺失字段降级显示"—"，后续可读文件内 Exif 偏移 |
| P2-4 | **RAW 详情页（存量问题）** | `BitmapRegionDecoder` 不支持 RAW，`AvifRegionDecoder` 不认 `image/x-*` → 详情可能只是系统缩略图放大 | 实测确认；需要时引入 RAW 专用解码（DNG 可走 `ImageDecoder`） |
| P2-5 | 16KB page size | Android 15 部分设备 16KB 页 | jxl-coder 2.6.1 已对齐；移除 avif-coder 后少一个 native 风险 |
| P2-6 | 大 JXL 缩略图生成慢 | 列表反复触发生成 | 现有 `ThumbnailManager` 缓存已覆盖；监控失败重试风暴 |

---

## 九、方案自评审（v3：合理性 / 精简性 / 可靠性）

### 9.1 总体结论

- **合理性：通过**。决策树与现状代码结构同构（`loadImage` 的 when 分派本来就存在），不是新架构，只是"扩条件 + 删分支"。
- **精简性：通过**。最小必要改动约 100 行级（见 9.3），无过度设计。
- **可靠性：有条件通过**。方向可靠，但评审发现 **3 处必须修正**（9.2 的 R1~R3）+ 1 处语义泛化（R4）+ 8 项需实测验证（9.4）。

### 9.2 修订项（必须改，否则方案不成立）

**R1 — `AvifRegionDecoder` 改 `ImageDecoder` 必须 `setTargetColorSpace(SRGB)`**

- 坑：`ImageDecoder` 解码 **10-bit AVIF 输出 RGBA_F16（8B/px）**，子采样 tile 内存翻倍、解码变慢，**反而比现在的 `HeifCoder`（libavif 默认 8-bit 输出）更差**——"原生化"变成性能倒退。
- 修：`decodeRegion` 里 `decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))`，解码时转换输出 8-bit sRGB tile（4B/px），内存/性能回到基线。
- 注意：tile 不做 HDR 是**正确**的（tile 是预览/缩放用，HDR 由整图路径负责）。

**R2 — "优先复用 `photo.isHdr`"对 AVIF/HEIF 无效**

- 事实：`MediaScanner.detectUltraHdr()` **仅对 JPEG 检测**（源码注释明确），AVIF/HEIF 的 `isHdr` 恒为 false。
- 因此"用扫描期标记跳过探测"只对 JPEG 生效；AVIF/HEIF 每张详情页都会触发一次 200×200 探测（滑动时连续解码有压力）。
- 决策：**不扩展扫描器**（解析 ISOBMFF nclx/bit-depth 成本高、收益低），改为在详情页加**探测结果缓存**（`LruCache<photoId, HdrStatus>`），同一张图不重复探测。

**R3 — HEIF gain map 原生解码存疑（最高不确定项）**

- Android 15 官方明确背书的是 **JPEG** 的 ISO 21496-1 gain map；**HEIF 容器内 gain map 能否被 `ImageDecoder` 解码出 `hasGainmap()==true`，官方文档未明确承诺**。
- 对策：先用真实 HEIF gain map 样本在 Android 15 上实测；若不支持，HEIF HDR 降级为 `colorSpace.isWideGamut → WIDE`（可接受的显示效果），不影响整体架构。

**R4 — `loadHdrBitmap` 语义泛化**

- 探测结果现在是三档：`hasGainmap → HDR`、`isWideGamut → WIDE`、`否则 → 回退`。
- 现有 `loadHdrBitmap` 只处理 gainmap，需扩展为"原生解码（HDR/WIDE 共用）"，按位图实际属性设置 `COLOR_MODE_HDR` / `COLOR_MODE_WIDE_COLOR_GAMUT`，函数名同步改名（如 `loadNativeHighDynamic`）。

### 9.3 精简性核对（最终 diff 面）

| 改动 | 规模 | 必要性 |
|---|---|---|
| minSdk 35 / targetSdk 36 | 2 行 | 必要 |
| 删除 15 处 SDK_INT 分支 | 15 处 | 必要 |
| `shouldProbeHdr` 条件扩展 + `isHdr` 优先 + 探测缓存 | ~15 行 | 必要 |
| `AvifRegionDecoder` 重构（含 R1） | ~30 行 | 必要（去 native 库） |
| 移除 avif-coder（toml + build.gradle） | 2 处 | 必要 |
| 权限合并 `PermissionUtils` | 净减 ~40 行 | 必要 |
| Manifest 清理 | 3 行 | 必要 |
| desugar / splashscreen 移除 | 可选 | 可做可不做（splashscreen 建议做；desugar 先实测 zoomimage 编译） |

结论：无新增架构、无重复抽象，属于"最小必要改动"级别。

### 9.4 可靠性验证清单（实测驱动，落地前必须过）

1. 10-bit AVIF → `ImageDecoder` 输出位深/色彩空间（RGBA_F16? BT2020?）→ 确认 HDR/WIDE 分流正确
2. HEIF gain map（API 35）→ `ImageDecoder` 能否 `hasGainmap()==true`（R3 的验证）
3. Ultra HDR JPEG 走 Glide 小图路径 → 确认 gainmap 丢失情况（决定小图是否强制 ImageDecoder）
4. 连续缩放 AVIF 大图 → R1 后 tile 内存/帧率达标
5. ViewPager2 快速翻页 → HDR 屏上 colorMode 不闪
6. SDR 设备 → `COLOR_MODE_HDR` 的 fallback 行为正常
7. 16KB page 设备 → jxl-coder 正常加载
8. 48MP JPEG/AVIF 大图 → 子采样内存峰值无 OOM

### 9.5 风险重估

- 相比 v2：无新增结构性风险；R1~R4 修正后方案可落地。
- 剩余最高不确定项：**R3（HEIF gain map）**——先行样本验证，不可行则走 WIDE 降级路径，不影响整体架构。
- 次高：R1 的 tile 解码性能需在真机（尤其 10-bit AVIF 样张）验证。

---

## 十、大图解码链路评估（v4：Glide / 子采样限制与更先进方案）

### 10.1 现状限制诊断

| 环节 | 限制 | 影响 |
|---|---|---|
| Glide Downsampler | 默认 `ARGB_8888`（8-bit），10-bit AVIF/HEIF 被降级；`inSampleSize` 只能 2 的幂；bitmap pool 不管理 F16 | 列表/预览可用；**高位深信息在 Glide 链路丢失** |
| 现有 `AvifRegionDecoder` | 整帧解码 + 裁切（无真 tile 解码）；缩放时 sampleSize 变化即全图重解；10-bit 源经 ImageDecoder 输出 F16（8B/px） | 大 AVIF 连续缩放卡顿、内存峰值高 |
| JXL 路径 | 软件解码（libjxl）、无子采样、无硬件加速 | 4096 上限已限内存；超清放大受限于上限 |
| MediaStore 缩略图 | JXL 无系统缩略图；AVIF/HEIF 依赖系统/原生生成 | 已有 ThumbnailManager 自建缓存兜底 |

### 10.2 平台能力边界（关键事实，v4 修正）

- `BitmapRegionDecoder` 官方支持列表：JPEG、PNG、WebP、HEIF、AVIF。
- **但 AVIF 的区域解码支持是 2025-09 才合入 Skia 的（commit "BitmapRegionDecoder: Enable AVIF support"，Bug 435430895，作者 Vignesh Venkat），对应 Android 16（API 36）+**。Android 15（API 35）的 `BitmapRegionDecoder` 仍不认 AVIF。
- 结论：
  1. **minSdk 35 下 AVIF 大图区域解码必须保留自定义 `AvifRegionDecoder`**——这是平台边界，不是实现问题（v3 结论不变，且依据更明确）。
  2. **HEIF 的区域解码支持更早（Skia kHEIF 早已支持）**——HEIF 大图可实测走 ZoomImage 内置 `BitmapRegionDecoder` 原生子采样，**不需要 avif-coder、也不需要自定义解码器**；若个别设备内置失败，再注册 `AvifRegionDecoder`（ImageDecoder 实现）兜底。
  3. 未来若升 minSdk 36：AVIF 也可全原生，`AvifRegionDecoder` 可整体删除。

### 10.3 更先进方案（按落地优先级）

**A. 本次改造内可落地（推荐）**
1. **HEIF 走原生子采样**：验证 ZoomImage 内置 `BitmapRegionDecoder` 对 HEIF 的可靠性，通过则 HEIF 不再经过自定义解码器（AVIF 专属自定义 + HEIF 原生 = 最优组合）。
2. **`AvifRegionDecoder` v2**：`ImageDecoder` + `setTargetColorSpace(SRGB)`（tile 输出 8-bit，避免 F16 内存翻倍）+ **多级采样缓存**（预解码 512 / 1024 / 2048 三档，按当前缩放就近取档）+ sampleSize 容差（|Δ| ≤ 2 复用），消除连续缩放的反复全图重解。
3. **渐进加载**：进入详情页先显示缓存缩略图（512px）→ 快速解码 2K 原生预览 → 用户放大时才解码高分辨率——**避免一进页面就整帧解码 48MP**。
4. **高位深/HDR 整图路径**：保留 `ImageDecoder` 原生输出（F16/gainmap）+ `colorMode`；只有 tile/预览走 8-bit——两者职责分离。
5. **吃硬件加速**：移除 avif-coder 后，原生 `ImageDecoder` 对 HEIF 走 HEVC 解码器（硬件优先）、AVIF 走 AV1 解码器（Android 15 dav1d 软件优化 + 硬件），比 avif-coder 的 libdav1d 纯软件解码更快。

**B. 架构演进（后续，按用户规模决定）**
6. **多分辨率金字塔 + 磁盘缓存**（Google Photos 模式）：按需预生成 512 / 2K / 4K 三档并落盘，详情页按缩放级别切换档位，彻底消除"整帧重解"——这是大图体验的终极形态。
7. **`SeekableByteChannel` / `FileChannel.map` 内存映射**读取，避免整文件读入内存、减少 IO 拷贝。

**C. 动图/序列（功能补齐，非性能必需）**
8. HEICS / AVIF 序列（Live Photo 类）：`ImageDecoder` 只解首帧；更先进的做法是 `MediaCodec` 解 HEVC/AV1 帧序列（复用 ExoPlayer 管线）或引入第三方动图解码器。

**D. 展示链路确认（平台最优，无需第三方）**
9. HDR / 高位深展示的标准答案 = `ImageDecoder`（保留位深与 gainmap）+ `Bitmap` + `window.colorMode`（HDR/WIDE）+ API 35 `setDesiredHdrHeadroom`。项目 v3 设计已对齐此链路。

### 10.4 结论

- **"会不会限制"：会。** Glide 只适合列表/预览层；大图必须走 RegionDecoder 链路；**AVIF 在 minSdk 35 没有原生 tile 解码**（API 36+ 才有），所以自定义解码器必须保留——这是平台能力边界。
- **"更先进"：** 优先做 A 组（本次改造内全部可实现，改动量可控）；B 组是规模化后的体验升级；C 组是功能补齐。
- **唯一能"删掉自定义解码器"的路径是未来升 minSdk 36**（留作后手，不是本次范围）。

---

## 十一、技术选型定稿（v5：JXL 单独处理方案 + 原生栈确认）

### 11.1 需求满足矩阵（AVIF / HEIF：原生 + HDR + 子采样）

| 需求 | HEIF | AVIF | 实现载体 |
|---|---|---|---|
| 原生解码 | ✅ API 26+ | ✅ API 31+（34 强制） | `ImageDecoder` / `BitmapFactory`（移除 avif-coder） |
| HDR 展示 | ✅ 高位深 API 34+；gain map（21496-1）API 35（待实测，见 R3） | ✅ 10-bit/HDR API 34+ | ImageDecoder 保留 F16/gainmap + `colorMode` + headroom |
| 子采样 | ✅ **原生**（`BitmapRegionDecoder` 支持 HEIF，ZoomImage 内置） | ⚠️ **自定义**（API 35 无原生 AVIF 区域解码，2025-09 才合入 Skia，属 API 36+） | HEIF 走内置；AVIF 走 `AvifRegionDecoder` v2（ImageDecoder 实现 + 多级缓存） |

**结论：现有技术栈满足全部三项需求，不需要更换库。** AVIF 子采样是唯一"半原生"点，且是格式 + 平台双重边界（见 11.3），不是库能解决的。

### 11.2 为什么不换库（备选评估）

| 候选 | 能否解决 AVIF 区域解码 | 结论 |
|---|---|---|
| ZoomImage 1.6（现状） | 提供 RegionDecoder 扩展机制，AVIF 需自定义（现状即最优用法） | 保留 |
| Glide 5 / Coil / Fresco | 均基于 BitmapFactory/ImageDecoder 整帧解码，**无 AVIF region API**（Bilibili 落地文章证实 Fresco 也不支持 AVIF 区域解码） | 不换 |
| awxkee avif-coder / libavif | 无公开 region/tile 解码 API（libdav1d/libaom/libavif 均无"只解部分区域"接口） | 移除（解码走原生） |
| libheif（HEIF tile） | HEIF 有 tiles，但系统 BitmapRegionDecoder 已覆盖 HEIF | 不需要 |

**根本原因**：AVIF 是 AV1 帧内编码格式，**业界没有任何库提供"只解码部分区域"的公开 API**——Google Photos / Apple 的实现都是"整帧解码（硬件加速）+ 多分辨率缓存"。所以 AVIF 大图体验的关键不在选库，而在**渐进加载 + 多级缓存**（v4 已设计）。

### 11.3 JXL 单独处理方案（定稿）

| 环节 | 方案 | 说明 |
|---|---|---|
| 格式识别 | `Photo.isJxl`（MIME + 扩展名双重，已有） | MediaStore 不识别 .jxl，扩展名兜底 |
| 列表缩略图 | `ThumbnailManager` 对 JXL 走 **jxl-coder 核心库** 生成 512px（`RGBA_8888`）落盘 → Glide 优先加载缓存文件 | 现状已实现，保留 |
| Glide 兜底 | **jxl-coder-glide** 的 LibraryGlideModule（`StarGalleryGlideModule` 已保证注册） | 其他场景的 Glide 解码路径 |
| 详情页 | `loadJxlDirect`：jxl-coder `decodeSampled`（DEFAULT 自动 8/16-bit）→ 窗口模式按 bitmap 属性（F16/广色域 → WIDE） | 现状已实现，删 SDK_INT 门槛 |
| 内存保护 | 16-bit 解码上限 **2560**（RGBA_F16 = 8B/px，4096 时 ≈ 89~134MB） | v3 P1-1 修订 |
| EXIF | JXL 无 EXIF 支持 → 信息面板字段降级显示 | 已知限制 |
| 子采样 | **不支持**（无 BitmapRegionDecoder），放大清晰度受解码上限约束 | 平台边界，接受 |

**JXL 不需要任何新库，保持 jxl-coder + jxl-coder-glide 双组件，其余链路不动。**

### 11.4 最终技术栈（改造后）

| 层 | 组件 | 状态 |
|---|---|---|
| 图片加载（列表/缓存） | Glide 5.0.9 | 保留 |
| 大图缩放（View） | ZoomImage 1.6.0 | 保留 |
| AVIF 子采样 | `AvifRegionDecoder` v2（ImageDecoder + SRGB tile + 多级采样缓存 + 渐进） | 重写 |
| HEIF 子采样 | ZoomImage 内置 `BitmapRegionDecoder`（原生） | 验证启用 |
| AVIF/HEIF 解码 + HDR | `ImageDecoder` / `BitmapFactory`（原生） | 统一走原生 |
| JXL 解码 | jxl-coder + jxl-coder-glide | 保留（独立路径） |
| AVIF/HEIF 第三方 | ~~avif-coder / avif-coder-glide~~ | **移除** |
| desugar / splashscreen | 视实测移除（可选） | 可选 |

---

## 十二、高位深（10-bit / 12-bit）支持评估（v6）

### 12.1 结论先行

- **10-bit AVIF / HEIF：✅ 原生支持**（解码 + HDR/广色域显示），v5 方案已覆盖，另补 1 处修正（R5）。
- **12-bit：❌ 不支持**——不是"特别麻烦"，而是 Android 平台**不存在 12-bit 链路**（解码不保证、位图无 12-bit 格式、显示无 12-bit 面板）。

### 12.2 10-bit 支持方案（全链路，全部原生）

| 环节 | 能力 | 说明 |
|---|---|---|
| 解码 | ✅ 原生 | AVIF 10-bit：API 34+ 强制（baseline 内）；HEIF 10-bit（HEVC Main10）：API 28+ 可解（实测 10-bit P3 正常）；HEIF gain map（ISO 21496-1）：API 35（待实测，R3） |
| 数据格式 | RGBA_F16 | 10-bit 源 → F16（16-bit float）+ wide gamut colorSpace（BT2020 / P3） |
| 显示 | HDR / WIDE | HDR 屏：`COLOR_MODE_HDR`（gainmap 或 PQ/HLG）· `COLOR_MODE_WIDE_COLOR_GAMUT`（P3 广色域）；SDR 屏：系统 tone map 到 8-bit，颜色正确但动态范围压缩（物理限制，非代码问题） |
| 现有方案 | 已覆盖 | HDR 候选路径（ImageDecoder 保留 F16/gainmap）+ `colorModeForBitmap` |

**R5（本次新增修订）**：`colorModeForBitmap` 需**恢复 transfer function（PQ/HLG）检测**——10-bit PQ/HLG 编码的 AVIF/HEIF（`BT2020_PQ` / `BT2020_HLG` colorSpace）应走 **`COLOR_MODE_HDR`**，而非仅 `WIDE`。只用 `isWideGamut` 会把 PQ/HLG 内容误判为广色域 SDR，导致亮度/动态范围错误。项目 AGENTS.md 记载的三重检测（`hasGainmap` / `RGBA_F16` / ColorSpace 名称）正是为此设计，v3 简化时**不要丢失 PQ/HLG 分支**。

### 12.3 12-bit：不支持的三层理由

1. **解码不保证**：官方 AVIF 支持只承诺 baseline profile；12-bit（AV1 profile 2 / HEVC Main12）不在 CDD 强制范围，通用解码器可能直接解码失败——Oppo 为 12-bit HEIF 专门定制 ROM 即是"通用设备不保证"的佐证。
2. **无 12-bit 位图格式**：Android 高位深位图只有 `RGBA_F16`（16-bit float）。12-bit 整数必须转换到 F16 或 8-bit，原生 12-bit 精度在格式层就不存在。
3. **无 12-bit 显示面板（决定性）**：消费级手机屏最高 10-bit（多数为 8-bit + FRC）。12-bit 内容在 10-bit 面板上多出的 2-bit 物理上显示不出来，HDR 显示链路也只到 10-bit。

**结论**：12-bit 文件的正确处理 = 解码时降级显示（10-bit F16 或 8-bit），观感与 10-bit 文件无区别。**不建议为 12-bit 保留任何第三方兜底库**（样本极少、性价比极低）；个别 12-bit 文件解码失败按普通失败处理（现有 try-catch 回退已覆盖）。

### 12.4 落地清单

1. R5：`colorModeForBitmap` 恢复 PQ/HLG → HDR 分支（本次新增）
2. R3 实测：HEIF gain map 原生解码（10-bit HDR HEIF 的确定性）
3. 测试样本：10-bit AVIF（PQ）、10-bit HEIF（P3 / HLG）、Ultra HDR JPEG（gainmap）各一
4. **不做**：12-bit 的任何专项支持

---

*编制：Mobile App Builder / 日期：2026-08-11（v6：高位深评估——10-bit 全原生支持 + R5 PQ/HLG→HDR 修正；12-bit 因平台无解码/无格式/无面板链路而不支持）*
*依据：Android 官方媒体格式文档、Android 15 功能与 API 概览、apilevels.com 覆盖率（2026-08）、项目源码走查*
