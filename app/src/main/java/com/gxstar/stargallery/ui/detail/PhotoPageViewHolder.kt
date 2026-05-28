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
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ContentImageSource
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
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

    @OptIn(UnstableApi::class)
    private var viewHolderScope: CoroutineScope? = null

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

    fun bind(photo: Photo, scope: CoroutineScope? = null) {
        // 如果是同一张照片且已经加载过,跳过重新加载
        if (currentPhoto?.id == photo.id && currentPhoto?.uri == photo.uri) {
            currentPhoto = photo
            return
        }

        // 取消之前的作用域
        viewHolderScope?.coroutineContext?.cancelChildren()
        // 使用传入的 scope 或创建新的(用于预览图加载的协程)
        viewHolderScope = scope

        currentPhoto = photo
        binding.progressBar.visibility = View.VISIBLE

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
     * 
     * 优化策略：
     * 1. 小图（< 2000px）：直接用 Glide 加载原图，无需子采样
     * 2. 大图（>= 2000px）：先用 Glide 加载缩略图预览，再启用子采样加载高清区域
     * 3. 保持原始宽高比，不裁剪
     * 4. 支持 HDR 图片
     */
    private fun loadImage(photo: Photo) {
        setMediaVisibility(photo = true)
        binding.progressBar.visibility = View.VISIBLE

        val context = binding.root.context
        val isPotentialHdr = photo.isHeic || photo.isAvif || photo.isUltraHdr
        val maxDimension = maxOf(photo.width, photo.height)

        // 大图或 RAW 格式启用子采样（AVIF 通过 AvifRegionDecoder 使用 ImageDecoder 解码）
        val needSubsampling = maxDimension >= 2000 || photo.isRaw

        if (!needSubsampling) {
            loadFullImage(photo, isPotentialHdr)
        } else {
            loadWithSubsampling(photo, isPotentialHdr)
        }
    }

    /**
     * 直接加载完整图片（适用于小图）
     */
    private fun loadFullImage(photo: Photo, isPotentialHdr: Boolean) {
        val requestBuilder = Glide.with(binding.root.context)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .fitCenter()

        if (isPotentialHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestBuilder.apply(
                RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
            )
        }

        requestBuilder.into(binding.ivPhoto)
        binding.progressBar.visibility = View.GONE
    }

    /**
     * 使用子采样加载大图
     * 先显示缩略图,再启用子采样加载高清区域
     */
    private fun loadWithSubsampling(photo: Photo, isPotentialHdr: Boolean) {
        val context = binding.root.context

        // 第一步: 加载缩略图作为预览
        val previewRequest = Glide.with(context)
            .asBitmap()
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .override(1200) // 只指定一边,Glide 会自动保持比例
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)

        if (isPotentialHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            previewRequest.apply(
                RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
            )
        }

        previewRequest.into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    // 显示缩略图
                    binding.ivPhoto.setImageBitmap(resource)
                    binding.progressBar.visibility = View.GONE
                    updateEdgeState()
                    setupHdrMode(resource)

                    // 启用子采样 - 避免闪烁: 先设置子采样再清除缩略图
                    enableSubsampling(photo)
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
     * 启用子采样功能
     * ZoomImage 会自动根据缩放级别只加载可视区域的图片块(tiles)
     */
    private fun enableSubsampling(photo: Photo) {
        try {
            // 注册 AVIF 自定义区域解码器（使用 ImageDecoder 替代不支持的 BitmapRegionDecoder）
            binding.ivPhoto.subsampling.setRegionDecoders(listOf(AvifRegionDecoder.Factory()))
            val imageSource = ContentImageSource(binding.root.context, photo.uri)
            binding.ivPhoto.setSubsamplingImage(imageSource)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        return false
    }

    fun resetZoom() {
        // ZoomImageView 会自动处理缩放重置
    }

    fun recycle() {
        viewHolderScope?.coroutineContext?.cancelChildren()
        viewHolderScope = null

        if (currentPhoto?.isVideo == true) {
            ExoPlayerManager.pause()
        }
        currentPhoto = null
        binding.videoView.player = null
        exoPlayer = null

        // 重置图片缩放状态
        resetZoom()

        // 清理子采样资源（释放大图解码器）
        binding.ivPhoto.setSubsamplingImage(null as com.github.panpf.zoomimage.subsampling.ImageSource?)

        // 清理 Glide 加载的图片
        Glide.with(binding.root.context).clear(binding.ivPhoto)
        Glide.with(binding.root.context).clear(binding.ivGif)
        Glide.with(binding.root.context).clear(binding.ivVideoCover)

        // 重置视图可见性
        binding.ivPhoto.visibility = View.GONE
        binding.ivGif.visibility = View.GONE
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
