package com.gxstar.stargallery.ui.common

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import com.google.android.material.R as MaterialR

/**
 * 平板（屏幕宽度 >= 600dp）上将 BottomSheetDialog 的内容限制为 [maxWidthDp] 并水平居中，
 * 避免参数卡片、选项列表在平板上被横向拉伸到全宽。手机保持全宽不变。
 *
 * 在 BottomSheetDialogFragment 的 onStart() 中调用即可。
 */
fun DialogFragment.constrainBottomSheetWidth(maxWidthDp: Int = 480) {
    val dialog = dialog ?: return
    if (resources.configuration.screenWidthDp < 600) return
    val bottomSheet = dialog.findViewById<ViewGroup>(MaterialR.id.design_bottom_sheet) ?: return
    val maxWidthPx = (maxWidthDp * resources.displayMetrics.density).toInt()
        .coerceAtMost(resources.displayMetrics.widthPixels)
    val params = (bottomSheet.layoutParams as? FrameLayout.LayoutParams)
        ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    params.width = maxWidthPx
    params.gravity = Gravity.CENTER_HORIZONTAL
    bottomSheet.layoutParams = params
}
