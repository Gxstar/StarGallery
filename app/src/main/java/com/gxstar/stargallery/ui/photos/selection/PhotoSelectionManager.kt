package com.gxstar.stargallery.ui.photos.selection

import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.photos.PhotoListAdapter
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhotoSelectionManager(
    private var recyclerView: RecyclerView?,
    private var adapter: PhotoListAdapter?
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
                val item = adapter?.snapshot()?.getOrNull(index)
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
    
    private fun updateSelectionState() {
        _count.value = selectedPositions.size
        _isMode.value = selectedPositions.isNotEmpty()
    }
    
    val selectedPhotoIds: Set<Long>
        get() {
            val ids = mutableSetOf<Long>()
            val snapshot = adapter?.snapshot() ?: return emptySet()
            selectedPositions.forEach { pos ->
                val item = snapshot.getOrNull(pos)
                if (item is PhotoModel.PhotoItem) {
                    ids.add(item.photo.id)
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
    
    fun toggleSelectionMode() {
        if (_isMode.value) exitSelectionMode() else enterSelectionMode()
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
        val snapshot = adapter?.snapshot() ?: return -1
        for (i in 0 until snapshot.size) {
            val item = snapshot[i]
            if (item is PhotoModel.PhotoItem && item.photo.id == id) {
                return i
            }
        }
        return -1
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
