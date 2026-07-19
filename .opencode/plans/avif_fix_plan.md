# AVIF 修复 — 引入 avif-coder 重写 AvifRegionDecoder

## 根因
`AvifRegionDecoder` 用 `ImageDecoder` 整图解码后裁切（伪子采样），对 AVIF 解码慢、HDR 支持差、部分文件失败。`Photo.isAvif` 依赖 MIME（同 JXL 问题）。

## 修正（已落地）
- `gradle/libs.versions.toml` + `app/build.gradle.kts`：引入 `io.github.awxkee:avif-coder:2.2.1` + `avif-coder-glide:2.2.1`。
- `AvifRegionDecoder.kt`：解码内核从 `ImageDecoder` → `HeifCoder.decodeSampled`，取尺寸用 `HeifCoder.getSize`（失败回退 `BitmapFactory`），不缓存字节流（每次重新读）。
- 扩展名兜底同 JXL 计划（共用 `isAvif` 修改）。
