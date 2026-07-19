# JXL 修复 — 扩展示名兜底（只修识别，不改加载管线）

## 根因
MediaStore 不识别 `.jxl` 扩展名，返回 `application/octet-stream` 或空 MIME。
`Photo.isJxl = mimeType == "image/jxl"` 恒为 false → JXL 不走 `loadJxlDirect` / `decodeJxlThumbnail`。

## 修正（已落地）
- `Photo.kt`：新增 `displayName` 字段 + `extension` 派生属性；`isJxl`/`isAvif`/`isHeic` 改为 MIME 主判断 + 扩展名兜底双保险。
- `ThumbnailManager.generateThumbnail()`：JXL 分支条件添加 `ext == "jxl"` 兜底；`displayName` 从调用点传入。
- `PhotosViewModel` / `PhotoDetailViewModel` / `MediaRepository`（三处 `toPhoto()`）：传透 `displayName` + 查询投影补 `DISPLAY_NAME` 列。
