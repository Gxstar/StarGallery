package com.gxstar.stargallery.ui.detail

import android.content.res.Configuration
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

import android.app.WallpaperManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.util.HdrDisplayManager
import com.gxstar.stargallery.databinding.FragmentPhotoDetailBinding
import com.gxstar.stargallery.ui.common.DeleteOptionsBottomSheet
import com.gxstar.stargallery.ui.photos.PhotosFragment
import com.yalantis.ucrop.UCrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class PhotoDetailFragment : Fragment() {

    private var _binding: FragmentPhotoDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoDetailViewModel by viewModels()
    private lateinit var pagerAdapter: PhotoPagerAdapter

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var hdrDisplayManager: HdrDisplayManager

    private var startY = 0f
    private var isDragging = false
    private var imageWasZoomedOnDown = false

    // 是否处于全屏模式
    private var isFullscreen = false

    // 当前页面是否可以左右滑动切换
    private var canSwipeToSwitch = true

    // 是否已设置过初始位置（用于避免删除后重置位置）
    private var hasInitialPositionBeenSet = false

    // 待操作的照片 ID，用于删除/回收站操作后将 ID 传回 PhotosFragment
    private var pendingActionPhotoId: Long? = null

    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle().apply {
                putLongArray("photo_ids", pendingActionPhotoId?.let { longArrayOf(it) })
                putBoolean("is_remove", true)
            })
            handlePhotoDeleted()
        }
    }

    private val trashRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle().apply {
                putLongArray("photo_ids", pendingActionPhotoId?.let { longArrayOf(it) })
                putBoolean("is_remove", true)
            })
            handlePhotoDeleted()
        }
    }

    private val favoriteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onFavoriteConfirmed()
        }
    }

    private var cropTempFile: java.io.File? = null

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tempFile = cropTempFile
        cropTempFile = null
        if (result.resultCode == Activity.RESULT_OK && tempFile?.exists() == true) {
            showCropConfirmDialog(tempFile)
        } else {
            tempFile?.delete()
        }
    }

    private fun handlePhotoDeleted() {
        val currentPosition = binding.viewPager.currentItem
        pagerAdapter.removePhotoAt(currentPosition)
        val hasMorePhotos = viewModel.removeCurrentPhoto(currentPosition)
        if (!hasMorePhotos) {
            findNavController().navigateUp()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotoDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackPressedCallback()
        setupWindowInsets()
        setupViewPager()
        setupViews()
        setupSwipeToDismiss()
        applyDetailOverlayWidth()
        observeData()

        setFragmentResultListener("photo_edited") { _, _ ->
            viewModel.refreshCurrentPhoto()
        }

        // 初始状态下应用状态栏图标颜色
        updateSystemBarIcons(!isFullscreen)
    }

    private fun setupBackPressedCallback() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().navigateUp()
                }
            }
        )
    }

    /**
     * 设置 Window Insets 监听
     * 处理顶栏和底栏的 Padding，使其完美避开状态栏和导航栏
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // 顶栏增加状态栏高度的 padding
            binding.topBar.setPadding(0, systemBars.top, 0, 0)
            // 底栏增加导航栏高度的 padding
            binding.bottomBar.setPadding(0, binding.bottomBar.paddingTop, 0, systemBars.bottom)
            
            windowInsets
        }
    }

    /**
     * 平板（宽度 >= 600dp）上将顶部/底部 overlay 工具栏限制为 640dp 并水平居中，
     * 避免按钮被拉伸到全宽、点击热区过大。
     */
    private fun applyDetailOverlayWidth() {
        if (resources.configuration.screenWidthDp < 600) return
        val maxWidthPx = (640 * resources.displayMetrics.density).toInt()
            .coerceAtMost(resources.displayMetrics.widthPixels)
        (binding.topBar.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            p.width = maxWidthPx
            p.gravity = Gravity.CENTER_HORIZONTAL
            binding.topBar.layoutParams = p
        }
        (binding.bottomBar.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            p.width = maxWidthPx
            p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            binding.bottomBar.layoutParams = p
        }
    }

    private fun setupViewPager() {
        pagerAdapter = PhotoPagerAdapter(
            lifecycleOwner = this,
            onEdgeSwipe = { canSwipeToSwitch = true },
            viewPagerSwipeController = { canSwipe -> canSwipeToSwitch = canSwipe },
            onSingleTap = { toggleFullscreen() },
            hdrDisplayEnabled = { hdrDisplayManager.isHdrDisplayEnabled }
        )

        binding.viewPager.adapter = pagerAdapter
        // 预加载左右各 1 页，大图场景下减少并行解码的内存和 CPU 竞争
        binding.viewPager.offscreenPageLimit = 1

        var lastPosition = -1

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (lastPosition >= 0 && lastPosition != position) {
                    pagerAdapter.getViewHolder(lastPosition)?.resetZoom()
                    ExoPlayerManager.pause()
                }
                lastPosition = position
                pagerAdapter.activePosition = position
                viewModel.setPosition(position)
                canSwipeToSwitch = true
            }
        })
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnFavorite.setOnClickListener {
            val intentSender = viewModel.prepareToggleFavorite()
            if (intentSender != null) {
                try {
                    favoriteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.add_to_favorite_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDelete.setOnClickListener {
            showDeleteOptionsDialog()
        }
        
        binding.btnSend.setOnClickListener {
            shareMedia()
        }

        binding.btnInfo.setOnClickListener {
            viewModel.currentPhoto.value?.let { photo ->
                PhotoInfoBottomSheet.newInstance(photo).show(childFragmentManager, PhotoInfoBottomSheet.TAG)
            }
        }

        binding.btnEdit.setOnClickListener {
            val photo = viewModel.currentPhoto.value ?: return@setOnClickListener
            if (photo.isRaw) {
                Toast.makeText(requireContext(), R.string.edit_raw_unsupported, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (photo.isVideo) {
                Toast.makeText(requireContext(), R.string.edit_video_unsupported, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCrop(photo)
        }

        binding.btnMore.setOnClickListener {
            showPopupMenu(it)
        }
    }

    private fun startCrop(photo: com.gxstar.stargallery.data.model.Photo) {
        val tempFile = java.io.File(requireContext().cacheDir, "crop_${System.currentTimeMillis()}.jpg")
        cropTempFile = tempFile
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(95)
            setToolbarColor(Color.WHITE)
            setToolbarWidgetColor(Color.BLACK)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(true)
        }
        UCrop.of(photo.uri, android.net.Uri.fromFile(tempFile))
            .withOptions(options)
            .start(requireContext(), cropLauncher)
    }

    private fun showCropConfirmDialog(tempFile: java.io.File) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit)
            .setMessage(R.string.save_crop_confirm)
            .setPositiveButton(R.string.save) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val photo = viewModel.currentPhoto.value
                    if (photo == null) {
                        tempFile.delete()
                        return@launch
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                    mediaRepository.copyAllExif(
                        photo.uri,
                        android.net.Uri.fromFile(tempFile),
                        opts.outWidth,
                        opts.outHeight
                    )
                    val destUri = mediaRepository.createImageCopyPlaceholder(photo)
                    if (destUri != null) {
                        requireContext().contentResolver.openOutputStream(destUri)?.use { output ->
                            tempFile.inputStream().use { input -> input.copyTo(output) }
                        }
                        mediaRepository.finalizeImageCopy(destUri)
                    }
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        if (destUri != null) {
                            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
                            viewModel.refreshCurrentPhoto()
                        } else {
                            Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                tempFile.delete()
            }
            .show()
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = android.widget.PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photo_detail, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_set_wallpaper -> {
                    setWallpaper()
                    true
                }
                R.id.action_hide -> {
                    confirmHidePhoto()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun confirmHidePhoto() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hide)
            .setMessage(getString(R.string.hide_selected_confirm, 1))
            .setPositiveButton(R.string.hide) { _, _ ->
                viewModel.hideCurrentPhoto()
                Toast.makeText(requireContext(), R.string.hidden_success, Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setWallpaper() {
        val photo = viewModel.currentPhoto.value ?: return
        try {
            val intent = WallpaperManager.getInstance(requireContext())
                .getCropAndSetWallpaperIntent(photo.uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.set_wallpaper_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteOptionsDialog() {
        DeleteOptionsBottomSheet.newInstance(
            onMoveToTrash = { moveToTrash() },
            onDeletePermanently = { deletePermanently() }
        ).show(childFragmentManager, DeleteOptionsBottomSheet.TAG)
    }
    
    private fun moveToTrash() {
        viewModel.currentPhoto.value?.let { photo ->
            mediaRepository.trashPhoto(photo)?.let { intentSender ->
                try {
                    pendingActionPhotoId = photo.id
                    trashRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.move_to_trash_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun deletePermanently() {
        viewModel.deletePhoto { intentSender ->
            if (intentSender != null) {
                try {
                    pendingActionPhotoId = viewModel.currentPhoto.value?.id
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 切换全屏模式
     * 采用平滑的 Alpha 动画切换工具栏可见性
     */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        
        val controller = WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView)
        
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            binding.rootContainer.setBackgroundColor(Color.BLACK)
            fadeView(binding.topBar, false)
            fadeView(binding.bottomBar, false)
            updateSystemBarIcons(false)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())

            val typedValue = TypedValue()
            requireActivity().theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, typedValue, true
            )
            binding.rootContainer.setBackgroundColor(typedValue.data)
            fadeView(binding.topBar, true)
            fadeView(binding.bottomBar, true)

            val isLightTheme = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            updateSystemBarIcons(isLightTheme)
        }
    }

    private fun fadeView(view: View, show: Boolean) {
        view.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(200)
            .withStartAction { if (show) view.visibility = View.VISIBLE }
            .withEndAction { if (!show) view.visibility = View.GONE }
            .start()
    }

    private fun updateSystemBarIcons(lightBars: Boolean) {
        val window = requireActivity().window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
    
    private fun setupSwipeToDismiss() {
        binding.viewPager.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                    val pos = binding.viewPager.currentItem
                    imageWasZoomedOnDown = pagerAdapter.getViewHolder(pos)?.isImageZoomed() == true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (imageWasZoomedOnDown) return@setOnTouchListener false
                    val deltaY = event.rawY - startY
                    if (kotlin.math.abs(deltaY) > 50) isDragging = true
                    if (isDragging) {
                        val alpha = 1f - kotlin.math.abs(deltaY) / 500f
                        binding.viewPager.alpha = alpha.coerceIn(0.3f, 1f)
                        binding.viewPager.translationY = deltaY * 0.5f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!imageWasZoomedOnDown && isDragging) {
                        val deltaY = event.rawY - startY
                        if (kotlin.math.abs(deltaY) > 200) {
                            findNavController().navigateUp()
                        } else {
                            binding.viewPager.animate()
                                .translationY(0f)
                                .alpha(1f)
                                .setDuration(200)
                                .start()
                        }
                    }
                    isDragging = false
                    imageWasZoomedOnDown = false
                }
            }
            false
        }
    }
    
    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photos.collect { photos ->
                    if (photos.isNotEmpty()) {
                        val previousCount = pagerAdapter.getPhotoCount()
                        if (!hasInitialPositionBeenSet) {
                            val initialPosition = viewModel.getInitialPosition()
                            pagerAdapter.submitList(photos) {
                                if (binding.viewPager.currentItem != initialPosition) {
                                    binding.viewPager.setCurrentItem(initialPosition, false)
                                }
                                hasInitialPositionBeenSet = true
                            }
                        } else if (previousCount == 1 && photos.size > 1) {
                            pagerAdapter.submitList(photos) {
                                binding.viewPager.setCurrentItem(binding.viewPager.currentItem, false)
                            }
                        } else {
                            pagerAdapter.submitList(photos)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentPhoto.collect { photo ->
                    photo?.let { updateFavoriteIcon(it.isFavorite) }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dateText.collect { date -> binding.tvDate.text = date }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.infoText.collect { info -> binding.tvInfo.text = info }
            }
        }
    }
    
    private fun shareMedia() {
        val photo = viewModel.currentPhoto.value ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = photo.mimeType
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.send)))
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        val iconRes = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
        binding.ivFavorite.setImageResource(iconRes)
    }
    
    override fun onResume() {
        super.onResume()
        if (isFullscreen) {
            val controller = WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        updateSystemBarIcons(!isFullscreen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转时重置缩放状态
        pagerAdapter.getCurrentViewHolder()?.resetZoom()
        // 旋转后按新屏宽重算 overlay 工具栏限宽
        applyDetailOverlayWidth()
    }

    override fun onDestroyView() {
        // 退出时确保恢复系统栏显示
        val controller = WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        
        pagerAdapter.clear()
        ExoPlayerManager.release()
        _binding = null
        super.onDestroyView()
    }
}
