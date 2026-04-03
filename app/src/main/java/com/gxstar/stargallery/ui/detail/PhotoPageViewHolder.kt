package com.gxstar.stargallery.ui.detail
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
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
 * 单张图片页面的 ViewHolder
 * 负责加载和显示单张图片/视频/GIF
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
    
    // 单击/双击检测
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSingleTap = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var tapCount = 0
    
    // 双击检测时间窗口
    private val doubleTapTimeout = 300L
    // 单击判定阈值
    private val tapDistanceThreshold = 30f
    
    // 边缘滑动检测
    private var lastX = 0f
    private var isAtLeftEdge = false
    private var isAtRightEdge = false
    
    init {
        setupEdgeSwipeDetection()
        setupTapDetection()
    }
    
    private fun setupEdgeSwipeDetection() {
        binding.ivPhoto.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    isAtLeftEdge = false
                    isAtRightEdge = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    updateEdgeState()
                    
                    // 向右滑动（手指向右，想看左边内容或切换到上一张）
                    if (dx > 0 && isAtLeftEdge) {
                        onEdgeSwipe?.invoke(true)
                        viewPagerSwipeController?.invoke(true)
                    }
                    // 向左滑动（手指向左，想看右边内容或切换到下一张）
                    else if (dx < 0 && isAtRightEdge) {
                        onEdgeSwipe?.invoke(false)
                        viewPagerSwipeController?.invoke(true)
                    }
                    
                    lastX = event.x
                }
            }
            // 返回 false 让事件继续传递给 SSIV
            false
        }
    }
    
    /**
     * 更新边缘状态
     */
    private fun updateEdgeState() {
        if (!binding.ivPhoto.isReady) {
            isAtLeftEdge = true
            isAtRightEdge = true
            return
        }
        
        val scale = binding.ivPhoto.scale
        val minScale = binding.ivPhoto.minScale
        
        // 如果没有放大，两边都是边缘
        if (scale <= minScale * 1.01f) {
            isAtLeftEdge = true
            isAtRightEdge = true
            return
        }
        
        // 获取图片可视区域
        val center = binding.ivPhoto.center ?: PointF(0.5f, 0.5f)
        val sWidth = binding.ivPhoto.sWidth.toFloat()
        val viewWidth = binding.ivPhoto.width.toFloat()
        
        if (sWidth <= 0) {
            isAtLeftEdge = true
            isAtRightEdge = true
            return
        }
        
        // 计算可视区域的中心在图片坐标系中的位置（0-1 之间）
        val centerX = center.x / sWidth
        
        // 计算可视区域的宽度占图片的比例
        val visibleRatio = viewWidth / (sWidth * scale)
        
        // 计算可视区域左右边界在图片坐标系中的位置
        val visibleLeft = centerX - visibleRatio / 2
        val visibleRight = centerX + visibleRatio / 2
        
        // 判断是否到达边缘
        isAtLeftEdge = visibleLeft <= 0.02f
        isAtRightEdge = visibleRight >= 0.98f
    }
    
    /**
     * 设置图片加载事件监听器
     */
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
    
    /**
     * 设置单击/双击检测
     * 使用延迟策略：单击延迟执行，如果在延迟期间有第二次点击则取消单击
     */
    private fun setupTapDetection() {
        // 为 SSIV 设置触摸监听，处理单击并让双击传递给 SSIV
        binding.ivPhoto.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                }
                MotionEvent.ACTION_UP -> {
                    val upTime = System.currentTimeMillis()
                    val upX = event.x
                    val upY = event.y
                    
                    val duration = upTime - downTime
                    val distance = sqrt((upX - downX) * (upX - downX) + (upY - downY) * (upY - downY))
                    
                    // 判断是否为有效点击
                    if (duration < 300 && distance < tapDistanceThreshold) {
                        tapCount++
                        
                        if (tapCount == 1) {
                            // 第一次点击，延迟执行单击
                            pendingSingleTap = true
                            handler.postDelayed({
                                if (pendingSingleTap && tapCount == 1) {
                                    // 执行单击
                                    onSingleTap?.invoke()
                                }
                                pendingSingleTap = false
                                tapCount = 0
                            }, doubleTapTimeout)
                        } else if (tapCount == 2) {
                            // 第二次点击，取消单击，让 SSIV 处理双击
                            pendingSingleTap = false
                            tapCount = 0
                            handler.removeCallbacksAndMessages(null)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    updateEdgeState()
                    
                    // 向右滑动（手指向右，想看左边内容或切换到上一张）
                    if (dx > 0 && isAtLeftEdge) {
                        onEdgeSwipe?.invoke(true)
                        viewPagerSwipeController?.invoke(true)
                    }
                    // 向左滑动（手指向左，想看右边内容或切换到下一张）
                    else if (dx < 0 && isAtRightEdge) {
                        onEdgeSwipe?.invoke(false)
                        viewPagerSwipeController?.invoke(true)
                    }
                    
                    lastX = event.x
                }
            }
            // 返回 false 让事件继续传递给 SSIV
            false
        }
        
        // 为 GIF 设置单击监听
        binding.ivGif.setOnClickListener {
            onSingleTap?.invoke()
        }
        
        // 为视频容器设置单击监听
        binding.videoView.setOnClickListener {
            onSingleTap?.invoke()
        }
    }
    
    /**
     * 绑定照片数据
     */
    @OptIn(UnstableApi::class)
    fun bind(photo: Photo) {
        currentPhoto = photo
        
        // 重置视图状态
        binding.progressBar.visibility = View.VISIBLE
        
        // 重新设置图片加载事件监听器（recycle 时会被清除）
        setupImageEventListener()
        
        // 显示 RAW 标签
        updateRawTag(photo)
        
        // 如果是视频且正在播放，恢复播放状态
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
     * 恢复视频播放状态（滑动回来时）
     */
    @OptIn(UnstableApi::class)
    private fun restoreVideoPlayback(photo: Photo) {
        binding.ivPhoto.visibility = View.GONE
        binding.ivGif.visibility = View.GONE
        binding.ivVideoCover.visibility = View.GONE
        binding.ivPlayButton.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        
        viewPagerSwipeController?.invoke(true)
        
        exoPlayer = ExoPlayerManager.getPlayer(binding.root.context)
        binding.videoView.player = exoPlayer
        
        binding.progressBar.visibility = View.GONE
    }
    
    /**
     * 更新 RAW 标签显示
     */
    private fun updateRawTag(photo: Photo) {
        when {
            photo.isRaw -> {
                binding.tvRawTag.visibility = View.VISIBLE
                binding.tvRawTag.text = "RAW"
            }
            photo.pairedRawId != null -> {
                // 如果是 JPG+RAW 组合，显示组合标签
                binding.tvRawTag.visibility = View.VISIBLE
                binding.tvRawTag.text = photo.getBaseFormatName()
            }
            else -> {
                binding.tvRawTag.visibility = View.GONE
            }
        }
    }
    
    /**
     * 设置 RAW 标签可见性
     */
    fun setRawTagVisibility(visible: Boolean) {
        if (visible && (currentPhoto?.isRaw == true || currentPhoto?.pairedRawId != null)) {
            binding.tvRawTag.visibility = View.VISIBLE
        } else {
            binding.tvRawTag.visibility = View.GONE
        }
    }
    
    /**
     * 检测图片是否处于放大状态
     */
    fun isImageZoomed(): Boolean {
        if (!binding.ivPhoto.isReady) return false
        return binding.ivPhoto.scale > binding.ivPhoto.minScale * 1.01f
    }
    
    /**
     * 加载视频
     */
    @OptIn(UnstableApi::class)
    private fun loadVideo(photo: Photo) {
        binding.ivPhoto.visibility = View.GONE
        binding.ivGif.visibility = View.GONE
        
        // 视频时启用 ViewPager2 滑动
        viewPagerSwipeController?.invoke(true)
        
        // 显示视频封面和播放按钮
        binding.ivVideoCover.visibility = View.VISIBLE
        binding.ivPlayButton.visibility = View.VISIBLE
        binding.videoView.visibility = View.GONE
        
        // 使用 Glide 加载视频第一帧作为封面
        Glide.with(binding.root.context)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .into(binding.ivVideoCover)
        
        // 点击播放按钮开始播放
        binding.ivPlayButton.setOnClickListener {
            startVideoPlayback(photo)
        }
        
        // 点击封面也触发播放
        binding.ivVideoCover.setOnClickListener {
            startVideoPlayback(photo)
        }
        
        binding.progressBar.visibility = View.GONE
    }
    
    /**
     * 开始视频播放
     */
    @OptIn(UnstableApi::class)
    private fun startVideoPlayback(photo: Photo) {
        // 隐藏封面和播放按钮
        binding.ivVideoCover.visibility = View.GONE
        binding.ivPlayButton.visibility = View.GONE
        
        // 显示播放器
        binding.videoView.visibility = View.VISIBLE
        
        // 使用单例播放器
        exoPlayer = ExoPlayerManager.getPlayer(binding.root.context)
        binding.videoView.player = exoPlayer
        
        // 如果正在播放其他视频，先停止
        if (ExoPlayerManager.getCurrentVideoId() != photo.id) {
            ExoPlayerManager.clear()
            ExoPlayerManager.play(photo.id, photo.uri, autoPlay = true)
        }
    }
    
    /**
     * 加载 GIF
     */
    private fun loadGif(photo: Photo) {
        binding.ivPhoto.visibility = View.GONE
        binding.videoView.visibility = View.GONE
        binding.ivGif.visibility = View.VISIBLE
        
        // GIF 时启用 ViewPager2 滑动
        viewPagerSwipeController?.invoke(true)
        
        Glide.with(binding.root.context)
            .asGif()
            .load(photo.uri)
            .into(binding.ivGif)
        
        binding.progressBar.visibility = View.GONE
    }
    
    /**
     * 加载普通图片
     */
    private fun loadImage(photo: Photo) {
        binding.ivGif.visibility = View.GONE
        binding.videoView.visibility = View.GONE
        binding.ivPhoto.visibility = View.VISIBLE
        
        // 设置图片旋转角度
        binding.ivPhoto.orientation = photo.orientation
        
        // 设置双击放大参数
        binding.ivPhoto.setDoubleTapZoomDuration(300)
        // 双击放大倍率：从最小缩放 1.8 倍，更自然的放大效果
        binding.ivPhoto.setDoubleTapZoomScale(1.8f)
        binding.ivPhoto.setMaxScale(4f)
        
        // 异步加载图片
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val file = downloadImageToCache(photo)
                
                if (file != null && file.exists()) {
                    tempFile = file
                    binding.ivPhoto.setImage(ImageSource.uri(file.absolutePath))
                } else {
                    binding.ivPhoto.setImage(ImageSource.uri(photo.uri))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                try {
                    binding.ivPhoto.setImage(ImageSource.uri(photo.uri))
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 下载图片到缓存
     */
    private suspend fun downloadImageToCache(photo: Photo): File? = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(binding.root.context.cacheDir, "photo_detail")
            if (!tempDir.exists()) tempDir.mkdirs()
            
            val extension = when {
                photo.mimeType == "image/png" -> "png"
                photo.mimeType == "image/gif" -> "gif"
                photo.mimeType == "image/webp" -> "webp"
                else -> "jpg"
            }
            
            val file = File(tempDir, "photo_${photo.id}.${extension}")
            
            if (file.exists() && file.length() == photo.size) {
                return@withContext file
            }
            
            binding.root.context.contentResolver.openInputStream(photo.uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 重置图片到原始大小
     */
    fun resetZoom() {
        if (binding.ivPhoto.isReady) {
            binding.ivPhoto.resetScaleAndCenter()
        }
    }
    
    /**
     * 清理资源
     */
    fun recycle() {
        handler.removeCallbacksAndMessages(null)
        
        // 清理视频播放器引用（但不释放单例）
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