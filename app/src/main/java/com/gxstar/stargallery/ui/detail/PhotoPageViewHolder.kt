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
    
    @OptIn(UnstableApi::class)
    fun bind(photo: Photo) {
        currentPhoto = photo
        binding.progressBar.visibility = View.VISIBLE
        setupImageEventListener()
        updateRawTag(photo)
        
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
    
    @OptIn(UnstableApi::class)
    private fun restoreVideoPlayback(photo: Photo) {
        setMediaVisibility(video = true)
        viewPagerSwipeController?.invoke(true)
        exoPlayer = ExoPlayerManager.getPlayer(binding.root.context)
        binding.videoView.player = exoPlayer
        binding.progressBar.visibility = View.GONE
    }
    
    private fun updateRawTag(photo: Photo) {
        val isRawCapable = photo.isRaw || photo.pairedRawId != null
        binding.tvRawTag.visibility = if (isRawCapable) View.VISIBLE else View.GONE
        if (isRawCapable) {
            binding.tvRawTag.text = if (photo.isRaw) "RAW" else photo.getBaseFormatName()
        }
    }
    
    fun setRawTagVisibility(visible: Boolean) {
        val isRawCapable = currentPhoto?.isRaw == true || currentPhoto?.pairedRawId != null
        binding.tvRawTag.visibility = if (visible && isRawCapable) View.VISIBLE else View.GONE
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