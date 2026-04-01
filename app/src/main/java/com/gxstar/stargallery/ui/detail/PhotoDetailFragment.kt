package com.gxstar.stargallery.ui.detail

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
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
    lateinit var sharedPreferences: android.content.SharedPreferences
    
    @Inject
    lateinit var mediaRepository: MediaRepository
    
    private var startY = 0f
    private var isDragging = false
    
    // 是否处于全屏模式
    private var isFullscreen = false
    
    // 当前页面是否可以左右滑动切换
    private var canSwipeToSwitch = true
    
    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            // 通知首页刷新数据
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, bundleOf())
            findNavController().navigateUp()
        }
    }
    
    private val trashRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
            // 通知首页刷新数据
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, bundleOf())
            findNavController().navigateUp()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotoDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewPager()
        setupViews()
        setupSwipeToDismiss()
        observeData()
    }

    private fun setupViewPager() {
        pagerAdapter = PhotoPagerAdapter(
            onEdgeSwipe = { isSwipeRight ->
                // 边缘滑动时，允许 ViewPager2 接管滑动
                canSwipeToSwitch = true
            },
            viewPagerSwipeController = { canSwipe ->
                // 根据图片缩放状态控制是否可以滑动切换
                canSwipeToSwitch = canSwipe
            },
            onSingleTap = {
                // 单击切换全屏模式
                toggleFullscreen()
            }
        )
        
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 1
        
        // 记录上一个页面位置
        var lastPosition = -1
        
        // 拦截 ViewPager2 的触摸事件，根据图片状态决定是否允许滑动
        binding.viewPager.getChildAt(0).setOnTouchListener { v, event ->
            val currentItem = pagerAdapter.getCurrentViewHolder()
            val isImageZoomed = currentItem?.isImageZoomed() ?: false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 按下时，如果图片放大，禁止 ViewPager2 拦截
                    if (isImageZoomed) {
                        binding.viewPager.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // 移动时，根据当前状态和边缘检测决定是否允许 ViewPager2 拦截
                    if (isImageZoomed && !canSwipeToSwitch) {
                        binding.viewPager.requestDisallowInterceptTouchEvent(true)
                    } else {
                        binding.viewPager.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 抬起时重置状态
                    canSwipeToSwitch = true
                    binding.viewPager.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        
        // 监听页面切换
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 页面切换完成后，重置前一个页面的缩放状态（此时前一个页面已不可见，重置是无感的）
                if (lastPosition >= 0 && lastPosition != position) {
                    pagerAdapter.getViewHolder(lastPosition)?.resetZoom()
                }
                lastPosition = position
                
                viewModel.setPosition(position)
                // 页面切换后重置状态
                canSwipeToSwitch = true
            }
        })
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnFavorite.setOnClickListener {
            viewModel.toggleFavorite()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteOptionsDialog()
        }
        
        binding.btnSend.setOnClickListener {
            shareMedia()
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
                    trashRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), R.string.move_to_trash_failed, Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                Toast.makeText(requireContext(), R.string.move_to_trash_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun deletePermanently() {
        viewModel.deletePhoto { intentSender ->
            if (intentSender != null) {
                try {
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 切换全屏模式
     */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        
        val activity = requireActivity()
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        
        if (isFullscreen) {
            // 进入全屏模式：隐藏系统栏和工具栏，背景变黑
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            binding.rootContainer.setBackgroundColor(resources.getColor(R.color.black, null))
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            
            // 同时隐藏 RAW 标签
            pagerAdapter.getCurrentViewHolder()?.setRawTagVisibility(false)
        } else {
            // 退出全屏模式：显示系统栏和工具栏，背景变白
            controller.show(WindowInsetsCompat.Type.systemBars())
            
            binding.rootContainer.setBackgroundColor(resources.getColor(R.color.white, null))
            binding.topBar.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            
            // 显示 RAW 标签
            pagerAdapter.getCurrentViewHolder()?.setRawTagVisibility(true)
        }
    }
    
    /**
     * 设置下拉返回功能
     */
    private fun setupSwipeToDismiss() {
        binding.viewPager.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startY
                    if (kotlin.math.abs(deltaY) > 50) {
                        isDragging = true
                    }
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
                        
                        // 设置初始位置
                        val initialPosition = viewModel.getInitialPosition()
                        if (binding.viewPager.currentItem != initialPosition) {
                            binding.viewPager.setCurrentItem(initialPosition, false)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentPhoto.collect { photo ->
                    photo?.let {
                        updateFavoriteIcon(it.isFavorite)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dateText.collect { date ->
                    binding.tvDate.text = date
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.infoText.collect { info ->
                    binding.tvInfo.text = info
                }
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
        // 恢复时确保工具栏和背景色正确
        if (isFullscreen) {
            binding.rootContainer.setBackgroundColor(resources.getColor(R.color.black, null))
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
        } else {
            binding.rootContainer.setBackgroundColor(resources.getColor(R.color.white, null))
            binding.topBar.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        // 清理资源
        pagerAdapter.clear()
        
        // 退出时恢复系统栏显示
        try {
            val activity = activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        _binding = null
        super.onDestroyView()
    }
}