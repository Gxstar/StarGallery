# 修复 16-bit / 广色域 JXL 在详情页无法正确显示（参考 Goodwy/Gallery）

## 诊断结论

用户反馈：8-bit JXL 能正常显示，但 **16-bit JXL** 在首页与详情页都显示失败；而 Goodwy/Gallery
能正常显示同一张图。

### 关键事实（已通过源码对比证实）
- **StarGallery 与 Goodwy/Gallery 用的是同一个解码库** `awxkee:jxl-coder-glide`
  （StarGallery 2.6.1 / Goodwy 2.6.0，仅补丁级差异）。JXL 解码能力**本身一致**。
- `jxl-coder-glide` 默认 `PreferredColorConfig.DEFAULT` → 对 16-bit JXL 输出
  **`Bitmap.Config.RGBA_F16` + 广色域 `ColorSpace`**（不转 8-bit）。解码**是成功的**。
- **本质差异只有一个：窗口色彩模式（`window.colorMode`）的处理。**
  - Goodwy 在 `onResourceReady` 里读取解码 Bitmap 的 `colorSpace.isWideGamut` / `hasGainmap()`，
    把 `Activity.window.colorMode` 设为 `COLOR_MODE_WIDE_COLOR_GAMUT`（或 `COLOR_MODE_HDR`），
    SurfaceFlinger 因而以 10/16-bit 广色域管线渲染，高位深得以保留显示。
  - StarGallery 仅在「Ultra HDR（gainmap）」分支才设置色彩模式；
    普通 16-bit JXL（非 gainmap）落到 `loadFullImage`，**完全不读 Bitmap config、不设色彩模式**，
    window 停留在 `COLOR_MODE_DEFAULT`（SDR）。在 SDR 窗口下，RGBA_F16 / 广色域 Bitmap 因
    色域不匹配而**无法正确呈现（黑屏 / 不显示）**。

### 佐证：StarGallery 当前代码缺口
- `Photo.isUltraHdr` 仅对 `image/jpeg` 为真（`Photo.kt:87-88`），16-bit JXL 不是 Ultra HDR，
  故 `shouldProbeHdr` 为 false，不会进入 gainmap 探测分支。
- `loadFullImage()`（`PhotoPageViewHolder.kt`）只做 `Glide.load().into()`，**无 onResourceReady
  色彩模式处理**。
- `applyWindowColorMode(isHdr)`（`PhotoPageViewHolder.kt:407`）只支持「HDR / DEFAULT」两态，
  **缺少 `WIDE_COLOR_GAMUT` 分支**，且从未对广色域 JXL 调用。

---

## 实施步骤

### 步骤 1：扩展 `applyWindowColorMode` 支持三态
**文件**：`app/src/main/java/com/gxstar/stargallery/ui/detail/PhotoPageViewHolder.kt`

将 `applyWindowColorMode(isHdr: Boolean)` 改为按色域级别设置：
```kotlin
private fun applyWindowColorMode(mode: ColorMode) {
    lastAppliedHdrMode = (mode == ColorMode.HDR)
    if (!isActive) return
    hdrHandler.removeCallbacksAndMessages(null)
    hdrHandler.post {
        if (!isActive) return@post
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity?.window?.colorMode = when (mode) {
                ColorMode.HDR -> ActivityInfo.COLOR_MODE_HDR
                ColorMode.WIDE -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                ColorMode.DEFAULT -> ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }
}

private enum class ColorMode { DEFAULT, WIDE, HDR }
```
同步更新 `resetWindowColorMode()` 使用 `ColorMode.DEFAULT`，并保留 `lastAppliedHdrMode`
语义（仅 HDR 为 true，供 `bind()` 恢复用）。

### 步骤 2：在 JXL / 大图加载完成后按 Bitmap 色域设置色彩模式
**文件**：`PhotoPageViewHolder.kt`

新增辅助方法，从解码结果读取色域：
```kotlin
private fun colorModeForBitmap(bitmap: Bitmap?): ColorMode {
    if (bitmap == null) return ColorMode.DEFAULT
    val cs = bitmap.colorSpace
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && bitmap.hasGainmap() -> ColorMode.HDR
        cs != null && cs.isWideGamut -> ColorMode.WIDE
        else -> ColorMode.DEFAULT
    }
}
```

在以下落点接入（加载成功后调用 `applyWindowColorMode(colorModeForBitmap(...))`）：

1. **`loadFullImage()`**：`Glide.with().into()` 改为带 `CustomTarget`，在 `onResourceReady` 中：
   ```kotlin
   override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
       binding.ivPhoto.setImageDrawable(resource)
       val bitmap = (resource as? BitmapDrawable)?.bitmap
       applyWindowColorMode(colorModeForBitmap(bitmap))
       binding.progressBar.visibility = View.GONE
       updateEdgeState()
   }
   ```
   （JXL 默认走此路径，这正是 16-bit JXL 的主修复点。）

2. **`loadWithSubsampling()`**：其 `CustomTarget.onResourceReady` 中同样调用
   `applyWindowColorMode(colorModeForBitmap((resource as? BitmapDrawable)?.bitmap))`
   （非 JXL 大图若有广色域也一并支持）。

3. **`loadHdrBitmap()`**：已有 `applyWindowColorMode(hasGainmap)`，保留不变（HDR 分支）。

### 步骤 3：网格缩略图的诊断与一致性（二级修复）
**文件**：`app/src/main/java/com/gxstar/stargallery/ui/common/PhotoGridViewHolder.kt`
**文件**：`app/src/main/java/com/gxstar/stargallery/data/local/ThumbnailManager.kt`

- 缩略图是 512px 的 8-bit JPEG（`ThumbnailManager` 压缩为 `COMPRESS_FORMAT.JPEG`），
  **本身不含高位深**，所以网格缩略图应能正常显示（Goodwy 同理）。
- 若测试发现**网格缩略图仍不显示 16-bit JXL**，则问题在 `ThumbnailManager` 的 Glide
  `submit(512,512)` 解码环节（可能 RGBA_F16 在缩略图尺寸下仍异常）。此时在
  `generateThumbnail()` 的 Glide 请求中显式指定 `.downSampleDecision` / 或在解码失败时回退到
  `JxlCoder.decodeSampled()` 核心库直接解码（方案 B 的兜底）。
- **本次先不修改网格加载链**，待真机验证；若失败再补 `JxlCoder` 核心库兜底（见「回退方案」）。

### 步骤 4：对齐 jxl-coder 版本（可选）
当前 `2.6.1` 合法且较新，与 Goodwy 的 `2.6.0` 仅补丁差。**保持 `2.6.1` 不变**。
（若真机验证 2.6.1 仍有 16-bit 异常，再降到 2.6.0 对比。）

---

## 回退 / 兜底方案（仅当步骤 2 不足时启用）
若 `jxl-coder-glide` 在特定场景对 16-bit JXL 仍输出异常，可引入 `awxkee:jxl-coder` **核心库**
（`implementation(libs.jxl.coder)`），在 `ThumbnailManager.generateThumbnail()` 与详情页
`onResourceReady` 失败分支用 `JxlCoder.decodeSampled(bytes, w, h)` 直接解码，显式
`preferredColorConfig = PreferredColorConfig.RGBA_F16`。这是比 glide 插件更可控的兜底。

---

## 验证
1. `./gradlew assembleDebug` 编译通过。
2. Android 16 设备放入 **16-bit JXL**（如从 RAW/HDR 导出的 16-bit float JXL）：
   - 详情页应正确显示（window 切到 WIDE_COLOR_GAMUT / HDR），可缩放。
   - 网格缩略图应显示（若失败，启用回退方案）。
3. `adb logcat | grep -iE "glide|jxl|colorMode"` 确认无解码异常；可临时加日志打印
   `bitmap.colorSpace` / `isWideGamut` 以确认色域判定正确。
4. 回归：8-bit JXL、JPEG/RAW/AVIF/HEIC、HDR(gainmap)、视频/GIF 均正常；切换图片时
   window colorMode 随图正确切换（SDR↔WIDE↔HDR），离开详情页复位为 DEFAULT。
