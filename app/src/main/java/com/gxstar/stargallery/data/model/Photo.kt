package com.gxstar.stargallery.data.model

import android.net.Uri

data class Photo(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val dateModified: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val bucketId: Long,
    val bucketName: String,
    val latitude: Double?,
    val longitude: Double?,
    val isFavorite: Boolean = false
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
    
    val isImage: Boolean
        get() = mimeType.startsWith("image/")
}
