package com.gxstar.stargallery.ui.photos.selection

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.ui.common.DragSelectHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 照片选择模式管理器
 * 封装选择状态、拖动选择逻辑和回调
 */
class PhotoSelectionManager(
    private val fragment: Fragment,
    private val photoProvider: DragSelectHelper.PhotoProvider
) {
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    private val dragSelectHelper: DragSelectHelper = DragSelectHelper(photoProvider) { count ->
        _selectedCount.value = count
        if (count == 0) {
            exitSelectionMode()
        }
    }

    val selectedPhotoIds: Set<Long> get() = dragSelectHelper.selectedPhotoIds
    val dragSelectTouchListener get() = dragSelectHelper.createTouchListener(fragment.requireContext())

    /**
     * 进入选择模式
     */
    fun enterSelectionMode() {
        _isSelectionMode.value = true
        _selectedCount.value = 0
    }

    /**
     * 退出选择模式
     */
    fun exitSelectionMode() {
        _isSelectionMode.value = false
        dragSelectHelper.clearSelection()
        _selectedCount.value = 0
    }

    /**
     * 切换选择模式
     */
    fun toggleSelectionMode() {
        if (_isSelectionMode.value) {
            exitSelectionMode()
        } else {
            enterSelectionMode()
        }
    }

    /**
     * 切换单个照片的选中状态
     */
    fun toggleSelection(photo: Photo) {
        dragSelectHelper.toggleSelection(photo)
    }

    /**
     * 开始拖动选择
     */
    fun startDragSelection(position: Int) {
        if (!_isSelectionMode.value) {
            enterSelectionMode()
        }
        dragSelectHelper.startDragSelection(position)
    }

    /**
     * 检查照片是否被选中
     */
    fun isSelected(photoId: Long): Boolean = dragSelectHelper.isSelected(photoId)

    /**
     * 更新位置映射（数据变化后调用）
     */
    fun updatePositionMap() {
        dragSelectHelper.updatePositionMap()
    }

    /**
     * 全选当前可见项
     */
    fun selectAll(photos: List<Photo>) {
        fragment.lifecycleScope.launch {
            photos.forEach { photo ->
                if (!dragSelectHelper.isSelected(photo.id)) {
                    dragSelectHelper.toggleSelection(photo)
                }
            }
        }
    }

    /**
     * 获取照片位置
     */
    fun getPosition(photoId: Long): Int? = dragSelectHelper.getPosition(photoId)

    fun onDestroy() {
        exitSelectionMode()
    }
}
