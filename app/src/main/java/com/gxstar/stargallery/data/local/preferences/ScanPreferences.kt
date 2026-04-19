package com.gxstar.stargallery.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 扫描状态偏好设置
 * 用于跟踪首次扫描状态
 */
@Singleton
class ScanPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("scan_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_SCAN_COMPLETED = "scan_completed"
        private const val KEY_LAST_SCAN_TIME = "last_scan_time"
        private const val KEY_LAST_MEDIA_COUNT = "last_media_count"
        private const val KEY_INCREMENTAL_SINCE_DELETION_CHECK = "incremental_since_deletion_check"
        private const val DELETION_CHECK_INTERVAL = 50 // 每 50 次增量扫描检查一次删除
    }

    /**
     * 是否已完成首次扫描
     */
    var isScanCompleted: Boolean
        get() = prefs.getBoolean(KEY_SCAN_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCAN_COMPLETED, value).apply()

    /**
     * 最后扫描时间（毫秒时间戳）
     */
    var lastScanTime: Long
        get() = prefs.getLong(KEY_LAST_SCAN_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SCAN_TIME, value).apply()

    /**
     * 最后扫描的媒体数量
     */
    var lastMediaCount: Int
        get() = prefs.getInt(KEY_LAST_MEDIA_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_MEDIA_COUNT, value).apply()

    /**
     * 上次删除检查后的增量扫描次数
     */
    var incrementalSinceDeletionCheck: Int
        get() = prefs.getInt(KEY_INCREMENTAL_SINCE_DELETION_CHECK, 0)
        set(value) = prefs.edit().putInt(KEY_INCREMENTAL_SINCE_DELETION_CHECK, value).apply()

    /**
     * 是否需要检查删除
     */
    fun needsDeletionCheck(): Boolean {
        return incrementalSinceDeletionCheck >= DELETION_CHECK_INTERVAL
    }

    /**
     * 重置增量扫描计数
     */
    fun resetIncrementalCounter() {
        incrementalSinceDeletionCheck = 0
    }
    
    /**
     * 重置扫描状态（用于调试）
     */
    fun reset() {
        prefs.edit().clear().apply()
    }
}
