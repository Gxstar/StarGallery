package com.gxstar.stargallery.ui.common

import android.view.View
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * BottomSheetDialogFragment 平板限宽居中。
 *
 * 手机上保持全宽，平板/大屏上限制最大宽度并居中，避免弹窗被拉伸到整屏。
 *
 * 注意：不能通过 window.setLayout(…, WRAP_CONTENT) 限宽——
 * 那会改变 BottomSheetDialog 的窗口布局模型，导致弹窗底部与屏幕边缘脱开出现缝隙。
 * 正确做法是用 BottomSheetBehavior.maxWidth，由 Behavior 在布局层面限宽居中。
 *
 * @param maxWidthDp 最大宽度（dp），默认 640
 */
fun DialogFragment.constrainBottomSheetWidth(maxWidthDp: Int = 640) {
    val dialog = dialog ?: return
    val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
    val density = resources.displayMetrics.density
    BottomSheetBehavior.from(sheet).maxWidth = (maxWidthDp * density).toInt()
}
