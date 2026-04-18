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
        setupBaseInfo(photo, metadata)

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
        CoroutineScope(Dispatchers.Main).launch {
            val fullName = withContext(Dispatchers.IO) { getFullFileName(photo.uri) }
            binding.tvFilename.text = fullName ?: ""
        }

        CoroutineScope(Dispatchers.Main).launch {
            val brand = withContext(Dispatchers.IO) { readCameraMake(photo) }
            if (!brand.isNullOrBlank()) {
                binding.tvBrand.text = brand
                binding.tvBrand.visibility = View.VISIBLE
            } else {
                binding.tvBrand.visibility = View.GONE
            }
        }

        val sizeStr = formatFileSize(photo.size)

        val width = photo.width
        val height = photo.height
        if (width > 0 && height > 0) {
            val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
            val pixelsStr = DecimalFormat("0").format(megapixels) + "MP"
            binding.tvCombinedFileInfo.text = "${width}*${height}•${pixelsStr}•$sizeStr"
        } else {
            binding.tvCombinedFileInfo.text = sizeStr
        }

        val sdf = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        val dateMs = when {
            metadata?.dateTakenOriginal != null && metadata.dateTakenOriginal > 0 -> metadata.dateTakenOriginal
            metadata?.dateTaken != null && metadata.dateTaken > 0 -> metadata.dateTaken
            photo.dateTaken > 0 -> photo.dateTaken
            else -> photo.dateModified * 1000
        }
        binding.tvDate.text = sdf.format(dateMs)

        if (metadata != null) {
            val make = metadata.cameraMake ?: ""
            val model = metadata.cameraModel ?: ""
            val cameraDisplay = when {
                model.isNotBlank() && model.contains(make, true) -> model
                model.isNotBlank() && make.isNotBlank() -> "$make $model"
                model.isNotBlank() -> model
                make.isNotBlank() -> make
                else -> null
            }
            binding.tvCamera.text = cameraDisplay ?: "Unknown"
            
            binding.tvLens.text = metadata.lensModel ?: "Unknown"
            
            val aperture = metadata.aperture ?: "---"
            val shutter = metadata.exposureTime ?: "---"
            val iso = metadata.iso?.toString() ?: "---"
            
            val exposureParams = buildString {
                if (aperture != "---") {
                    val fNumber = if (aperture.startsWith("f/")) {
                        aperture.replace("f/", "f")
                    } else if (aperture.startsWith("f")) {
                        aperture
                    } else {
                        "f$aperture"
                    }
                    append(fNumber)
                }
                if (shutter != "---") {
                    if (isNotEmpty()) append(" • ")
                    val formattedShutter = formatShutterSpeed(shutter)
                    if (formattedShutter.contains("/")) {
                        append("${formattedShutter}s")
                    } else {
                        append(formattedShutter)
                    }
                }
                if (iso != "---") {
                    if (isNotEmpty()) append(" • ")
                    append("ISO$iso")
                }
                if (isEmpty()) append("---")
            }
            binding.tvExposureParams.text = exposureParams
            
            val focalLength = metadata.focalLength ?: "---"
            binding.tvFocalLength.text = focalLength
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

    private suspend fun readCameraMake(photo: Photo): String? = withContext(Dispatchers.IO) {
        try {
            requireContext().contentResolver.openInputStream(photo.uri)?.use { inputStream ->
                val metadata = ImageMetadataReader.readMetadata(inputStream)
                val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                val make = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()

                if (make.isNullOrBlank()) return@withContext null

                val cleaned = make
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

                cleaned.takeIf { it.isNotBlank() } ?: make
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateExifInfo(exifMetadata: Metadata, existingMetadata: MediaMetadata?) {
        val exifIFD0 = exifMetadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val exifSubIFD = exifMetadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val jpegDir = exifMetadata.getFirstDirectoryOfType(JpegDirectory::class.java)
        val panasonicMakernote = exifMetadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java)

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
            val pixelsStr = DecimalFormat("0").format(megapixels) + "MP"
            binding.tvCombinedFileInfo.text = "${width}*${height}•${pixelsStr}•$sizeStr"
        } else {
            binding.tvCombinedFileInfo.text = sizeStr
        }

        val aperture = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FNUMBER)
            ?: existingMetadata?.aperture
            ?: "---"

        val shutterSpeed = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_SHUTTER_SPEED)
        val exposureTime = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
        val shutter = shutterSpeed ?: exposureTime
            ?: existingMetadata?.exposureTime
            ?: "---"

        val iso = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)
            ?: existingMetadata?.iso?.toString()
            ?: "---"

        val focalLength = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
            ?: existingMetadata?.focalLength
            ?: "---"
        val focalLength35mm = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)

        var focalDisplay = focalLength.ifBlank { "---" }
        if (focalLength35mm != null && focalLength != "---" && focalLength != "Unknown") {
            val isEquivalent = focalLength == focalLength35mm
            focalDisplay = if (isEquivalent) {
                focalLength
            } else {
                "$focalLength (等效 $focalLength35mm)"
            }
        } else if (focalDisplay == "---" && existingMetadata != null) {
            focalDisplay = existingMetadata.focalLength ?: "---"
        }

        binding.tvFocalLength.text = focalDisplay

        val exposureParams = buildString {
            if (aperture != "---") {
                val fNumber = if (aperture.startsWith("f/")) {
                    aperture.replace("f/", "f")
                } else if (aperture.startsWith("f")) {
                    aperture
                } else {
                    "f$aperture"
                }
                append(fNumber)
            }
            if (shutter != "---") {
                if (isNotEmpty()) append(" • ")
                val formattedShutter = formatShutterSpeed(shutter)
                if (formattedShutter.contains("/")) {
                    append("${formattedShutter}s")
                } else {
                    append(formattedShutter)
                }
            }
            if (iso != "---") {
                if (isNotEmpty()) append(" • ")
                append("ISO$iso")
            }
            if (isEmpty()) append("---")
        }
        binding.tvExposureParams.text = exposureParams

        val exifMake = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE) ?: ""
        val exifModel = exifIFD0?.getString(ExifIFD0Directory.TAG_MODEL) ?: ""
        val exifCameraDisplay = if (exifModel.contains(exifMake, true)) exifModel else "$exifMake $exifModel"
        val cameraDisplay = exifCameraDisplay.ifBlank {
            val dbMake = existingMetadata?.cameraMake ?: ""
            val dbModel = existingMetadata?.cameraModel ?: ""
            val dbCameraDisplay = if (dbModel.contains(dbMake, true)) dbModel else "$dbMake $dbModel"
            dbCameraDisplay.ifBlank { "Unknown" }
        }
        binding.tvCamera.text = cameraDisplay

        val lensModel = exifSubIFD?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)
            ?: existingMetadata?.lensModel
            ?: panasonicMakernote?.getString(0x0051)
            ?: "Unknown"
        binding.tvLens.text = lensModel

        val lut1 = panasonicMakernote?.getString(0x00F1)
        val lut2 = panasonicMakernote?.getString(0x00F4)

        if (!lut1.isNullOrBlank()) {
            binding.tvLut1.text = "LUT1:$lut1"
            binding.tvLut1.visibility = View.VISIBLE
        } else {
            binding.tvLut1.visibility = View.GONE
        }

        if (!lut2.isNullOrBlank()) {
            binding.tvLut2.text = "LUT2:$lut2"
            binding.tvLut2.visibility = View.VISIBLE
        } else {
            binding.tvLut2.visibility = View.GONE
        }

        val sdf = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        val dateMs = when {
            existingMetadata?.dateTakenOriginal != null && existingMetadata.dateTakenOriginal > 0 -> existingMetadata.dateTakenOriginal
            existingMetadata?.dateTaken != null && existingMetadata.dateTaken > 0 -> existingMetadata.dateTaken
            photo?.dateTaken ?: 0L > 0 -> photo?.dateTaken ?: 0L
            else -> (photo?.dateModified ?: 0) * 1000
        }
        binding.tvDate.text = sdf.format(dateMs)
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun formatShutterSpeed(rawShutter: String?): String {
        if (rawShutter == null || rawShutter == "---") return "---"
        
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