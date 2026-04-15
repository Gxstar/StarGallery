package com.gxstar.stargallery.ui.photos

import android.Manifest
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.util.ViewPreloadSizeProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.gxstar.stargallery.ui.photos.action.BatchActionHandler
import com.gxstar.stargallery.ui.photos.animation.PhotoItemAnimator
import com.gxstar.stargallery.ui.photos.launcher.IntentSenderManager
import com.gxstar.stargallery.ui.photos.refresh.MediaChangeDetector
import com.gxstar.stargallery.ui.photos.selection.PhotoSelectionManager
import com.gxstar.stargallery.ui.photos.scanner.ScanningProgressDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import javax.inject.Inject

/**
 * 照片列表 Fragment
 * 职责：协调各管理器，处理 UI 事件
 */
@AndroidEntryPoint
class PhotosFragment : Fragment() {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels()

    // 管理器
    private lateinit var selectionManager: PhotoSelectionManager
    private lateinit var batchActionHandler: BatchActionHandler
    private lateinit var intentSenderManager: IntentSenderManager
    private lateinit var mediaChangeDetector: MediaChangeDetector

    // UI 组件
    private lateinit var photoAdapter: PhotoPagingAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    // 状态
    private var currentSpanCount = MIN_SPAN_COUNT  // 默认值，实际值在 setupSettings 中计算
    private var itemSize = 0
    private var isWarmedUp = false  // 是否已预热（首次加载完成后的优化）

    // 收藏操作类型（用于显示对应的 Toast 消息）
    private var pendingFavoriteAction = BatchActionHandler.FAVORITE_ACTION_NONE

    // Adapter provider（延迟绑定到 selectionManager）
    private var isSelectionModeProvider: () -> Boolean = { false }
    private var isSelectedProvider: (Long) -> Boolean = { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentSenderManager = IntentSenderManager(this)
        
        // 在 onCreate 中初始化一次适配器和管理器，避免视图重建时丢失状态
        setupSettings()
        initAdapter()
        initManagers()
        bindSelectionProviders()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initMediaChangeDetector() // 视图创建后初始化
        setupRecyclerView()
        setupClickListeners()
        setupFragmentResultListener()
        observeData()
        observeSelectionState()
        checkPermissions()
    }

    /**
     * 初始化 Adapter
     */
    private fun initAdapter() {
        photoAdapter = PhotoPagingAdapter(
            itemSize = itemSize,
            spanCount = currentSpanCount,
            onPhotoClick = { photo -> handlePhotoClick(photo) },
            onPhotoLongClick = { photo -> handlePhotoLongClick(photo) },
            isSelectionModeProvider = { isSelectionModeProvider() },
            isSelectedProvider = { id -> isSelectedProvider(id) }
        ).apply {
            // 禁止在数据加载完成前恢复状态，防止跳动
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    /**
     * 绑定 provider 到 selectionManager
     */
    private fun bindSelectionProviders() {
        isSelectionModeProvider = { selectionManager.isSelectionMode.value }
        isSelectedProvider = { id -> selectionManager.isSelected(id) }
    }

    /**
     * 初始化各管理器
     */
    private fun initManagers() {
        // 选择模式管理器
        selectionManager = PhotoSelectionManager(this, photoAdapter)

        // 批量操作处理器
        batchActionHandler = BatchActionHandler(
            this,
            mediaRepository,
            childFragmentManager
        )
    }

    /**
     * 初始化媒体变化检测器（单独处理，因为需要 viewLifecycleOwner）
     */
    private fun initMediaChangeDetector() {
        mediaChangeDetector = MediaChangeDetector(
            viewLifecycleOwner,
            requireContext()
        ) {
            // 检测到变化时，保存位置后刷新
            val savedPosition = gridLayoutManager.findFirstVisibleItemPosition()
            var savedOffset = 0
            if (savedPosition != RecyclerView.NO_POSITION) {
                val firstVisibleView = gridLayoutManager.findViewByPosition(savedPosition)
                if (firstVisibleView != null) {
                    savedOffset = firstVisibleView.top
                }
            }
            
            // 刷新数据
            photoAdapter.clearCache()
            viewModel.refresh()
            photoAdapter.refresh()
            
            // 尝试恢复位置
            if (savedPosition >= 0) {
                binding.rvPhotos.post {
                    try {
                        gridLayoutManager.scrollToPositionWithOffset(savedPosition, savedOffset)
                    } catch (e: Exception) {
                        // 忽略位置恢复失败
                    }
                }
            }
            
            Toast.makeText(requireContext(), R.string.new_photos_detected, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 加载设置（列数、排序、分组）
     */
    private fun setupSettings() {
        // 计算最优列数或读取用户保存的偏好
        val savedSpanCount = sharedPreferences.getInt(KEY_SPAN_COUNT, -1)
        currentSpanCount = if (savedSpanCount > 0) {
            savedSpanCount
        } else {
            calculateOptimalSpanCount()
        }
        calculateItemSize()

        val sortType = loadSortType()
        viewModel.setSortType(sortType)

        val groupType = loadGroupType()
        viewModel.setGroupType(groupType)
    }
    
    /**
     * 根据屏幕宽度计算最优列数（响应式设计）
     */
    private fun calculateOptimalSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val spanCount = (screenWidthDp / MIN_CELL_WIDTH_DP).toInt()
        return spanCount.coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)
    }

    /**
     * 设置 RecyclerView
     */
    private fun setupRecyclerView() {
        // GridLayoutManager
        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // 防御性检查：避免在数据未准备好时访问导致卡顿
                    if (position < 0 || position >= photoAdapter.itemCount) return 1
                    return if (photoAdapter.getItemViewType(position) == 0) currentSpanCount else 1
                }
            }.apply {
                isSpanIndexCacheEnabled = true
            }
            // 增加布局预取数量，减少首次滑动卡顿
            isItemPrefetchEnabled = true
            isMeasurementCacheEnabled = true
            initialPrefetchItemCount = PREFETCH_ITEM_COUNT
        }

        // RecyclerView 配置
        binding.rvPhotos.apply {
            layoutManager = gridLayoutManager
            adapter = photoAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE)
            itemAnimator = PhotoItemAnimator()
            addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
            addOnItemTouchListener(selectionManager.dragSelectTouchListener)
        }

        // Glide 预加载
        setupGlidePreloader()

        // FastScroller
        setupFastScroller()

        // 监听数据更新
        photoAdapter.addOnPagesUpdatedListener {
            // 通知适配器数据已更新，触发缓存更新
            photoAdapter.onPagesUpdated()
            // 增量更新位置映射（优化后只处理新增项）
            selectionManager.updatePositionMap()
        }
    }

    /**
     * 设置 Glide 预加载
     */
    private fun setupGlidePreloader() {
        val glideRequest = Glide.with(this)
        val preloadSizeProvider = ViewPreloadSizeProvider<Uri>()
        val preloader = RecyclerViewPreloader(
            glideRequest,
            PhotoPreloadModelProvider(glideRequest, photoAdapter, itemSize),
            preloadSizeProvider,
            PRELOAD_ITEM_COUNT
        )
        binding.rvPhotos.addOnScrollListener(preloader)
    }

    /**
     * 设置 FastScroller
     */
    private fun setupFastScroller() {
        val thumbDrawable = requireContext().getDrawable(R.drawable.fastscroll_thumb_material)!!
        val trackDrawable = requireContext().getDrawable(R.drawable.fastscroll_track_material)!!

        FastScrollerBuilder(binding.rvPhotos)
            .setThumbDrawable(thumbDrawable)
            .setTrackDrawable(trackDrawable)
            .setPopupStyle { popupView ->
                popupView.setTextSize(18f)
                popupView.setTextColor(requireContext().getColor(R.color.white))
                popupView.setBackgroundColor(requireContext().getColor(R.color.fastscroll_thumb))
                popupView.setPadding(28, 18, 28, 18)
            }
            .build()
    }

    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        // 正常模式
        binding.btnMore.setOnClickListener { showPopupMenu(it) }
        binding.btnFilter.setOnClickListener { viewModel.toggleFavoritesOnly() }

        // 选择模式
        binding.btnBack.setOnClickListener { selectionManager.exitSelectionMode() }
        binding.btnShare.setOnClickListener { handleShareAction() }
        binding.btnFavorite.setOnClickListener { handleFavoriteAction() }
        binding.btnDelete.setOnClickListener { handleDeleteAction() }
    }

    /**
     * 处理照片点击
     */
    private fun handlePhotoClick(photo: Photo) {
        if (selectionManager.isSelectionMode.value) {
            selectionManager.toggleSelection(photo)
        } else {
            navigateToDetail(photo)
        }
    }

    /**
     * 处理照片长按
     */
    private fun handlePhotoLongClick(photo: Photo): Boolean {
        val position = selectionManager.getPosition(photo.id) ?: return false
        selectionManager.startDragSelection(position)
        return true
    }

    /**
     * 处理分享操作
     */
    private fun handleShareAction() {
        val photos = getSelectedPhotos()
        if (photos.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_photos_selected, Toast.LENGTH_SHORT).show()
            return
        }
        batchActionHandler.sharePhotos(photos)
        selectionManager.exitSelectionMode()
    }

    /**
     * 处理收藏操作
     */
    private fun handleFavoriteAction() {
        val photos = getSelectedPhotos()
        if (photos.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_photos_selected, Toast.LENGTH_SHORT).show()
            return
        }

        pendingFavoriteAction = calculateFavoriteAction(photos)

        val selectedIds = photos.map { it.id }.toSet()

        // 先设置 IntentSender 结果回调
        intentSenderManager.setFavoriteCallback { success ->
            if (success) {
                // 显示成功提示
                val message = when (pendingFavoriteAction) {
                    BatchActionHandler.FAVORITE_ACTION_ADD -> getString(R.string.added_to_favorite)
                    BatchActionHandler.FAVORITE_ACTION_REMOVE -> getString(R.string.removed_from_favorite)
                    BatchActionHandler.FAVORITE_ACTION_MIXED -> getString(R.string.favorite_toggled)
                    else -> null
                }
                message?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                }

                // 延迟一点时间让动画更自然，然后刷新
                binding.rvPhotos.postDelayed({
                    refreshData(smooth = true)
                    selectionManager.exitSelectionMode()
                }, 300)
            }
            pendingFavoriteAction = BatchActionHandler.FAVORITE_ACTION_NONE
        }

        val hasRequest = batchActionHandler.favoritePhotos(
            photos,
            intentSenderManager.favoriteLauncher,
            pendingFavoriteAction
        )

        // 如果没有需要 IntentSender 的请求（直接成功），也刷新并退出
        if (!hasRequest) {
            // 显示视觉反馈（渐隐效果）
            smoothRefreshItems(selectedIds)

            binding.rvPhotos.postDelayed({
                refreshData(smooth = true)
                selectionManager.exitSelectionMode()
            }, 300)
        }
    }

    /**
     * 处理删除操作
     */
    private fun handleDeleteAction() {
        val photos = getSelectedPhotos()
        if (photos.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_photos_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIds = photos.map { it.id }.toSet()

        // 设置移至回收站的结果回调
        intentSenderManager.setTrashCallback { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                // 延迟刷新让动画更自然
                binding.rvPhotos.postDelayed({
                    refreshData(smooth = true)
                    selectionManager.exitSelectionMode()
                }, 300)
            }
        }

        // 设置永久删除的结果回调
        intentSenderManager.setDeleteCallback { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                // 延迟刷新让动画更自然
                binding.rvPhotos.postDelayed({
                    refreshData(smooth = true)
                    selectionManager.exitSelectionMode()
                }, 300)
            }
        }

        // 显示删除选项对话框
        batchActionHandler.showDeleteOptions(
            photos,
            intentSenderManager.trashLauncher,
            intentSenderManager.deleteLauncher
        ) {
            // 这个回调只在不需要 IntentSender 时触发（直接成功或失败）
            // 添加视觉反馈后延迟刷新
            smoothRefreshItems(selectedIds)
            binding.rvPhotos.postDelayed({
                refreshData(smooth = true)
                selectionManager.exitSelectionMode()
            }, 300)
        }
    }

    /**
     * 计算收藏操作类型
     */
    private fun calculateFavoriteAction(photos: List<Photo>): Int {
        val hasFavorite = photos.any { !it.isFavorite }
        val hasUnfavorite = photos.any { it.isFavorite }
        return when {
            hasFavorite && hasUnfavorite -> BatchActionHandler.FAVORITE_ACTION_MIXED
            hasFavorite -> BatchActionHandler.FAVORITE_ACTION_ADD
            hasUnfavorite -> BatchActionHandler.FAVORITE_ACTION_REMOVE
            else -> BatchActionHandler.FAVORITE_ACTION_NONE
        }
    }

    /**
     * 获取选中的照片列表
     */
    private fun getSelectedPhotos(): List<Photo> {
        val selectedIds = selectionManager.selectedPhotoIds
        return selectedIds.mapNotNull { id -> findPhotoById(id) }
    }

    /**
     * 根据 ID 查找照片（使用缓存优化）
     */
    private fun findPhotoById(id: Long): Photo? {
        for (i in 0 until photoAdapter.itemCount) {
            photoAdapter.getPhoto(i)?.let { if (it.id == id) return it }
        }
        return null
    }

    /**
     * 设置 Fragment 结果监听
     */
    private fun setupFragmentResultListener() {
        setFragmentResultListener(REQUEST_KEY_PHOTO_DELETED) { _, _ ->
            refreshData()
        }
    }

    /**
     * 观察数据流
     */
    private fun observeData() {
        // 观察 Paging 数据
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoPagingFlow.collectLatest { pagingData ->
                    photoAdapter.submitData(pagingData)
                }
            }
        }

        // 观察加载状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                photoAdapter.loadStateFlow.collect { loadStates ->
                    val isInitialLoading = loadStates.refresh is LoadState.Loading
                    val isEmpty = loadStates.refresh is LoadState.NotLoading && photoAdapter.itemCount == 0
                    val hasError = loadStates.refresh is LoadState.Error

                    binding.progressBar.visibility = if (isInitialLoading) View.VISIBLE else View.GONE
                    binding.emptyStateView.visibility = if (isEmpty && !isInitialLoading) View.VISIBLE else View.GONE
                    binding.rvPhotos.visibility = if (isInitialLoading || hasError) View.GONE else View.VISIBLE

                    // 首次加载完成后预热，减少后续滑动卡顿
                    if (isInitialLoading && !isWarmedUp) {
                        // 正在刷新状态
                    } else if (!isInitialLoading && !isWarmedUp && photoAdapter.itemCount > 0) {
                        isWarmedUp = true
                        // 移除这里的 warmupSpanCache() 调用，因为其后台线程操作存在竞争风险
                    }
                }
            }
        }

        // 观察照片数量
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoCount.collect { updateSubtitle() }
            }
        }

        // 观察收藏筛选状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showFavoritesOnly.collect { showFavoritesOnly ->
                    binding.btnFilter.setImageResource(
                        if (showFavoritesOnly) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
                    )
                    updateSubtitle()
                }
            }
        }
        
        // 观察是否需要首次扫描
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.needsScan.collect { needsScan ->
                    if (needsScan) {
                        showScanningDialog()
                        viewModel.startScan()
                        viewModel.observeScanState()
                    }
                }
            }
        }
        
        // 观察扫描状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanState.collect { state ->
                    when (state) {
                        is MetadataScanner.ScanState.Completed -> {
                            // 扫描完成，保存位置后刷新数据
                            val savedPosition = gridLayoutManager.findFirstVisibleItemPosition()
                            var savedOffset = 0
                            if (savedPosition != RecyclerView.NO_POSITION) {
                                val firstVisibleView = gridLayoutManager.findViewByPosition(savedPosition)
                                if (firstVisibleView != null) {
                                    savedOffset = firstVisibleView.top
                                }
                            }
                            
                            refreshData(smooth = false)
                            viewModel.refresh()
                            
                            // 恢复位置
                            if (savedPosition >= 0) {
                                binding.rvPhotos.post {
                                    try {
                                        gridLayoutManager.scrollToPositionWithOffset(savedPosition, savedOffset)
                                    } catch (e: Exception) {
                                        // 忽略
                                    }
                                }
                            }
                        }
                        else -> {
                            // 其他状态由对话框处理
                        }
                    }
                }
            }
        }

        // 观察排序和分组状态并同步到适配器，用于快速滑动时的日期显示
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.currentSortType,
                    viewModel.currentGroupType
                ) { sortType, groupType -> Pair(sortType, groupType) }
                    .collect { (sortType, groupType) ->
                        photoAdapter.updateSortAndGroupType(sortType, groupType)
                    }
            }
        }
    }
    
    /**
     * 显示扫描进度对话框
     */
    private fun showScanningDialog() {
        val dialog = ScanningProgressDialog.newInstance()
        dialog.show(childFragmentManager, ScanningProgressDialog.TAG)
    }

    /**
     * 观察选择状态变化
     */
    private fun observeSelectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionManager.isSelectionMode.collect { isSelectionMode ->
                    if (isSelectionMode) {
                        binding.normalToolbar.visibility = View.GONE
                        binding.selectionToolbar.visibility = View.VISIBLE
                    } else {
                        binding.normalToolbar.visibility = View.VISIBLE
                        binding.selectionToolbar.visibility = View.GONE
                    }
                    refreshVisibleItems()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionManager.selectedCount.collect { count ->
                    binding.tvSelectionCount.text = getString(R.string.selected, count)
                }
            }
        }
    }

    /**
     * 检查权限
     */
    private fun checkPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        com.permissionx.guolindev.PermissionX.init(this)
            .permissions(*permissions)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    // 仅当适配器为空（首次启动或由于错误未加载）时才刷新
                    // 避免每次从详情页返回时都触发刷新导致列表跳动
                    if (photoAdapter.itemCount == 0) {
                        refreshData(smooth = false)
                        viewModel.refresh()
                    }
                }
            }
    }

    /**
     * 显示菜单
     */
    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photos, popupMenu.menu)

        popupMenu.menu.findItem(R.id.action_select)?.title =
            if (selectionManager.isSelectionMode.value) getString(R.string.cancel_select)
            else getString(R.string.select)

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_select -> {
                    selectionManager.toggleSelectionMode()
                    true
                }
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_group -> { showGroupDialog(); true }
                R.id.action_columns -> { showColumnsDialog(); true }
                R.id.action_trash -> { navigateToTrash(); true }
                else -> false
            }
        }
        popupMenu.show()
    }

    /**
     * 显示排序对话框
     */
    private fun showSortDialog() {
        val currentSortType = viewModel.currentSortType.value
        val options = arrayOf(
            getString(R.string.sort_by_date_taken),
            getString(R.string.sort_by_date_added)
        )
        val checkedItem = when (currentSortType) {
            MediaRepository.SortType.DATE_TAKEN -> 0
            MediaRepository.SortType.DATE_ADDED -> 1
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSortType = if (which == 0) {
                    MediaRepository.SortType.DATE_TAKEN
                } else {
                    MediaRepository.SortType.DATE_ADDED
                }
                saveSortType(newSortType)
                viewModel.setSortType(newSortType)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadSortType(): MediaRepository.SortType {
        return when (sharedPreferences.getInt(KEY_SORT_TYPE, 0)) {
            0 -> MediaRepository.SortType.DATE_TAKEN
            else -> MediaRepository.SortType.DATE_ADDED
        }
    }

    private fun saveSortType(sortType: MediaRepository.SortType) {
        sharedPreferences.edit()
            .putInt(KEY_SORT_TYPE, if (sortType == MediaRepository.SortType.DATE_TAKEN) 0 else 1)
            .apply()
    }

    /**
     * 显示分组对话框
     */
    private fun showGroupDialog() {
        val currentGroupType = viewModel.currentGroupType.value
        val options = arrayOf(
            getString(R.string.group_by_day),
            getString(R.string.group_by_month),
            getString(R.string.group_by_year)
        )
        val checkedItem = when (currentGroupType) {
            GroupType.DAY -> 0
            GroupType.MONTH -> 1
            GroupType.YEAR -> 2
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_group)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newGroupType = when (which) {
                    0 -> GroupType.DAY
                    1 -> GroupType.MONTH
                    2 -> GroupType.YEAR
                    else -> GroupType.DAY
                }
                saveGroupType(newGroupType)
                viewModel.setGroupType(newGroupType)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadGroupType(): GroupType {
        return when (sharedPreferences.getInt(KEY_GROUP_TYPE, 0)) {
            0 -> GroupType.DAY
            1 -> GroupType.MONTH
            2 -> GroupType.YEAR
            else -> GroupType.DAY
        }
    }

    private fun saveGroupType(groupType: GroupType) {
        val value = when (groupType) {
            GroupType.DAY -> 0
            GroupType.MONTH -> 1
            GroupType.YEAR -> 2
        }
        sharedPreferences.edit().putInt(KEY_GROUP_TYPE, value).apply()
    }

    /**
     * 显示列数选择对话框
     */
    private fun showColumnsDialog() {
        val options = arrayOf("3", "4", "5", "6", "7", "8")
        val checkedItem = currentSpanCount - 3

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_columns)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSpan = which + 3
                if (newSpan != currentSpanCount) {
                    updateSpanCount(newSpan)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSpanCount(newSpanCount: Int) {
        currentSpanCount = newSpanCount
        sharedPreferences.edit().putInt(KEY_SPAN_COUNT, newSpanCount).apply()

        calculateItemSize()
        gridLayoutManager.spanCount = newSpanCount
        photoAdapter.updateConfig(itemSize, newSpanCount)

        // 更新分割线
        while (binding.rvPhotos.itemDecorationCount > 0) {
            binding.rvPhotos.removeItemDecorationAt(0)
        }
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(newSpanCount, dpToPx(2), true))
    }

    /**
     * 刷新数据
     * @param smooth 是否使用平滑刷新（带有动画过渡）
     * @param preservePosition 是否保持滚动位置（从详情页返回时使用）
     */
    private fun refreshData(smooth: Boolean = true, preservePosition: Boolean = false) {
        // 如果需要保持位置，保存当前滚动位置
        var savedPosition = -1
        var savedOffset = 0
        if (preservePosition) {
            savedPosition = gridLayoutManager.findFirstVisibleItemPosition()
            if (savedPosition != RecyclerView.NO_POSITION) {
                val firstVisibleView = gridLayoutManager.findViewByPosition(savedPosition)
                if (firstVisibleView != null) {
                    savedOffset = firstVisibleView.top
                }
            }
        }
        
        // 清除适配器缓存
        photoAdapter.clearCache()
        viewModel.refresh()
        photoAdapter.refresh()
        mediaChangeDetector.reset()
        
        // 恢复滚动位置
        if (preservePosition && savedPosition >= 0) {
            binding.rvPhotos.post {
                try {
                    gridLayoutManager.scrollToPositionWithOffset(savedPosition, savedOffset)
                } catch (e: Exception) {
                    // 忽略位置恢复失败
                }
            }
        }
    }

    /**
     * 平滑刷新单个照片项（用于收藏状态变化等）
     * 优化：只遍历可见项范围，避免遍历整个列表
     */
    private fun smoothRefreshItems(photoIds: Set<Long>) {
        // 获取可见范围
        val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
        
        if (firstVisible == RecyclerView.NO_POSITION) return
        
        // 找到对应的位置并局部刷新
        val positions = mutableListOf<Int>()
        val searchEnd = minOf(lastVisible + 10, photoAdapter.itemCount - 1)
        
        for (i in maxOf(0, firstVisible - 10)..searchEnd) {
            photoAdapter.getPhoto(i)?.let { photo ->
                if (photo.id in photoIds) {
                    positions.add(i)
                }
            }
        }

        // 使用渐变动画刷新
        if (positions.isNotEmpty()) {
            positions.forEach { position ->
                val holder = binding.rvPhotos.findViewHolderForAdapterPosition(position)
                holder?.itemView?.animate()
                    ?.alpha(0.7f)
                    ?.setDuration(150)
                    ?.withEndAction {
                        photoAdapter.notifyItemChanged(position)
                        holder.itemView.animate()
                            .alpha(1f)
                            .setDuration(150)
                            .start()
                    }
                    ?.start()
            }
        }
    }

    /**
     * 刷新可见项
     */
    private fun refreshVisibleItems() {
        val first = gridLayoutManager.findFirstVisibleItemPosition()
        val last = gridLayoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            photoAdapter.notifyItemRangeChanged(first, last - first + 1)
        }
    }

    /**
     * 更新副标题
     */
    private fun updateSubtitle() {
        val count = viewModel.getCurrentPhotoCount()
        val showFavoritesOnly = viewModel.showFavoritesOnly.value
        binding.tvSubtitle.text = if (showFavoritesOnly) {
            getString(R.string.favorite_count, count)
        } else {
            getString(R.string.photo_count, count)
        }
    }

    /**
     * 导航到详情页
     */
    private fun navigateToDetail(photo: Photo) {
        val sortTypeValue = if (viewModel.currentSortType.value == MediaRepository.SortType.DATE_TAKEN) 0 else 1
        val action = PhotosFragmentDirections.actionPhotosFragmentToPhotoDetailFragment(photo.id, sortTypeValue)
        findNavController().navigate(action)
    }

    /**
     * 导航到回收站
     */
    private fun navigateToTrash() {
        val action = PhotosFragmentDirections.actionPhotosFragmentToTrashFragment()
        findNavController().navigate(action)
    }

    /**
     * 处理返回键
     */
    fun onBackPressed(): Boolean {
        return if (selectionManager.isSelectionMode.value) {
            selectionManager.exitSelectionMode()
            true
        } else {
            false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // 屏幕旋转时重新计算最优列数
        val optimalSpanCount = calculateOptimalSpanCount()
        
        // 如果当前列数与最优列数不同，更新配置
        if (currentSpanCount != optimalSpanCount) {
            updateSpanCount(optimalSpanCount)
        } else {
            calculateItemSize()
            photoAdapter.updateConfig(itemSize, currentSpanCount)
        }
    }

    private fun calculateItemSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemSpacing = dpToPx(2) * (currentSpanCount + 1)
        itemSize = (screenWidth - itemSpacing) / currentSpanCount
    }

    /**
     * 预热 span 缓存
     * 注意：原有逻辑在 Background 线程执行会导致并发修改 SpanSizeLookup 的缓存引起跳动
     * 现在改为非阻塞方式在 UI 空闲时处理（可选操作，目前为了稳定性先禁用）
     */
    private fun warmupSpanCache() {
        // 为了解决详情页返回后的跳动问题，我们暂时禁用后台线程预热逻辑
        // 因为 SpanSizeLookup 的缓存是非线程安全的
    }

    /**
     * 预加载首屏图片到 Glide 缓存
     * 已由 RecyclerViewPreloader 处理，此方法保留但不再主动调用
     * 减少重复预加载造成的资源竞争
     */
    private fun preloadInitialImages() {
        val preloadCount = minOf(12, photoAdapter.itemCount)
        val thumbnailSize = (itemSize / 2).coerceAtLeast(100)

        for (i in 0 until preloadCount) {
            val photo = photoAdapter.getPhoto(i) ?: continue
            Glide.with(requireContext())
                .load(photo.uri)
                .override(itemSize, itemSize)
                .centerCrop()
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
                .preload(thumbnailSize, thumbnailSize)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        // 清理资源，避免内存泄漏
        selectionManager.onDestroy()
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        gridLayoutManager.spanSizeLookup = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        // 响应式列数配置：基于每个单元格的最小宽度
        private const val MIN_CELL_WIDTH_DP = 100  // 每个单元格最小宽度（dp）
        private const val MIN_SPAN_COUNT = 3       // 最小列数
        private const val MAX_SPAN_COUNT = 10      // 最大列数
        
        private const val ITEM_VIEW_CACHE_SIZE = 24
        private const val PRELOAD_ITEM_COUNT = 6
        // RecyclerView 预取数量（每行预取的数量 * 列数）
        private const val PREFETCH_ITEM_COUNT = 12

        private const val KEY_SPAN_COUNT = "span_count"
        private const val KEY_SORT_TYPE = "sort_type"
        private const val KEY_GROUP_TYPE = "group_type"

        const val REQUEST_KEY_PHOTO_DELETED = "photo_deleted"
    }
}