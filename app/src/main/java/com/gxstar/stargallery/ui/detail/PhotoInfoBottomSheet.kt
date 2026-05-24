package com.gxstar.stargallery.ui.detail

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.LayoutPhotoInfoBottomSheetBinding
import com.gxstar.stargallery.ui.util.DateUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import javax.inject.Inject

/**
 * 图片信息详情弹窗
 * 数据来源：Room 数据库（由全量扫描 + EXIF 批量提取预先填充）
 */
@AndroidEntryPoint
class PhotoInfoBottomSheet : BottomSheetDialogFragment() {

    @Inject lateinit var photoDao: PhotoDao

    private var _binding: LayoutPhotoInfoBottomSheetBinding? = null
    private val binding get() = _binding!!

    private var photo: Photo? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutPhotoInfoBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val p = photo ?: return
        loadFromDatabase(p)
    }

    private fun loadFromDatabase(photo: Photo) {
        CoroutineScope(Dispatchers.Main).launch {
            val entity = withContext(Dispatchers.IO) {
                photoDao.getPhotoById(photo.id)
            }
            if (entity == null) return@launch
            bindData(photo, entity)
        }
    }

    private fun bindData(photo: Photo, entity: com.gxstar.stargallery.data.local.db.PhotoEntity) {
        // 文件名
        binding.tvTitle.text = entity.displayName ?: ""

        // 格式徽章
        val formatName = resolveFormatName(entity.mimeType)
        setupFormatBadge(formatName)

        // 日期
        val dateMs = when {
            photo.dateTaken > 0 -> photo.dateTaken
            photo.dateModified > 0 -> photo.dateModified * 1000L
            else -> System.currentTimeMillis()
        }
        val sizeStr = formatFileSize(photo.size)
        val flashStr = if (entity.flash == true) "  •  ⚡" else ""
        binding.tvDate.text = "${DateUtils.formatDate(dateMs)}  ${DateUtils.formatTime(dateMs)}  •  $sizeStr$flashStr"

        // 拍摄设备
        val make = entity.cameraMake?.trim()
        val model = entity.cameraModel?.trim()
        val cameraDisplay = when {
            model.isNullOrBlank() && make.isNullOrBlank() -> null
            model.isNullOrBlank() -> make
            model?.contains(make ?: "", ignoreCase = true) == true -> model
            !make.isNullOrBlank() -> "$make $model"
            else -> model
        }
        if (!cameraDisplay.isNullOrBlank()) {
            binding.tvCameraValue.text = cameraDisplay
            binding.tvCameraValue.visibility = View.VISIBLE
        } else {
            binding.tvCameraValue.visibility = View.GONE
        }

        val lens = entity.lensModel?.trim()
        if (!lens.isNullOrBlank()) {
            binding.tvLensValue.text = lens
            binding.rowLens.visibility = View.VISIBLE
        } else {
            binding.rowLens.visibility = View.GONE
        }

        // 分辨率 + 文件大小
        val exifWidth = entity.exifImageWidth ?: 0
        val exifHeight = entity.exifImageHeight ?: 0
        val dispWidth = if (exifWidth > 0) exifWidth else photo.width
        val dispHeight = if (exifHeight > 0) exifHeight else photo.height
        if (dispWidth > 0 && dispHeight > 0) {
            val mp = (dispWidth.toLong() * dispHeight.toLong()) / 1_000_000.0
            val mpStr = DecimalFormat("0.0").format(mp)
            binding.tvResolutionValue.text = "${dispWidth} × ${dispHeight}  •  ${mpStr} MP"
            binding.tvResolutionValue.visibility = View.VISIBLE
        } else {
            binding.tvResolutionValue.visibility = View.GONE
        }

        // 拍摄参数
        val iso = entity.isoEquivalent
        val fNumber = entity.fNumber
        val shutterSpeed = entity.shutterSpeed
        val focalLength = entity.focalLength
        val equivFocal = entity.focalLength35mmEquiv
        val exposureComp = entity.exposureCompensation
        val meteringMode = entity.meteringMode

        if (iso != null && iso > 0) {
            binding.tvIsoValue.text = iso.toString()
        } else {
            binding.tvIsoValue.text = null
        }

        if (fNumber != null && fNumber > 0f) {
            binding.tvApertureValue.text = "f/${String.format("%.1f", fNumber)}"
        } else {
            binding.tvApertureValue.text = null
        }

        if (shutterSpeed != null && shutterSpeed > 0f) {
            binding.tvShutterValue.text = formatShutterSpeed(shutterSpeed)
            // 从 EXIF 读取精确的描述字符串覆盖浮点换算值
            readExposureTimeFromExif(photo)
        } else {
            binding.tvShutterValue.text = null
        }

        // 焦距：默认显示等效焦距，点击切换物理/等效
        var showPhysicalFocal = false
        if (focalLength != null && focalLength > 0f) {
            val hasEquiv = equivFocal != null && equivFocal > 0 && focalLength.toInt() != equivFocal
            fun updateFocalDisplay() {
                if (showPhysicalFocal) {
                    binding.tvFocalLabel.text = "物理焦距"
                    binding.tvFocalValue.text = "${focalLength.toInt()} mm"
                } else if (hasEquiv) {
                    binding.tvFocalLabel.text = "等效焦距"
                    binding.tvFocalValue.text = "${equivFocal} mm"
                } else {
                    binding.tvFocalLabel.text = "焦距"
                    binding.tvFocalValue.text = "${focalLength.toInt()} mm"
                }
            }
            updateFocalDisplay()
            binding.tvFocalValue.setOnClickListener {
                showPhysicalFocal = !showPhysicalFocal
                updateFocalDisplay()
            }
        } else {
            binding.tvFocalLabel.text = "焦距"
            binding.tvFocalValue.text = null
            binding.tvFocalValue.setOnClickListener(null)
        }

        if (exposureComp != null) {
            val sign = if (exposureComp >= 0f) "+" else ""
            binding.tvExposureCompValue.text = "${sign}${String.format("%.1f", exposureComp)} EV"
        } else {
            binding.tvExposureCompValue.text = null
        }

        if (!meteringMode.isNullOrBlank()) {
            binding.tvMeteringModeValue.text = resolveMeteringMode(meteringMode)
        } else {
            binding.tvMeteringModeValue.text = null
        }

        // 如果没有任何曝光参数，隐藏整个卡片
        val hasExposure = (iso != null && iso > 0) ||
                (fNumber != null && fNumber > 0f) ||
                (shutterSpeed != null && shutterSpeed > 0f) ||
                (focalLength != null && focalLength > 0f) ||
                exposureComp != null ||
                !meteringMode.isNullOrBlank()
        if (!hasExposure) {
            binding.cardExposure.visibility = View.GONE
        }

        // 位置信息
        val lat = entity.latitude
        val lng = entity.longitude
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            binding.cardLocation.visibility = View.VISIBLE
            val latStr = decimalToDms(lat) + if (lat >= 0) " N" else " S"
            val lngStr = decimalToDms(lng) + if (lng >= 0) " E" else " W"
            binding.tvLocationCoords.text = "$latStr  $lngStr"

            binding.tvLocationCoords.setOnClickListener {
                openInMap(lat, lng)
            }
        } else {
            binding.cardLocation.visibility = View.GONE
        }

        // LUT
        val lut1 = entity.lut1?.trim()?.takeIf { it.isNotBlank() }
        val lut2 = entity.lut2?.trim()?.takeIf { it.isNotBlank() }
        if (lut1 != null || lut2 != null) {
            binding.containerLuts.visibility = View.VISIBLE
            if (lut1 != null) {
                binding.chipLut1.text = "LUT1: $lut1"
                binding.chipLut1.visibility = View.VISIBLE
            } else {
                binding.chipLut1.visibility = View.GONE
            }
            if (lut2 != null) {
                binding.chipLut2.text = "LUT2: $lut2"
                binding.chipLut2.visibility = View.VISIBLE
            } else {
                binding.chipLut2.visibility = View.GONE
            }
        } else {
            binding.containerLuts.visibility = View.GONE
        }
    }

    private fun isChineseLocale(): Boolean {
        val locale = resources.configuration.locales.get(0)
        return locale.language == "zh"
    }

    private fun resolveMeteringMode(mode: String): String {
        if (!isChineseLocale()) return mode
        return when (mode) {
            "Unknown" -> "未知"
            "Average" -> "平均"
            "Center weighted average" -> "中央重点"
            "Spot" -> "点"
            "Multi-spot" -> "多点"
            "Multi-segment" -> "多分区"
            "Partial" -> "局部"
            "(Other)" -> "其他"
            else -> mode
        }
    }

    private fun resolveFormatName(mimeType: String): String {
        return when {
            mimeType == "image/jpeg" -> "JPG"
            mimeType == "image/png" -> "PNG"
            mimeType == "image/gif" -> "GIF"
            mimeType == "image/webp" -> "WebP"
            mimeType == "image/avif" -> "AVIF"
            mimeType == "image/heic" -> "HEIC"
            mimeType == "image/heif" -> "HEIF"
            mimeType == "image/bmp" || mimeType == "image/x-ms-bmp" -> "BMP"
            mimeType == "image/x-adobe-dng" -> "DNG"
            mimeType == "image/x-sony-arw" -> "ARW"
            mimeType == "image/x-canon-cr2" -> "CR2"
            mimeType == "image/x-canon-cr3" -> "CR3"
            mimeType == "image/x-nikon-nef" -> "NEF"
            mimeType == "image/x-olympus-orf" -> "ORF"
            mimeType == "image/x-panasonic-rw2" -> "RW2"
            mimeType.startsWith("video/") -> "视频"
            mimeType.startsWith("image/x-") -> "RAW"
            else -> mimeType.substringAfterLast("/").uppercase()
        }
    }

    private fun setupFormatBadge(format: String) {
        if (format.isBlank()) {
            binding.badgeFormat.visibility = View.GONE
            return
        }
        binding.badgeFormat.visibility = View.VISIBLE
        binding.badgeFormat.text = format

        val color = when (format) {
            "JPG", "JPEG" -> 0xFF007AFF.toInt()
            "PNG" -> 0xFF8E8E93.toInt()
            "GIF" -> 0xFFFF9500.toInt()
            "WebP" -> 0xFF00BFA5.toInt()
            "AVIF" -> 0xFF34C759.toInt()
            "HEIC", "HEIF" -> 0xFFAF52DE.toInt()
            "BMP" -> 0xFF8E8E93.toInt()
            "DNG", "ARW", "CR2", "CR3", "NEF", "ORF", "RW2", "RAW" -> 0xFFFF9500.toInt()
            "视频" -> 0xFFFF3B30.toInt()
            else -> 0xFF007AFF.toInt()
        }
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(color)
        }
        binding.badgeFormat.background = drawable
    }

    private fun readExposureTimeFromExif(photo: Photo) {
        CoroutineScope(Dispatchers.Main).launch {
            val desc = withContext(Dispatchers.IO) {
                try {
                    val originalUri = try {
                        MediaStore.setRequireOriginal(photo.uri)
                    } catch (_: Exception) {
                        photo.uri
                    }
                    requireContext().contentResolver.openInputStream(originalUri)?.use { stream ->
                        val metadata = com.drew.imaging.ImageMetadataReader.readMetadata(stream)
                        val subIFD = metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifSubIFDDirectory::class.java)
                        subIFD?.getDescription(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
                    }
                } catch (_: Exception) { null }
            }
            if (!desc.isNullOrBlank()) {
                val cleaned = desc.removeSuffix(" s").removeSuffix("sec").trim()
                // 避免过长的分数（如 "1054277/1000000000"），改用 formatShutterSpeed 的简洁格式
                val isLongFraction = cleaned.contains("/") && cleaned.replace("/", "").any { it.isDigit() } &&
                        cleaned.length > 12
                if (!isLongFraction) {
                    binding.tvShutterValue.text = cleaned
                }
            }
        }
    }

    private fun formatShutterSpeed(seconds: Float): String {
        return if (seconds >= 1f) {
            "${String.format("%.1f", seconds)} s"
        } else {
            val denominator = (1.0 / seconds).let { kotlin.math.round(it).toInt() }
            if (denominator > 0) "1/${denominator} s" else "${String.format("%.3f", seconds)} s"
        }
    }

    private fun decimalToDms(decimal: Double): String {
        val degrees = decimal.toInt()
        val minutesFull = (decimal - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60
        return "${degrees}°${minutes}'${String.format("%.1f", seconds)}\""
    }

    private fun openInMap(lat: Double, lng: Double) {
        try {
            val geoUri = Uri.parse("geo:${lat},${lng}?q=${lat},${lng}")
            val intent = Intent(Intent.ACTION_VIEW, geoUri)
            startActivity(intent)
        } catch (_: Exception) {
            // 没有地图应用
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
