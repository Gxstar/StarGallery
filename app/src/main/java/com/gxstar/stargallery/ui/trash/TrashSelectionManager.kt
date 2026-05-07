package com.gxstar.stargallery.ui.trash

import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.trash.TrashAdapter
import com.gxstar.stargallery.ui.common.BaseSelectionManager

class TrashSelectionManager(
    recyclerView: RecyclerView?,
    adapter: TrashAdapter?
) : BaseSelectionManager(recyclerView, adapter) {

    private val trashAdapter: TrashAdapter? get() = adapter as? TrashAdapter

    override fun getItemCount(): Int = trashAdapter?.itemCount ?: 0

    override fun getPhotoAtPosition(position: Int): Photo? {
        return trashAdapter?.currentList?.getOrNull(position)
    }

    override fun notifyItemChanged(position: Int) {
        trashAdapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
    }
}