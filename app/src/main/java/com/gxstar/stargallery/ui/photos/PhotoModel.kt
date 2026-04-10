package com.gxstar.stargallery.ui.photos

import com.gxstar.stargallery.data.model.Photo

/**
 * 用于RecyclerView的UI模型
 * 支持照片项和日期分隔符两种类型
 */
sealed class PhotoModel {
    /** 照片项 */
    data class PhotoItem(val photo: Photo) : PhotoModel()
    /** 日期分隔符 */
    data class SeparatorItem(val dateText: String) : PhotoModel()
}
