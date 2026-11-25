package com.gxstar.stargallery.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 悬浮玻璃导航条（[com.example.liquidglass.LiquidGlassTabBar]）占用的垂直空间换算。
 *
 * 导航条悬浮在内容之上，列表必须自己预留出「导航条高度 + 上下间距 + 系统手势条」的内边距，
 * 否则滚到最底时最后一行会被压住。历史上这里写死 80dp，在 gesture 导航机型上
 * （手势条约 24~45dp）明显不足，实测最后一行缩略图底边紧贴导航条上沿。
 *
 * 这里把三处高度集中成常量，改导航条尺寸时只需要动这一个文件。
 */
object FloatingNavMetrics {

    /**
     * 导航条内容高度，与 activity_main.xml 中 bottom_nav_glass 的 layout_height 保持一致。
     * 自绘导航项后压到 56dp，对齐系统相册（HyperOS 实测约 52dp）。
     */
    const val NAV_HEIGHT_DP = 56

    /** 导航条与屏幕底部的间距，与 MainActivity.setupWindowInsets 中的基础 margin 一致 */
    const val NAV_BOTTOM_GAP_DP = 12

    /** 列表末尾与导航条之间留出的呼吸空间，避免最后一行视觉上「贴住」玻璃 */
    const val CONTENT_BREATHING_DP = 12

    /**
     * 列表底部需要预留的像素高度。
     *
     * @param systemBarBottom 系统导航栏 / 手势条高度（来自 WindowInsets）
     */
    fun contentReservePx(density: Float, systemBarBottom: Int): Int =
        ((NAV_HEIGHT_DP + NAV_BOTTOM_GAP_DP + CONTENT_BREATHING_DP) * density).toInt() +
            systemBarBottom

    /**
     * 给列表设置随系统栏实时变化的底部内边距。
     *
     * 调用后 XML 里的固定 paddingBottom 会被此处的动态值覆盖，避免机型差异导致遮挡。
     */
    fun View.applyFloatingNavBottomInset() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val systemBarBottom = windowInsets
                .getInsets(WindowInsetsCompat.Type.systemBars())
                .bottom
            val reserve = contentReservePx(view.resources.displayMetrics.density, systemBarBottom)
            if (view.paddingBottom != reserve) {
                view.updatePadding(bottom = reserve)
            }
            windowInsets
        }
    }
}
