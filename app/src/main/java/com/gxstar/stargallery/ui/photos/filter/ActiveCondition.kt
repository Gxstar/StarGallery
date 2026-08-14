package com.gxstar.stargallery.ui.photos.filter

/**
 * 一条「当前生效的筛选条件」，用于顶栏 chip 条展示与逐条移除
 *
 * 分成这几类是因为移除动作各不相同（收藏是开关、搜索要退出搜索栏、
 * 排除相册属于偏好设置），而维度类条件用 [Dimension] 一种就够，
 * 新增筛选维度不需要在这里增加分支。
 */
sealed interface ActiveCondition {

    val label: String

    /** 只看收藏 */
    data class Favorites(override val label: String) : ActiveCondition

    /** 文本搜索 */
    data class Search(
        val query: String,
        override val label: String
    ) : ActiveCondition

    /** 任意注册维度上的选择 */
    data class Dimension(
        val id: FilterDimensionId,
        override val label: String
    ) : ActiveCondition

    /** 相册排除（来自相册管理页的偏好设置） */
    data class ExcludedAlbums(override val label: String) : ActiveCondition
}
