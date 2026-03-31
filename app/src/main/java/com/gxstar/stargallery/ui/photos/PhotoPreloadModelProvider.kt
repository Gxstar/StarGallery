package com.gxstar.stargallery.ui.photos

import android.net.Uri
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager

/**
 * Glide预加载ModelProvider
 * 用于RecyclerViewPreloader预加载图片
 * 优化：直接从 adapter 获取指定范围的图片 URI
 */
class PhotoPreloadModelProvider(
    private val requestManager: RequestManager,
    private val adapter: PhotoPagingAdapter,
    private val itemSize: Int
) : ListPreloader.PreloadModelProvider<Uri> {

    override fun getPreloadItems(position: Int): MutableList<Uri> {
        // 直接从 adapter 获取指定位置的图片 URI
        val photo = adapter.getPhoto(position)
        return if (photo != null) {
            mutableListOf(photo.uri)
        } else {
            mutableListOf()
        }
    }

    override fun getPreloadRequestBuilder(item: Uri): RequestBuilder<*> {
        return requestManager
            .load(item)
            .centerCrop()
            .override(itemSize, itemSize)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
    }
}
