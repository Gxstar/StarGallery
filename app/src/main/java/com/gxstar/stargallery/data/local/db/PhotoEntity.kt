package com.gxstar.stargallery.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 数据库实体，对应 MediaStore 中的一张照片/视频
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey
    val id: Long,

    // MediaStore 基础字段
    val uri: String,
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

    // EXIF 扩展字段（扫描时填充）
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val isoEquivalent: Int? = null,
    val focalLength: Float? = null,
    val focalLength35mmEquiv: Int? = null,
    val fNumber: Float? = null,
    val shutterSpeed: Float? = null,
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null,
    val lut1: String? = null,
    val lut2: String? = null
)