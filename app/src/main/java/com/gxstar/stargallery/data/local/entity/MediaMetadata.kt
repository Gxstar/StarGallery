package com.gxstar.stargallery.data.local.entity

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体元数据实体
 * 存储从 EXIF 和 MediaStore 提取的完整元数据
 * 
 * 日期字段说明：
 * - dateTakenOriginal: EXIF DateTimeOriginal，最准确的拍摄时间
 * - dateTaken: 用于排序的时间戳，优先 EXIF，降级 MediaStore
 * - dateAdded: MediaStore 添加时间（秒级）
 * - dateModified: 文件修改时间（秒级）
 */
@Entity(
    tableName = "media_metadata",
    indices = [
        Index(value = ["date_taken"], orders = [androidx.room.Index.Order.DESC]),
        Index(value = ["date_added"], orders = [androidx.room.Index.Order.DESC]),
        Index(value = ["bucket_id"]),
        Index(value = ["is_favorite"]),
        Index(value = ["mime_type"])
    ]
)
data class MediaMetadata(
    @PrimaryKey
    val id: Long,
    
    // 文件路径（存储为字符串，使用时转换为 Uri）
    @ColumnInfo(name = "uri")
    val uri: String,
    
    // MIME 类型
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    
    // ===== 日期相关字段 =====
    
    /**
     * EXIF DateTimeOriginal（毫秒时间戳）
     * 这是最准确的拍摄时间，从 EXIF 提取
     * 可能为 null（某些照片没有此标签）
     */
    @ColumnInfo(name = "date_taken_original")
    val dateTakenOriginal: Long?,
    
    /**
     * 用于排序的拍摄时间（毫秒时间戳）
     * 优先级：EXIF DateTimeOriginal > MediaStore DATE_TAKEN > 文件修改时间
     * 这是排序的主要依据
     */
    @ColumnInfo(name = "date_taken")
    val dateTaken: Long,
    
    /**
     * MediaStore 添加时间（秒级时间戳）
     * 照片被添加到媒体库的时间
     */
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,
    
    /**
     * 文件修改时间（秒级时间戳）
     */
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    
    // ===== 图片尺寸 =====
    
    @ColumnInfo(name = "width")
    val width: Int,
    
    @ColumnInfo(name = "height")
    val height: Int,
    
    @ColumnInfo(name = "orientation")
    val orientation: Int,
    
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    
    // ===== 相册信息 =====
    
    @ColumnInfo(name = "bucket_id")
    val bucketId: Long,
    
    @ColumnInfo(name = "bucket_name")
    val bucketName: String,
    
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    
    // ===== GPS 信息 =====
    
    @ColumnInfo(name = "latitude")
    val latitude: Double?,
    
    @ColumnInfo(name = "longitude")
    val longitude: Double?,
    
    @ColumnInfo(name = "altitude")
    val altitude: Double?,
    
    // ===== 相机信息 =====
    
    @ColumnInfo(name = "camera_make")
    val cameraMake: String?,
    
    @ColumnInfo(name = "camera_model")
    val cameraModel: String?,
    
    // ===== 拍摄参数 =====
    
    @ColumnInfo(name = "lens_model")
    val lensModel: String?,
    
    @ColumnInfo(name = "focal_length")
    val focalLength: String?,  // 如 "50mm"
    
    @ColumnInfo(name = "aperture")
    val aperture: String?,     // 如 "f/1.8"
    
    @ColumnInfo(name = "iso")
    val iso: Int?,
    
    @ColumnInfo(name = "exposure_time")
    val exposureTime: String?, // 如 "1/125"
    
    // ===== 元数据状态 =====
    
    /**
     * 最后扫描时间（毫秒时间戳）
     * 用于增量更新判断
     */
    @ColumnInfo(name = "date_scanned")
    val dateScanned: Long,
    
    /**
     * 是否为视频
     */
    @ColumnInfo(name = "is_video")
    val isVideo: Boolean = false
) {
    /**
     * 获取 Uri 对象
     */
    fun getUri(): Uri = Uri.parse(uri)
    
    /**
     * 是否为 GIF
     */
    val isGif: Boolean
        get() = mimeType == "image/gif"
    
    /**
     * 是否为 RAW 格式
     */
    val isRaw: Boolean
        get() = mimeType.startsWith("image/x-") && mimeType !in setOf(
            "image/x-icon",
            "image/x-ms-bmp",
            "image/x-png",
            "image/x-rgb",
            "image/x-xbitmap",
            "image/x-xpixmap"
        )
}
