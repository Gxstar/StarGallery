package com.gxstar.stargallery.ui.hidden

import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.common.BaseSelectionManager

class HiddenSelectionManager(
    recyclerView: RecyclerView?,
    adapter: HiddenAdapter?
) : BaseSelectionManager(recyclerView, adapter) {

    private val hiddenAdapter: HiddenAdapter? get() = adapter as? HiddenAdapter

    override fun getItemCount(): Int = hiddenAdapter?.itemCount ?: 0

    override fun getPhotoAtPosition(position: Int): Photo? {
        return hiddenAdapter?.currentList?.getOrNull(position)
    }

    override fun notifyItemChanged(position: Int) {
        hiddenAdapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
    }
}
