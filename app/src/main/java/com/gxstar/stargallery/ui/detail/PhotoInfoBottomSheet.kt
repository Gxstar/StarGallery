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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutPhotoInfoBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        photo?.let { loadPhotoInfo(it) }
    }

    private fun loadPhotoInfo(photo: Photo) {
        setupBaseInfo(photo)

        CoroutineScope(Dispatchers.Main).launch {
            val exifMetadata = extractMetadata(photo)
            exifMetadata?.let { updateExifInfo(it, photo) }
        }
    }

    private fun setupBaseInfo(photo: Photo) {
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

        val sdf = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
        val dateMs = when {
            photo.dateTaken > 0 -> photo.dateTaken
            else -> photo.dateModified * 1000
        }
        binding.tvDate.text = sdf.format(dateMs)
    }

    private fun updateExifInfo(exifMetadata: Metadata, photo: Photo) {
        try {
            val exifIFD0 = exifMetadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val subIFD = exifMetadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val panasonicMakernote = exifMetadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java)
            
            val make = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()
            val model = exifIFD0?.getString(ExifIFD0Directory.TAG_MODEL)?.trim()
            val cameraDisplay = when {
                model.isNullOrBlank() && make.isNullOrBlank() -> null
                model.isNullOrBlank() -> make
                model?.contains(make ?: "", true) == true -> model
                !make.isNullOrBlank() -> "$make $model"
                else -> model
            }
            if (!cameraDisplay.isNullOrBlank()) {
                binding.tvCamera.text = cameraDisplay
                binding.tvCamera.visibility = View.VISIBLE
            } else {
                binding.tvCamera.visibility = View.GONE
            }
            
            val lens = subIFD?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)?.trim()
                ?: panasonicMakernote?.getString(0x0051)?.trim()
            if (!lens.isNullOrBlank()) {
                binding.tvLens.text = lens
                binding.tvLens.visibility = View.VISIBLE
            } else {
                binding.tvLens.visibility = View.GONE
            }
            
            val exifWidth = subIFD?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH) ?: 0
            val exifHeight = subIFD?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT) ?: 0
            val width = if (exifWidth > 0) exifWidth else photo.width
            val height = if (exifHeight > 0) exifHeight else photo.height
            if (width > 0 && height > 0) {
                val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
                val pixelsStr = DecimalFormat("0.0").format(megapixels) + " MP"
                val sizeStr = formatFileSize(photo.size)
                binding.tvResolution.text = "${pixelsStr} • ${width} × ${height} • $sizeStr"
                binding.tvResolution.visibility = View.VISIBLE
            } else {
                binding.tvResolution.visibility = View.GONE
            }
            
            val exposureParts = mutableListOf<String>()
            
            val iso = subIFD?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)
            if (iso != null && iso > 0) {
                exposureParts.add("ISO $iso")
            }
            
            val focalLengthDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
            val equivFocalLength = subIFD?.getInteger(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)
            if (!focalLengthDesc.isNullOrBlank()) {
                val match = Regex("(\\d+(?:\\.\\d+)?)").find(focalLengthDesc)
                val physicalFocal = match?.let { it.groupValues[1].removeSuffix(".0") } ?: focalLengthDesc
                val physicalFocalNum = physicalFocal.toFloatOrNull()
                val focalStr = if (equivFocalLength != null && equivFocalLength > 0 && physicalFocalNum?.toInt() != equivFocalLength) {
                    "${physicalFocal} mm (${equivFocalLength} mm)"
                } else {
                    "${physicalFocal} mm"
                }
                exposureParts.add(focalStr)
            }
            
            val fNumberDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_FNUMBER)
            if (!fNumberDesc.isNullOrBlank()) {
                val match = Regex("[fF]/?(\\d+(?:\\.\\d+)?)").find(fNumberDesc)
                exposureParts.add("f/${match?.let { it.groupValues[1] } ?: fNumberDesc}")
            }
            
            val shutterSpeedDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_SHUTTER_SPEED)
            val exposureTimeDesc = subIFD?.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
            val shutterStr = if (!shutterSpeedDesc.isNullOrBlank()) {
                val apexValue = shutterSpeedDesc.toFloatOrNull()
                if (apexValue != null) {
                    val exposureTime = Math.pow(2.0, -apexValue.toDouble())
                    if (exposureTime >= 1) {
                        "${String.format("%.1f", exposureTime)} s"
                    } else {
                        val denominator = (1.0 / exposureTime).toInt()
                        "1/${denominator} s"
                    }
                } else {
                    shutterSpeedDesc
                }
            } else if (!exposureTimeDesc.isNullOrBlank()) {
                val exposureValue = exposureTimeDesc.toFloatOrNull()
                if (exposureValue != null) {
                    if (exposureValue >= 1) {
                        "${String.format("%.1f", exposureValue)} s"
                    } else {
                        val denominator = (1.0 / exposureValue).toInt()
                        "1/${denominator} s"
                    }
                } else {
                    exposureTimeDesc
                }
            } else {
                null
            }
            shutterStr?.let { exposureParts.add(it) }
            
            if (exposureParts.isNotEmpty()) {
                binding.tvExposureParams.text = exposureParts.joinToString(" • ")
                binding.tvExposureParams.visibility = View.VISIBLE
            } else {
                binding.tvExposureParams.visibility = View.GONE
            }
            
            val lut1 = panasonicMakernote?.getString(0x00F1)
            if (!lut1.isNullOrBlank()) {
                binding.tvLut1.text = "LUT1: $lut1"
                binding.tvLut1.visibility = View.VISIBLE
            } else {
                binding.tvLut1.visibility = View.GONE
            }
            
            val lut2 = panasonicMakernote?.getString(0x00F4)
            if (!lut2.isNullOrBlank()) {
                binding.tvLut2.text = "LUT2: $lut2"
                binding.tvLut2.visibility = View.VISIBLE
            } else {
                binding.tvLut2.visibility = View.GONE
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
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
