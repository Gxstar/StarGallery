package com.gxstar.stargallery.ui.detail

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.local.entity.MediaMetadata
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.LayoutPhotoInfoBottomSheetBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 图片信息详情弹窗
 * 优先使用数据库中的元数据，降级时使用 Metadata-extractor 解析
 */
class PhotoInfoBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutPhotoInfoBottomSheetBinding? = null
    private val binding get() = _binding!!

    private var photo: Photo? = null
    private var metadata: MediaMetadata? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutPhotoInfoBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        photo?.let { loadPhotoInfo(it, metadata) }
    }

    private fun loadPhotoInfo(photo: Photo, metadata: MediaMetadata?) {
        // 1. 设置已知的基础信息
        setupBaseInfo(photo, metadata)

        // 2. 如果有数据库元数据，先用它显示，然后补充缺失的 EXIF 字段
        if (metadata != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val exifMetadata = extractMetadata(photo)
                exifMetadata?.let { updateExifInfo(it, metadata) }
            }
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                val exifMetadata = extractMetadata(photo)
                exifMetadata?.let { updateExifInfo(it, null) }
            }
        }
    }

    private fun setupBaseInfo(photo: Photo, metadata: MediaMetadata?) {
        // 1. 异步获取带扩展名的文件名 (第一行，主显示)
        CoroutineScope(Dispatchers.Main).launch {
            val fullName = withContext(Dispatchers.IO) { getFullFileName(photo.uri) }
            binding.tvFilename.text = fullName ?: ""
        }

        // 2. 基础信息暂存 (第二行，弱化显示)
        val sizeStr = formatFileSize(photo.size)
        binding.tvCombinedFileInfo.text = sizeStr

        // 3. 拍摄日期 - 优先使用数据库中的准确时间
        binding.rowDate.tvLabel.text = getString(R.string.info_date)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        // 优先级：数据库 dateTakenOriginal > 数据库 dateTaken > photo.dateTaken > photo.dateModified
        val dateMs = when {
            metadata?.dateTakenOriginal != null && metadata.dateTakenOriginal > 0 -> metadata.dateTakenOriginal
            metadata?.dateTaken != null && metadata.dateTaken > 0 -> metadata.dateTaken
            photo.dateTaken > 0 -> photo.dateTaken
            else -> photo.dateModified * 1000
        }
        binding.rowDate.tvValue.text = sdf.format(dateMs)

        // 4. 相机信息 - 如果数据库中有，直接显示
        binding.rowCamera.tvLabel.text = getString(R.string.info_camera)
        binding.rowLens.tvLabel.text = getString(R.string.info_lens)

        if (metadata != null) {
            // 相机型号
            val make = metadata.cameraMake ?: ""
            val model = metadata.cameraModel ?: ""
            val cameraDisplay = when {
                model.isNotBlank() && model.contains(make, true) -> model
                model.isNotBlank() && make.isNotBlank() -> "$make $model"
                model.isNotBlank() -> model
                make.isNotBlank() -> make
                else -> null
            }
            binding.rowCamera.tvValue.text = cameraDisplay ?: "Unknown"
            
            // 镜头型号
            binding.rowLens.tvValue.text = metadata.lensModel ?: "Unknown"
            
            // 分辨率和像素量
            val width = metadata.width
            val height = metadata.height
            if (width > 0 && height > 0) {
                val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
                val pixelsStr = DecimalFormat("0.0").format(megapixels) + " MP"
                binding.tvCombinedFileInfo.text = "${width}×${height}  •  $pixelsStr  •  $sizeStr"
            }
            
            // 拍摄参数
            binding.tvAperture.text = metadata.aperture ?: "---"
            binding.tvShutter.text = metadata.exposureTime ?: "---"
            binding.tvIso.text = metadata.iso?.let { "ISO $it" } ?: "---"
            binding.tvFocalLength.text = metadata.focalLength ?: "---"
        }
    }

    private fun getFullFileName(uri: android.net.Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractMetadata(photo: Photo) = withContext(Dispatchers.IO) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(photo.uri)
            inputStream?.use { ImageMetadataReader.readMetadata(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateFromDatabaseMetadata(metadata: MediaMetadata) {
        // 数据已在 setupBaseInfo 中处理，这里只处理额外信息
    }

    private fun updateExifInfo(exifMetadata: Metadata, existingMetadata: MediaMetadata?) {
        val exifIFD0 = exifMetadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val exifSubIFD = exifMetadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val jpegDir = exifMetadata.getFirstDirectoryOfType(JpegDirectory::class.java)
        val panasonicMakernote = exifMetadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java)

        // --- 1. 分辨率、像素量与文件大小合并 (第二行弱化显示) ---
        var width = 0
        var height = 0

        if (jpegDir != null) {
            width = jpegDir.getInteger(JpegDirectory.TAG_IMAGE_WIDTH) ?: 0
            height = jpegDir.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT) ?: 0
        } else if (exifSubIFD != null) {
            width = exifSubIFD.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH) ?: 0
            height = exifSubIFD.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT) ?: 0
        }

        val sizeStr = formatFileSize(photo?.size ?: 0)
        if (width > 0 && height > 0) {
            val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
            val pixelsStr = DecimalFormat("0.0").format(megapixels) + " MP"
            binding.tvCombinedFileInfo.text = "${width}×${height}  •  $pixelsStr  •  $sizeStr"
        } else {
            binding.tvCombinedFileInfo.text = sizeStr
        }

        // --- 2. 拍摄参数 (2x2 宫格填充) ---
        // 光圈：EXIF > 数据库
        val aperture = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FNUMBER)
            ?: existingMetadata?.aperture
            ?: "---"

        // 快门速度：EXIF > 数据库
        val shutterSpeed = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_SHUTTER_SPEED)
        val exposureTime = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
        val shutter = shutterSpeed ?: exposureTime
            ?: existingMetadata?.exposureTime
            ?: "---"

        // ISO：EXIF > 数据库
        val iso = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)
            ?: existingMetadata?.iso?.toString()
            ?: "---"

        // 焦距：EXIF > 数据库
        val focalLength = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
            ?: existingMetadata?.focalLength
            ?: "---"
        val focalLength35mm = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)

        // 处理焦距显示逻辑
        var focalDisplay = focalLength.ifBlank { "---" }
        if (focalLength35mm != null && focalLength != "---" && focalLength != "Unknown") {
            val isEquivalent = focalLength == focalLength35mm
            focalDisplay = if (isEquivalent) {
                focalLength
            } else {
                "$focalLength (等效 $focalLength35mm)"
            }
        } else if (focalDisplay == "---" && existingMetadata != null) {
            // EXIF 没有 35mm 等效焦距，但数据库有焦距数据，显示数据库的值
            focalDisplay = existingMetadata.focalLength ?: "---"
        }

        binding.tvAperture.text = aperture
        binding.tvShutter.text = formatShutterSpeed(shutter)
        binding.tvIso.text = if (iso != "---") (if (iso.startsWith("ISO", true)) iso else "ISO $iso") else "---"
        binding.tvFocalLength.text = focalDisplay

        // --- 3. 设备信息 ---
        // 相机品牌型号：EXIF > 数据库
        val exifMake = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE) ?: ""
        val exifModel = exifIFD0?.getString(ExifIFD0Directory.TAG_MODEL) ?: ""
        val exifCameraDisplay = if (exifModel.contains(exifMake, true)) exifModel else "$exifMake $exifModel"
        val cameraDisplay = exifCameraDisplay.ifBlank {
            val dbMake = existingMetadata?.cameraMake ?: ""
            val dbModel = existingMetadata?.cameraModel ?: ""
            val dbCameraDisplay = if (dbModel.contains(dbMake, true)) dbModel else "$dbMake $dbModel"
            dbCameraDisplay.ifBlank { "Unknown" }
        }
        binding.rowCamera.tvValue.text = cameraDisplay

        // 镜头：EXIF > 数据库 > 松下相机 Makernote (0x0051)
        val lensModel = exifSubIFD?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)
            ?: existingMetadata?.lensModel
            ?: panasonicMakernote?.getString(0x0051)
            ?: "Unknown"
        binding.rowLens.tvValue.text = lensModel

        // LUT1 和 LUT2：松下相机 Makernote (0x00F1, 0x00F4)
        val lut1 = panasonicMakernote?.getString(0x00F1)
        val lut2 = panasonicMakernote?.getString(0x00F4)

        if (!lut1.isNullOrBlank()) {
            binding.rowLut1.tvLabel.text = "LUT1"
            binding.rowLut1.tvValue.text = lut1
            binding.rowLut1.root.visibility = View.VISIBLE
        } else {
            binding.rowLut1.root.visibility = View.GONE
        }

        if (!lut2.isNullOrBlank()) {
            binding.rowLut2.tvLabel.text = "LUT2"
            binding.rowLut2.tvValue.text = lut2
            binding.rowLut2.root.visibility = View.VISIBLE
        } else {
            binding.rowLut2.root.visibility = View.GONE
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun formatShutterSpeed(rawShutter: String?): String {
        if (rawShutter == null || rawShutter == "---") return "---"
        
        // 如果已经是分数形式（如 "1/100"），直接返回
        if (rawShutter.contains("/")) {
            val parts = rawShutter.split("/")
            if (parts.size == 2) {
                val numerator = parts[0].trim().toDoubleOrNull() ?: return rawShutter
                val denominator = parts[1].trim().toDoubleOrNull() ?: return rawShutter
                if (denominator == 0.0) return rawShutter
                
                val value = numerator / denominator
                if (value >= 1) {
                    return if (value == value.toLong().toDouble()) {
                        value.toLong().toString()
                    } else {
                        DecimalFormat("0.#").format(value)
                    }
                }
                
                val gcd = gcd(numerator.toLong(), denominator.toLong())
                val simplifiedNumerator = (numerator / gcd).toLong()
                val simplifiedDenominator = (denominator / gcd).toLong()
                
                return if (simplifiedDenominator > 1000) {
                    findClosestStandardShutter(value) ?: rawShutter
                } else {
                    "${simplifiedNumerator}/${simplifiedDenominator}"
                }
            }
            return rawShutter
        }
        
        val value = rawShutter.toDoubleOrNull() ?: return rawShutter
        if (value >= 1) {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                DecimalFormat("0.#").format(value)
            }
        }
        
        val standardShutters = listOf(
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            1.0/2, 1.0/4, 1.0/8, 1.0/15, 1.0/30, 1.0/60,
            1.0/125, 1.0/250, 1.0/500, 1.0/1000, 1.0/2000,
            1.0/4000, 1.0/8000, 1.0/16000, 1.0/32000
        )
        
        for (standard in standardShutters) {
            if (Math.abs(value - standard) < 0.0001) {
                return formatAsFraction(standard)
            }
        }
        
        return formatAsFraction(value)
    }
    
    private fun formatAsFraction(value: Double): String {
        if (value >= 1) return DecimalFormat("0.#").format(value)
        
        val denominator = 10000L
        val numerator = (value * denominator).toLong()
        val gcd = gcd(numerator, denominator)
        
        val simplifiedNumerator = numerator / gcd
        val simplifiedDenominator = denominator / gcd
        
        return "${simplifiedNumerator}/${simplifiedDenominator}"
    }
    
    private fun findClosestStandardShutter(value: Double): String? {
        val standardShutters = listOf(
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            1.0/2, 1.0/4, 1.0/8, 1.0/15, 1.0/30, 1.0/60,
            1.0/125, 1.0/250, 1.0/500, 1.0/1000, 1.0/2000,
            1.0/4000, 1.0/8000
        )
        
        var closest: String? = null
        var minDiff = Double.MAX_VALUE
        
        for (standard in standardShutters) {
            val diff = Math.abs(value - standard)
            if (diff < minDiff && diff < 0.001) {
                minDiff = diff
                closest = formatAsFraction(standard)
            }
        }
        return closest
    }
    
    private fun gcd(a: Long, b: Long): Long {
        return if (b == 0L) a else gcd(b, a % b)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PhotoInfoBottomSheet"

        fun newInstance(photo: Photo, metadata: MediaMetadata? = null): PhotoInfoBottomSheet {
            return PhotoInfoBottomSheet().apply {
                this.photo = photo
                this.metadata = metadata
            }
        }
    }
}