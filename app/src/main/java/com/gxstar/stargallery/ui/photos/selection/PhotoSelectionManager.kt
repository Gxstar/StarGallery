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

    // 防止长按后点击同一 item 重复 toggle
    private var skipNextToggleForPhotoId: Long? = null

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
        skipNextToggleForPhotoId = null
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
        // 如果是长按后首次点击，跳过 toggle（已由 startDragSelection 处理）
        if (photo.id == skipNextToggleForPhotoId) {
            skipNextToggleForPhotoId = null
            return
        }
        dragSelectHelper.toggleSelection(photo)
    }

    /**
     * 开始拖动选择
     */
    fun startDragSelection(position: Int) {
        if (!_isSelectionMode.value) {
            enterSelectionMode()
        }
        // 校准位置，确保 idToPosition 映射正确
        val photo = dragSelectHelper.getPhotoAtPosition(position)
        if (photo != null) {
            val correctPosition = dragSelectHelper.findCorrectPosition(photo.id, position)
            // 标记长按后首次点击时跳过 toggle，避免 onClick 重复触发
            skipNextToggleForPhotoId = photo.id
            dragSelectHelper.startDragSelection(correctPosition)
        }
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
