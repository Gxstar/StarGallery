package com.gxstar.stargallery.ui.photos.refresh

import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.gxstar.stargallery.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体库变化检测器
 * 监听生命周期并自动检测 MediaStore 变化
 */
class MediaChangeDetector(
    private val lifecycleOwner: LifecycleOwner,
    private val mediaRepository: MediaRepository,
    private val sharedPreferences: SharedPreferences,
    private val onChangeDetected: () -> Unit
) : LifecycleEventObserver {

    private var checkJob: Job? = null
    private var lastKnownTimestamp: Long = 0
    private var isFirstResume = true

    companion object {
        private const val PREFS_KEY_LAST_TIMESTAMP = "last_media_timestamp"
        private const val DEBOUNCE_MS = 500L  // 防抖时间
    }

    init {
        lifecycleOwner.lifecycle.addObserver(this)
        // 恢复上次记录的时间戳
        lastKnownTimestamp = sharedPreferences.getLong(PREFS_KEY_LAST_TIMESTAMP, 0L)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> onResume()
            Lifecycle.Event.ON_PAUSE -> onPause()
            Lifecycle.Event.ON_DESTROY -> onDestroy()
            else -> {}
        }
    }

    /**
     * 在 onResume 时检测变化
     */
    private fun onResume() {
        if (isFirstResume) {
            // 首次启动，记录当前时间戳但不刷新
            isFirstResume = false
            lifecycleOwner.lifecycleScope.launch {
                lastKnownTimestamp = getLatestTimestamp()
                saveTimestamp(lastKnownTimestamp)
            }
        } else {
            // 从后台返回，检测是否有变化
            checkForChanges()
        }
    }

    private fun onPause() {
        checkJob?.cancel()
    }

    private fun onDestroy() {
        lifecycleOwner.lifecycle.removeObserver(this)
        checkJob?.cancel()
    }

    /**
     * 检测媒体库是否有变化
     */
    private fun checkForChanges() {
        checkJob?.cancel()
        checkJob = lifecycleOwner.lifecycleScope.launch {
            delay(DEBOUNCE_MS)  // 防抖

            val currentTimestamp = getLatestTimestamp()

            if (currentTimestamp > lastKnownTimestamp) {
                // 检测到新照片
                lastKnownTimestamp = currentTimestamp
                saveTimestamp(currentTimestamp)
                withContext(Dispatchers.Main) {
                    onChangeDetected()
                }
            }
        }
    }

    /**
     * 强制刷新时间戳记录
     */
    fun updateTimestamp() {
        lifecycleOwner.lifecycleScope.launch {
            lastKnownTimestamp = getLatestTimestamp()
            saveTimestamp(lastKnownTimestamp)
        }
    }

    /**
     * 重置检测状态（用于手动刷新后）
     */
    fun reset() {
        isFirstResume = false
        lifecycleOwner.lifecycleScope.launch {
            lastKnownTimestamp = getLatestTimestamp()
            saveTimestamp(lastKnownTimestamp)
        }
    }

    private suspend fun getLatestTimestamp(): Long = withContext(Dispatchers.IO) {
        mediaRepository.getLatestMediaTimestamp()
    }

    private fun saveTimestamp(timestamp: Long) {
        sharedPreferences.edit().putLong(PREFS_KEY_LAST_TIMESTAMP, timestamp).apply()
    }
}
