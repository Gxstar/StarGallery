package com.gxstar.stargallery.ui.detail

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.exif.ExifExtractor
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.LayoutPhotoInfoBottomSheetBinding
import com.gxstar.stargallery.ui.util.CoordinateUtils
import com.gxstar.stargallery.ui.util.DateUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope

/**
 * 图片信息详情弹窗
 * 数据来源：Room 数据库（由全量扫描 + EXIF 批量提取预先填充）
 */
@AndroidEntryPoint
class PhotoInfoBottomSheet : BottomSheetDialogFragment() {

    @Inject lateinit var photoDao: PhotoDao
    @Inject lateinit var exifExtractor: ExifExtractor

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

        viewLifecycleOwner.lifecycleScope.launch {
            // 1. 先从数据库加载已有数据
            val entity = withContext(Dispatchers.IO) {
                photoDao.getPhotoById(p.id)
            }

            // 2. 绑定已有数据（无论完整与否都先显示）
            if (entity != null) {
                bindData(p, entity)
            } else {
                bindBasicInfo(p)
            }

            // 3. 判断是否需要实时提取 EXIF（仅用于展示，不写数据库）
            val needsExif = entity == null || !hasCompleteExif(entity)

            if (needsExif) {
                Log.i(TAG, "EXIF 数据不完整，启动实时提取（仅展示）: photoId=${p.id}")
                extractExifForDisplay(p)
            }
        }
    }

    /**
     * 判断 EXIF 数据是否完整
     * 只要相机品牌或型号任一存在即认为有足够信息展示
     */
    private fun hasCompleteExif(entity: com.gxstar.stargallery.data.local.db.PhotoEntity): Boolean {
        val hasCameraData = !entity.cameraMake.isNullOrBlank() || !entity.cameraModel.isNullOrBlank()
        val hasGpsData = entity.latitude != null || entity.longitude != null
        // 只要有相机信息或 GPS 信息即认为足够
        return hasCameraData || hasGpsData
    }

    /**
     * 绑定基本信息（当数据库无记录时）
     * 显示文件名、格式、分辨率等基本信息
     */
    private fun bindBasicInfo(photo: Photo) {
        // 文件名（从 URI 提取）
        val fileName = photo.uri.lastPathSegment?.substringAfterLast("/") ?: ""
        binding.tvTitle.text = fileName

        // 格式徽章
        val formatName = resolveFormatName(photo.mimeType)
        setupFormatBadge(formatName)

        // 日期
        val dateMs = if (photo.dateTaken > 0) photo.dateTaken else System.currentTimeMillis()
        val sizeStr = formatFileSize(photo.size)
        binding.tvDate.text = "${DateUtils.formatDate(dateMs)}  ${DateUtils.formatTime(dateMs)}  •  $sizeStr"

        // 分辨率
        if (photo.width > 0 && photo.height > 0) {
            val mp = (photo.width.toLong() * photo.height.toLong()) / 1_000_000.0
            val mpStr = DecimalFormat("0.0").format(mp)
            binding.tvResolutionValue.text = "${photo.width} × ${photo.height}  •  ${mpStr} MP"
            binding.tvResolutionValue.visibility = View.VISIBLE
        }

        // 其他 EXIF 信息暂时隐藏（等待实时提取）
        binding.tvCameraValue.visibility = View.GONE
        binding.rowLens.visibility = View.GONE
        binding.cardExposure.visibility = View.GONE
        binding.cardLocation.visibility = View.GONE
        binding.cardPhotoStyle.visibility = View.GONE
    }

    /**
     * 实时提取 EXIF（仅用于展示，不写数据库）
     * 在后台扫描进程之外独立运行，不影响扫描
     */
    private fun extractExifForDisplay(photo: Photo) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val exifData = withContext(Dispatchers.IO) {
                    // photo.uri 已经是 Uri 类型，无需再次 parse
                    exifExtractor.extractExif(photo.uri)
                }

                if (exifData != null) {
                    // 仅更新 UI，不写数据库
                    bindExifDataToUi(exifData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "实时 EXIF 提取失败: photoId=${photo.id}", e)
            }
        }
    }

    /**
     * 将实时提取的 EXIF 数据绑定到 UI（仅展示）
     */
    private fun bindExifDataToUi(exifData: com.gxstar.stargallery.data.local.exif.ExifExtractor.ExifData) {
        // 拍摄设备
        val make = exifData.cameraMake?.trim()
        val model = exifData.cameraModel?.trim()
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
        }

        // 镜头
        val lens = exifData.lensModel?.trim()
        if (!lens.isNullOrBlank()) {
            binding.tvLensValue.text = lens
            binding.rowLens.visibility = View.VISIBLE
        }

        // 分辨率
        val width = exifData.exifImageWidth ?: 0
        val height = exifData.exifImageHeight ?: 0
        if (width > 0 && height > 0) {
            val mp = (width.toLong() * height.toLong()) / 1_000_000.0
            val mpStr = DecimalFormat("0.0").format(mp)
            binding.tvResolutionValue.text = "$width × $height  •  ${mpStr} MP"
            binding.tvResolutionValue.visibility = View.VISIBLE
        }

        // 拍摄参数
        val iso = exifData.isoEquivalent
        val fNumber = exifData.fNumber
        val shutterSpeed = exifData.shutterSpeed
        val focalLength = exifData.focalLength
        val equivFocal = exifData.focalLength35mmEquiv
        val exposureComp = exifData.exposureCompensation
        val meteringMode = exifData.meteringMode

        if (iso != null && iso > 0) {
            binding.tvIsoValue.text = iso.toString()
        }

        if (fNumber != null && fNumber > 0f) {
            binding.tvApertureValue.text = "f/${String.format("%.1f", fNumber)}"
        }

        if (shutterSpeed != null && shutterSpeed > 0f) {
            binding.tvShutterValue.text = formatShutterSpeed(shutterSpeed)
        }

        // 焦距
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
        }

        if (exposureComp != null) {
            val sign = if (exposureComp >= 0f) "+" else ""
            binding.tvExposureCompValue.text = "${sign}${String.format("%.2f", exposureComp)} EV"
        }

        if (!meteringMode.isNullOrBlank()) {
            binding.tvMeteringModeValue.text = resolveMeteringMode(meteringMode)
        }

        // 显示曝光参数卡片
        val hasExposure = (iso != null && iso > 0) ||
                (fNumber != null && fNumber > 0f) ||
                (shutterSpeed != null && shutterSpeed > 0f) ||
                (focalLength != null && focalLength > 0f) ||
                exposureComp != null ||
                !meteringMode.isNullOrBlank()
        if (hasExposure) {
            binding.cardExposure.visibility = View.VISIBLE
        }

        // 位置信息
        val lat = exifData.latitude
        val lng = exifData.longitude
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            binding.cardLocation.visibility = View.VISIBLE
            val latStr = decimalToDms(lat) + if (lat >= 0) " N" else " S"
            val lngStr = decimalToDms(lng) + if (lng >= 0) " E" else " W"
            binding.tvLocationCoords.text = "$latStr  $lngStr"

            binding.tvLocationCoords.setOnClickListener {
                openInMap(lat, lng)
            }
        }

        // 照片风格 + LUT
        val photoStyle = exifData.photoStyle?.trim()?.takeIf { it.isNotBlank() }
        val lut1 = exifData.lut1?.trim()?.takeIf { it.isNotBlank() }
        val lut2 = exifData.lut2?.trim()?.takeIf { it.isNotBlank() }
        val hasPhotoStyle = photoStyle != null
        val hasLut = lut1 != null || lut2 != null
        if (hasPhotoStyle || hasLut) {
            binding.cardPhotoStyle.visibility = View.VISIBLE
            if (photoStyle != null) {
                binding.tvPhotoStyleLabel.visibility = View.VISIBLE
                binding.tvPhotoStyleValue.text = photoStyle
            }
            if (hasLut) {
                binding.rowLuts.visibility = View.VISIBLE
                if (lut1 != null) {
                    binding.chipLut1.text = lut1
                    binding.chipLut1.visibility = View.VISIBLE
                }
                if (lut2 != null) {
                    binding.chipLut2.text = lut2
                    binding.chipLut2.visibility = View.VISIBLE
                }
            }
        }

        // 闪光灯
        if (exifData.flash == true) {
            val currentText = binding.tvDate.text
            if (!currentText.contains("⚡")) {
                binding.tvDate.text = "$currentText  •  ⚡"
            }
        }
    }

    private fun bindData(photo: Photo, entity: com.gxstar.stargallery.data.local.db.PhotoEntity) {
        // 文件名
        binding.tvTitle.text = entity.displayName ?: ""

        // 格式徽章
        val formatName = resolveFormatName(entity.mimeType)
        setupFormatBadge(formatName)

        // 日期（优先使用数据库中最新值，而非传入的快照对象）
        val dateMs = when {
            entity.dateTaken > 0 -> entity.dateTaken
            entity.dateModified > 0 -> entity.dateModified * 1000L
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
            binding.tvExposureCompValue.text = "${sign}${String.format("%.2f", exposureComp)} EV"
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

        // 照片风格 + LUT
        val photoStyle = entity.photoStyle?.trim()?.takeIf { it.isNotBlank() }
        val lut1 = entity.lut1?.trim()?.takeIf { it.isNotBlank() }
        val lut2 = entity.lut2?.trim()?.takeIf { it.isNotBlank() }
        val hasPhotoStyle = photoStyle != null
        val hasLut = lut1 != null || lut2 != null
        if (hasPhotoStyle || hasLut) {
            binding.cardPhotoStyle.visibility = View.VISIBLE
            if (photoStyle != null) {
                binding.tvPhotoStyleLabel.visibility = View.VISIBLE
                binding.tvPhotoStyleValue.text = photoStyle
            } else {
                binding.tvPhotoStyleLabel.visibility = View.GONE
                binding.tvPhotoStyleValue.text = null
            }
            if (hasLut) {
                binding.rowLuts.visibility = View.VISIBLE
                if (lut1 != null) {
                    binding.chipLut1.text = lut1
                    binding.chipLut1.visibility = View.VISIBLE
                } else {
                    binding.chipLut1.visibility = View.GONE
                }
                if (lut2 != null) {
                    binding.chipLut2.text = lut2
                    binding.chipLut2.visibility = View.VISIBLE
                } else {
                    binding.chipLut2.visibility = View.GONE
                }
            } else {
                binding.rowLuts.visibility = View.GONE
            }
        } else {
            binding.rowLuts.visibility = View.GONE
            binding.cardPhotoStyle.visibility = View.GONE
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
        viewLifecycleOwner.lifecycleScope.launch {
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
            val (gcjLat, gcjLng) = CoordinateUtils.wgs84ToGcj02(lat, lng)

            val intents = mutableListOf<Intent>()

            // 高德 — WGS-84 + dev=1（由高德内部转换 GCJ-02）
            Intent(Intent.ACTION_VIEW,
                Uri.parse("androidamap://viewMap?lat=$lat&lon=$lng&dev=1")).also {
                it.setPackage("com.autonavi.minimap")
                intents.add(it)
            }

            // 腾讯 — WGS-84（原始值）
            Intent(Intent.ACTION_VIEW,
                Uri.parse("qqmap://map/geocoder?coord=$lat,$lng&referer=StarGallery")).also {
                it.setPackage("com.tencent.map")
                intents.add(it)
            }

            // 百度 — WGS-84（原始值）
            Intent(Intent.ACTION_VIEW,
                Uri.parse("baidumap://map/geocoder?location=$lat,$lng")).also {
                it.setPackage("com.baidu.BaiduMap")
                intents.add(it)
            }

            // 谷歌 — GCJ-02
            Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:$gcjLat,$gcjLng?q=$gcjLat,$gcjLng")).also {
                it.setPackage("com.google.android.apps.maps")
                intents.add(it)
            }

            // 兜底 — WGS-84（RFC geo: URI 标准）
            val fallback = Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:$lat,$lng?q=$lat,$lng"))

            val chooser = Intent.createChooser(fallback, "选择地图")
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            startActivity(chooser)
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
