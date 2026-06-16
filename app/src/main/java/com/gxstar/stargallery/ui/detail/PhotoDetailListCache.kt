package com.gxstar.stargallery.ui.detail

import com.gxstar.stargallery.data.model.Photo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片详情页列表缓存
 *
 * 网格页（PhotosFragment / AlbumDetailFragment / HiddenFragment）在跳转到详情页前，
 * 把当前可见的已排序/过滤照片列表写入缓存。PhotoDetailViewModel 初始化时优先读取，
 * 从而避免详情页重新查询 Room、过滤、排序，实现打开即可左右滑动。
 *
 * 设计为一次性快照：take() 读取后立即清空，避免旧缓存影响下一次导航。
 */
@Singleton
class PhotoDetailListCache @Inject constructor() {
    @Volatile
    private var cachedPhotos: List<Photo>? = null

    /**
     * 写入当前可见照片列表
     */
    fun put(photos: List<Photo>) {
        cachedPhotos = photos
    }

    /**
     * 读取并清空缓存
     * @return 缓存的照片列表；若不存在或不包含指定照片 ID，返回 null
     */
    fun take(expectedPhotoId: Long): List<Photo>? {
        val photos = cachedPhotos
        cachedPhotos = null
        return photos?.takeIf { it.any { photo -> photo.id == expectedPhotoId } }
    }

    /**
     * 强制清空缓存
     */
    fun clear() {
        cachedPhotos = null
    }
}
