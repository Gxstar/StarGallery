package com.gxstar.stargallery.ui.util

import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.photos.GroupType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

    fun getTimestampMillis(photo: Photo, sortType: MediaRepository.SortType): Long {
        return when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> {
                if (photo.dateTaken > 0) photo.dateTaken
                else photo.dateAdded * 1000L
            }
            MediaRepository.SortType.DATE_ADDED -> photo.dateAdded * 1000L
        }
    }

    fun formatDateText(
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

    private fun formatRelativeDay(timestampMillis: Long): String {
        val photoCalendar = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        val todayCalendar = Calendar.getInstance()

        val photoYear = photoCalendar.get(Calendar.YEAR)
        val photoDayOfYear = photoCalendar.get(Calendar.DAY_OF_YEAR)
        val todayYear = todayCalendar.get(Calendar.YEAR)
        val todayDayOfYear = todayCalendar.get(Calendar.DAY_OF_YEAR)

        val isChinese = Locale.getDefault().language == "zh"

        return when {
            photoYear == todayYear && photoDayOfYear == todayDayOfYear ->
                if (isChinese) "今天" else "Today"
            photoYear == todayYear && photoDayOfYear == todayDayOfYear - 1 ->
                if (isChinese) "昨天" else "Yesterday"
            photoYear == todayYear - 1 &&
                photoCalendar.get(Calendar.DAY_OF_YEAR) == photoCalendar.getActualMaximum(Calendar.DAY_OF_YEAR) &&
                todayDayOfYear == 1 ->
                if (isChinese) "昨天" else "Yesterday"
            else -> formatDate(timestampMillis)
        }
    }

    private fun formatMonth(timestampMillis: Long): String {
        val locale = Locale.getDefault()
        val sdf = if (locale.language == "zh") {
            SimpleDateFormat("yyyy年M月", locale)
        } else {
            SimpleDateFormat("MMMM yyyy", locale)
        }
        return sdf.format(Date(timestampMillis))
    }

    private fun formatYear(timestampMillis: Long): String {
        val locale = Locale.getDefault()
        val sdf = if (locale.language == "zh") {
            SimpleDateFormat("yyyy年", locale)
        } else {
            SimpleDateFormat("yyyy", locale)
        }
        return sdf.format(Date(timestampMillis))
    }

    fun formatDate(timestampMillis: Long): String {
        val locale = Locale.getDefault()
        val sdf = if (locale.language == "zh") {
            SimpleDateFormat("yyyy年M月d日", locale)
        } else {
            SimpleDateFormat("MMM d, yyyy", locale)
        }
        return sdf.format(Date(timestampMillis))
    }

    fun formatTime(timestampMillis: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))
    }

    fun formatDateTime(timestampMillis: Long): String {
        val locale = Locale.getDefault()
        val sdf = if (locale.language == "zh") {
            SimpleDateFormat("yyyy年M月d日 HH:mm", locale)
        } else {
            SimpleDateFormat("MMM d, yyyy HH:mm", locale)
        }
        return sdf.format(Date(timestampMillis))
    }
}
