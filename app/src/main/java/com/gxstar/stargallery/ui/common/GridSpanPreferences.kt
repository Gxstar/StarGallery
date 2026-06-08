package com.gxstar.stargallery.ui.common

import android.content.SharedPreferences

/**
 * 网格列数偏好：按"竖屏/横屏"分别存储。
 * 解析规则：
 *   1) 当前方向有存值 → 用
 *   2) 否则另一方向有存值 → 用另一方向（用户在另一边设过，暂复用）
 *   3) 都没有 → 使用方传入的 [fallback]（通常为按屏宽计算的最佳值）
 *
 * 提供 [save] / [load] 抽象（用 key 前缀区分不同页面），
 * 避免 PhotosFragment 与 AlbumDetailFragment 互相覆盖。
 */
object GridSpanPreferences {

    private const val PREFIX_PORTRAIT = "_portrait"
    private const val PREFIX_LANDSCAPE = "_landscape"

    fun resolveForOrientation(
        prefs: SharedPreferences,
        prefix: String,
        isLandscape: Boolean,
        fallback: Int
    ): Int {
        val portraitSaved = prefs.getInt(prefix + PREFIX_PORTRAIT, -1)
        val landscapeSaved = prefs.getInt(prefix + PREFIX_LANDSCAPE, -1)
        val currentSaved = if (isLandscape) landscapeSaved else portraitSaved
        val otherSaved = if (isLandscape) portraitSaved else landscapeSaved
        return when {
            currentSaved > 0 -> currentSaved
            otherSaved > 0 -> otherSaved
            else -> fallback
        }
    }

    fun save(prefs: SharedPreferences, prefix: String, isLandscape: Boolean, value: Int) {
        val key = prefix + if (isLandscape) PREFIX_LANDSCAPE else PREFIX_PORTRAIT
        prefs.edit().putInt(key, value).apply()
    }
}
