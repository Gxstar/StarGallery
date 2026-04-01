package com.gxstar.stargallery.ui.util

import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.GroupType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期格式化工具类
 */
object DateUtils {

    /**
     * 获取照片的有效时间戳（毫秒）
     * 优先使用拍摄时间，若为空则使用添加时间
     */
    fun getTimestampMillis(photo: Photo, sortType: MediaRepository.SortType): Long {
        return when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> {
                if (photo.dateTaken > 0) photo.dateTaken 
                else photo.dateAdded * 1000L
            }
            MediaRepository.SortType.DATE_ADDED -> photo.dateAdded * 1000L
        }
    }

    /**
     * 格式化日期文本（用于列表分组标题）
     * 支持按日/月/年分组，自动识别"今天"、"昨天"
     */
    fun formatDateText(
        photo: Photo, 
        sortType: MediaRepository.SortType, 
        groupType: GroupType
    ): String {
        val timestampMillis = getTimestampMillis(photo, sortType)
        val date = Date(timestampMillis)
        
        val formatStr = when (groupType) {
            GroupType.DAY -> "yyyy年M月d日"
            GroupType.MONTH -> "yyyy年M月"
            GroupType.YEAR -> "yyyy年"
        }
        
        val dateFormat = SimpleDateFormat(formatStr, Locale.CHINA)
        val dateStr = dateFormat.format(date)
        
        // 只有选择"按日分组"时，才进行"今天"、"昨天"判断
        if (groupType == GroupType.DAY) {
            val calendar = Calendar.getInstance()
            val todayStr = dateFormat.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val yesterdayStr = dateFormat.format(calendar.time)
            
            return when (dateStr) {
                todayStr -> "今天"
                yesterdayStr -> "昨天"
                else -> dateStr
            }
        }
        
        return dateStr
    }

    /**
     * 格式化日期（yyyy年M月d日）
     */
    fun formatDate(timestampMillis: Long): String {
        val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        return dateFormat.format(Date(timestampMillis))
    }

    /**
     * 格式化时间（HH:mm）
     */
    fun formatTime(timestampMillis: Long): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
        return timeFormat.format(Date(timestampMillis))
    }

    /**
     * 格式化日期和时间（yyyy年M月d日 HH:mm）
     */
    fun formatDateTime(timestampMillis: Long): String {
        val format = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)
        return format.format(Date(timestampMillis))
    }
}
