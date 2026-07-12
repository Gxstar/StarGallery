# StarGallery 缩略图生成逻辑分析与改造方案

> 目标：分析 `ThumbnailManager` + `MediaScanner` + `PhotoGridViewHolder` 的缩略图链路，找出隐患，给出对标 Google Photos / Simple Gallery / Aves 的改造方案。

## 一、当前链路梳理

**触发时机**
- 全量扫描：扫描写入 Room → EXIF 提取完成 → `generateThumbnailsForAllPhotos()` 批量生成
- 增量扫描：新照片 → `generateThumbnailsForPhotos()`

**生成（`ThumbnailManager.generateThumbnail`）**
```
ImageDecoder.createSource(contentResolver, uri)
  → decodeBitmap { setTargetSize(512, 512) }   // fitCenter 保比例
  → compress(JPEG, 85)
  → 写入 cacheDir/thumbnails/{id}.jpg
```
仅 `mimeType.startsWith("image/x-")` 被跳过（RAW）。

**显示（`PhotoGridViewHolder.loadImage`）**
```
thumbnailPath 存在且文件存在 → 加载该文件
否则 → 回退 photo.uri（原图）
→ Glide .centerCrop().override(itemSize).diskCacheStrategy(RESOURCE)
```

## 二、问题隐患（按严重程度）

### P0 — 明显异常

1. **缩略图被拉伸变形（已修复）**
   `ImageDecoder.setTargetSize(512, 512)` 会**精确缩放到给定正方形尺寸、不保比例**，竖图被压扁存成 512×512 的 JPEG；Glide 再 `centerCrop` 裁正方形时内容已经压扁 → 网格里看到变形。已在 `ThumbnailManager` 改为按原图比例计算目标尺寸（长边 512），并将缓存目录迁移到 `thumbnails_v2` 强制旧图重建。

2. **视频缩略图永远生成失败**
   `video/mp4` 等进入 `ImageDecoder.decodeBitmap`，ImageDecoder 不支持视频 → 抛异常 → 返回 `null` → `thumbnailPath` 永远为 `null`。
   后果：网格只能回退到 `photo.uri`，Glide 对视频 URI 用 `MediaMetadataRetriever` 每次滚动都临时抽帧，**无磁盘缓存、首帧常黑、CPU 抖动大**。这是视频格子"忽闪/卡/黑"的直接原因。

3. **RAW 显示成错误图标**
   `image/x-*`（ARW/NEF/CR2/CR3/DNG…）被直接跳过 → `thumbnailPath` 为 `null` → 回退原图 → Glide 也解不了 RAW → 显示 `ic_photo_error`。虽然标了 "RAW" 标签，但视觉上就是"坏图"。

### P1 — 质量 / 性能

3. **生成时机靠后，长时间加载原图**
   EXIF 全部提取完才生成全部缩略图。完成前 `thumbnailPath` 为 `null`，网格只能 `Glide` 加载**原图**再 `override` 缩放。几千张时：长时间满屏解原图 → 滚动卡顿、内存尖峰。知名相册都是"先出图、再补细节"。

4. **无编辑失效机制**
   文件被修改（MediaStore id 不变、内容变了）后，旧缩略图仍在，且 Glide 按文件路径缓存 → **一直显示旧图**，直到清缓存。系统相册/Google Photos 会自动更新。

5. **cacheDir 不可靠**
   缩略图放在 `context.cacheDir`，Android 在存储紧张时可能清除该目录 → 缩略图全丢 → 再次批量重建 → 卡顿。

### P2 — 细节隐患

6. 缺 Glide `signature`：覆盖/重建缩略图文件后，Glide 磁盘缓存可能命中旧图。
7. 列数变化（3↔8）改变 `itemSize` → Glide 按尺寸分别缓存，切换后整屏缓存失效重算。
8. 取消不彻底：`thumbnailJob.cancel()` 无法中断正在执行的 `ImageDecoder.decodeBitmap`（阻塞、不检查 `isActive`），新扫描可能与旧任务并发写盘。
9. `itemSize` 可能为 0（span 未算好时）→ 跳过 `override` → Glide 按原图尺寸加载 → OOM/卡顿风险。
10. 固定 512 在平板/大屏高列场景略软；JPEG 压缩丢失 PNG 透明通道（罕见）。
11. 增量生成用裸 `CoroutineScope(Dispatchers.IO).launch` 且未跟踪 Job，无法取消/感知完成。

## 三、根因

- **生成源选错**：用 ImageDecoder 硬解原图，视频/RAW 原生不支持；而不是用系统已缓存的 MediaStore 缩略图。
- **生成时机错**：大爆炸式"全部生成"，而非"按需 / 优先可见"。
- **无版本与失效管理**。

## 四、改造方案（对标 Google Photos / Simple Gallery / Aves）

### 核心改动：改用 `ContentResolver.loadThumbnail(uri, Size, signal)`
- API 29+（minSdk 30 ✓）。系统级缩略图：OS 已缓存、随文件编辑自动失效、朝向已校正、视频也能抽帧。
- **一次性解决 P0 的视频与"原图重解码"问题**。

```kotlin
suspend fun generateThumbnail(uri: Uri, photoId: Long, mimeType: String): String? =
    withContext(Dispatchers.IO) {
        if (mimeType.startsWith("image/x-")) return@withContext generateRawThumbnail(uri, photoId)
        try {
            val signal = CancellationSignal()
            // 关联协程取消：在 finally 中 signal.cancel()
            val bmp = context.contentResolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), signal)
            saveBitmap(bmp, photoId)
        } catch (e: Exception) {
            // 回退：ImageDecoder 解原图
            decodeViaImageDecoder(uri, photoId)
        }
    }
```

### 视频
`loadThumbnail` 已支持抽帧；个别机型失败则回退 `MediaMetadataRetriever` 取约 1s 处帧。

### RAW
`loadThumbnail` 多数机型对专有 RAW 返回 `null`/异常 → 回退：
- 优先 `ExifInterface(path).thumbnailBytes`（DNG 等内嵌 JPEG 预览）
- 仍失败 → 写标记（如 `thumbnailPath = "raw_placeholder"`）避免无限重试，UI 显示内嵌预览或优雅占位（对标 Aves 用原生解码，工程量大，可作为后续）。

### 失效 / 版本管理
- DB 增加 `thumbnailETag = "${size}_${dateModified}"`，生成前比对，变化才重建。
- Glide 加载加 `signature(ObjectKey(etag))`，缓存随文件更新。

### 时机：按需 + 优先
- 阶段方案 A（最简）：批量生成仍保留，但按 `dateTaken DESC` 排序（近照优先）；网格首次 `bind` 时对缺图项触发"协同生成"（debounce），滚到哪生成到哪。
- 阶段方案 B（推荐，对标 Simple Gallery/Aves）：放弃"扫描后全量生成"，改为 Glide 自定义 `ModelLoader`，加载时若本地缺图则即时 `loadThumbnail` 生成并缓存，天然"可见才生成、优先可见"。

### 持久性
- 缩略图目录迁到 `context.filesDir/thumbnails`（或 app-specific 外部目录），避免被系统清 cache 误删；即便仍用 cache，因 `loadThumbnail` 很便宜，丢失也可接受。

### Glide 配置
- 固定缩略图尺寸（如 512 长边）以复用磁盘缓存；加 `signature`；`diskCacheStrategy` 用 `AUTOMATIC`。

## 五、对比表

| 维度 | 当前 | Google Photos / 系统 | Simple Gallery | 改造目标 |
|---|---|---|---|---|
| 视频 | 不生成 / 抽帧卡 | 系统缩略图 | MediaMetadataRetriever | `loadThumbnail` |
| RAW | 错误图标 | 内嵌预览 | 原生解码 | ExifInterface 预览 + 占位 |
| 编辑失效 | 不更新 | 自动 | 按修改时间 | ETag + signature |
| 生成时机 | 全量后 | 即时 | 按需 | 按需 / 近照优先 |
| 朝向 | OK | OK | OK | OK |

## 六、落地建议（分阶段，低风险优先）

- **阶段 1（高收益低风险）**：`generateThumbnail` 改用 `loadThumbnail` + 视频/RAW 回退 + `CancellationSignal` 关联取消。立刻修 P0。
- **阶段 2**：加 `thumbnailETag` + Glide `signature` 失效机制（修 P1-4/6）。
- **阶段 3**：改为按需生成（Glide `ModelLoader`）或近照优先（修 P1-3 的卡顿窗口）。
- **阶段 4**：目录迁移到 `filesDir` + 列数缓存复用（修 P1-5/7）。

## 七、关键代码位置

- `app/src/main/java/com/gxstar/stargallery/data/local/ThumbnailManager.kt`（生成核心）
- `app/src/main/java/com/gxstar/stargallery/data/local/scanner/MediaScanner.kt`（触发与批量逻辑，L581-714）
- `app/src/main/java/com/gxstar/stargallery/ui/common/PhotoGridViewHolder.kt`（显示，`loadImage` L78-99）
- `app/src/main/java/com/gxstar/stargallery/ui/photos/PhotoPreloadModelProvider.kt`（预加载）
- `app/src/main/java/com/gxstar/stargallery/data/local/db/PhotoEntity.kt`（thumbnailPath 字段）
