package com.gxstar.stargallery.ui.common

import android.content.Context
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.data.model.Photo

/**
 * 拖动多选辅助类
 * 封装了照片列表的长按拖动批量选择功能
 * 优化：增量更新位置映射，减少主线程阻塞
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
    
    // 用于增量更新：记录上次已知的项目数量
    private var lastKnownItemCount = 0

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
        val correctPosition = findCorrectPosition(photoProvider.getPhoto(position)?.id ?: -1, position)
        photoProvider.getPhoto(correctPosition)?.let { photo ->
            if (!selectedIds.contains(photo.id)) {
                selectedIds.add(photo.id)
                photoProvider.notifyItemNeedsUpdate(correctPosition)
                onSelectionChanged(selectedIds.size)
            }
        }
        touchListener?.setIsActive(true, correctPosition)
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
     * 优化：增量更新，只处理新增项
     */
    fun updatePositionMap() {
        val currentItemCount = photoProvider.getItemCount()
        
        // 如果项数相同，可能数据没变化，跳过更新
        if (currentItemCount == lastKnownItemCount && idToPosition.isNotEmpty()) {
            return
        }
        
        // 如果之前没有映射（首次加载），或者项数减少（数据被清空），则完全重建
        if (idToPosition.isEmpty() || currentItemCount < lastKnownItemCount) {
            idToPosition.clear()
            for (i in 0 until currentItemCount) {
                photoProvider.getPhoto(i)?.let { idToPosition[it.id] = i }
            }
        } else {
            // 增量更新：只处理新增的项
            for (i in lastKnownItemCount until currentItemCount) {
                photoProvider.getPhoto(i)?.let { idToPosition[it.id] = i }
            }
        }
        
        lastKnownItemCount = currentItemCount
    }
    
    /**
     * 强制完全重建位置映射
     * 用于数据完全刷新时
     */
    fun rebuildPositionMap() {
        idToPosition.clear()
        val currentItemCount = photoProvider.getItemCount()
        for (i in 0 until currentItemCount) {
            photoProvider.getPhoto(i)?.let { idToPosition[it.id] = i }
        }
        lastKnownItemCount = currentItemCount
    }

    /**
     * 获取照片的位置
     */
    fun getPosition(photoId: Long): Int? {
        // 先尝试从缓存获取
        idToPosition[photoId]?.let { cachedPosition ->
            // 验证缓存是否正确
            val cachedPhoto = photoProvider.getPhoto(cachedPosition)
            if (cachedPhoto?.id == photoId) {
                return cachedPosition
            }
        }
        // 缓存无效或不存在，遍历查找
        return findCorrectPosition(photoId)
    }

    /**
     * 获取指定位置的 Photo
     */
    fun getPhotoAtPosition(position: Int): Photo? {
        return photoProvider.getPhoto(position)
    }

    /**
     * 查找正确的位置（验证 idToPosition 缓存）
     */
    fun findCorrectPosition(photoId: Long, hintPosition: Int? = null): Int {
        // 如果有暗示位置，先验证暗示位置是否正确
        hintPosition?.let { hint ->
            val photoAtHint = photoProvider.getPhoto(hint)
            if (photoAtHint?.id == photoId) {
                return hint
            }
        }
        // 遍历查找正确的位置
        for (i in 0 until photoProvider.getItemCount()) {
            val photo = photoProvider.getPhoto(i)
            if (photo?.id == photoId) {
                // 更新缓存
                idToPosition[photoId] = i
                return i
            }
        }
        // 找不到返回暗示位置或 -1
        return hintPosition ?: -1
    }

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
