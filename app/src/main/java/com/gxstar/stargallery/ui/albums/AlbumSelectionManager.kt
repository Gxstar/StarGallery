package com.gxstar.stargallery.ui.albums

import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlbumSelectionManager(
    private var recyclerView: RecyclerView?,
    private var adapter: AlbumDetailAdapter?
) {
    private val selectedPositions = mutableSetOf<Int>()
    private var dragSelectListener: DragSelectTouchListener? = null

    private val _isMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isMode.asStateFlow()

    private val _count = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _count.asStateFlow()

    fun init() {
        val receiver = object : DragSelectReceiver {
            override fun getItemCount(): Int = adapter?.itemCount ?: 0

            override fun isSelected(index: Int): Boolean = selectedPositions.contains(index)

            override fun isIndexSelectable(index: Int): Boolean {
                val item = adapter?.currentList?.getOrNull(index)
                return item is PhotoModel.PhotoItem
            }

            override fun setSelected(index: Int, selected: Boolean) {
                if (selected) {
                    selectedPositions.add(index)
                } else {
                    selectedPositions.remove(index)
                }
                adapter?.notifyItemChanged(index, PAYLOAD_SELECTION_CHANGED)
                updateSelectionState()
            }
        }

        dragSelectListener = DragSelectTouchListener.create(recyclerView!!.context, receiver)
        recyclerView?.addOnItemTouchListener(dragSelectListener!!)
    }

    fun startDragSelection(startPosition: Int) {
        if (!_isMode.value) {
            _isMode.value = true
            selectedPositions.add(startPosition)
            adapter?.notifyItemChanged(startPosition, PAYLOAD_SELECTION_CHANGED)
            updateSelectionState()
        }
        dragSelectListener?.setIsActive(true, startPosition)
    }

    fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        adapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
        updateSelectionState()
    }

    fun isInSelectionMode(): Boolean = _isMode.value

    fun isSelectedPosition(position: Int): Boolean = selectedPositions.contains(position)

    fun getSelectedPhotoIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        val snapshot = adapter?.currentList ?: return ids
        selectedPositions.forEach { position ->
            val item = snapshot.getOrNull(position)
            if (item is PhotoModel.PhotoItem) {
                ids.add(item.photo.id)
            }
        }
        return ids
    }

    fun getSelectedPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val snapshot = adapter?.currentList ?: return photos
        selectedPositions.forEach { position ->
            val item = snapshot.getOrNull(position)
            if (item is PhotoModel.PhotoItem) {
                photos.add(item.photo)
            }
        }
        return photos
    }

    fun exitSelectionMode() {
        val previousPositions = selectedPositions.toList()
        selectedPositions.clear()
        _isMode.value = false
        _count.value = 0
        previousPositions.forEach { position ->
            adapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
        }
    }

    private fun updateSelectionState() {
        _count.value = selectedPositions.size
        _isMode.value = selectedPositions.isNotEmpty()
    }

    fun clear() {
        dragSelectListener?.let { listener ->
            recyclerView?.removeOnItemTouchListener(listener)
        }
        adapter = null
        recyclerView?.adapter = null
        recyclerView = null
        dragSelectListener = null
        selectedPositions.clear()
    }

    companion object {
        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }
}
