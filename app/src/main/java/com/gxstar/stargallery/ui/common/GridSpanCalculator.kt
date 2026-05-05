package com.gxstar.stargallery.ui.common

import android.util.DisplayMetrics

object GridSpanCalculator {
    const val MIN_SPAN_COUNT = 3
    const val MAX_SPAN_COUNT = 10
    const val MIN_CELL_WIDTH_DP = 80

    fun calculateOptimalSpanCount(displayMetrics: DisplayMetrics): Int {
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val spanCount = (screenWidthDp / MIN_CELL_WIDTH_DP).toInt()
        return spanCount.coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)
    }

    fun calculateItemSize(screenWidthPx: Int, spanCount: Int, spacingPx: Int): Int {
        val totalSpacing = spacingPx * (spanCount + 1)
        return (screenWidthPx - totalSpacing) / spanCount
    }

    fun dpToPx(dp: Int, displayMetrics: DisplayMetrics): Int =
        (dp * displayMetrics.density).toInt()
}