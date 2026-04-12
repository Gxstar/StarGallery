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
import com.drew.metadata.jpeg.JpegDirectory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.LayoutPhotoInfoBottomSheetBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 图片信息详情弹窗
 * 使用 Metadata-extractor 解析详细的 EXIF 数据
 */
class PhotoInfoBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutPhotoInfoBottomSheetBinding? = null
    private val binding get() = _binding!!

    private var photo: Photo? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutPhotoInfoBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        photo?.let { loadPhotoInfo(it) }
    }

    private fun loadPhotoInfo(photo: Photo) {
        // 1. 设置已知的基础信息
        setupBaseInfo(photo)

        // 2. 异步解析 EXIF 信息
        CoroutineScope(Dispatchers.Main).launch {
            val metadata = extractMetadata(photo)
            metadata?.let { updateExifInfo(it) }
        }
    }

    private fun setupBaseInfo(photo: Photo) {
        // 1. 异步获取带扩展名的文件名 (第一行，主显示)
        CoroutineScope(Dispatchers.Main).launch {
            val fullName = withContext(Dispatchers.IO) { getFullFileName(photo.uri) }
            binding.tvFilename.text = fullName ?: ""
        }

        // 2. 基础信息暂存 (第二行，弱化显示)
        val sizeStr = formatFileSize(photo.size)
        binding.tvCombinedFileInfo.text = sizeStr

        // 3. 其他信息
        binding.rowDate.tvLabel.text = getString(R.string.info_date)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateMs = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified * 1000
        binding.rowDate.tvValue.text = sdf.format(dateMs)

        binding.rowCamera.tvLabel.text = getString(R.string.info_camera)
        binding.rowLens.tvLabel.text = getString(R.string.info_lens)
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

    private fun updateExifInfo(metadata: Metadata) {
        val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val exifSubIFD = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val jpegDir = metadata.getFirstDirectoryOfType(JpegDirectory::class.java)

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
        val aperture = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FNUMBER) ?: "---"
        
        // 优先使用 ShutterSpeed，没有则使用 ExposureTime
        val shutterSpeed = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_SHUTTER_SPEED)
        val exposureTime = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
        val shutter = shutterSpeed ?: exposureTime ?: "---"
        val iso = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT) ?: "---"
        
        val focalLength = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH) ?: "---"
        val focalLength35mm = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)
        
        // 处理焦距显示逻辑
        var focalDisplay = focalLength.ifBlank { "---" }
        if (focalLength35mm != null && focalLength != "---" && focalLength != "Unknown") {
            val isEquivalent = focalLength == focalLength35mm
            focalDisplay = if (isEquivalent) {
                // 物理焦距等于等效焦距，不显示等效信息
                focalLength
            } else {
                // 物理焦距不等于等效焦距，显示"24mm (等效 35mm)"
                "$focalLength (等效 $focalLength35mm)"
            }
        }
        
        binding.tvAperture.text = aperture
        binding.tvShutter.text = formatShutterSpeed(shutter)
        binding.tvIso.text = if (iso != "---") (if (iso.startsWith("ISO", true)) iso else "ISO $iso") else "---"
        binding.tvFocalLength.text = focalDisplay

        // --- 3. 设备信息 ---
        val make = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE) ?: ""
        val model = exifIFD0?.getString(ExifIFD0Directory.TAG_MODEL) ?: ""
        val cameraDisplay = if (model.contains(make, true)) model else "$make $model"
        binding.rowCamera.tvValue.text = cameraDisplay.ifBlank { "Unknown" }

        val lensModel = exifSubIFD?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL) ?: "Unknown"
        binding.rowLens.tvValue.text = lensModel
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
                // 如果值 >= 1，直接显示小数
                if (value >= 1) {
                    return if (value == value.toLong().toDouble()) {
                        value.toLong().toString()
                    } else {
                        DecimalFormat("0.#").format(value)
                    }
                }
                
                // 值 < 1，转换为分数形式
                val gcd = gcd(numerator.toLong(), denominator.toLong())
                val simplifiedNumerator = (numerator / gcd).toLong()
                val simplifiedDenominator = (denominator / gcd).toLong()
                
                return if (simplifiedDenominator > 1000) {
                    // 分母太大，尝试找最接近的标准快门速度
                    findClosestStandardShutter(value) ?: rawShutter
                } else {
                    "${simplifiedNumerator}/${simplifiedDenominator}"
                }
            }
            return rawShutter
        }
        
        // 如果是小数形式（如 "0.01"），转换为分数
        val value = rawShutter.toDoubleOrNull() ?: return rawShutter
        if (value >= 1) {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                DecimalFormat("0.#").format(value)
            }
        }
        
        // 值 < 1，转换为分数
        // 常见快门速度标准值（秒）
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
        
        // 没找到标准值，转换为分数
        return formatAsFraction(value)
    }
    
    private fun formatAsFraction(value: Double): String {
        if (value >= 1) return DecimalFormat("0.#").format(value)
        
        // 转换为分母在合理范围内的分数
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
        fun newInstance(photo: Photo): PhotoInfoBottomSheet {
            return PhotoInfoBottomSheet().apply {
                this.photo = photo
            }
        }
    }
}
