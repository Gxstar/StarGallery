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
    val isFavorite: Boolean = false,
    val displayName: String = "",  // 文件名（不含扩展名），用于同名合并
    val pairedRawId: Long? = null, // 关联的 RAW 文件 ID（如果存在同名 RAW）
    val hasRawPair: Boolean = false // 是否有配对的 RAW 文件（用于 UI 显示标签）
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
    
    val isImage: Boolean
        get() = mimeType.startsWith("image/")
    
    val isGif: Boolean
        get() = mimeType == "image/gif"
    
    /**
     * 判断是否为 RAW 格式照片
     * 常见 RAW 格式：DNG, ARW, CR2, CR3, NEF, ORF, RW2, RAF, SRW
     */
    val isRaw: Boolean
        get() = when (mimeType.lowercase()) {
            "image/x-adobe-dng", "image/dng" -> true
            "image/x-sony-arw" -> true
            "image/x-canon-cr2", "image/x-canon-cr3" -> true
            "image/x-nikon-nef" -> true
            "image/x-olympus-orf" -> true
            "image/x-panasonic-rw2" -> true
            "image/x-fuji-raf" -> true
            "image/x-samsung-srw" -> true
            "image/x-raw", "image/raw" -> true
            else -> false
        }
    
    /**
     * 获取格式标签文本
     * 例如："RAW"、"JPG+RAW"、"HEIF+RAW"
     */
    val formatTag: String
        get() {
            val baseFormat = getBaseFormatName()
            return if (pairedRawId != null) {
                "$baseFormat+RAW"
            } else if (isRaw) {
                "RAW"
            } else {
                ""
            }
        }
    
    /**
     * 获取基础格式名称
     */
    fun getBaseFormatName(): String {
        return when (mimeType.lowercase()) {
            "image/jpeg" -> "JPG"
            "image/png" -> "PNG"
            "image/heif", "image/heic" -> "HEIF"
            "image/webp" -> "WEBP"
            "image/gif" -> "GIF"
            else -> mimeType.substringAfter("/").uppercase()
        }
    }
}