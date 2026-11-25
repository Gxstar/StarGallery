package com.gxstar.stargallery.ui.common

import android.graphics.Insets
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.fragment.app.DialogFragment
import kotlin.math.min

/**
 * BottomSheetDialogFragment 平板限宽居中。
 *
 * 手机上保持全宽，平板/大屏上限制最大宽度并居中，避免弹窗被拉伸到整屏。
 *
 * @param maxWidthDp 最大宽度（dp），默认 640
 */
fun DialogFragment.constrainBottomSheetWidth(maxWidthDp: Int = 640) {
    val activity = activity ?: return
    val dialog = dialog ?: return
    val window = dialog.window ?: return

    val widthPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm: WindowMetrics = activity.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets: Insets = wm.windowInsets.getInsets(WindowInsets.Type.systemBars())
        wm.bounds.width() - insets.left - insets.right
    } else {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { activity.windowManager.defaultDisplay.getMetrics(it) }
        metrics.widthPixels
    }

    val density = activity.resources.displayMetrics.density
    val maxWidthPx = (maxWidthDp * density).toInt()
    window.setLayout(min(widthPx, maxWidthPx), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
}
