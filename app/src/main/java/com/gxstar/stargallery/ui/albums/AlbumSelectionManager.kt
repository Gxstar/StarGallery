package com.gxstar.stargallery.ui.albums

import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.albums.AlbumDetailAdapter
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.common.BaseSelectionManager

class AlbumSelectionManager(
    recyclerView: RecyclerView?,
    adapter: AlbumDetailAdapter?
) : BaseSelectionManager(recyclerView, adapter) {

    private val albumAdapter: AlbumDetailAdapter? get() = adapter as? AlbumDetailAdapter

    override fun getItemCount(): Int = albumAdapter?.itemCount ?: 0

    override fun getPhotoAtPosition(position: Int): Photo? {
        val item = albumAdapter?.currentList?.getOrNull(position)
        return (item as? PhotoModel.PhotoItem)?.photo
    }

    override fun isPositionSelectable(position: Int): Boolean {
        val item = albumAdapter?.currentList?.getOrNull(position)
        return item is PhotoModel.PhotoItem
    }

    override fun notifyItemChanged(position: Int) {
        albumAdapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
    }
}