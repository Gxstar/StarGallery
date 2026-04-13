package com.gxstar.stargallery.data.local.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.drew.imaging.ImageMetadataReader
import com.drew.lang.GeoLocation
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.file.FileSystemDirectory
import com.gxstar.stargallery.data.local.entity.MediaMetadata
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EXIF 元数据提取器
 * 使用 metadata-extractor 库读取图片的 EXIF 信息
 */
object ExifExtractor {
    
    private const val TAG = "ExifExtractor"
    
    // EXIF 日期格式
    private val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    
    /**
     * 提取结果
     */
    data class ExifResult(
        val dateTakenOriginal: Long? = null,  // EXIF DateTimeOriginal
        val dateTaken: Long? = null,          // 用于排序的时间
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,
        val cameraMake: String? = null,
        val cameraModel: String? = null,
        val lensModel: String? = null,
        val focalLength: String? = null,
        val aperture: String? = null,
        val iso: Int? = null,
        val exposureTime: String? = null,
        val orientation: Int = 0,
        val width: Int = 0,
        val height: Int = 0
    )
    
    /**
     * 从图片 Uri 提取 EXIF 信息
     */
    fun extract(context: Context, uri: Uri): ExifResult {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                extractFromInputStream(inputStream)
            } ?: ExifResult()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract EXIF from $uri: ${e.message}")
            ExifResult()
        }
    }
    
    /**
     * 从 InputStream 提取 EXIF 信息
     */
    private fun extractFromInputStream(inputStream: InputStream): ExifResult {
        return try {
            val metadata = ImageMetadataReader.readMetadata(inputStream)
            parseMetadata(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse EXIF: ${e.message}")
            ExifResult()
        }
    }
    
    /**
     * 解析元数据
     */
    private fun parseMetadata(metadata: Metadata): ExifResult {
        var dateTakenOriginal: Long? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var altitude: Double? = null
        var cameraMake: String? = null
        var cameraModel: String? = null
        var lensModel: String? = null
        var focalLength: String? = null
        var aperture: String? = null
        var iso: Int? = null
        var exposureTime: String? = null
        var orientation = 0
        var width = 0
        var height = 0
        
        // 提取拍摄日期
        val exifSubIFD = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        exifSubIFD?.let { dir ->
            // DateTimeOriginal - 最准确的拍摄时间
            dateTakenOriginal = parseExifDate(dir.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
                ?: parseExifDate(dir.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED))
            
            // 拍摄参数
            lensModel = dir.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)
            
            focalLength = dir.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
                ?.let { formatFocalLength(it) }
            
            aperture = dir.getDescription(ExifSubIFDDirectory.TAG_FNUMBER)
                ?.let { formatAperture(it) }
                ?: dir.getDescription(ExifSubIFDDirectory.TAG_APERTURE)
                    ?.let { formatAperture(it) }
            
            iso = dir.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)
            
            exposureTime = dir.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
                ?.let { formatExposureTime(it) }
            
            // 图像尺寸
            width = dir.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH) ?: 0
            height = dir.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT) ?: 0
            
            // 方向
            orientation = dir.getInteger(ExifSubIFDDirectory.TAG_ORIENTATION) ?: 0
        }
        
        // 提取相机信息
        val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        exifIFD0?.let { dir ->
            if (cameraMake == null) cameraMake = dir.getString(ExifIFD0Directory.TAG_MAKE)
            if (cameraModel == null) cameraModel = dir.getString(ExifIFD0Directory.TAG_MODEL)
            if (orientation == 0) orientation = dir.getInteger(ExifIFD0Directory.TAG_ORIENTATION) ?: 0
            if (width == 0) width = dir.getInteger(ExifIFD0Directory.TAG_IMAGE_WIDTH) ?: 0
            if (height == 0) height = dir.getInteger(ExifIFD0Directory.TAG_IMAGE_HEIGHT) ?: 0
        }
        
        // 提取 GPS 信息
        val gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
        gpsDirectory?.let { dir ->
            val geoLocation: GeoLocation? = dir.getGeoLocation()
            if (geoLocation != null && !geoLocation.isZero) {
                latitude = geoLocation.latitude
                longitude = geoLocation.longitude
            }
            altitude = dir.getDoubleObject(GpsDirectory.TAG_ALTITUDE)?.let {
                val ref = dir.getString(GpsDirectory.TAG_ALTITUDE_REF)
                // 如果 ref 是 "1" 表示海拔以下，需要取反
                if (ref != null && ref.isNotEmpty() && ref[0] == '1') -it else it
            }
        }
        
        // 如果没有从 EXIF 获取到宽度高度，尝试从 FileSystemDirectory 获取
        if (width == 0 || height == 0) {
            val fileDir = metadata.getFirstDirectoryOfType(FileSystemDirectory::class.java)
            // 文件系统目录通常没有尺寸信息，这里只是占位
        }
        
        return ExifResult(
            dateTakenOriginal = dateTakenOriginal,
            dateTaken = dateTakenOriginal, // 优先使用 EXIF 时间
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            cameraMake = cameraMake?.trim(),
            cameraModel = cameraModel?.trim(),
            lensModel = lensModel?.trim(),
            focalLength = focalLength,
            aperture = aperture,
            iso = iso,
            exposureTime = exposureTime,
            orientation = orientation,
            width = width,
            height = height
        )
    }
    
    /**
     * 解析 EXIF 日期字符串
     * 格式通常为 "yyyy:MM:dd HH:mm:ss"
     */
    private fun parseExifDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            val date = exifDateFormat.parse(dateStr)
            date?.time
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse EXIF date: $dateStr")
            null
        }
    }
    
    /**
     * 格式化焦距
     */
    private fun formatFocalLength(value: String): String {
        // 提取数字部分，如 "50.0 mm" -> "50mm"
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(value)
        return match?.let { "${it.groupValues[1].removeSuffix(".0")}mm" } ?: value
    }
    
    /**
     * 格式化光圈值
     */
    private fun formatAperture(value: String): String {
        // 提取数字部分，如 "f/1.8" 或 "F1.8"
        val match = Regex("[fF]/?(\\d+(?:\\.\\d+)?)").find(value)
        return match?.let { "f/${it.groupValues[1]}" } ?: value
    }
    
    /**
     * 格式化曝光时间
     */
    private fun formatExposureTime(value: String): String {
        // 保持原格式，如 "1/125" 或 "0.008"
        return value
    }
    
    /**
     * 计算最终的拍摄时间
     * 优先级：EXIF DateTimeOriginal > MediaStore DATE_TAKEN > 文件修改时间
     * 
     * @param exifTime EXIF DateTimeOriginal (毫秒)
     * @param mediaStoreDateTaken MediaStore DATE_TAKEN (毫秒)
     * @param dateModified 文件修改时间 (秒，需转换)
     */
    fun calculateDateTaken(
        exifTime: Long?,
        mediaStoreDateTaken: Long,
        dateModified: Long
    ): Long {
        return when {
            exifTime != null && exifTime > 0 -> exifTime
            mediaStoreDateTaken > 0 -> mediaStoreDateTaken
            else -> dateModified * 1000L
        }
    }
    
    /**
     * 将 MediaMetadata 转换为 Photo 模型所需的属性
     */
    fun toPhotoProperties(metadata: MediaMetadata): Triple<Int, Int, Int> {
        return Triple(metadata.width, metadata.height, metadata.orientation)
    }
}
