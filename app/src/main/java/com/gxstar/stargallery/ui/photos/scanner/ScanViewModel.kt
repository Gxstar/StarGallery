package com.gxstar.stargallery.ui.photos.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
import com.gxstar.stargallery.data.repository.MetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 扫描状态 ViewModel
 * 管理首次扫描流程
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val scanPreferences: ScanPreferences
) : ViewModel() {
    
    private val _scanState = MutableStateFlow<MetadataScanner.ScanState>(MetadataScanner.ScanState.Idle)
    val scanState: StateFlow<MetadataScanner.ScanState> = _scanState.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    init {
        observeScanState()
        checkInitialization()
    }
    
    /**
     * 检查是否需要扫描
     */
    private fun checkInitialization() {
        viewModelScope.launch {
            val needsScan = metadataRepository.needsScan()
            if (needsScan && !scanPreferences.isScanCompleted) {
                // 数据库为空且未完成首次扫描 → 全量扫描
                _isInitialized.value = false
                startScan()
            } else if (!needsScan && scanPreferences.isScanCompleted) {
                // 数据库已有数据且已完成首次扫描 → 执行增量扫描更新新增/修改的媒体
                _isInitialized.value = true
                performIncrementalScan()
            } else {
                // 首次扫描进行中或已完成
                _isInitialized.value = true
            }
        }
    }
    
    /**
     * 观察扫描状态
     */
    private fun observeScanState() {
        viewModelScope.launch {
            metadataRepository.getScanState().collect { state ->
                _scanState.value = state
                
                // 扫描完成时更新偏好设置
                if (state is MetadataScanner.ScanState.Completed) {
                    scanPreferences.isScanCompleted = true
                    scanPreferences.lastScanTime = System.currentTimeMillis()
                    scanPreferences.lastMediaCount = state.totalScanned
                    _isInitialized.value = true
                }
            }
        }
    }
    
    /**
     * 开始扫描
     */
    fun startScan() {
        viewModelScope.launch {
            metadataRepository.performFullScan()
        }
    }
    
    /**
     * 执行增量扫描
     */
    fun performIncrementalScan() {
        viewModelScope.launch {
            metadataRepository.performIncrementalScan()
        }
    }
    
    /**
     * 强制重新扫描
     */
    fun forceRescan() {
        viewModelScope.launch {
            metadataRepository.performFullScan()
        }
    }
}
