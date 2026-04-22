package com.gxstar.stargallery.ui.detail

import android.content.res.Configuration
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentPhotoDetailBinding
import com.gxstar.stargallery.ui.common.DeleteOptionsBottomSheet
import com.gxstar.stargallery.ui.photos.PhotosFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PhotoDetailFragment : Fragment() {

    private var _binding: FragmentPhotoDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoDetailViewModel by viewModels()
    private lateinit var pagerAdapter: PhotoPagerAdapter

    @Inject
    lateinit var mediaRepository: MediaRepository

    private var startY = 0f
    private var isDragging = false

    // 是否处于全屏模式
    private var isFullscreen = false

    // 当前页面是否可以左右滑动切换
    private var canSwipeToSwitch = true

    // 是否已设置过初始位置（用于避免删除后重置位置）
    private var hasInitialPositionBeenSet = false
    
    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, bundleOf())
            handlePhotoDeleted()
        }
    }
    
    private val trashRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, bundleOf())
            handlePhotoDeleted()
        }
    }

    private val favoriteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onFavoriteConfirmed()
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
        observeData()

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

    private fun setupViewPager() {
        pagerAdapter = PhotoPagerAdapter(
            onEdgeSwipe = { canSwipeToSwitch = true },
            viewPagerSwipeController = { canSwipe -> canSwipeToSwitch = canSwipe },
            onSingleTap = { toggleFullscreen() }
        )

        binding.viewPager.adapter = pagerAdapter
        // 增加预加载页数，让滑动更流畅
        // 预加载左右各 2 页，平衡内存占用与滑动体验
        binding.viewPager.offscreenPageLimit = 2

        var lastPosition = -1

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (lastPosition >= 0 && lastPosition != position) {
                    pagerAdapter.getViewHolder(lastPosition)?.resetZoom()
                }
                lastPosition = position
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

        binding.btnMore.setOnClickListener {
            showMoreOptionsDialog()
        }
    }

    private fun showMoreOptionsDialog() {
        val bottomSheet = TagsSettingsBottomSheet.newInstance()
        bottomSheet.setOnTagsChangedListener { selectedTags ->
            // 更新所有可见的 ViewHolder 的标签显示
            pagerAdapter.updateAllTagsVisibility(selectedTags)
        }
        bottomSheet.show(childFragmentManager, TagsSettingsBottomSheet.TAG)
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
            // 隐藏系统栏
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // 动画切换背景和工具栏
            binding.rootContainer.setBackgroundColor(Color.BLACK)
            fadeView(binding.topBar, false)
            fadeView(binding.bottomBar, false)
        } else {
            // 显示系统栏
            controller.show(WindowInsetsCompat.Type.systemBars())

            binding.rootContainer.setBackgroundColor(Color.WHITE)
            fadeView(binding.topBar, true)
            fadeView(binding.bottomBar, true)
        }
        
        updateSystemBarIcons(!isFullscreen)
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
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startY
                    if (kotlin.math.abs(deltaY) > 50) isDragging = true
                    if (isDragging) {
                        val alpha = 1f - kotlin.math.abs(deltaY) / 500f
                        binding.viewPager.alpha = alpha.coerceIn(0.3f, 1f)
                        binding.viewPager.translationY = deltaY * 0.5f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                    isDragging = false
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
                        pagerAdapter.submitList(photos)
                        // 只在首次加载时设置位置，后续删除等操作不重置位置
                        if (!hasInitialPositionBeenSet) {
                            val initialPosition = viewModel.getInitialPosition()
                            if (binding.viewPager.currentItem != initialPosition) {
                                binding.viewPager.setCurrentItem(initialPosition, false)
                            }
                            hasInitialPositionBeenSet = true
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