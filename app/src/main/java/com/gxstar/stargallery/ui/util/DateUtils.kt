package com.gxstar.stargallery.ui.util

import android.content.Context
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.GroupType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期格式化工具类
 * 优化：使用日历精确判断日期分组，确保同一天的照片显示相同的日期标题
 */
object DateUtils {

    private val monthFormat by lazy { SimpleDateFormat("yyyy年M月", Locale.CHINA) }
    private val yearFormat by lazy { SimpleDateFormat("yyyy年", Locale.CHINA) }
    private val dateFormat by lazy { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }
    private val timeFormat by lazy { SimpleDateFormat("HH:mm", Locale.CHINA) }
    private val dateTimeFormat by lazy { SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA) }

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
     * 同一天的照片统一显示"今天"、"昨天"或具体日期
     */
    fun formatDateText(
        context: Context,
        photo: Photo, 
        sortType: MediaRepository.SortType, 
        groupType: GroupType
    ): String {
        val timestampMillis = getTimestampMillis(photo, sortType)
        
        return when (groupType) {
            GroupType.DAY -> formatRelativeDay(timestampMillis)
            GroupType.MONTH -> formatMonth(timestampMillis)
            GroupType.YEAR -> formatYear(timestampMillis)
        }
    }

    /**
     * 格式化相对日期（今天、昨天、或具体日期）
     * 使用日历精确判断日期，确保同一天的照片分组一致
     */
    private fun formatRelativeDay(timestampMillis: Long): String {
        val photoCalendar = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        val todayCalendar = Calendar.getInstance()
        
        // 比较年、月、日
        val photoYear = photoCalendar.get(Calendar.YEAR)
        val photoDayOfYear = photoCalendar.get(Calendar.DAY_OF_YEAR)
        val todayYear = todayCalendar.get(Calendar.YEAR)
        val todayDayOfYear = todayCalendar.get(Calendar.DAY_OF_YEAR)
        
        return when {
            photoYear == todayYear && photoDayOfYear == todayDayOfYear -> "今天"
            photoYear == todayYear && photoDayOfYear == todayDayOfYear - 1 -> "昨天"
            // 处理跨年情况（1月1日显示"昨天"）
            photoYear == todayYear - 1 && 
                photoCalendar.get(Calendar.DAY_OF_YEAR) == photoCalendar.getActualMaximum(Calendar.DAY_OF_YEAR) &&
                todayDayOfYear == 1 -> "昨天"
            else -> formatDate(timestampMillis)
        }
    }

    /**
     * 格式化月份（yyyy年M月）
     */
    private fun formatMonth(timestampMillis: Long): String = monthFormat.format(Date(timestampMillis))

    /**
     * 格式化年份（yyyy年）
     */
    private fun formatYear(timestampMillis: Long): String = yearFormat.format(Date(timestampMillis))

    /**
     * 格式化日期（yyyy年M月d日）
     */
    fun formatDate(timestampMillis: Long): String = dateFormat.format(Date(timestampMillis))

    /**
     * 格式化时间（HH:mm）
     */
    fun formatTime(timestampMillis: Long): String = timeFormat.format(Date(timestampMillis))

    /**
     * 格式化日期和时间（yyyy年M月d日 HH:mm）
     */
    fun formatDateTime(timestampMillis: Long): String = dateTimeFormat.format(Date(timestampMillis))
}