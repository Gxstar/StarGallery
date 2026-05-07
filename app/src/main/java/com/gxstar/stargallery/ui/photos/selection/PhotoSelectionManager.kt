package com.gxstar.stargallery.ui.photos.selection

import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.photos.PhotoListAdapter
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.common.BaseSelectionManager

class PhotoSelectionManager(
    recyclerView: RecyclerView?,
    adapter: PhotoListAdapter?
) : BaseSelectionManager(recyclerView, adapter) {

    private val photoAdapter: PhotoListAdapter? get() = adapter as? PhotoListAdapter

    override fun getItemCount(): Int = photoAdapter?.itemCount ?: 0

    override fun getPhotoAtPosition(position: Int): Photo? {
        val item = photoAdapter?.snapshot()?.getOrNull(position)
        return (item as? PhotoModel.PhotoItem)?.photo
    }

    override fun isPositionSelectable(position: Int): Boolean {
        val item = photoAdapter?.snapshot()?.getOrNull(position)
        return item is PhotoModel.PhotoItem
    }

    override fun notifyItemChanged(position: Int) {
        photoAdapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
    }
}