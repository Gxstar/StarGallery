package com.gxstar.stargallery.ui.common

import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.data.model.Photo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base selection manager for photo grids.
 * Handles drag-to-select, toggle selection, and selection state tracking.
 *
 * Subclasses must provide [getItemAtPosition] to access items by position
 * and [getItemCount] for total count.
 */
abstract class BaseSelectionManager(
    protected var recyclerView: RecyclerView?,
    protected var adapter: RecyclerView.Adapter<*>?
) {
    protected val _selectedPhotoIds = mutableSetOf<Long>()
    protected var dragSelectListener: DragSelectTouchListener? = null

    private val _isMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isMode.asStateFlow()

    private val _count = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _count.asStateFlow()

    /** Get item count from adapter */
    protected abstract fun getItemCount(): Int

    /** Get photo at position, returns null if not available or not a photo item */
    protected abstract fun getPhotoAtPosition(position: Int): Photo?

    /** Check if position is selectable (e.g., not a header item) */
    protected open fun isPositionSelectable(position: Int): Boolean = true

    /** Refresh item at position (with selection payload) */
    protected abstract fun notifyItemChanged(position: Int)

    /** Refresh all visible items */
    protected open fun refreshAllVisible() {
        val count = getItemCount()
        if (count > 0) {
            adapter?.notifyItemRangeChanged(0, count, PAYLOAD_SELECTION_CHANGED)
        }
    }

    open fun init() {
        val receiver = object : DragSelectReceiver {
            override fun getItemCount(): Int = this@BaseSelectionManager.getItemCount()

            override fun isSelected(index: Int): Boolean {
                val photo = getPhotoAtPosition(index) ?: return false
                return _selectedPhotoIds.contains(photo.id)
            }

            override fun isIndexSelectable(index: Int): Boolean =
                this@BaseSelectionManager.isPositionSelectable(index)

            override fun setSelected(index: Int, selected: Boolean) {
                val photo = getPhotoAtPosition(index) ?: return
                if (selected) {
                    _selectedPhotoIds.add(photo.id)
                } else {
                    _selectedPhotoIds.remove(photo.id)
                }
                notifyItemChanged(index)
                updateSelectionState()
            }
        }

        dragSelectListener = DragSelectTouchListener.create(recyclerView!!.context, receiver)
        recyclerView?.addOnItemTouchListener(dragSelectListener!!)
    }

    private fun updateSelectionState() {
        _count.value = _selectedPhotoIds.size
        _isMode.value = _selectedPhotoIds.isNotEmpty()
    }

    open fun isInSelectionMode(): Boolean = _isMode.value

    open fun enterSelectionMode() {
        _isMode.value = true
        refreshAllVisible()
    }

    open fun exitSelectionMode() {
        _selectedPhotoIds.clear()
        _count.value = 0
        _isMode.value = false
        refreshAllVisible()
    }

    open fun toggleSelectionMode() {
        if (_isMode.value) exitSelectionMode() else enterSelectionMode()
    }

    open fun toggleSelection(photo: Photo) {
        if (_selectedPhotoIds.contains(photo.id)) {
            _selectedPhotoIds.remove(photo.id)
        } else {
            _selectedPhotoIds.add(photo.id)
        }
        val position = findPositionByPhotoId(photo.id)
        if (position >= 0) notifyItemChanged(position)
        updateSelectionState()
    }

    open fun toggleSelection(position: Int) {
        toggleSelectionAtPosition(position)
    }

    private fun toggleSelectionAtPosition(position: Int) {
        if (position < 0) return
        val photo = getPhotoAtPosition(position) ?: return
        if (_selectedPhotoIds.contains(photo.id)) {
            _selectedPhotoIds.remove(photo.id)
        } else {
            _selectedPhotoIds.add(photo.id)
        }
        notifyItemChanged(position)
        updateSelectionState()
    }

    open fun isSelected(id: Long): Boolean = _selectedPhotoIds.contains(id)

    open fun isSelectedPosition(position: Int): Boolean {
        val photo = getPhotoAtPosition(position) ?: return false
        return _selectedPhotoIds.contains(photo.id)
    }

    /** Get set of selected photo IDs */
    val selectedPhotoIds: Set<Long>
        get() = _selectedPhotoIds

    open fun getSelectedPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val count = getItemCount()
        for (i in 0 until count) {
            val photo = getPhotoAtPosition(i)
            if (photo != null && _selectedPhotoIds.contains(photo.id)) {
                photos.add(photo)
            }
        }
        return photos
    }

    open fun startDragSelection(startPosition: Int) {
        if (!_isMode.value) {
            _isMode.value = true
            val photo = getPhotoAtPosition(startPosition)
            if (photo != null) _selectedPhotoIds.add(photo.id)
            notifyItemChanged(startPosition)
            updateSelectionState()
        }
        dragSelectListener?.setIsActive(true, startPosition)
    }

    protected fun findPositionByPhotoId(id: Long): Int {
        val count = getItemCount()
        for (i in 0 until count) {
            val photo = getPhotoAtPosition(i)
            if (photo?.id == id) {
                return i
            }
        }
        return -1
    }

    open fun clear() {
        dragSelectListener?.let { listener ->
            recyclerView?.removeOnItemTouchListener(listener)
        }
        _selectedPhotoIds.clear()
        adapter = null
        recyclerView?.adapter = null
        recyclerView = null
        dragSelectListener = null
    }

    companion object {
        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }
}