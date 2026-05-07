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
    protected var adapter: Any?
) {
    protected val selectedPositions = mutableSetOf<Int>()
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
            // Try with payload first
            try {
                val method = adapter?.javaClass?.getMethod(
                    "notifyItemRangeChanged",
                    Int::class.java,
                    Int::class.java,
                    String::class.java
                )
                method?.invoke(adapter, 0, count, PAYLOAD_SELECTION_CHANGED)
            } catch (e: Exception) {
                // Fallback: try without payload
                try {
                    val method = adapter?.javaClass?.getMethod(
                        "notifyItemRangeChanged",
                        Int::class.java,
                        Int::class.java
                    )
                    method?.invoke(adapter, 0, count)
                } catch (ignored: Exception) {
                    // Ignore
                }
            }
        }
    }

    open fun init() {
        val receiver = object : DragSelectReceiver {
            override fun getItemCount(): Int = this@BaseSelectionManager.getItemCount()

            override fun isSelected(index: Int): Boolean = selectedPositions.contains(index)

            override fun isIndexSelectable(index: Int): Boolean =
                this@BaseSelectionManager.isPositionSelectable(index)

            override fun setSelected(index: Int, selected: Boolean) {
                if (selected) {
                    selectedPositions.add(index)
                } else {
                    selectedPositions.remove(index)
                }
                notifyItemChanged(index)
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

    open fun isInSelectionMode(): Boolean = _isMode.value

    open fun enterSelectionMode() {
        _isMode.value = true
        refreshAllVisible()
    }

    open fun exitSelectionMode() {
        val previousPositions = selectedPositions.toList()
        selectedPositions.clear()
        _count.value = 0
        _isMode.value = false
        previousPositions.forEach { pos ->
            notifyItemChanged(pos)
        }
    }

    open fun toggleSelectionMode() {
        if (_isMode.value) exitSelectionMode() else enterSelectionMode()
    }

    open fun toggleSelection(photo: Photo) {
        val position = findPositionByPhotoId(photo.id)
        toggleSelectionAtPosition(position)
    }

    open fun toggleSelection(position: Int) {
        toggleSelectionAtPosition(position)
    }

    private fun toggleSelectionAtPosition(position: Int) {
        if (position < 0) return
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
        updateSelectionState()
    }

    open fun isSelected(id: Long): Boolean {
        val position = findPositionByPhotoId(id)
        return position >= 0 && selectedPositions.contains(position)
    }

    open fun isSelectedPosition(position: Int): Boolean = selectedPositions.contains(position)

    /** Get set of selected photo IDs */
    val selectedPhotoIds: Set<Long>
        get() {
            val ids = mutableSetOf<Long>()
            val count = getItemCount()
            for (i in 0 until count) {
                if (selectedPositions.contains(i)) {
                    getPhotoAtPosition(i)?.let { ids.add(it.id) }
                }
            }
            return ids
        }

    open fun getSelectedPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val count = getItemCount()
        for (i in 0 until count) {
            if (selectedPositions.contains(i)) {
                getPhotoAtPosition(i)?.let { photos.add(it) }
            }
        }
        return photos
    }

    open fun startDragSelection(startPosition: Int) {
        if (!_isMode.value) {
            _isMode.value = true
            selectedPositions.add(startPosition)
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