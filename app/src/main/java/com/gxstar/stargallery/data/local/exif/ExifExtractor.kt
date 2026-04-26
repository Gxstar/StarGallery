package com.gxstar.stargallery.data.local.exif

import android.content.Context
import android.net.Uri
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.gxstar.stargallery.data.local.db.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

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
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                android.util.Log.w("ExifExtractor", "openInputStream returned null for $uri")
                return@withContext null
            }
            inputStream.use { stream ->
                val metadata = ImageMetadataReader.readMetadata(stream)
                val result = parseExifMetadata(metadata)
                android.util.Log.d("ExifExtractor", "EXIF parsed for $uri: $result")
                // 如果所有字段都是 null，说明没有有效的 EXIF 数据
                if (result.isAllNull()) {
                    android.util.Log.w("ExifExtractor", "All EXIF fields are null for $uri")
                    return@withContext null
                }
                return@withContext result
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

        // isoEquivalent
        val isoEquivalent = subIFD?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)?.takeIf { it > 0 }
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

        // shutterSpeed - APEX 转换为秒
        val shutterSpeedDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_SHUTTER_SPEED)
        val exposureTimeDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
        val shutterSpeed = parseShutterSpeed(shutterSpeedDesc, exposureTimeDesc)
        android.util.Log.v("ExifExtractor", "shutterSpeed: desc=$shutterSpeedDesc, exposure=$exposureTimeDesc, parsed=$shutterSpeed")

        // exifImageWidth / exifImageHeight
        val exifImageWidth = subIFD?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)?.takeIf { it > 0 }
        val exifImageHeight = subIFD?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)?.takeIf { it > 0 }

        // lut1 / lut2 (Panasonic)
        val lut1 = panasonicMakernote?.getString(0x00F1)?.trim()?.takeIf { it.isNotBlank() }
        val lut2 = panasonicMakernote?.getString(0x00F4)?.trim()?.takeIf { it.isNotBlank() }

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
            lut2 = lut2
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

    /**
     * 从快门速度描述转换为秒
     * APEX 值: 2^(-apex) = exposure time
     * 例如 APEX 10 -> 1/1024 秒 -> 0.0009765625f
     */
    private fun parseShutterSpeed(shutterSpeedDesc: String?, exposureTimeDesc: String?): Float? {
        val apexValue = shutterSpeedDesc?.toFloatOrNull()
        if (apexValue != null) {
            val exposureTime = 2.0.pow(-apexValue.toDouble()).toFloat()
            return exposureTime
        }

        // 尝试从曝光时间直接解析
        val exposureValue = exposureTimeDesc?.toFloatOrNull()
        if (exposureValue != null) {
            return exposureValue
        }

        return null
    }

    /**
     * EXIF 提取结果数据类
     */
    data class ExifData(
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
    ) {
        fun isAllNull(): Boolean {
            return cameraMake == null && cameraModel == null && lensModel == null &&
                    isoEquivalent == null && focalLength == null && focalLength35mmEquiv == null &&
                    fNumber == null && shutterSpeed == null && exifImageWidth == null &&
                    exifImageHeight == null && lut1 == null && lut2 == null
        }
    }

    companion object {
        /**
         * 将 ExifData 应用到 PhotoEntity，返回更新后的 PhotoEntity
         */
        fun applyToEntity(entity: PhotoEntity, exifData: ExifData): PhotoEntity {
            return entity.copy(
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
                lut2 = exifData.lut2
            )
        }
    }
}