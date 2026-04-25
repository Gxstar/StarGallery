package com.gxstar.stargallery.ui.photos.selection

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.photos.PhotoModel
import com.gxstar.stargallery.ui.photos.PhotoPagingAdapter
import com.gxstar.stargallery.ui.photos.PhotoViewHolder
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
    private val recyclerView: RecyclerView,
    private val adapter: PhotoPagingAdapter
) {
    private var tracker: SelectionTracker<Long>? = null
    private var dragCtrl: DragController? = null
    
    private val _isMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isMode.asStateFlow()
    
    private val _count = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _count.asStateFlow()
    
    fun init() {
        tracker = SelectionTracker.Builder("ps", recyclerView, PhotoItemKeyProvider(adapter), PhotoItemDetailsLookup(recyclerView), StorageStrategy.createLongStorage())
            .withSelectionPredicate(object : SelectionTracker.SelectionPredicate<Long>() {
                override fun canSetStateForKey(k: Long, s: Boolean) = true
                override fun canSetStateAtPosition(p: Int, s: Boolean) = true
                override fun canSelectMultiple() = true
            }).build().also {
                it.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        val c = it.selection?.size() ?: 0
                        _count.value = c
                        _isMode.value = c > 0
                    }
                })
            }
        dragCtrl = DragController(recyclerView, tracker!!)
        recyclerView.addOnItemTouchListener(dragCtrl!!)
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
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION_CHANGED)
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
    
    fun startDragSelection(position: Int) {
        if (!_isMode.value) enterSelectionMode()
        val id = adapter.getPhotoKey(position)
        if (id != RecyclerView.NO_ID) tracker?.select(id)
        dragCtrl?.activate(position)
    }
    
    fun isSelected(id: Long) = tracker?.isSelected(id) ?: false
    fun getTracker() = tracker
}

@SuppressLint("ClickableViewAccessibility")
private class DragController(
    private val rv: RecyclerView,
    private val tracker: SelectionTracker<Long>
) : RecyclerView.OnItemTouchListener {
    private var active = false
    private var start = -1
    private var last = -1
    
    fun activate(pos: Int) { active = true; start = pos; last = pos }
    fun deactivate() { active = false; start = -1; last = -1 }
    
    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) = if (!active) false else when (e.actionMasked) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { deactivate(); false }
        else -> false
    }
    
    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> deactivate()
        }
    }
    
    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    
    private fun handleMove(e: MotionEvent) {
        val view = rv.findChildViewUnder(e.x, e.y) ?: return
        val holder = rv.getChildViewHolder(view) ?: return
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || pos == last) return
        val a = rv.adapter as? PhotoPagingAdapter ?: return
        val key = a.getPhotoKey(pos)
        if (key == RecyclerView.NO_ID) return
        if (pos > last) {
            if (!tracker.isSelected(key)) tracker.select(key)
        } else if (start >= pos) {
            tracker.deselect(key)
        }
        last = pos
    }
}
