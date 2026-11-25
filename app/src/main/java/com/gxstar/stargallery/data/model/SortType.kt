package com.gxstar.stargallery.data.model

/**
 * 照片排序方式。
 *
 * 从 MediaRepository 内部枚举迁出为顶层模型：SortType 同时被 data 层（查询排序）
 * 和 ui 层（网格/详情/工具）使用，放在 Repository 内部会迫使 UI 工具层
 * 为了一个枚举而依赖整个 data 实现。
 */
enum class SortType {
    DATE_TAKEN,   // 拍摄时间
    DATE_ADDED    // 创建时间
}
