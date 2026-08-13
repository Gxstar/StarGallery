package com.gxstar.stargallery.ui.detail

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
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
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import com.awxkee.jxlcoder.JxlResizeFilter
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig as AvifColorConfig
import com.radzivon.bartoshyk.avif.coder.ScaleMode as AvifScaleMode
import com.radzivon.bartoshyk.avif.coder.ScalingQuality as AvifScalingQuality
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

        // HDR 候选：JPEG（Ultra HDR gainmap）/ HEIF（10-bit 高位深、PQ/HLG、gain map）
        // AVIF 单独走 avif-coder（libavif）路径：原生解码器不支持 4:2:2/4:4:4 与
        // 12-bit 样本（实测 10-bit 4:4:4 原生失败），详情页经 Glide + AvifRegionDecoder
        // 解码，色彩模式仍按位图实际属性（colorModeForBitmap）设置
        val isHdrCandidate = photo.isUltraHdr || photo.isHeic
        val shouldProbeHdr = (photo.isHdr || isHdrCandidate) && hdrDisplayEnabled()

        if (photo.isAvif) {
            // AVIF 自适应：优先原生 ImageDecoder（4:2:0 的 HDR AVIF → 真 HDR/WIDE），
            // 原生不支持（4:2:2/4:4:4 与 12-bit 抛异常）→ 回退 libavif 直连（WIDE 保底）
            loadAvifAdaptive(photo, maxDimension, ctx)
        } else if (shouldProbeHdr) {
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
     * AVIF 自适应解码（双路径）：
     * - 原生 ImageDecoder 可解（4:2:0）：
     *   * HDR/宽色域候选（gainmap / PQ / wide gamut）→ 原生整图解码，真 HDR/WIDE 显示
     *   * 普通 SDR → Glide + AvifRegionDecoder 子采样
     * - 原生不支持（4:2:2/4:4:4 与 12-bit，解码抛异常）→ 回退 libavif 直连（WIDE 保底）
     */
    private fun loadAvifAdaptive(photo: Photo, maxDim: Int, ctx: Context) {
        viewHolderScope?.launch(Dispatchers.IO) {
            val nativeProbe = try {
                val source = ImageDecoder.createSource(ctx.contentResolver, photo.uri)
                val probe = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSize(200, 200)
                }
                val result = probe.hasGainmap() || probe.colorSpace?.isWideGamut == true
                probe.recycle()
                result
            } catch (_: Exception) {
                null // 原生不支持该样本（4:4:4/12-bit 等）→ 回退 libavif
            }

            withContext(Dispatchers.Main) {
                when (nativeProbe) {
                    true -> {
                        Log.d("PhotoPage", "loadAvifAdaptive: native HDR/WIDE path ${photo.uri}")
                        loadNativeFullImage(photo, maxDim, ctx)
                    }
                    false -> {
                        Log.d("PhotoPage", "loadAvifAdaptive: native SDR path ${photo.uri}")
                        // SDR AVIF 也走原生整图解码（禁用子采样）：
                        // AVIF 无原生 region 解码，模拟瓦片（整帧软解）在放大时瓦片加载极慢
                        // 且解码器池并发会重复整帧解码，导致显示停留在低分辨率预览图（糊）。
                        // 与 JXL 一致采用整图 + 位图变换，放大清晰度由解码分辨率保证。
                        loadNativeFullImage(photo, maxDim, ctx)
                    }
                    null -> {
                        Log.d("PhotoPage", "loadAvifAdaptive: libavif fallback ${photo.uri}")
                        loadAvifDirect(photo, maxDim, ctx)
                    }
                }
            }
        }
    }

    /**
     * 直接调用 avif-coder（libavif）核心库解码 AVIF 字节流，绕过 Glide 的 content:// 路径。
     *
     * 背景：
     * - Android 原生解码器仅保证 AVIF baseline（4:2:0 8/10-bit），4:2:2/4:4:4 与 12-bit
     *   样本（实测 01045811.avif：profile 1 / 10-bit / 4:4:4）原生解码失败；
     * - Glide 对 content:// Uri 的 avif-coder-glide 集成存在 FD/Stream 路径不确定性，
     *   直连 HeifCoder 字节流解码最可控（与 loadJxlDirect 同一模式）。
     *
     * DEFAULT 色彩配置由库自动选择（10-bit 源 → RGBA_F16 保留位深，输出 scRGB-nl 广色域，
     * 窗口切 WIDE；注意：libavif 会把 PQ/BT.2020 转换到 scRGB，无法保留 HDR 动态范围，
     * 这是库的 native 行为，Kotlin API 未暴露色彩空间选项）。
     * 解码上限 2560：RGBA_F16 为 8B/px，4096 长边约 134MB，防 OOM。
     */
    private fun loadAvifDirect(photo: Photo, maxDimension: Int, ctx: Context) {
        binding.ivPhoto.subsampling.setDisabled(true) // 直连全图显示，不子采样
        viewHolderScope?.launch(Dispatchers.IO) {
            val bitmap = try {
                ctx.contentResolver.openInputStream(photo.uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val target = if (maxDimension > AVIF_MAX_DECODE_PX) AVIF_MAX_DECODE_PX else maxDimension
                    HeifCoder().decodeSampled(
                        bytes,
                        target,
                        target,
                        AvifColorConfig.DEFAULT,
                        AvifScaleMode.FIT,
                        AvifScalingQuality.DEFAULT
                    )
                }
            } catch (e: Exception) {
                Log.e("PhotoPage", "loadAvifDirect decode failed ${photo.uri}", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (bitmap != null && isActive) {
                    // 确认 HDR 保留：cfg=RGBA_F16 + cs=BT2020_PQ/BT2020_HLG 表示高位深 HDR 输出
                    Log.d("PhotoPage", "loadAvifDirect OK ${photo.uri} cfg=${bitmap.config} cs=${bitmap.colorSpace} w=${bitmap.width} h=${bitmap.height}")
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
     * - JXL 不支持 BitmapRegionDecoder，禁用子采样（直连解码后全图显示）
     * - AVIF 无原生 region 解码，模拟瓦片（整帧软解）放大时瓦片加载极慢且并发重复解码，
     *   禁用子采样，由 ImageDecoder/libavif 整图解码后位图变换（loadAvifAdaptive 已处理）
     * - 其余（JPEG/PNG/HEIC 等）：注册 AVIF 自定义区域解码器（对非 AVIF 无影响），
     *   交由 GlideZoomImageView 在加载后自动生成 SubsamplingImage 驱动子采样
     */
    private fun configureSubsampling(photo: Photo) {
        if (photo.isJxl || photo.isAvif) {
            binding.ivPhoto.subsampling.setDisabled(true)
        } else {
            binding.ivPhoto.subsampling.setRegionDecoders(listOf(AvifRegionDecoder.Factory()))
        }
    }

    /**
     * 快速探测高位深/HDR 候选并走对应路径
     * - photo.isHdr（扫描期已字节探测 gainmap）→ 跳过重复探测，直接走原生整图解码
     * - 否则小图探测 gainmap / 宽色域（覆盖 10-bit AVIF/HEIF 的 PQ/HLG，R5）
     */
    private fun checkHdrAndLoad(photo: Photo, maxDim: Int, needSubsample: Boolean, ctx: Context) {
        viewHolderScope?.launch(Dispatchers.IO) {
            val isHdrCandidate = try {
                if (photo.isHdr) {
                    true
                } else {
                    val source = ImageDecoder.createSource(ctx.contentResolver, photo.uri)
                    val probe = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.setTargetSize(200, 200)
                    }
                    val result = probe.hasGainmap() || probe.colorSpace?.isWideGamut == true
                    probe.recycle()
                    result
                }
            } catch (_: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                if (isHdrCandidate) {
                    loadNativeFullImage(photo, maxDim, ctx)
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
     * 用 ImageDecoder 解码完整图像，禁用子采样（整图 + 位图变换）。
     * - HDR 候选（HEIF/UltraHDR gainmap、AVIF PQ/HLG）：保留 gainmap 与高位深，真 HDR/WIDE 显示
     * - AVIF 原生可解（4:2:0 SDR/HDR）：整图显示，避免模拟瓦片（整帧软解）的放大卡顿/模糊
     * 超出 MAX_HDR_DECODE_PX 时长边等比缩放
     */
    private fun loadNativeFullImage(photo: Photo, maxDim: Int, ctx: Context) {
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
                    binding.ivPhoto.subsampling.setDisabled(true)
                    binding.ivPhoto.setImageBitmap(bitmap)
                    // R4：按位图实际属性（gainmap / PQ/HLG / 宽色域）统一决定色彩模式
                    applyWindowColorMode(colorModeForBitmap(bitmap))
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
     *
     * 注意：必须 `.into(binding.ivPhoto)` 直接把 Glide Request 挂到 GlideZoomImageView 上，
     * 库才会在 onDrawableChanged → resetImageSource() 时通过 getRequestFromView() 拿到
     * Request + model，进而自动生成 SubsamplingImage 驱动子采样。
     * 不能使用 CustomTarget + 手动 setImageDrawable（view 上无 Request，子采样会被显式清除）。
     */
    private fun loadFullImage(photo: Photo, ctx: Context) {
        configureSubsampling(photo)
        Glide.with(ctx)
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .fitCenter()
            .addListener(imageLoadListener("loadFullImage", photo))
            .into(binding.ivPhoto)
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
            .addListener(imageLoadListener("loadWithSubsampling", photo))
            .into(binding.ivPhoto)
    }

    /**
     * Glide 加载监听：资源就绪后按解码 Bitmap 的色域设置窗口色彩模式、隐藏进度条、更新边缘状态。
     * onResourceReady 返回 false，让 Glide 继续把 Drawable 交给 GlideZoomImageView 显示
     * （此时 view 上的 Request 已存在，库会自动生成 SubsamplingImage 驱动子采样）。
     */
    private fun imageLoadListener(tag: String, photo: Photo) =
        object : RequestListener<android.graphics.drawable.Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<android.graphics.drawable.Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                Log.e("PhotoPage", "$tag FAILED ${photo.uri} mime=${photo.mimeType} isJxl=${photo.isJxl}", e)
                binding.progressBar.visibility = View.GONE
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
                applyWindowColorMode(colorModeForBitmap(bmp))
                binding.progressBar.visibility = View.GONE
                updateEdgeState()
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
     * - gainmap（Ultra HDR / HEIF gain map）→ HDR
     * - PQ / HLG transfer（10-bit AVIF/HEIF 高位深 HDR 内容）→ HDR（R5）
     * - 广色域 ColorSpace（如 16-bit JXL 的 RGBA_F16、P3）→ WIDE
     * - 其余 → DEFAULT
     */
    private fun colorModeForBitmap(bitmap: android.graphics.Bitmap?): ColorMode {
        if (bitmap == null) return ColorMode.DEFAULT
        val cs = bitmap.colorSpace
        return when {
            // R5：gainmap 直判，minSdk 35 无需版本保护
            bitmap.hasGainmap() -> ColorMode.HDR
            // R5：仅凭 isWideGamut 会把 PQ/HLG 误判为广色域 SDR，导致亮度/动态范围错误
            cs != null && (cs.name == ColorSpace.Named.BT2020_PQ.name || cs.name == ColorSpace.Named.BT2020_HLG.name) ->
                ColorMode.HDR
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
            val window = activity?.window
            window?.colorMode = when (mode) {
                ColorMode.HDR -> ActivityInfo.COLOR_MODE_HDR
                ColorMode.WIDE -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                ColorMode.DEFAULT -> ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    /**
     * 重置窗口色彩模式为默认（SDR），同步执行，用于 recycle 场景
     */
    private fun resetWindowColorMode() {
        if (!isActive) return
        lastAppliedColorMode = ColorMode.DEFAULT
        val window = activity?.window
        window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
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
        // AVIF 直连解码上限（DEFAULT 配置下 10-bit 源输出 RGBA_F16 = 8B/px，防 OOM）
        private const val AVIF_MAX_DECODE_PX = 2560

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
