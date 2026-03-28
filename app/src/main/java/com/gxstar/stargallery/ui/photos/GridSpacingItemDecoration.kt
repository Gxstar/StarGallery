package com.gxstar.stargallery.ui.photos

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val includeEdge: Boolean = true
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val spanIndex = (view.layoutParams as? androidx.recyclerview.widget.GridLayoutManager.LayoutParams)?.spanIndex ?: 0

        // 日期标题占满整行，不需要间距
        val viewType = parent.adapter?.getItemViewType(position) ?: 1
        if (viewType == 0) {
            outRect.set(0, 0, 0, 0)
            return
        }

        if (includeEdge) {
            outRect.left = spacing - spanIndex * spacing / spanCount
            outRect.right = (spanIndex + 1) * spacing / spanCount
            if (position < spanCount) {
                outRect.top = spacing
            }
            outRect.bottom = spacing
        } else {
            outRect.left = spanIndex * spacing / spanCount
            outRect.right = spacing - (spanIndex + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing
            }
        }
    }
}
