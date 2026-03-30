package com.gxstar.stargallery.ui.photos

import android.net.Uri
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager

/**
 * Glide预加载ModelProvider
 * 用于RecyclerViewPreloader预加载图片
 */
class PhotoPreloadModelProvider(
    private val requestManager: RequestManager,
    private val getPreloadUris: () -> List<Uri>
) : ListPreloader.PreloadModelProvider<Uri> {

    override fun getPreloadItems(position: Int): MutableList<Uri> {
        val uris = getPreloadUris()
        return if (position >= 0 && position < uris.size) {
            mutableListOf(uris[position])
        } else {
            mutableListOf()
        }
    }

    override fun getPreloadRequestBuilder(item: Uri): RequestBuilder<*> {
        return requestManager
            .load(item)
            .centerCrop()
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
    }
}
