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
    
    init {
        setupEdgeSwipeDetection()
        setupTapDetection()
    }
    
    private fun setupEdgeSwipeDetection() {
        binding.ivPhoto.onEdgeSwipeListener = object : EdgeSubsamplingScaleImageView.OnEdgeSwipeListener {
            override fun onEdgeSwipe(isSwipeRight: Boolean) {
                onEdgeSwipe?.invoke(isSwipeRight)
            }
        }
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
            }
            // 返回 false 让事件继续传递给 SSIV，SSIV 会处理双击放大
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
    fun bind(photo: Photo) {
        currentPhoto = photo
        binding.progressBar.visibility = View.VISIBLE
        
        when {
            photo.isVideo -> loadVideo(photo)
            photo.isGif -> loadGif(photo)
            else -> loadImage(photo)
        }
    }
    
    /**
     * 加载视频
     */
    @OptIn(UnstableApi::class)
    private fun loadVideo(photo: Photo) {
        binding.ivPhoto.visibility = View.GONE
        binding.ivGif.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        
        // 视频时启用 ViewPager2 滑动
        viewPagerSwipeController?.invoke(true)
        
        exoPlayer = ExoPlayer.Builder(binding.root.context).build().apply {
            binding.videoView.player = this
            val mediaItem = MediaItem.fromUri(photo.uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        
        binding.progressBar.visibility = View.GONE
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
        
        binding.ivPhoto.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() {
                binding.progressBar.visibility = View.GONE
                // 图片加载完成，检测缩放状态控制 ViewPager2
                updateViewPagerSwipeState()
            }

            override fun onImageLoaded() {
                binding.progressBar.visibility = View.GONE
                updateViewPagerSwipeState()
            }

            override fun onPreviewLoadError(e: Exception?) {}

            override fun onImageLoadError(e: Exception?) {
                binding.progressBar.visibility = View.GONE
            }

            override fun onTileLoadError(e: Exception?) {}

            override fun onPreviewReleased() {}
        })
        
        // 监听缩放变化
        binding.ivPhoto.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
            override fun onScaleChanged(newScale: Float, origin: Int) {
                updateViewPagerSwipeState()
            }

            override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                updateViewPagerSwipeState()
            }
        })
        
        binding.ivPhoto.setDoubleTapZoomDuration(300)
        binding.ivPhoto.setDoubleTapZoomScale(3f)
        binding.ivPhoto.setMaxScale(5f)
        
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
     * 更新 ViewPager2 滑动状态
     * 图片放大时禁用 ViewPager2 滑动，恢复原大时启用
     */
    private fun updateViewPagerSwipeState() {
        if (!binding.ivPhoto.isReady) return
        
        val scale = binding.ivPhoto.scale
        val minScale = binding.ivPhoto.minScale
        
        // 只有在图片放大时才禁用 ViewPager2 滑动
        val isZoomed = scale > minScale * 1.01f
        viewPagerSwipeController?.invoke(!isZoomed)
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
     * 清理资源
     */
    fun recycle() {
        handler.removeCallbacksAndMessages(null)
        
        exoPlayer?.release()
        exoPlayer = null
        
        tempFile?.delete()
        tempFile = null
        
        // 清理图片资源
        binding.ivPhoto.recycle()
        binding.ivPhoto.setOnImageEventListener(null)
        binding.ivPhoto.setOnStateChangedListener(null)
        binding.ivPhoto.setOnTouchListener(null)
        
        Glide.with(binding.root.context).clear(binding.ivGif)
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
