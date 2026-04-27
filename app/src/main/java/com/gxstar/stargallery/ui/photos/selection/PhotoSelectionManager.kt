package com.gxstar.stargallery.ui.photos.selection

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.photos.PhotoModel
import com.gxstar.stargallery.ui.photos.PhotoPagingAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhotoItemKeyProvider(
    private val adapter: PhotoPagingAdapter
) : ItemKeyProvider<Long>(SCOPE_MAPPED) {
    override fun getKey(position: Int): Long? = adapter.getPhotoKey(position)
    override fun getPosition(key: Long): Int = adapter.getPhotoPosition(key)
}

class PhotoItemDetailsLookup(
    private val recyclerView: RecyclerView
) : ItemDetailsLookup<Long>() {
    override fun getItemDetails(event: MotionEvent): ItemDetails<Long>? {
        val view = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
        val holder = recyclerView.getChildViewHolder(view) ?: return null
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return null
        val item = try { (recyclerView.adapter as? PhotoPagingAdapter)?.snapshot()?.getOrNull(pos) } catch (e: Exception) { null }
        if (item is PhotoModel.PhotoItem) {
            return object : ItemDetails<Long>() {
                override fun getPosition(): Int = pos
                override fun getSelectionKey(): Long? = item.photo.id
            }
        }
        return null
    }
}

class PhotoSelectionManager(
    private var recyclerView: RecyclerView?,
    private var adapter: PhotoPagingAdapter?
) {
    private var tracker: SelectionTracker<Long>? = null
    
    private val _isMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isMode.asStateFlow()
    
    private val _count = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _count.asStateFlow()
    
    fun init() {
        tracker = SelectionTracker.Builder("ps", recyclerView!!, PhotoItemKeyProvider(adapter!!), PhotoItemDetailsLookup(recyclerView!!), StorageStrategy.createLongStorage())
            .withSelectionPredicate(object : SelectionTracker.SelectionPredicate<Long>() {
                override fun canSetStateForKey(key: Long, nextState: Boolean): Boolean = true
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = true
                override fun canSelectMultiple(): Boolean = true
            }).build().also {
                it.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        val c = it.selection?.size() ?: 0
                        _count.value = c
                        _isMode.value = c > 0
                    }
                })
            }
    }
    
    val selectedPhotoIds: Set<Long> get() = tracker?.selection?.toSet() ?: emptySet()
    
    fun isInSelectionMode(): Boolean = _isMode.value
    
    fun enterSelectionMode() { 
        _isMode.value = true
        refreshAllVisible()
    }
    
    fun exitSelectionMode() {
        _isMode.value = false
        tracker?.clearSelection()
        _count.value = 0
        refreshAllVisible()
    }
    
    private fun refreshAllVisible() {
        // 刷新所有 item，确保包括预加载的 ViewHolder
        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, PAYLOAD_SELECTION_CHANGED)
    }
    
    companion object {
        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }
    
    fun toggleSelectionMode() {
        if (_isMode.value) exitSelectionMode() else enterSelectionMode()
    }
    
    fun toggleSelection(photo: Photo) {
        tracker?.let { t ->
            if (t.isSelected(photo.id)) t.deselect(photo.id) else t.select(photo.id)
        }
    }
    
    fun isSelected(id: Long) = tracker?.isSelected(id) ?: false
    fun getTracker() = tracker
    
    fun clear() {
        tracker?.clearSelection()
        tracker = null
        adapter = null
        recyclerView?.adapter = null
        recyclerView = null
    }
}
