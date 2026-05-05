package com.gxstar.stargallery.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 简化版选择管理器
 * 用于 AlbumDetailFragment（不需要拖动选择功能）
 * 直接用 StateFlow 管理选择，无内存泄漏风险
 */
class PhotoSelectionTracker {
    private val _selectedIds = mutableSetOf<Long>()
    private val _isSelectionMode = MutableStateFlow(false)

    val selectedIds: Set<Long> get() = _selectedIds.toSet()
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()
    val selectedCount: StateFlow<Int> = MutableStateFlow(0).also {
        // Keep reference for external observation
    }

    private val _selectedCountFlow = MutableStateFlow(0)
    val selectedCountFlow: StateFlow<Int> = _selectedCountFlow.asStateFlow()

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.clear()
        _selectedCountFlow.value = 0
    }

    fun toggleSelection(photoId: Long) {
        if (_selectedIds.contains(photoId)) {
            _selectedIds.remove(photoId)
        } else {
            _selectedIds.add(photoId)
        }
        _selectedCountFlow.value = _selectedIds.size
        _isSelectionMode.value = _selectedIds.isNotEmpty()
    }

    fun select(photoId: Long) {
        _selectedIds.add(photoId)
        _selectedCountFlow.value = _selectedIds.size
        _isSelectionMode.value = true
    }

    fun deselect(photoId: Long) {
        _selectedIds.remove(photoId)
        _selectedCountFlow.value = _selectedIds.size
        _isSelectionMode.value = _selectedIds.isNotEmpty()
    }

    fun isSelected(photoId: Long): Boolean = _selectedIds.contains(photoId)

    fun toggleSelectionMode() {
        if (_isSelectionMode.value) exitSelectionMode() else enterSelectionMode()
    }

    fun clear() {
        _selectedIds.clear()
        _isSelectionMode.value = false
        _selectedCountFlow.value = 0
    }
}