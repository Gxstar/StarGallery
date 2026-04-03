package com.gxstar.stargallery.ui.common

import android.content.Context
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.data.model.Photo

/**
 * 拖动多选辅助类
 * 封装了照片列表的长按拖动批量选择功能
 */
class DragSelectHelper(
    private val photoProvider: PhotoProvider,
    private val onSelectionChanged: (Int) -> Unit
) : DragSelectReceiver {

    /**
     * 照片提供者接口，由 Adapter 实现
     */
    interface PhotoProvider {
        fun getPhoto(position: Int): Photo?
        fun getItemCount(): Int
        /**
         * 通知指定位置的项需要更新（调用 Adapter 的 notifyItemChanged）
         */
        fun notifyItemNeedsUpdate(position: Int)
    }

    private val selectedIds = mutableSetOf<Long>()
    private val idToPosition = mutableMapOf<Long, Int>()
    private var touchListener: DragSelectTouchListener? = null

    val selectedPhotoIds: Set<Long> get() = selectedIds.toSet()
    val selectedCount: Int get() = selectedIds.size
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()

    /**
     * 创建拖动选择触摸监听器
     */
    fun createTouchListener(context: Context): DragSelectTouchListener {
        return DragSelectTouchListener.create(context, this) {
            hotspotHeight = dpToPx(context, 56)
        }.also { touchListener = it }
    }

    /**
     * 开始拖动选择
     */
    fun startDragSelection(position: Int) {
        photoProvider.getPhoto(position)?.let { photo ->
            if (!selectedIds.contains(photo.id)) {
                selectedIds.add(photo.id)
                photoProvider.notifyItemNeedsUpdate(position)
                onSelectionChanged(selectedIds.size)
            }
        }
        touchListener?.setIsActive(true, position)
    }

    /**
     * 切换单个照片的选中状态
     */
    fun toggleSelection(photo: Photo) {
        val position = idToPosition[photo.id] ?: return
        if (selectedIds.contains(photo.id)) {
            selectedIds.remove(photo.id)
        } else {
            selectedIds.add(photo.id)
        }
        photoProvider.notifyItemNeedsUpdate(position)
        onSelectionChanged(selectedIds.size)
    }

    /**
     * 清除所有选择
     */
    fun clearSelection() {
        touchListener?.setIsActive(false, -1)
        selectedIds.clear()
    }

    /**
     * 检查指定照片是否被选中
     */
    fun isSelected(photoId: Long): Boolean = selectedIds.contains(photoId)

    /**
     * 更新照片ID到位置的映射
     */
    fun updatePositionMap() {
        idToPosition.clear()
        for (i in 0 until photoProvider.getItemCount()) {
            photoProvider.getPhoto(i)?.let { idToPosition[it.id] = i }
        }
    }

    /**
     * 获取照片的位置
     */
    fun getPosition(photoId: Long): Int? = idToPosition[photoId]

    // ========== DragSelectReceiver 实现 ==========
    
    override fun setSelected(index: Int, selected: Boolean) {
        photoProvider.getPhoto(index)?.let { photo ->
            val wasSelected = selectedIds.contains(photo.id)
            if (selected && !wasSelected) {
                selectedIds.add(photo.id)
                photoProvider.notifyItemNeedsUpdate(index)
            } else if (!selected && wasSelected) {
                selectedIds.remove(photo.id)
                photoProvider.notifyItemNeedsUpdate(index)
            }
            onSelectionChanged(selectedIds.size)
        }
    }

    override fun isSelected(index: Int): Boolean =
        photoProvider.getPhoto(index)?.let { selectedIds.contains(it.id) } ?: false

    override fun isIndexSelectable(index: Int): Boolean =
        index >= 0 && index < photoProvider.getItemCount() && photoProvider.getPhoto(index) != null

    override fun getItemCount(): Int = photoProvider.getItemCount()

    companion object {
        fun dpToPx(context: Context, dp: Int): Int =
            (dp * context.resources.displayMetrics.density).toInt()
    }
}
