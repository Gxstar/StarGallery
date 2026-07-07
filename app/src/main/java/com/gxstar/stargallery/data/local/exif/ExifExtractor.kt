package com.gxstar.stargallery.data.local.exif

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.gxstar.stargallery.data.local.db.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EXIF 信息提取器
 * 将 EXIF 元数据提取并转换为 PhotoEntity 的 EXIF 字段
 * 参考 PhotoInfoBottomSheet 的提取逻辑
 */
@Singleton
class ExifExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 从 Uri 提取 EXIF 信息，返回包含 EXIF 字段的 PhotoEntity（仅 EXIF 字段有值）
     */
    suspend fun extractExif(uri: Uri): ExifData? = withContext(Dispatchers.IO) {
        try {
            // 使用 setRequireOriginal 请求未被红删（含 GPS）的原始文件
            // ACCESS_MEDIA_LOCATION 权限不足时抛出 SecurityException，降级到普通流
            val originalUri = try {
                MediaStore.setRequireOriginal(uri)
            } catch (_: Exception) {
                uri
            }

            // 1. 从实际文件获取真实宽高（BitmapFactory 仅解码边界，不加载像素）
            var realWidth: Int? = null
            var realHeight: Int? = null
            try {
                context.contentResolver.openInputStream(originalUri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        realWidth = opts.outWidth
                        realHeight = opts.outHeight
                    }
                }
            } catch (_: Exception) {}

            // 2. 从实际文件获取真实大小
            var fileSize: Long? = null
            try {
                context.contentResolver.openAssetFileDescriptor(originalUri, "r")?.use { fd ->
                    val len = fd.length
                    if (len > 0L) fileSize = len
                }
            } catch (_: Exception) {}

            // 3. 检测 Ultra HDR（读前 128KB 搜索 gainmap XMP 命名空间）
            val isHdr = detectUltraHdr(originalUri)

            // 4. 解析 EXIF 元数据
            val inputStream = context.contentResolver.openInputStream(originalUri)
            if (inputStream == null) {
                android.util.Log.w("ExifExtractor", "openInputStream returned null for $uri")
                return@withContext null
            }
            inputStream.use { stream ->
                val metadata = ImageMetadataReader.readMetadata(stream)
                val result = parseExifMetadata(metadata)
                android.util.Log.d("ExifExtractor", "EXIF parsed for $uri: $result")
                if (result.isAllNull()) {
                    android.util.Log.w("ExifExtractor", "All EXIF fields are null for $uri")
                    return@withContext null
                }
                return@withContext result.copy(
                    width = realWidth,
                    height = realHeight,
                    size = fileSize,
                    isHdr = isHdr
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ExifExtractor", "Failed to extract EXIF for $uri", e)
            return@withContext null
        }
    }

    private fun parseExifMetadata(metadata: Metadata): ExifData {
        val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val subIFD = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val panasonicMakernote = metadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java)
        val gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)

        // cameraMake - 清理特殊后缀
        val rawMake = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()
        val cameraMake = rawMake?.let { cleanCameraMake(it) }
        android.util.Log.v("ExifExtractor", "cameraMake: raw=$rawMake, cleaned=$cameraMake")

        // cameraModel
        val cameraModel = exifIFD0?.getString(ExifIFD0Directory.TAG_MODEL)?.trim()
        android.util.Log.v("ExifExtractor", "cameraModel: $cameraModel")

        // lensModel
        val lensModel = subIFD?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)?.trim()
            ?: panasonicMakernote?.getString(0x0051)?.trim()
        android.util.Log.v("ExifExtractor", "lensModel: $lensModel")

        // isoEquivalent — 优先用 TAG_ISO_SPEED (EXIF 2.3+)，fallback TAG_ISO_EQUIVALENT
        val isoEquivalent = subIFD?.getInteger(ExifSubIFDDirectory.TAG_ISO_SPEED)?.takeIf { it > 0 }
            ?: subIFD?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)?.takeIf { it > 0 }
        android.util.Log.v("ExifExtractor", "isoEquivalent: $isoEquivalent")

        // focalLength - 提取数字部分
        val focalLengthDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
        val focalLength = parseFocalLength(focalLengthDesc)
        android.util.Log.v("ExifExtractor", "focalLength: desc=$focalLengthDesc, parsed=$focalLength")

        // focalLength35mmEquiv
        val focalLength35mmEquiv = subIFD?.getInteger(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)?.takeIf { it > 0 }

        // fNumber - 提取数字部分
        val fNumberDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_FNUMBER)
        val fNumber = parseFNumber(fNumberDesc)
        android.util.Log.v("ExifExtractor", "fNumber: desc=$fNumberDesc, parsed=$fNumber")

        // shutterSpeed — 优先用曝光时间有理数（精确分数），降级 APEX 换算
        val shutterSpeed = parseShutterSpeed(subIFD)
        android.util.Log.v("ExifExtractor", "shutterSpeed: parsed=$shutterSpeed")

        // exifImageWidth / exifImageHeight
        val exifImageWidth = subIFD?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)?.takeIf { it > 0 }
        val exifImageHeight = subIFD?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)?.takeIf { it > 0 }

        // lut1 / lut2 (Panasonic) — 0x00F1/0x00F4 是 LUT 名称字符串，0x00F3/0x00F5 是 0-100 百分比透明度
        val lut1 = panasonicMakernote?.getString(0x00F1)?.trim()?.takeIf { it.isNotBlank() }
        val lut1opacity = panasonicMakernote?.getInteger(0x00F3)?.takeIf { it in 0..100 }
        val lut2 = panasonicMakernote?.getString(0x00F4)?.trim()?.takeIf { it.isNotBlank() }
        val lut2opacity = panasonicMakernote?.getInteger(0x00F5)?.takeIf { it in 0..100 }
        
        // photoStyle — 根据相机品牌选择对应映射
        val photoStyle = PhotoStyleResolver.resolve(cameraMake, cameraModel, metadata)

        // exposureCompensation
        val exposureCompensation = subIFD?.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_BIAS)
            ?.let { if (it.denominator != 0L) it.numerator.toFloat() / it.denominator.toFloat() else null }
            ?: subIFD?.getFloatObject(ExifSubIFDDirectory.TAG_EXPOSURE_BIAS)

        // meteringMode — 用 getDescription() 获取人类可读字符串
        val meteringMode = subIFD?.getDescription(ExifSubIFDDirectory.TAG_METERING_MODE)
            ?.trim()?.takeIf { it.isNotBlank() }

        // flash — TAG_FLASH 非 0 表示闪光灯触发
        val flashRaw = subIFD?.getInteger(ExifSubIFDDirectory.TAG_FLASH)
        val flash = when {
            flashRaw == null -> null
            flashRaw and 0x0001 != 0 -> true  // bit 0 set = flash fired
            else -> false
        }

        // GPS 坐标 — 使用 GeoLocation 自动处理 DMS→十进制转换和 N/S/E/W 方向
        val geoLocation = gpsDirectory?.getGeoLocation()
        val latitude = geoLocation?.takeIf { !it.isZero() }?.latitude
        val longitude = geoLocation?.takeIf { !it.isZero() }?.longitude
        android.util.Log.v("ExifExtractor", "GPS: lat=$latitude, lng=$longitude")

        // 拍摄日期 — 优先级：DateTimeOriginal → DateTimeDigitized → IFD0 DateTime
        // getDateOriginal() 使用 "yyyy:MM:dd HH:mm:ss"（EXIF 标准），
        // 部分软件写入横线格式，需降级到 getString() + 多格式手动解析
        val dateTimeOriginal = parseExifDate(subIFD?.getDateOriginal(), subIFD, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
        val dateTimeDigitized = parseExifDate(subIFD?.getDateDigitized(), subIFD, ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED)
        val ifd0DateTime = parseExifDate(exifIFD0?.getDate(ExifIFD0Directory.TAG_DATETIME), exifIFD0, ExifIFD0Directory.TAG_DATETIME)
        android.util.Log.v("ExifExtractor", "dates: original=$dateTimeOriginal digitized=$dateTimeDigitized ifd0=$ifd0DateTime")

        return ExifData(
            cameraMake = cameraMake,
            cameraModel = cameraModel,
            lensModel = lensModel,
            isoEquivalent = isoEquivalent,
            focalLength = focalLength,
            focalLength35mmEquiv = focalLength35mmEquiv,
            fNumber = fNumber,
            shutterSpeed = shutterSpeed,
            exifImageWidth = exifImageWidth,
            exifImageHeight = exifImageHeight,
            lut1 = lut1,
            lut1opacity = lut1opacity,
            lut2 = lut2,
            lut2opacity = lut2opacity,
            latitude = latitude,
            longitude = longitude,
            flash = flash,
            exposureCompensation = exposureCompensation,
            meteringMode = meteringMode,
            photoStyle = photoStyle,
            dateTimeOriginal = dateTimeOriginal,
            dateTimeDigitized = dateTimeDigitized,
            ifd0DateTime = ifd0DateTime
        )
    }

    /**
     * 清理相机品牌字符串
     * 移除常见后缀和前缀
     */
    private fun cleanCameraMake(make: String): String {
        return make
            .removeSuffix("CORPORATION").trim()
            .removeSuffix("CORP.").trim()
            .removeSuffix("CO., LTD").trim()
            .removeSuffix("CO.,LTD").trim()
            .removeSuffix("DIGITAL CAMERA").trim()
            .removeSuffix("ELECTRONICS").trim()
            .removePrefix("NIKON ").trim()
            .removePrefix("Canon ").trim()
            .removePrefix("SONY ").trim()
            .removePrefix("FUJIFILM ").trim()
            .takeIf { it.isNotBlank() } ?: make
    }

    /**
     * 解析 EXIF 拍摄日期（毫秒时间戳）
     * getDateOriginal() 只认 "yyyy:MM:dd HH:mm:ss"（冒号分隔）,
     * 遇到横线/斜杠格式会返回 null，降级到 getString() + 多格式手动解析
     */
    private fun parseExifDate(dateObj: java.util.Date?, dir: com.drew.metadata.Directory?, tag: Int): Long? {
        if (dateObj != null) return dateObj.time
        val raw = dir?.getString(tag)?.trim() ?: return null
        if (raw.isBlank()) return null
        val formats = arrayOf(
            "yyyy:MM:dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy:MM:dd",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).parse(raw)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * 从 focalLength 描述中提取数字（毫米）
     * 例如 "50.0 mm" -> 50.0f
     */
    private fun parseFocalLength(description: String?): Float? {
        if (description.isNullOrBlank()) return null
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(description)
        return match?.let {
            it.groupValues[1].toFloatOrNull()
        }
    }

    /**
     * 从 fNumber 描述中提取数字
     * 例如 "f/2.8" 或 "F2.8" -> 2.8f
     */
    private fun parseFNumber(description: String?): Float? {
        if (description.isNullOrBlank()) return null
        val match = Regex("[fF]/?(\\d+(?:\\.\\d+)?)").find(description)
        return match?.let {
            it.groupValues[1].toFloatOrNull()
        }
    }

    private fun parseShutterSpeed(subIFD: ExifSubIFDDirectory?): Float? {
        val rational = subIFD?.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
        if (rational != null && rational.denominator > 0L && rational.numerator > 0L) {
            return rational.numerator.toFloat() / rational.denominator.toFloat()
        }
        return null
    }

    /**
     * 检测 Ultra HDR — 读取 JPEG 文件前 128KB 搜索 gainmap XMP 命名空间
     * Google Ultra HDR 规范要求 XMP 中包含 "http://ns.adobe.com/hdr-gain-map/1.0/"
     */
    private fun detectUltraHdr(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(128 * 1024)
                val bytesRead = stream.read(buffer)
                bytesRead > 0 && String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    .contains("hdr-gain-map")
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * EXIF 提取结果数据类
     */
    data class ExifData(
        val width: Int? = null,
        val height: Int? = null,
        val size: Long? = null,
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
        val lut1opacity: Int? = null,
        val lut2: String? = null,
        val lut2opacity: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val flash: Boolean? = null,
        val exposureCompensation: Float? = null,
        val meteringMode: String? = null,
        val photoStyle: String? = null,
        val isHdr: Boolean = false,
        val dateTimeOriginal: Long? = null,
        val dateTimeDigitized: Long? = null,
        val ifd0DateTime: Long? = null
    ) {
        fun isAllNull(): Boolean {
            return cameraMake == null && cameraModel == null && lensModel == null &&
                    isoEquivalent == null && focalLength == null && focalLength35mmEquiv == null &&
                    fNumber == null && shutterSpeed == null && exifImageWidth == null &&
                    exifImageHeight == null && lut1 == null && lut1opacity == null &&
                    lut2 == null && lut2opacity == null &&
                    latitude == null && longitude == null && flash == null &&
                    exposureCompensation == null && meteringMode == null && photoStyle == null &&
                    dateTimeOriginal == null && dateTimeDigitized == null && ifd0DateTime == null
        }
    }

    companion object {
        /**
         * 将 ExifData 应用到 PhotoEntity，返回更新后的 PhotoEntity
         */
        fun applyToEntity(entity: PhotoEntity, exifData: ExifData): PhotoEntity {
            val dateTaken = resolveBestDate(exifData, entity.dateTaken)
            return entity.copy(
                width = exifData.width ?: entity.width,
                height = exifData.height ?: entity.height,
                size = exifData.size ?: entity.size,
                cameraMake = exifData.cameraMake,
                cameraModel = exifData.cameraModel,
                lensModel = exifData.lensModel,
                isoEquivalent = exifData.isoEquivalent,
                focalLength = exifData.focalLength,
                focalLength35mmEquiv = exifData.focalLength35mmEquiv,
                fNumber = exifData.fNumber,
                shutterSpeed = exifData.shutterSpeed,
                exifImageWidth = exifData.exifImageWidth,
                exifImageHeight = exifData.exifImageHeight,
                lut1 = exifData.lut1,
                lut1opacity = exifData.lut1opacity,
                lut2 = exifData.lut2,
                lut2opacity = exifData.lut2opacity,
                latitude = exifData.latitude,
                longitude = exifData.longitude,
                flash = exifData.flash,
                exposureCompensation = exifData.exposureCompensation,
                meteringMode = exifData.meteringMode,
                photoStyle = exifData.photoStyle,
                isHdr = exifData.isHdr,
                dateTaken = dateTaken
            )
        }

        /**
         * 按优先级解析最佳拍摄日期（毫秒时间戳）：
         * DateTimeOriginal → DateTimeDigitized → IFD0 DateTime → 当前 dateTaken（修改时间）
         */
        fun resolveBestDate(exifData: ExifData, currentDateTaken: Long): Long {
            return exifData.dateTimeOriginal
                ?: exifData.dateTimeDigitized
                ?: exifData.ifd0DateTime
                ?: currentDateTaken
        }
    }
}