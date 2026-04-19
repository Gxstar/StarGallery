package com.gxstar.stargallery.ui.detail

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.github.panpf.zoomimage.ZoomImageView
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 单张媒体页面的 ViewHolder
 * 负责加载和显示照片/视频/GIF，并处理手势
 */
class PhotoPageViewHolder(
    internal val binding: ItemPhotoPageBinding,
    private val onEdgeSwipe: ((isSwipeRight: Boolean) -> Unit)? = null,
    private val viewPagerSwipeController: ((enabled: Boolean) -> Unit)? = null,
    private val onSingleTap: (() -> Unit)? = null
) {
    private var exoPlayer: ExoPlayer? = null
    private var currentPhoto: Photo? = null
    private var tagLoadingJob: Job? = null

    private var downX = 0f
    private var lastX = 0f
    private var isAtLeftEdge = false
    private var isAtRightEdge = false
    private var hasNotifiedEdgeSwipe = false
    private var lastEdgeDirection = 0

    private val swipeThreshold = 10f

    init {
        setupZoomImageView()
        setupTapDetection()
    }

    private fun setupZoomImageView() {
        // ZoomImageView 默认配置已足够，无需额外设置
    }

    private fun setupTapDetection() {
        // ZoomImageView 的双击缩放由库自动处理
        // 我们只需要处理单击事件
        binding.ivPhoto.apply {
            setOnClickListener {
                onSingleTap?.invoke()
            }
        }

        // 边缘滑动检测
        binding.ivPhoto.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            false
        }

        binding.ivGif.setOnClickListener { onSingleTap?.invoke() }
        binding.videoView.setOnClickListener { onSingleTap?.invoke() }
        binding.mediaContainer.setOnClickListener { onSingleTap?.invoke() }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                hasNotifiedEdgeSwipe = false
                lastEdgeDirection = 0
                updateEdgeState()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalDx = event.x - downX
                updateEdgeState()

                if (abs(totalDx) > swipeThreshold) {
                    if (dx > 0 && isAtLeftEdge) {
                        if (lastEdgeDirection != 1 && !hasNotifiedEdgeSwipe) {
                            lastEdgeDirection = 1
                            hasNotifiedEdgeSwipe = true
                            onEdgeSwipe?.invoke(true)
                            viewPagerSwipeController?.invoke(true)
                        }
                    } else if (dx < 0 && isAtRightEdge) {
                        if (lastEdgeDirection != 2 && !hasNotifiedEdgeSwipe) {
                            lastEdgeDirection = 2
                            hasNotifiedEdgeSwipe = true
                            onEdgeSwipe?.invoke(false)
                            viewPagerSwipeController?.invoke(true)
                        }
                    }
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hasNotifiedEdgeSwipe = false
                lastEdgeDirection = 0
            }
        }
    }

    private fun updateEdgeState() {
        // 简化边缘检测逻辑，ZoomImageView 处理缩放时自动禁用 ViewPager 滑动
        viewPagerSwipeController?.invoke(true)
    }

    // 当前标签设置
    private var currentSelectedTags: Set<TagType> = TagType.entries.toSet()

    @OptIn(UnstableApi::class)
    fun bind(photo: Photo) {
        currentPhoto = photo
        binding.progressBar.visibility = View.VISIBLE

        // 加载标签设置
        currentSelectedTags = TagSettingsManager.getSelectedTags(binding.root.context)

        // 显示/隐藏标签容器
        setupTags(photo)

        if (photo.isVideo && ExoPlayerManager.getCurrentVideoId() == photo.id) {
            restoreVideoPlayback(photo)
        } else {
            when {
                photo.isVideo -> loadVideo(photo)
                photo.isGif -> loadGif(photo)
                else -> loadImage(photo)
            }
        }
    }

    /**
     * 更新标签可见性（从设置对话框调用）
     * 会重新根据设置加载标签，包括异步读取 EXIF 信息
     */
    fun updateTagVisibility(selectedTags: Set<TagType>) {
        currentSelectedTags = selectedTags
        currentPhoto?.let { photo ->
            loadTagsAsync(photo)
        }
    }

    /**
     * 设置标签显示
     * 根据照片属性和用户设置动态添加标签
     */
    private fun setupTags(photo: Photo) {
        loadTagsAsync(photo)
    }

    /**
     * 异步加载标签（相机品牌、照片风格）
     * 使用 tagLoadingJob 管理生命周期，回收时自动取消
     */
    private fun loadTagsAsync(photo: Photo) {
        // 取消之前的异步加载
        tagLoadingJob?.cancel()

        // 清除现有标签（保留 RAW 标签作为模板）
        binding.tvRawTag.visibility = View.GONE
        while (binding.tagsContainer.childCount > 1) {
            binding.tagsContainer.removeViewAt(binding.tagsContainer.childCount - 1)
        }

        val tags = mutableListOf<String>()

        if (photo.isRaw && currentSelectedTags.contains(TagType.RAW)) {
            tags.add("RAW")
        }

        displayTags(tags)

        if (photo.isVideo || photo.isGif) return

        tagLoadingJob = CoroutineScope(Dispatchers.Main).launch {
            val newTags = tags.toMutableList()

            if (currentSelectedTags.contains(TagType.CAMERA_MAKE)) {
                val makeTag = readCameraMake(photo)
                makeTag?.let {
                    if (!newTags.contains(it)) {
                        newTags.add(it)
                        displayTags(newTags)
                    }
                }
            }

            if (currentSelectedTags.contains(TagType.PHOTO_STYLE)) {
                val photoStyleTag = readPhotoStyle(photo)
                photoStyleTag?.let {
                    if (!newTags.contains(it)) {
                        newTags.add(it)
                        displayTags(newTags)
                    }
                }
            }
        }
    }

    /**
     * 显示标签列表
     */
    private fun displayTags(tags: List<String>) {
        if (tags.isEmpty()) {
            binding.tagsContainer.visibility = View.GONE
            return
        }

        // 按固定优先级排序
        val sortedTags = tags.sortedBy { getTagPriority(it) }

        binding.tagsContainer.visibility = View.VISIBLE
        val density = binding.root.context.resources.displayMetrics.density
        val marginStart = (6 * density).toInt()

        // 动态添加标签
        sortedTags.forEachIndexed { index, tagText ->
            val tagView = if (index == 0) {
                // 复用第一个 TextView
                binding.tvRawTag.apply {
                    text = tagText
                    visibility = View.VISIBLE
                    // 第一个标签不需要左边距
                    (layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginStart = 0
                }
            } else {
                // 获取或创建标签
                if (index < binding.tagsContainer.childCount) {
                    binding.tagsContainer.getChildAt(index).apply {
                        // 后续标签添加左边距
                        (layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginStart = marginStart
                    } as android.widget.TextView
                } else {
                    createTagView(tagText).also {
                        binding.tagsContainer.addView(it)
                    }
                }
            }
            // 更新文本
            if (tagView is android.widget.TextView) {
                tagView.text = tagText
                tagView.visibility = View.VISIBLE
            }
        }

        // 隐藏多余的标签
        for (i in tags.size until binding.tagsContainer.childCount) {
            binding.tagsContainer.getChildAt(i).visibility = View.GONE
        }
    }

    /**
     * 异步读取 EXIF 中的相机品牌信息
     */
    private suspend fun readCameraMake(photo: Photo): String? = withContext(Dispatchers.IO) {
        try {
            binding.root.context.contentResolver.openInputStream(photo.uri)?.use { inputStream ->
                val metadata = ImageMetadataReader.readMetadata(inputStream)
                val exifIFD0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                val make = exifIFD0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()

                if (make.isNullOrBlank()) return@withContext null

                // 清理品牌名称（移除常见后缀）
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

                // 如果清理后为空，返回原始值
                cleaned.takeIf { it.isNotBlank() } ?: make
            }
        } catch (e: Exception) {
            null
        }
    }

    // PhotoStyle value mapping for Panasonic cameras (tag 0x0089)
    private val PHOTO_STYLE_MAP = mapOf(
        0 to "Auto",
        1 to "Standard",
        2 to "Vivid",
        3 to "Natural",
        4 to "Monochrome",
        5 to "Scenery",
        6 to "Portrait",
        8 to "Cinelike D",
        9 to "Cinelike V",
        11 to "L. Monochrome",
        12 to "Like709",
        15 to "L. Monochrome D",
        17 to "V-Log",
        18 to "Cinelike D2"
    )

    // 标签固定优先级（越小越靠前）
    private val TAG_PRIORITY = mapOf(
        "RAW" to 0,
        "Panasonic" to 1,
        "Canon" to 2,
        "NIKON" to 3,
        "SONY" to 4,
        "FUJIFILM" to 5
    )

    private fun getTagPriority(tag: String): Int {
        // RAW 最高优先级
        if (tag == "RAW") return 0
        // 相机品牌
        TAG_PRIORITY.entries.find { tag.startsWith(it.key) }?.let { return it.value }
        // PhotoStyle 值统一放最后
        if (PHOTO_STYLE_MAP.values.contains(tag)) return 100
        // 其他未匹配标签
        return 101
    }

    /**
     * 异步读取 EXIF 中的 Panasonic PhotoStyle
     */
    private suspend fun readPhotoStyle(photo: Photo): String? = withContext(Dispatchers.IO) {
        try {
            binding.root.context.contentResolver.openInputStream(photo.uri)?.use { inputStream ->
                val metadata = ImageMetadataReader.readMetadata(inputStream)
                val panasonicMakernote = metadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java)
                val photoStyleValue = panasonicMakernote?.getInteger(PanasonicMakernoteDirectory.TAG_PHOTO_STYLE)
                photoStyleValue?.let { PHOTO_STYLE_MAP[it] }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 动态创建标签 View
     */
    private fun createTagView(text: String): android.widget.TextView {
        val context = binding.root.context
        return android.widget.TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            background = context.getDrawable(R.drawable.bg_raw_tag)
            setPadding(
                (8 * context.resources.displayMetrics.density).toInt(),
                (3 * context.resources.displayMetrics.density).toInt(),
                (8 * context.resources.displayMetrics.density).toInt(),
                (3 * context.resources.displayMetrics.density).toInt()
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // 水平排列，标签之间 6dp 间距
                marginStart = if (binding.tagsContainer.childCount > 0) {
                    (6 * context.resources.displayMetrics.density).toInt()
                } else 0
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun restoreVideoPlayback(photo: Photo) {
        setMediaVisibility(video = true)
        viewPagerSwipeController?.invoke(true)
        exoPlayer = ExoPlayerManager.getPlayer(binding.root.context)
        binding.videoView.player = exoPlayer
        binding.videoView.showController()
        binding.progressBar.visibility = View.GONE
    }

    @OptIn(UnstableApi::class)
    private fun loadVideo(photo: Photo) {
        setMediaVisibility(videoCover = true)
        viewPagerSwipeController?.invoke(true)

        Glide.with(binding.root.context)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .into(binding.ivVideoCover)

        val startPlay = { startVideoPlayback(photo) }
        binding.ivPlayButton.setOnClickListener { startPlay() }
        binding.ivVideoCover.setOnClickListener { startPlay() }
        binding.progressBar.visibility = View.GONE
    }

    @OptIn(UnstableApi::class)
    private fun startVideoPlayback(photo: Photo) {
        setMediaVisibility(video = true)
        exoPlayer = ExoPlayerManager.getPlayer(binding.root.context)
        binding.videoView.player = exoPlayer
        binding.videoView.showController()

        if (ExoPlayerManager.getCurrentVideoId() != photo.id) {
            ExoPlayerManager.clear()
            ExoPlayerManager.play(photo.id, photo.uri, autoPlay = true)
        }
    }

    private fun loadGif(photo: Photo) {
        setMediaVisibility(gif = true)
        viewPagerSwipeController?.invoke(true)
        Glide.with(binding.root.context).asGif().load(photo.uri).into(binding.ivGif)
        binding.progressBar.visibility = View.GONE
    }

    /**
     * 使用 ZoomImageView 加载所有图片格式
     * ZoomImageView 支持子采样，无需区分格式
     * 同时检测并支持 Ultra HDR、HEIF HDR、AVIF HDR (Android 14+)
     * 
     * 对于 HEIF/AVIF HDR 图片，使用 RGBA_F16 格式保留高位深信息
     */
    private fun loadImage(photo: Photo) {
        setMediaVisibility(photo = true)

        // 判断是否可能是 HDR 格式（HEIF/AVIF/Ultra HDR）
        val isPotentialHdr = photo.isHeic || photo.isAvif || photo.isUltraHdr

        // 构建 Glide 请求
        val requestBuilder = Glide.with(binding.root.context)
            .asBitmap()
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)

        // 如果是潜在的 HDR 图片，使用 RGBA_F16 格式以保留高位深信息
        if (isPotentialHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestBuilder.apply(
                RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .encodeFormat(Bitmap.CompressFormat.PNG) // 无损格式保留更多信息
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
            )
        }

        requestBuilder.into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(
                resource: Bitmap,
                transition: Transition<in Bitmap>?
            ) {
                binding.ivPhoto.setImageBitmap(resource)
                binding.progressBar.visibility = View.GONE
                updateEdgeState()

                // 检测并设置 HDR 模式 (Android 14+)
                setupHdrMode(resource)
            }

            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                binding.ivPhoto.setImageDrawable(placeholder)
            }

            override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                binding.ivPhoto.setImageDrawable(errorDrawable)
                binding.progressBar.visibility = View.GONE
            }
        })
    }

    /**
     * 检测图片是否为 HDR 格式并设置窗口颜色模式
     * Android 14+ (API 34) 支持 Ultra HDR
     * 
     * 支持多种 HDR 格式：
     * - Ultra HDR (JPEG with Gainmap)
     * - HEIF/HEIC HDR
     * - AVIF HDR
     */
    private fun setupHdrMode(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val isHdr = isHdrBitmap(bitmap)
            val window = (binding.root.context as? Activity)?.window
            window?.colorMode = if (isHdr) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    /**
     * 检测 Bitmap 是否为 HDR 格式
     * 
     * 检测逻辑：
     * 1. Ultra HDR: 检查是否有 Gainmap (Android 14+)
     * 2. HEIF/HEIC/AVIF HDR: 检查 ColorSpace 是否为 HDR 色彩空间
     *    - 色域: BT.2020
     *    - 传输函数: PQ (ST2084) 或 HLG
     * 3. 高位深: 检查 Bitmap 配置是否为 RGBA_F16 (每通道 16 位浮点)
     */
    private fun isHdrBitmap(bitmap: Bitmap): Boolean {
        // 1. 检查是否为 Ultra HDR (JPEG with Gainmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (bitmap.hasGainmap()) {
                return true
            }
        }

        // 2. 检查 Bitmap 配置是否为高位深 (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (bitmap.config == Bitmap.Config.RGBA_F16) {
                // RGBA_F16 表示每通道 16 位浮点，是 HDR 图片的常见格式
                return true
            }
        }

        // 3. 检查 ColorSpace 是否为 HDR 色彩空间 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val colorSpace = bitmap.colorSpace
            if (colorSpace != null) {
                // 获取 ColorSpace 的名称进行判断
                val colorSpaceName = colorSpace.name
                
                // 常见的 HDR 色彩空间名称
                val hdrColorSpaces = setOf(
                    "BT2020",      // BT.2020 色域
                    "BT2020_HLG",  // BT.2020 + HLG
                    "BT2020_PQ",   // BT.2020 + PQ
                    "HDR",         // 通用 HDR
                    "LINEAR_EXTENDED_SRGB", // 扩展 SRGB
                )
                
                // 检查色彩空间名称是否包含 HDR 标识
                if (hdrColorSpaces.any { colorSpaceName?.contains(it, ignoreCase = true) == true }) {
                    return true
                }

                // 检查 ColorModel 是否为广色域
                if (colorSpace.model == android.graphics.ColorSpace.Model.RGB) {
                    // 检查是否为广色域色彩空间 (超出 sRGB 范围)
                    if (colorSpace.isWideGamut) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * 获取 Bitmap 的位深信息（用于调试）
     */
    private fun getBitmapBitDepth(bitmap: Bitmap): String {
        return when (bitmap.config) {
            Bitmap.Config.ALPHA_8 -> "8-bit (Alpha only)"
            Bitmap.Config.RGB_565 -> "16-bit (RGB 565)"
            Bitmap.Config.ARGB_4444 -> "16-bit (ARGB 4444)"
            Bitmap.Config.ARGB_8888 -> "32-bit (ARGB 8888, 8-bit per channel)"
            Bitmap.Config.RGBA_F16 -> "64-bit (RGBA F16, 16-bit float per channel)"
            Bitmap.Config.HARDWARE -> "Hardware"
            else -> "Unknown"
        }
    }

    private fun setMediaVisibility(
        photo: Boolean = false,
        gif: Boolean = false,
        videoCover: Boolean = false,
        video: Boolean = false
    ) {
        binding.ivPhoto.visibility = if (photo) View.VISIBLE else View.GONE
        binding.ivGif.visibility = if (gif) View.VISIBLE else View.GONE
        binding.ivVideoCover.visibility = if (videoCover) View.VISIBLE else View.GONE
        binding.ivPlayButton.visibility = if (videoCover) View.VISIBLE else View.GONE
        binding.videoView.visibility = if (video) View.VISIBLE else View.GONE
    }

    fun isImageZoomed(): Boolean {
        // 简化实现，ZoomImageView 内部处理缩放状态
        return false
    }

    fun resetZoom() {
        // ZoomImageView 会自动处理缩放重置
    }

    fun recycle() {
        tagLoadingJob?.cancel()
        tagLoadingJob = null
        currentPhoto = null
        binding.videoView.player = null
        exoPlayer = null

        // 重置图片缩放状态
        resetZoom()

        // 清理 Glide 加载的图片
        Glide.with(binding.root.context).clear(binding.ivPhoto)
        Glide.with(binding.root.context).clear(binding.ivGif)
        Glide.with(binding.root.context).clear(binding.ivVideoCover)

        // 重置视图可见性
        binding.ivPhoto.visibility = View.GONE
        binding.ivGif.visibility = View.GONE
        binding.ivVideoCover.visibility = View.GONE
        binding.ivPlayButton.visibility = View.GONE

        // 清理标签容器
        binding.tagsContainer.visibility = View.GONE
        while (binding.tagsContainer.childCount > 1) {
            binding.tagsContainer.removeViewAt(binding.tagsContainer.childCount - 1)
        }
        binding.tvRawTag.visibility = View.GONE
    }

    companion object {
        fun create(
            parent: ViewGroup,
            onEdgeSwipe: ((isSwipeRight: Boolean) -> Unit)? = null,
            viewPagerSwipeController: ((enabled: Boolean) -> Unit)? = null,
            onSingleTap: (() -> Unit)? = null
        ): PhotoPageViewHolder {
            val binding = ItemPhotoPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PhotoPageViewHolder(binding, onEdgeSwipe, viewPagerSwipeController, onSingleTap)
        }
    }
}
