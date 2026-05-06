package com.gxstar.stargallery.ui.trash

import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.data.model.Photo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrashSelectionManager(
    private var recyclerView: RecyclerView?,
    private var adapter: TrashAdapter?
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

            override fun isIndexSelectable(index: Int): Boolean = true

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

    private fun updateSelectionState() {
        _count.value = selectedPositions.size
        _isMode.value = selectedPositions.isNotEmpty()
    }

    val selectedPhotoIds: Set<Long>
        get() {
            val ids = mutableSetOf<Long>()
            selectedPositions.forEach { pos ->
                val photo = adapter?.currentList?.getOrNull(pos)
                if (photo != null) {
                    ids.add(photo.id)
                }
            }
            return ids
        }

    fun isInSelectionMode(): Boolean = _isMode.value

    fun enterSelectionMode() {
        _isMode.value = true
        refreshAllVisible()
    }

    fun exitSelectionMode() {
        val previousPositions = selectedPositions.toList()
        selectedPositions.clear()
        _count.value = 0
        _isMode.value = false
        previousPositions.forEach { pos ->
            adapter?.notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGED)
        }
    }

    private fun refreshAllVisible() {
        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, PAYLOAD_SELECTION_CHANGED)
    }

    fun toggleSelection(photo: Photo) {
        val position = findPositionByPhotoId(photo.id)
        if (position >= 0) {
            if (selectedPositions.contains(position)) {
                selectedPositions.remove(position)
            } else {
                selectedPositions.add(position)
            }
            adapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
            updateSelectionState()
        }
    }

    fun toggleSelection(photoId: Long) {
        val position = findPositionByPhotoId(photoId)
        if (position >= 0) {
            if (selectedPositions.contains(position)) {
                selectedPositions.remove(position)
            } else {
                selectedPositions.add(position)
            }
            adapter?.notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
            updateSelectionState()
        }
    }

    fun isSelected(id: Long): Boolean {
        val position = findPositionByPhotoId(id)
        return position >= 0 && selectedPositions.contains(position)
    }

    fun isSelectedPosition(position: Int): Boolean = selectedPositions.contains(position)

    fun startDragSelection(startPosition: Int) {
        if (!_isMode.value) {
            _isMode.value = true
            selectedPositions.add(startPosition)
            adapter?.notifyItemChanged(startPosition, PAYLOAD_SELECTION_CHANGED)
            updateSelectionState()
        }
        dragSelectListener?.setIsActive(true, startPosition)
    }

    private fun findPositionByPhotoId(id: Long): Int {
        val list = adapter?.currentList ?: return -1
        for (i in list.indices) {
            if (list[i].id == id) {
                return i
            }
        }
        return -1
    }

    fun clear() {
        dragSelectListener?.let { listener ->
            recyclerView?.removeOnItemTouchListener(listener)
        }
        selectedPositions.clear()
        adapter = null
        recyclerView?.adapter = null
        recyclerView = null
        dragSelectListener = null
    }

    companion object {
        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }
}
