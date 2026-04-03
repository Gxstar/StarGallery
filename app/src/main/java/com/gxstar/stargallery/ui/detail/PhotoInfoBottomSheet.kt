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
        // 异步获取带扩展名的文件名
        CoroutineScope(Dispatchers.Main).launch {
            val fullName = withContext(Dispatchers.IO) { getFullFileName(photo.uri) }
            binding.rowFilename.tvValue.text = fullName ?: photo.displayName
        }
        binding.rowFilename.tvLabel.text = getString(R.string.info_filename)
        binding.rowSize.tvLabel.text = getString(R.string.info_file_size)
        binding.rowSize.tvValue.text = formatFileSize(photo.size)

        binding.rowDate.tvLabel.text = getString(R.string.info_date)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateMs = if (photo.dateTaken > 0) photo.dateTaken else photo.dateModified * 1000
        binding.rowDate.tvValue.text = sdf.format(dateMs)

        // 占位/基础标签
        binding.rowDimensions.tvLabel.text = getString(R.string.info_dimensions)
        binding.rowPixels.tvLabel.text = getString(R.string.info_pixel_count)
        binding.rowCamera.tvLabel.text = getString(R.string.info_camera)
        binding.rowLens.tvLabel.text = getString(R.string.info_lens)
        binding.rowAperture.tvLabel.text = getString(R.string.info_aperture)
        binding.rowIso.tvLabel.text = getString(R.string.info_iso)
        binding.rowShutter.tvLabel.text = getString(R.string.info_shutter_speed)
        binding.rowFocalLength.tvLabel.text = getString(R.string.info_focal_length)
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

    private fun updateExifInfo(metadata: com.drew.metadata.Metadata) {
        val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val exifSubIFD = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val jpegDir = metadata.getFirstDirectoryOfType(JpegDirectory::class.java)

        // 分辨率与像素量
        var width = 0
        var height = 0
        
        if (jpegDir != null) {
            width = jpegDir.getInteger(JpegDirectory.TAG_IMAGE_WIDTH) ?: 0
            height = jpegDir.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT) ?: 0
        } else if (exifSubIFD != null) {
            width = exifSubIFD.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH) ?: 0
            height = exifSubIFD.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT) ?: 0
        }

        if (width > 0 && height > 0) {
            binding.rowDimensions.tvValue.text = "${width} × ${height}"
            val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
            binding.rowPixels.tvValue.text = DecimalFormat("0.0").format(megapixels) + " MP"
        }

        // 相机机型
        val make = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE) ?: ""
        val model = exifIFD0?.getString(ExifIFD0Directory.TAG_MODEL) ?: ""
        val cameraDisplay = if (model.contains(make, true)) model else "$make $model"
        binding.rowCamera.tvValue.text = cameraDisplay.ifBlank { "Unknown" }

        // 镜头型号
        val lensModel = exifSubIFD?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL) ?: "Unknown"
        binding.rowLens.tvValue.text = lensModel

        // 光圈
        val aperture = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FNUMBER) ?: "Unknown"
        binding.rowAperture.tvValue.text = aperture

        // ISO
        val iso = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT) ?: "Unknown"
        binding.rowIso.tvValue.text = iso

        // 快门速度
        val shutter = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME) ?: "Unknown"
        binding.rowShutter.tvValue.text = shutter

        // 焦距 (物理焦距 + 等效焦距)
        val focalLength = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_FOCAL_LENGTH) ?: "Unknown"
        val focalLength35mm = exifSubIFD?.getDescription(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)
        
        val focalDisplay = if (focalLength35mm != null && focalLength != "Unknown") {
            "$focalLength ($focalLength35mm)"
        } else {
            focalLength
        }
        binding.rowFocalLength.tvValue.text = focalDisplay
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
