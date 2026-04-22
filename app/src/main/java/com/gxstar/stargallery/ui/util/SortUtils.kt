package com.gxstar.stargallery.ui.util

import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository

/**
 * 统一排序工具类
 * 确保全项排序逻辑一致：优先拍摄时间，降级添加时间
 */
object SortUtils {

    /**
     * 对照片列表进行稳健排序
     * 使用 Photo.normalizedDateTaken 统一处理 dateTaken fallback
     */
    fun sortPhotos(photos: List<Photo>, sortType: MediaRepository.SortType): List<Photo> {
        return when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> {
                photos.sortedWith(
                    compareByDescending<Photo> { it.normalizedDateTaken }
                        .thenByDescending { it.dateAdded }
                        .thenByDescending { it.id }
                )
            }
            MediaRepository.SortType.DATE_ADDED -> {
                photos.sortedWith(
                    compareByDescending<Photo> { it.dateAdded }
                        .thenByDescending { it.id }
                )
            }
        }
    }
}
