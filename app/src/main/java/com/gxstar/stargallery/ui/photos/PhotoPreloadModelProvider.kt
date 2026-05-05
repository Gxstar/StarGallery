package com.gxstar.stargallery.ui.photos

import android.net.Uri
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.gxstar.stargallery.ui.photos.model.PhotoModel

/**
 * Glide预加载ModelProvider
 * 用于RecyclerViewPreloader预加载图片
 * 优化：通过回调获取指定位置的图片，避免直接持有 adapter 引用
 */
class PhotoPreloadModelProvider(
    private val requestManager: RequestManager,
    private val getItemAt: (Int) -> PhotoModel.PhotoItem?,
    private val itemSize: Int
) : ListPreloader.PreloadModelProvider<Uri> {

    override fun getPreloadItems(position: Int): MutableList<Uri> {
        val item = getItemAt(position)
        return if (item != null) {
            mutableListOf(item.photo.uri)
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