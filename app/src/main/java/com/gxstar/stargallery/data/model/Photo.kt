package com.gxstar.stargallery.data.model

import android.net.Uri

data class Photo(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val bucketId: Long,
    val bucketName: String,
    val latitude: Double?,
    val longitude: Double?,
    val orientation: Int = 0,
    val isFavorite: Boolean = false
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isGif: Boolean
        get() = mimeType == "image/gif"

    /**
     * 判断是否为 RAW 格式图片
     * 常见 RAW 格式 MIME 类型：
     * - image/x-adobe-dng (DNG)
     * - image/x-sony-arw (ARW)
     * - image/x-canon-cr2, image/x-canon-cr3 (CR2/CR3)
     * - image/x-nikon-nef (NEF)
     * - image/x-olympus-orf (ORF)
     * - image/x-fuji-raf (RAF)
     * - image/x-panasonic-rw2 (RW2)
     * - image/x-pentax-pef (PEF)
     * - image/x-raw (通用 RAW)
     */
    val isRaw: Boolean
        get() = mimeType.startsWith("image/x-") && mimeType !in setOf(
            "image/x-icon",      // .ico 图标
            "image/x-ms-bmp",    // .bmp 位图
            "image/x-png",       // .png (某些系统可能用这个)
            "image/x-rgb",       // RGB 图像
            "image/x-xbitmap",   // XBM
            "image/x-xpixmap"    // XPM
        )
}
