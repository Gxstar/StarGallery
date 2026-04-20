package com.gxstar.stargallery.ui.photos.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
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
    private val metadataScanner: MetadataScanner,
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
            val needsScan = metadataScanner.needsScan()
            when {
                needsScan -> {
                    _isInitialized.value = false
                    startScan()
                }
                scanPreferences.isScanCompleted -> {
                    _isInitialized.value = true
                    performIncrementalScan()
                }
                else -> {
                    scanPreferences.isScanCompleted = true
                    _isInitialized.value = true
                    performIncrementalScan()
                }
            }
        }
    }

    /**
     * 观察扫描状态
     */
    private fun observeScanState() {
        viewModelScope.launch {
            metadataScanner.scanState.collect { state ->
                _scanState.value = state

                if (state is MetadataScanner.ScanState.Completed) {
                    scanPreferences.isScanCompleted = true
                    scanPreferences.lastScanTime = System.currentTimeMillis()
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
            metadataScanner.performFullScan()
        }
    }

    /**
     * 执行增量扫描
     */
    fun performIncrementalScan() {
        viewModelScope.launch {
            metadataScanner.performIncrementalScan()
        }
    }

    /**
     * 强制重新扫描
     */
    fun forceRescan() {
        viewModelScope.launch {
            metadataScanner.performFullScan()
        }
    }
}
