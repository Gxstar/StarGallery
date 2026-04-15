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
     * 逻辑：优先 dateTaken，如果为 0 则使用 dateAdded * 1000
     * 增加 ID 降级确保排序结果稳定
     */
    fun sortPhotos(photos: List<Photo>, sortType: MediaRepository.SortType): List<Photo> {
        return when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> {
                photos.sortedWith(
                    compareByDescending<Photo> { 
                        // 即使 dateTaken 已经是计算过的，我们依然在这里加一层保险
                        if (it.dateTaken > 0) it.dateTaken else it.dateAdded * 1000L
                    }.thenByDescending { it.dateAdded }
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
