package com.gxstar.stargallery.ui.detail

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ContentImageSource
import com.github.panpf.zoomimage.view.zoom.ScrollBarSpec
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val onSingleTap: (() -> Unit)? = null,
    private val hdrDisplayEnabled: () -> Boolean = { true }
) {
    private var exoPlayer: ExoPlayer? = null
    private var currentPhoto: Photo? = null

    /** 从 context 链中提取真正的 Activity */
    private val activity: Activity? by lazy {
        var ctx = binding.root.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return@lazy ctx
            ctx = ctx.baseContext
        }
        ctx as? Activity
    }

    @OptIn(UnstableApi::class)
    private var viewHolderScope: CoroutineScope? = null
    private var viewHolderJob: Job? = null

    private var downX = 0f
    private var lastX = 0f
    private var isAtLeftEdge = false
    private var isAtRightEdge = false
    private var hasNotifiedEdgeSwipe = false
    private var lastEdgeDirection = 0

    /** 当前页面是否可见（由 Adapter 设置） */
    var isActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // 变为可见时，重新应用之前存储的窗口模式
                if (value && lastAppliedHdrMode) {
                    applyWindowColorMode(true)
                }
            }
        }

    /** 最近一次生效的窗口模式 */
    private var lastAppliedHdrMode: Boolean = false

    /** 用于延迟执行 window.colorMode 变更的 Handler，避免打断 ViewPager2 触摸/滚动 */
    private val hdrHandler = Handler(Looper.getMainLooper())

    private val swipeThreshold = 10f

    init {
        setupZoomImageView()
        setupTapDetection()
    }

    private fun setupZoomImageView() {
        // 启用滚动条并显示当前浏览位置（大图子采样后用户可见所处区域）。
        // 传入 windowInsetsTypeMask 让滚动条避开系统状态栏/导航栏，
        // 与底部工具栏的 systemBars inset 处理思路一致。
        binding.ivPhoto.scrollBar = ScrollBarSpec(
            windowInsetsTypeMask = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        )
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
        if (isImageZoomed()) return
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

        // 取消当前 ViewHolder 自己的协程（不影响其他 ViewHolder）
        viewHolderJob?.cancel()
        // 创建独立的子协程作用域，与 lifecycleScope 解耦
        viewHolderJob = scope?.let { SupervisorJob(it.coroutineContext[Job]) }
        viewHolderScope = scope?.let { CoroutineScope(it.coroutineContext + viewHolderJob!!) }

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
     */
    private fun loadImage(photo: Photo) {
        setMediaVisibility(photo = true)
        binding.progressBar.visibility = View.VISIBLE

        val ctx = binding.root.context
        val maxDimension = maxOf(photo.width, photo.height)
        // JXL 不支援 BitmapRegionDecoder，無法使用子採樣
        val needSubsampling = !photo.isJxl && (maxDimension >= 2000 || photo.isRaw)

        val shouldProbeHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && photo.isUltraHdr
            && hdrDisplayEnabled()

        if (shouldProbeHdr) {
            checkHdrAndLoad(photo, maxDimension, needSubsampling, ctx)
        } else if (needSubsampling) {
            loadWithSubsampling(photo, ctx)
        } else {
            loadFullImage(photo, ctx)
        }
    }

    /**
     * 快速探测 gainmap 并走对应路径
     */
    private fun checkHdrAndLoad(photo: Photo, maxDim: Int, needSubsample: Boolean, ctx: Context) {
        viewHolderScope?.launch(Dispatchers.IO) {
            val hasGainmap = try {
                val source = ImageDecoder.createSource(ctx.contentResolver, photo.uri)
                val probe = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSize(200, 200)
                }
                val result = probe.hasGainmap()
                probe.recycle()
                result
            } catch (_: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                if (hasGainmap) {
                    loadHdrBitmap(photo, maxDim, ctx)
                } else if (needSubsample) {
                    applyWindowColorMode(false)
                    loadWithSubsampling(photo, ctx)
                } else {
                    applyWindowColorMode(false)
                    loadFullImage(photo, ctx)
                }
            }
        }
    }

    /**
     * 用 ImageDecoder 解码完整图像，保留 gainmap
     * 超出 MAX_HDR_DECODE_PX 时长边等比缩放
     */
    private fun loadHdrBitmap(photo: Photo, maxDim: Int, ctx: Context) {
        viewHolderScope?.launch(Dispatchers.IO) {
            val bitmap = try {
                val source = ImageDecoder.createSource(ctx.contentResolver, photo.uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_HDR_DECODE_PX) {
                        val scale = MAX_HDR_DECODE_PX.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt(),
                            (info.size.height * scale).toInt()
                        )
                    }
                }
                // decodeBitmap 的返回值就是解码后的 Bitmap
            } catch (_: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    val hasGainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
                    binding.ivPhoto.setImageBitmap(bitmap)
                    applyWindowColorMode(hasGainmap)
                    updateEdgeState()
                } else {
                    loadFullImage(photo, ctx)
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 直接 Glide 加载完整图片（适用于小图 / 非 HDR 回退）
     */
    private fun loadFullImage(photo: Photo, ctx: Context) {
        Glide.with(ctx)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .fitCenter()
            .into(binding.ivPhoto)
        binding.progressBar.visibility = View.GONE
    }

    /**
     * 子采样加载大图：先 Glide 预览，再启用 ZoomImageView 子采样
     */
    private fun loadWithSubsampling(photo: Photo, ctx: Context) {
        Glide.with(ctx)
            .asBitmap()
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .override(1200)
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    binding.ivPhoto.setImageBitmap(resource)
                    binding.progressBar.visibility = View.GONE
                    updateEdgeState()
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
     * 设置 Activity 窗口的 HDR/SDR 色彩模式
     * window.colorMode 改动会触发 SurfaceFlinger surface 重配置，
     * 通过 Handler.post 推迟到当前消息批次后执行，避免打断 ViewPager2 状态转换。
     * 使用 Handler 而非 View.post 是为了在 recycle() 时能通过
     * removeCallbacksAndMessages 精确清理未执行的变更，防止旧页面影响新页面。
     */
    private fun applyWindowColorMode(isHdr: Boolean) {
        lastAppliedHdrMode = isHdr
        if (!isActive) {
            return
        }
        hdrHandler.removeCallbacksAndMessages(null)
        hdrHandler.post {
            if (!isActive) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val window = activity?.window
                window?.colorMode = if (isHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    /**
     * 重置窗口色彩模式为默认（SDR），同步执行，用于 recycle 场景
     */
    private fun resetWindowColorMode() {
        if (!isActive) return
        lastAppliedHdrMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val window = activity?.window
            window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
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
        return try {
            val engine = binding.ivPhoto.zoomable
            val scale = engine.transformState.value.scaleX
            val minScale = engine.minScaleState.value
            scale > minScale + 0.01f
        } catch (_: Exception) {
            false
        }
    }

    fun resetZoom() {
        // ZoomImageView 会自动处理缩放重置
    }

    fun recycle() {
        hdrHandler.removeCallbacksAndMessages(null)

        viewHolderJob?.cancel()
        viewHolderJob = null
        viewHolderScope = null

        resetWindowColorMode()

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
        private const val MAX_HDR_DECODE_PX = 4096

        fun create(
            parent: ViewGroup,
            onEdgeSwipe: ((isSwipeRight: Boolean) -> Unit)? = null,
            viewPagerSwipeController: ((enabled: Boolean) -> Unit)? = null,
            onSingleTap: (() -> Unit)? = null,
            hdrDisplayEnabled: () -> Boolean = { true }
        ): PhotoPageViewHolder {
            val binding = ItemPhotoPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PhotoPageViewHolder(binding, onEdgeSwipe, viewPagerSwipeController, onSingleTap, hdrDisplayEnabled)
        }
    }
}
