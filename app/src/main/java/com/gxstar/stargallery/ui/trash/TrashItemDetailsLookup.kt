package com.gxstar.stargallery.ui.trash

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView

/**
 * 回收站列表的 Details Lookup
 */
class TrashItemDetailsLookup(
    private val recyclerView: RecyclerView
) : ItemDetailsLookup<Long>() {

    override fun getItemDetails(event: MotionEvent): ItemDetails<Long>? {
        val view = recyclerView.findChildViewUnder(event.x, event.y)
            ?: return null

        val viewHolder = recyclerView.getChildViewHolder(view)
            as? TrashAdapter.TrashViewHolder
            ?: return null

        return viewHolder.getItemDetails()
    }
}