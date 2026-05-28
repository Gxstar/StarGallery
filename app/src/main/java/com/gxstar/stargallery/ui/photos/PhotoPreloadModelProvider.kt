package com.gxstar.stargallery.ui.photos

import android.net.Uri
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import java.io.File

/**
 * Glide预加载ModelProvider
 * 用于RecyclerViewPreloader预加载图片
 * 优化：通过回调获取指定位置的图片，避免直接持有 adapter 引用
 */
class PhotoPreloadModelProvider(
    private val requestManager: RequestManager,
    private val getItemAt: (Int) -> PhotoModel.PhotoItem?,
    private val itemSize: Int
) : ListPreloader.PreloadModelProvider<Any> {

    override fun getPreloadItems(position: Int): MutableList<Any> {
        val item = getItemAt(position) ?: return mutableListOf()
        val thumbFile = item.photo.thumbnailPath?.let { File(it) }
        return mutableListOf(
            if (thumbFile?.exists() == true) thumbFile else item.photo.uri
        )
    }

    override fun getPreloadRequestBuilder(item: Any): RequestBuilder<*> {
        return requestManager
            .load(item)
            .centerCrop()
            .override(itemSize, itemSize)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
    }
}