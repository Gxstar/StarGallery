package com.gxstar.stargallery.ui.detail

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

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
    private var tempFile: File? = null
    private var currentPhoto: Photo? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSingleTap = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var tapCount = 0
    
    private val doubleTapTimeout = 250L
    private val tapDistanceThreshold = 20f
    
    private var lastX = 0f
    private var isAtLeftEdge = false
    private var isAtRightEdge = false
    
    init {
        setupEdgeSwipeDetection()
        setupTapDetection()
    }
    
    private fun setupEdgeSwipeDetection() {
        binding.ivPhoto.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    isAtLeftEdge = false
                    isAtRightEdge = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    updateEdgeState()
                    
                    if (dx > 0 && isAtLeftEdge) {
                        onEdgeSwipe?.invoke(true)
                        viewPagerSwipeController?.invoke(true)
                    } else if (dx < 0 && isAtRightEdge) {
                        onEdgeSwipe?.invoke(false)
                        viewPagerSwipeController?.invoke(true)
                    }
                    lastX = event.x
                }
            }
            false
        }
    }
    
    private fun updateEdgeState() {
        if (!binding.ivPhoto.isReady) {
            isAtLeftEdge = true
            isAtRightEdge = true
            return
        }
        
        val scale = binding.ivPhoto.scale
        val minScale = binding.ivPhoto.minScale
        
        if (scale <= minScale * 1.01f) {
            isAtLeftEdge = true
            isAtRightEdge = true
            return
        }
        
        val center = binding.ivPhoto.center ?: PointF(0.5f, 0.5f)
        val sWidth = binding.ivPhoto.sWidth.toFloat()
        val viewWidth = binding.ivPhoto.width.toFloat()
        
        if (sWidth <= 0) {
            isAtLeftEdge = true
            isAtRightEdge = true
            return
        }
        
        val centerX = center.x / sWidth
        val visibleRatio = viewWidth / (sWidth * scale)
        val visibleLeft = centerX - visibleRatio / 2
        val visibleRight = centerX + visibleRatio / 2
        
        isAtLeftEdge = visibleLeft <= 0.01f
        isAtRightEdge = visibleRight >= 0.99f
    }
    
    private fun setupImageEventListener() {
        binding.ivPhoto.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() {
                binding.progressBar.visibility = View.GONE
                updateEdgeState()
            }
            override fun onImageLoaded() {
                binding.progressBar.visibility = View.GONE
                updateEdgeState()
            }
            override fun onPreviewLoadError(e: Exception?) {}
            override fun onImageLoadError(e: Exception?) {
                binding.progressBar.visibility = View.GONE
            }
            override fun onTileLoadError(e: Exception?) {}
            override fun onPreviewReleased() {}
        })
    }
    
    private fun setupTapDetection() {
        binding.ivPhoto.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    val distance = sqrt((event.x - downX) * (event.x - downX) + (event.y - downY) * (event.y - downY))
                    
                    if (duration < 300 && distance < tapDistanceThreshold) {
                        tapCount++
                        if (tapCount == 1) {
                            pendingSingleTap = true
                            handler.postDelayed({
                                if (pendingSingleTap) {
                                    onSingleTap?.invoke()
                                    tapCount = 0
                                    pendingSingleTap = false
                                }
                            }, doubleTapTimeout)
                        } else if (tapCount == 2) {
                            pendingSingleTap = false
                            tapCount = 0
                            handler.removeCallbacksAndMessages(null)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    updateEdgeState()
                    if ((dx > 0 && isAtLeftEdge) || (dx < 0 && isAtRightEdge)) {
                        viewPagerSwipeController?.invoke(true)
                    }
                    lastX = event.x
                }
            }
            false
        }
        
        binding.ivGif.setOnClickListener { onSingleTap?.invoke() }
        binding.videoView.setOnClickListener { onSingleTap?.invoke() }
        binding.mediaContainer.setOnClickListener { onSingleTap?.invoke() }
    }
    
    // 当前标签设置
    private var currentSelectedTags: Set<TagType> = TagType.entries.toSet()

    @OptIn(UnstableApi::class)
    fun bind(photo: Photo) {
        currentPhoto = photo
        binding.progressBar.visibility = View.VISIBLE
        setupImageEventListener()

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
                photo.needsGlideLoad -> loadModernImage(photo)  // AVIF/HEIC
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
            // 清除现有标签（保留 RAW 标签作为模板）
            binding.tvRawTag.visibility = View.GONE
            while (binding.tagsContainer.childCount > 1) {
                binding.tagsContainer.removeViewAt(binding.tagsContainer.childCount - 1)
            }

            val tags = mutableListOf<String>()

            // 根据照片属性和用户设置添加标签
            if (photo.isRaw && currentSelectedTags.contains(TagType.RAW)) {
                tags.add("RAW")
            }

            // 立即显示基础标签
            displayTags(tags)

            // 如果启用了相机品牌标签，异步读取
            if (!photo.isVideo && !photo.isGif && currentSelectedTags.contains(TagType.CAMERA_MAKE)) {
                CoroutineScope(Dispatchers.Main).launch {
                    val makeTag = readCameraMake(photo)
                    makeTag?.let {
                        if (!tags.contains(it)) {
                            tags.add(it)
                            displayTags(tags)
                        }
                    }
                }
            }

            // 如果启用了照片风格标签，异步读取
            if (!photo.isVideo && !photo.isGif && currentSelectedTags.contains(TagType.PHOTO_STYLE)) {
                CoroutineScope(Dispatchers.Main).launch {
                    val photoStyleTag = readPhotoStyle(photo)
                    photoStyleTag?.let {
                        if (!tags.contains(it)) {
                            tags.add(it)
                            displayTags(tags)
                        }
                    }
                }
            }
        }
    }

    /**
     * 设置标签显示
     * 根据照片属性和用户设置动态添加标签
     */
    private fun setupTags(photo: Photo) {
        // 清除现有标签（保留 RAW 标签作为模板）
        binding.tvRawTag.visibility = View.GONE
        // 移除动态添加的标签
        while (binding.tagsContainer.childCount > 1) {
            binding.tagsContainer.removeViewAt(binding.tagsContainer.childCount - 1)
        }

        val tags = mutableListOf<String>()

        // 根据照片属性和用户设置添加标签
        if (photo.isRaw && currentSelectedTags.contains(TagType.RAW)) {
            tags.add("RAW")
        }

        // 显示基础标签
        displayTags(tags)

        // 异步读取 EXIF 设备品牌信息
        if (!photo.isVideo && !photo.isGif && currentSelectedTags.contains(TagType.CAMERA_MAKE)) {
            CoroutineScope(Dispatchers.Main).launch {
                val makeTag = readCameraMake(photo)
                makeTag?.let {
                    if (!tags.contains(it)) {
                        tags.add(it)
                        displayTags(tags)
                    }
                }
            }
        }

        // 异步读取松下相机 PhotoStyle
        if (!photo.isVideo && !photo.isGif && currentSelectedTags.contains(TagType.PHOTO_STYLE)) {
            CoroutineScope(Dispatchers.Main).launch {
                val photoStyleTag = readPhotoStyle(photo)
                photoStyleTag?.let {
                    if (!tags.contains(it)) {
                        tags.add(it)
                        displayTags(tags)
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

    fun isImageZoomed(): Boolean {
        return binding.ivPhoto.isReady && binding.ivPhoto.scale > binding.ivPhoto.minScale * 1.01f
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
     * 使用 Glide 加载 AVIF/HEIC 等现代图片格式
     * SubsamplingScaleImageView 无法对这些格式进行分块加载优化，因此使用 Glide + ImageView
     */
    private fun loadModernImage(photo: Photo) {
        setMediaVisibility(gif = true)  // 复用 GIF 的 ImageView
        viewPagerSwipeController?.invoke(true)
        Glide.with(binding.root.context)
            .load(photo.uri)
            .into(binding.ivGif)
        binding.progressBar.visibility = View.GONE
    }
    
    private fun loadImage(photo: Photo) {
        setMediaVisibility(photo = true)
        binding.ivPhoto.orientation = photo.orientation
        binding.ivPhoto.setDoubleTapZoomDuration(250)
        binding.ivPhoto.setDoubleTapZoomScale(2.0f)
        binding.ivPhoto.setMaxScale(5.0f)
        
        CoroutineScope(Dispatchers.Main).launch {
            val file = downloadImageToCache(photo)
            if (file != null && file.exists()) {
                tempFile = file
                binding.ivPhoto.setImage(ImageSource.uri(file.absolutePath))
            } else {
                binding.ivPhoto.setImage(ImageSource.uri(photo.uri))
            }
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
    
    private suspend fun downloadImageToCache(photo: Photo): File? = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(binding.root.context.cacheDir, "photo_detail")
            if (!tempDir.exists()) tempDir.mkdirs()
            val extension = photo.mimeType.substringAfterLast("/", "jpg")
            val file = File(tempDir, "photo_${photo.id}.${extension}")
            
            if (file.exists() && file.length() == photo.size) return@withContext file
            
            binding.root.context.contentResolver.openInputStream(photo.uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) {
            null
        }
    }
    
    fun resetZoom() {
        if (binding.ivPhoto.isReady) binding.ivPhoto.resetScaleAndCenter()
    }
    
    fun recycle() {
        handler.removeCallbacksAndMessages(null)
        binding.videoView.player = null
        exoPlayer = null
        tempFile?.delete()
        tempFile = null

        // 重置图片缩放状态
        resetZoom()

        // 清理图片资源
        binding.ivPhoto.recycle()
        binding.ivPhoto.setOnImageEventListener(null)
        binding.ivPhoto.setOnTouchListener(null)

        Glide.with(binding.root.context).clear(binding.ivGif)
        Glide.with(binding.root.context).clear(binding.ivVideoCover)

        // 重置视频相关视图
        binding.ivVideoCover.visibility = View.GONE
        binding.ivPlayButton.visibility = View.GONE

        // 清理标签容器
        binding.tagsContainer.visibility = View.GONE
        // 移除动态添加的标签（保留第一个作为模板）
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