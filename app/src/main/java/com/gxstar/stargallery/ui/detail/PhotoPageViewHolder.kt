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
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import com.awxkee.jxlcoder.JxlResizeFilter
import com.github.panpf.zoomimage.GlideZoomImageView
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
                if (value && lastAppliedColorMode == ColorMode.HDR) {
                    applyWindowColorMode(ColorMode.HDR)
                }
            }
        }

    /** 最近一次生效的窗口色彩模式 */
    private var lastAppliedColorMode: ColorMode = ColorMode.DEFAULT

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
        } else if (photo.isJxl) {
            // JXL 不走 Glide 的 content:// 解码路径（MediaStore 缩略图对 JXL 失败），
            // 直接用 jxl-coder 核心库解码字节流，确保 8-bit / 16-bit 均能正确显示。
            loadJxlDirect(photo, maxDimension, ctx)
        } else if (needSubsampling) {
            loadWithSubsampling(photo, ctx)
        } else {
            loadFullImage(photo, ctx)
        }
    }

    /**
     * 直接调用 jxl-coder 核心库解码 JXL 字节流，绕过 Glide 对 content:// Uri
     * 的 MediaStore 缩略图路径（该路径对 JXL 会 setDataSource 失败）。
     * 16-bit / 广色域 JXL 用 RGBA_F16 配置解码，并切换窗口为 WIDE_COLOR_GAMUT。
     */
    private fun loadJxlDirect(photo: Photo, maxDimension: Int, ctx: Context) {
        configureSubsampling(photo) // JXL 禁用子采样
        viewHolderScope?.launch(Dispatchers.IO) {
            val bitmap = try {
                ctx.contentResolver.openInputStream(photo.uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    // 限制解码尺寸，超大图按长边缩放，避免 OOM；
                    // DEFAULT 配置让 jxl-coder 自动按源位深选择（16-bit→RGBA_F16，8-bit→ARGB_8888）。
                    val target = if (maxDimension > 4096) 4096 else maxDimension
                    JxlCoder.decodeSampled(
                        bytes,
                        target,
                        target,
                        PreferredColorConfig.DEFAULT,
                        ScaleMode.FIT,
                        JxlResizeFilter.CATMULL_ROM
                    )
                }
            } catch (e: Exception) {
                Log.e("PhotoPage", "loadJxlDirect decode failed ${photo.uri}", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (bitmap != null && isActive) {
                    binding.ivPhoto.setImageBitmap(bitmap)
                    applyWindowColorMode(colorModeForBitmap(bitmap))
                    updateEdgeState()
                } else if (isActive) {
                    binding.ivPhoto.setImageResource(android.R.color.darker_gray)
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 配置 GlideZoomImageView 的子采样能力：
     * - JXL 不支持 BitmapRegionDecoder，禁用子采样（Glide 直接全图显示）
     * - 非 JXL 大图：注册 AVIF 自定义区域解码器，交由 GlideZoomImageView 在加载后自动生成 SubsamplingImage
     */
    private fun configureSubsampling(photo: Photo) {
        if (photo.isJxl) {
            binding.ivPhoto.subsampling.setDisabled(true)
        } else {
            binding.ivPhoto.subsampling.setRegionDecoders(listOf(AvifRegionDecoder.Factory()))
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
                    applyWindowColorMode(ColorMode.DEFAULT)
                    loadWithSubsampling(photo, ctx)
                } else {
                    applyWindowColorMode(ColorMode.DEFAULT)
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
                    binding.ivPhoto.subsampling.setDisabled(true)
                    binding.ivPhoto.setImageBitmap(bitmap)
                    applyWindowColorMode(if (hasGainmap) ColorMode.HDR else ColorMode.DEFAULT)
                    updateEdgeState()
                } else {
                    loadFullImage(photo, ctx)
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 直接 Glide 加载完整图片（适用于小图 / JXL / 非 HDR 回退）
     * GlideZoomImageView 会自行驱动显示，JXL 已在 configureSubsampling 中禁用子采样。
     * 加载完成后按解码 Bitmap 的色域设置窗口色彩模式（16-bit / 广色域 JXL 需 WIDE_COLOR_GAMUT）。
     */
    private fun loadFullImage(photo: Photo, ctx: Context) {
        configureSubsampling(photo)
        Glide.with(ctx)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .fitCenter()
            .addListener(jxlListener("loadFullImage", photo))
            .into(object : CustomTarget<android.graphics.drawable.Drawable>() {
                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    transition: Transition<in android.graphics.drawable.Drawable>?
                ) {
                    binding.ivPhoto.setImageDrawable(resource)
                    val bitmap = (resource as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    applyWindowColorMode(colorModeForBitmap(bitmap))
                    binding.progressBar.visibility = View.GONE
                    updateEdgeState()
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
     * 子采样加载大图：注册 AVIF 区域解码器后由 Glide 加载，
     * GlideZoomImageView 在加载成功后会自动生成 SubsamplingImage 驱动子采样。
     * 加载完成后同样按解码 Bitmap 的色域设置窗口色彩模式。
     */
    private fun loadWithSubsampling(photo: Photo, ctx: Context) {
        configureSubsampling(photo)
        Glide.with(ctx)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .addListener(jxlListener("loadWithSubsampling", photo))
            .into(object : CustomTarget<android.graphics.drawable.Drawable>() {
                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    transition: Transition<in android.graphics.drawable.Drawable>?
                ) {
                    binding.ivPhoto.setImageDrawable(resource)
                    val bitmap = (resource as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    applyWindowColorMode(colorModeForBitmap(bitmap))
                    binding.progressBar.visibility = View.GONE
                    updateEdgeState()
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

    private fun jxlListener(tag: String, photo: Photo) = object : RequestListener<android.graphics.drawable.Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<android.graphics.drawable.Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            Log.e("PhotoPage", "$tag FAILED ${photo.uri} mime=${photo.mimeType} isJxl=${photo.isJxl}", e)
            return false
        }

        override fun onResourceReady(
            resource: android.graphics.drawable.Drawable,
            model: Any?,
            target: Target<android.graphics.drawable.Drawable>,
            dataSource: com.bumptech.glide.load.DataSource,
            isFirstResource: Boolean
        ): Boolean {
            val bmp = (resource as? android.graphics.drawable.BitmapDrawable)?.bitmap
            Log.d("PhotoPage", "$tag OK ${photo.uri} mime=${photo.mimeType} cfg=${bmp?.config} cs=${bmp?.colorSpace} from=$dataSource")
            return false
        }
    }

    /**
     * 窗口色彩模式：默认(SDR) / 广色域(WIDE) / HDR。
     * 16-bit / 广色域 JXL 需切到 WIDE_COLOR_GAMUT 才能正确呈现高位深。
     */
    private enum class ColorMode {
        DEFAULT, WIDE, HDR
    }

    /**
     * 根据解码出的 Bitmap 推断应设置的窗口色彩模式：
     * - gainmap（Ultra HDR）→ HDR
     * - 广色域 ColorSpace（如 16-bit JXL 的 RGBA_F16）→ WIDE
     * - 其余 → DEFAULT
     */
    private fun colorModeForBitmap(bitmap: android.graphics.Bitmap?): ColorMode {
        if (bitmap == null) return ColorMode.DEFAULT
        val cs = bitmap.colorSpace
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && bitmap.hasGainmap() -> ColorMode.HDR
            cs != null && cs.isWideGamut -> ColorMode.WIDE
            else -> ColorMode.DEFAULT
        }
    }

    /**
     * 设置 Activity 窗口的色彩模式（SDR / 广色域 / HDR）
     * window.colorMode 改动会触发 SurfaceFlinger surface 重配置，
     * 通过 Handler.post 推迟到当前消息批次后执行，避免打断 ViewPager2 状态转换。
     * 使用 Handler 而非 View.post 是为了在 recycle() 时能通过
     * removeCallbacksAndMessages 精确清理未执行的变更，防止旧页面影响新页面。
     */
    private fun applyWindowColorMode(mode: ColorMode) {
        lastAppliedColorMode = mode
        if (!isActive) {
            return
        }
        hdrHandler.removeCallbacksAndMessages(null)
        hdrHandler.post {
            if (!isActive) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val window = activity?.window
                window?.colorMode = when (mode) {
                    ColorMode.HDR -> ActivityInfo.COLOR_MODE_HDR
                    ColorMode.WIDE -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                    ColorMode.DEFAULT -> ActivityInfo.COLOR_MODE_DEFAULT
                }
            }
        }
    }

    /**
     * 重置窗口色彩模式为默认（SDR），同步执行，用于 recycle 场景
     */
    private fun resetWindowColorMode() {
        if (!isActive) return
        lastAppliedColorMode = ColorMode.DEFAULT
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

        // 清理 Glide 加载的图片（GlideZoomImageView 作为 Target，clear 时自动清理子采样）
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
