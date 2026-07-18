package com.gxstar.stargallery.ui.photos

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.util.ViewPreloadSizeProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatDelegate
import com.gxstar.stargallery.R
import com.gxstar.stargallery.StarGalleryApp
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.gxstar.stargallery.ui.photos.filter.FilterBottomSheet
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import com.gxstar.stargallery.ui.common.GridSpanCalculator
import com.gxstar.stargallery.ui.common.GridSpanPreferences
import com.gxstar.stargallery.ui.photos.action.BatchActionHandler
import com.gxstar.stargallery.ui.photos.animation.PhotoItemAnimator
import com.gxstar.stargallery.ui.photos.launcher.IntentSenderManager
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.photos.refresh.MediaChangeDetector
import com.gxstar.stargallery.ui.photos.selection.PhotoSelectionManager
import com.gxstar.stargallery.ui.detail.PhotoDetailListCache
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 照片列表 Fragment
 * 职责：协调各管理器，处理 UI 事件
 * 数据源：直接使用 MediaStore，通过 Paging 3 实现实时刷新
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
    private var photoAdapter: PhotoListAdapter? = null
    private var gridLayoutManager: GridLayoutManager? = null
    private var photoItemAnimator: PhotoItemAnimator? = null
    private var glidePreloader: RecyclerViewPreloader<*>? = null
    private var gridSpacingItemDecoration: GridSpacingItemDecoration? = null

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var photoDetailListCache: PhotoDetailListCache

    // 状态
    private var currentSpanCount = GridSpanCalculator.MIN_SPAN_COUNT
    private var itemSize = 0
    private var savedScrollPosition = -1
    private var savedScrollOffset = 0
    private var fastScrollerReady = false

    // 收藏操作类型
    private var pendingFavoriteAction = BatchActionHandler.FAVORITE_ACTION_NONE

    // Adapter provider
    private var isSelectionModeProvider: () -> Boolean = { false }
    private var isSelectedProvider: (Int) -> Boolean = { false }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectionManager.exitSelectionMode()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.rescanAfterPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentSenderManager = IntentSenderManager(this)
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
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
        initMediaChangeDetector()
        setupRecyclerView()
        setupClickListeners()
        setupSearchViews()
        setupFragmentResultListener()
        observeData()
        observeSelectionState()
        checkPermissions()
    }

    /**
     * 初始化 Adapter
     */
    private fun initAdapter() {
        photoAdapter = PhotoListAdapter(
            itemSize = itemSize,
            onPhotoClick = { photo -> handlePhotoClick(photo) },
            onPhotoLongClick = { position -> selectionManager.startDragSelection(position) },
            isSelectionModeProvider = { isSelectionModeProvider() },
            isSelectedProvider = { position -> isSelectedProvider(position) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    private fun bindSelectionProviders() {
        isSelectionModeProvider = { selectionManager.isInSelectionMode() }
        isSelectedProvider = { position -> selectionManager.isSelectedPosition(position) }
    }

    private fun initManagers() {
        // 延迟初始化 selectionManager，因为需要 recyclerView
        batchActionHandler = BatchActionHandler(this, mediaRepository, childFragmentManager)
    }

    /**
     * 初始化媒体变化检测器
     * ContentObserver 作为触发器，检测到变化时刷新 Paging 数据
     */
    private fun initMediaChangeDetector() {
        mediaChangeDetector = MediaChangeDetector(
            lifecycleOwner = viewLifecycleOwner,
            context = requireContext(),
            onChangeDetected = {
                viewModel.silentRefresh()
            },
            shouldSkipRefresh = {
                System.currentTimeMillis() - lastExplicitRefreshTime < 1000
            }
        )
    }

    private var lastExplicitRefreshTime = 0L

    // 首屏列表是否已渲染完成。用于解耦首屏渲染与后台静默同步：
    // 先让 Room 已有数据立即渲染列表，渲染完成后再触发增量扫描，
    // 避免冷启动时增量扫描（全量查 MediaStore + Room 差集）阻塞列表整体出现。
    private var hasRenderedFirstList = false

    private fun setupSettings() {
        // 用当前方向（onCreate 时）解析。
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        currentSpanCount = resolveSpanCountForOrientation(isLandscape)
        calculateItemSize()

        val sortType = loadSortType()
        viewModel.setSortType(sortType)

        val groupType = loadGroupType()
        viewModel.setGroupType(groupType)
    }

    private fun calculateOptimalSpanCount(): Int {
        return GridSpanCalculator.calculateOptimalSpanCount(resources.displayMetrics)
    }

    /**
     * 按"用户在该方向设置优先"解析列数：
     * 1) 当前方向存了用户值 → 用
     * 2) 否则另一方向存了值 → 用另一方向（用户没在当前方向设过，暂复用）
     * 3) 否则根据当前屏宽自动计算
     */
    private fun resolveSpanCountForOrientation(isLandscape: Boolean): Int {
        return GridSpanPreferences.resolveForOrientation(
            prefs = sharedPreferences,
            prefix = SPAN_COUNT_PREFIX,
            isLandscape = isLandscape,
            fallback = calculateOptimalSpanCount()
        )
    }

    private fun setupRecyclerView() {
        val photoListAdapter = photoAdapter ?: return
        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (position < 0 || position >= photoListAdapter.itemCount) return 1
                    return if (photoListAdapter.getItemViewType(position) == 0) currentSpanCount else 1
                }
            }.apply {
                isSpanIndexCacheEnabled = true
            }
            isItemPrefetchEnabled = true
            isMeasurementCacheEnabled = true
            initialPrefetchItemCount = currentSpanCount * 4
        }

        binding.rvPhotos.apply {
            layoutManager = gridLayoutManager
            adapter = photoListAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(8) // 减小缓存，避免快速刷新时出现复用残留
            photoItemAnimator = PhotoItemAnimator().apply {
                supportsChangeAnimations = false // 彻底禁用变更动画，消除残影
            }
            itemAnimator = photoItemAnimator
            val spacing = GridSpanCalculator.dpToPx(2, resources.displayMetrics)
            val decoration = GridSpacingItemDecoration(currentSpanCount, spacing, true)
            gridSpacingItemDecoration = decoration
            addItemDecoration(decoration)
        }

        selectionManager = PhotoSelectionManager(binding.rvPhotos, photoListAdapter)
        selectionManager.init()
        bindSelectionProviders()

        setupGlidePreloader()
    }

    private fun setupFastScroller() {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val bottomNavReserve = (80 * density).toInt()
        FastScrollerBuilder(binding.rvPhotos)
            .setPadding(0, 0, 0, bottomNavReserve)
            .setPopupTextProvider { _, position ->
                val list = photoAdapter?.currentList ?: return@setPopupTextProvider ""
                if (position !in list.indices) return@setPopupTextProvider ""
                for (i in position downTo 0) {
                    when (val item = list[i]) {
                        is PhotoModel.SeparatorItem -> return@setPopupTextProvider item.dateText
                        else -> {}
                    }
                }
                ""
            }
            .setPopupStyle { popupView ->
                val params = popupView.layoutParams as FrameLayout.LayoutParams
                params.gravity = Gravity.RIGHT
                popupView.layoutParams = params
                popupView.background = ContextCompat.getDrawable(context, R.drawable.bg_fastscroll_popup)
                popupView.setTextColor(ContextCompat.getColor(context, R.color.fastscroll_popup_text))
                popupView.textSize = 12f
                popupView.includeFontPadding = false
                popupView.translationX = -(32 * density)
            }
            .setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.fastscroll_track)!!)
            .setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.fastscroll_thumb)!!)
            .build()
    }

    private fun setupGlidePreloader() {
        val adapter = photoAdapter ?: return
        val preloadCount = currentSpanCount * 3
        val glideRequest = Glide.with(this)
        val preloadSizeProvider = ViewPreloadSizeProvider<Any>()
        val preloader = RecyclerViewPreloader(
            glideRequest,
            PhotoPreloadModelProvider(
                glideRequest,
                { position -> photoAdapter?.currentList?.getOrNull(position) as? PhotoModel.PhotoItem },
                itemSize
            ),
            preloadSizeProvider,
            preloadCount
        )
        glidePreloader?.let { binding.rvPhotos.removeOnScrollListener(it) }
        binding.rvPhotos.addOnScrollListener(preloader)
        glidePreloader = preloader
    }

    private fun setupClickListeners() {
        binding.btnMore.setOnClickListener { showPopupMenu(it) }
        binding.btnFilter.setOnClickListener { viewModel.toggleFavoritesOnly() }
        binding.btnFilterExif.setOnClickListener { showFilterSheet() }
        binding.btnSearch.setOnClickListener { enterSearchMode() }
        binding.btnBack.setOnClickListener { selectionManager.exitSelectionMode() }
        binding.btnShare.setOnClickListener { handleShareAction() }
        binding.btnHide.setOnClickListener { handleHideAction() }
        binding.btnFavorite.setOnClickListener { handleFavoriteAction() }
        binding.btnDelete.setOnClickListener { handleDeleteAction() }
    }

    /**
     * 进入搜索模式：显示 searchToolbar，隐藏 normalToolbar
     */
    private fun enterSearchMode() {
        binding.normalToolbar.visibility = View.GONE
        binding.searchToolbar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 退出搜索模式：隐藏 searchToolbar，恢复 normalToolbar
     */
    private fun exitSearchMode() {
        viewModel.setSearchQuery(null)
        binding.searchToolbar.visibility = View.GONE
        binding.normalToolbar.visibility = View.VISIBLE
        binding.etSearch.setText("")
        binding.btnSearchClear.visibility = View.GONE
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun setupSearchViews() {
        // 返回按钮退出搜索
        binding.btnSearchBack.setOnClickListener { exitSearchMode() }

        // 清除按钮清除输入
        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            viewModel.setSearchQuery(null)
        }

        // 输入框实时过滤
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    viewModel.setSearchQuery(null)
                    binding.btnSearchClear.visibility = View.GONE
                } else {
                    viewModel.setSearchQuery(text)
                    binding.btnSearchClear.visibility = View.VISIBLE
                }
            }
        })

        // 搜索键提交
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                binding.etSearch.clearFocus()
                true
            } else false
        }
    }

    private fun handlePhotoClick(photo: Photo) {
        if (selectionManager.isSelectionMode.value) {
            selectionManager.toggleSelection(photo)
        } else {
            navigateToDetail(photo)
        }
    }

    /**
     * 根据照片 ID 找到其在 RecyclerView 中的位置
     */
    private fun findPhotoPosition(photoId: Long): Int {
        val snapshot = photoAdapter?.currentList ?: return RecyclerView.NO_POSITION
        for (i in 0 until snapshot.size) {
            val item = snapshot[i]
            if (item is PhotoModel.PhotoItem && item.photo.id == photoId) {
                return i
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun handleShareAction() {
        val photos = getSelectedPhotosOrShowToast() ?: return
        batchActionHandler.sharePhotos(photos)
        selectionManager.exitSelectionMode()
    }

    private fun handleHideAction() {
        val photos = getSelectedPhotosOrShowToast() ?: return
        val selectedIds = photos.map { it.id }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hide)
            .setMessage(getString(R.string.hide_selected_confirm, photos.size))
            .setPositiveButton(R.string.hide) { _, _ ->
                Toast.makeText(requireContext(), R.string.hidden_success, Toast.LENGTH_SHORT).show()
                viewModel.updateHidden(selectedIds, true)
                selectionManager.exitSelectionMode()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleFavoriteAction() {
        val photos = getSelectedPhotosOrShowToast() ?: return

        pendingFavoriteAction = calculateFavoriteAction(photos)
        val selectedIds = photos.map { it.id }

        intentSenderManager.setFavoriteCallback { success ->
            if (success) {
                val message = when (pendingFavoriteAction) {
                    BatchActionHandler.FAVORITE_ACTION_ADD -> getString(R.string.added_to_favorite)
                    BatchActionHandler.FAVORITE_ACTION_REMOVE -> getString(R.string.removed_from_favorite)
                    BatchActionHandler.FAVORITE_ACTION_MIXED -> getString(R.string.favorite_toggled)
                    else -> null
                }
                message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                selectionManager.exitSelectionMode()
                // ViewModel 通过 _refreshTrigger 或 Room invalidationTracker 自动触发列表刷新
                when (pendingFavoriteAction) {
                    BatchActionHandler.FAVORITE_ACTION_ADD -> {
                        viewModel.updateFavorite(selectedIds, true)
                    }
                    BatchActionHandler.FAVORITE_ACTION_REMOVE -> {
                        viewModel.updateFavorite(selectedIds, false)
                    }
                    BatchActionHandler.FAVORITE_ACTION_MIXED -> {
                        val toFavorite = photos.filter { !it.isFavorite }.map { it.id }
                        val toUnfavorite = photos.filter { it.isFavorite }.map { it.id }
                        viewModel.updateFavoriteMixed(toFavorite, toUnfavorite)
                    }
                }
            }
            pendingFavoriteAction = BatchActionHandler.FAVORITE_ACTION_NONE
        }

        val hasRequest = batchActionHandler.favoritePhotos(
            photos,
            intentSenderManager.favoriteLauncher,
            pendingFavoriteAction
        )

        if (!hasRequest) {
            smoothRefreshItems(selectedIds.toSet())
            selectionManager.exitSelectionMode()
        }
    }

    private fun handleDeleteAction() {
        val photos = getSelectedPhotosOrShowToast() ?: return
        val selectedIds = photos.map { it.id }
        val onSuccess: () -> Unit = {
            selectionManager.exitSelectionMode()
            viewModel.deletePhotos(selectedIds)
            lastExplicitRefreshTime = System.currentTimeMillis()
        }

        com.gxstar.stargallery.ui.common.DeleteOptionsBottomSheet.newInstance(
            onMoveToTrash = {
                intentSenderManager.setTrashCallback { success ->
                    if (success) {
                        Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                        onSuccess()
                    }
                }
                batchActionHandler.moveToTrash(photos, intentSenderManager.trashLauncher) {
                    selectionManager.exitSelectionMode()
                }
            },
            onDeletePermanently = {
                intentSenderManager.setDeleteCallback { success ->
                    if (success) {
                        Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                        onSuccess()
                    }
                }
                batchActionHandler.deletePermanently(photos, intentSenderManager.deleteLauncher) {
                    selectionManager.exitSelectionMode()
                }
            }
        ).show(childFragmentManager, com.gxstar.stargallery.ui.common.DeleteOptionsBottomSheet.TAG)
    }

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

    private fun getSelectedPhotos(): List<Photo> {
        val selectedIds = selectionManager.selectedPhotoIds
        return selectedIds.mapNotNull { id -> findPhotoById(id) }
    }

    private fun getSelectedPhotosOrShowToast(): List<Photo>? {
        val photos = getSelectedPhotos()
        if (photos.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_photos_selected, Toast.LENGTH_SHORT).show()
            return null
        }
        return photos
    }

    private fun findPhotoById(id: Long): Photo? {
        val snapshot = photoAdapter?.currentList ?: return null
        for (i in 0 until snapshot.size) {
            val item = snapshot[i]
            if (item is PhotoModel.PhotoItem && item.photo.id == id) {
                return item.photo
            }
        }
        return null
    }

    private fun setupFragmentResultListener() {
        setFragmentResultListener(REQUEST_KEY_PHOTO_DELETED) { _, bundle ->
            val photoIds = bundle.getLongArray("photo_ids")?.toList() ?: emptyList()
            if (photoIds.isNotEmpty()) {
                if (bundle.getBoolean("is_remove", false)) {
                    // 删除/回收站操作：直接从 Room 移除，Room Flow 自动推送更新
                    viewModel.deletePhotos(photoIds)
                } else {
                    // 恢复操作：从 MediaStore 精确回写到 Room
                    viewModel.syncPhotosFromMediaStore(photoIds)
                }
            } else {
                // 无精确 ID 时兜底触发增量扫描
                refreshData()
            }
        }
    }

    private fun observeData() {
        var lastSortType: MediaRepository.SortType? = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.isScanning,
                    viewModel.isExtractingExif,
                    viewModel.photoListFlow
                ) { isScanning, isExtractingExif, photoModels ->
                    Triple(isScanning, isExtractingExif, photoModels)
                }.collect { (isScanning, isExtractingExif, photoModels) ->
                    val isSyncing = viewModel.isSyncing.value
                    val currentPos = gridLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                    val currentOffset = if (currentPos != RecyclerView.NO_POSITION) {
                        gridLayoutManager?.findViewByPosition(currentPos)?.top ?: 0
                    } else 0
                    val userIsAtTop = currentPos in 0..1 && currentOffset <= 50

                    val currentSortType = viewModel.currentSortType.value
                    val isSortChanged = lastSortType != null && lastSortType != currentSortType
                    lastSortType = currentSortType

                    if (isScanning || isExtractingExif || userIsAtTop || isSortChanged) {
                        if (binding.rvPhotos.itemAnimator != null) {
                            binding.rvPhotos.itemAnimator = null
                        }
                    }

                    val anchorPhotoId = if (isSortChanged) findFirstVisiblePhotoId() else -1L

                    val submitCallback = Runnable {
                        if (userIsAtTop) {
                            gridLayoutManager?.scrollToPositionWithOffset(0, 0)
                        } else if (isSortChanged && anchorPhotoId >= 0) {
                            scrollToPhotoById(anchorPhotoId)
                        }

                        if (!isScanning && !isExtractingExif && binding.rvPhotos.itemAnimator == null && photoItemAnimator != null) {
                            binding.rvPhotos.itemAnimator = photoItemAnimator
                        }

                        if (!fastScrollerReady && photoModels.isNotEmpty()) {
                            fastScrollerReady = true
                            binding.rvPhotos.post { setupFastScroller() }
                        }
                    }

                    if (isSortChanged) {
                        photoAdapter?.submitFullReorder(photoModels, submitCallback)
                    } else {
                        photoAdapter?.submitList(photoModels, submitCallback)
                    }

                    val isEmpty = photoModels.isEmpty()

                    // 首屏列表首次渲染完成后，再触发后台静默同步（增量扫描）。
                    // 这样 Room 已有数据先整体出现，扫描作为后台更新，不再阻塞首屏。
                    if (!hasRenderedFirstList && !isEmpty) {
                        hasRenderedFirstList = true
                        mediaChangeDetector.triggerInitialSync()
                    }

                    binding.scanningView.visibility = if (isScanning && !isEmpty) View.VISIBLE else View.GONE
                    binding.progressBar.visibility = if ((isScanning || isSyncing) && isEmpty) View.VISIBLE else View.GONE
                    binding.emptyStateView.visibility = if (!isScanning && !isSyncing && isEmpty) View.VISIBLE else View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filteredPhotoCount.collect { updateSubtitle() }
            }
        }

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isExifFilterActive.collect { isActive ->
                    binding.btnFilterExif.setImageResource(
                        if (isActive) R.drawable.ic_filter_active else R.drawable.ic_filter
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.currentSortType,
                    viewModel.currentGroupType
                ) { sortType, groupType -> Pair(sortType, groupType) }
                    .collect { (sortType, groupType) ->
                        photoAdapter?.updateSortAndGroupType(sortType, groupType)
                    }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.isExtractingExif,
                    viewModel.exifProgress
                ) { extracting, progress -> extracting to progress }
                .collect { (extracting, progress) ->
                    if (extracting) {
                        binding.exifProgressBar.visibility = View.VISIBLE
                        binding.exifProgressBar.progress = (progress * 100).toInt()
                    } else {
                        binding.exifProgressBar.progress = 100
                        delay(400)
                        binding.exifProgressBar.visibility = View.GONE
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSearching.collect { searching ->
                    binding.btnSearch.setImageResource(
                        if (searching) R.drawable.ic_filter_active else R.drawable.ic_search
                    )
                }
            }
        }
    }

    private fun findFirstVisiblePhotoId(): Long {
        val pos = gridLayoutManager?.findFirstVisibleItemPosition() ?: return -1L
        val list = photoAdapter?.currentList ?: return -1L
        for (i in pos until list.size) {
            val item = list[i]
            if (item is PhotoModel.PhotoItem) return item.photo.id
        }
        return -1L
    }

    private fun scrollToPhotoById(photoId: Long) {
        if (photoId < 0) return
        val list = photoAdapter?.currentList ?: return
        for (i in list.indices) {
            val item = list[i]
            if (item is PhotoModel.PhotoItem && item.photo.id == photoId) {
                gridLayoutManager?.scrollToPositionWithOffset(i, 0)
                return
            }
        }
    }

    private fun observeSelectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionManager.isSelectionMode.collect { isSelectionMode ->
                    backPressedCallback.isEnabled = isSelectionMode
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

    private fun checkPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
            }
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionLauncher.launch(permissions)
        }
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photos, popupMenu.menu)

        popupMenu.menu.findItem(R.id.action_select)?.title =
            if (selectionManager.isSelectionMode.value) getString(R.string.cancel_select)
            else getString(R.string.select)

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_select -> { selectionManager.toggleSelectionMode(); true }
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_group -> { showGroupDialog(); true }
                R.id.action_columns -> { showColumnsDialog(); true }
                R.id.action_theme -> { showThemeDialog(); true }
                R.id.action_trash -> { navigateToTrash(); true }
                R.id.action_hidden -> { navigateToHidden(); true }
                R.id.action_about -> { navigateToAbout(); true }
                R.id.action_settings -> { navigateToSettings(); true }
                else -> false
            }
        }
        popupMenu.show()
    }

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
                val newSortType = if (which == 0) MediaRepository.SortType.DATE_TAKEN else MediaRepository.SortType.DATE_ADDED
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
        // 用户主动改列数：写入偏好
        applySpanCount(newSpanCount, persist = true)
    }

    /**
     * 应用新的列数。
     * [persist] = true 时按当前方向写入偏好（仅在用户主动改列数时使用）。
     * [persist] = false 时不写（旋转时按 resolver 解析后应用，不算"用户设置"）。
     */
    private fun applySpanCount(newSpanCount: Int, persist: Boolean) {
        currentSpanCount = newSpanCount
        if (persist) {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            GridSpanPreferences.save(
                prefs = sharedPreferences,
                prefix = SPAN_COUNT_PREFIX,
                isLandscape = isLandscape,
                value = newSpanCount
            )
        }

        calculateItemSize()
        gridLayoutManager?.apply {
            spanCount = newSpanCount
            initialPrefetchItemCount = newSpanCount * 4
        }
        photoAdapter?.updateItemSize(itemSize)

        gridSpacingItemDecoration?.let { binding.rvPhotos.removeItemDecoration(it) }
        val spacing = GridSpanCalculator.dpToPx(2, resources.displayMetrics)
        val decoration = GridSpacingItemDecoration(newSpanCount, spacing, true)
        gridSpacingItemDecoration = decoration
        binding.rvPhotos.addItemDecoration(decoration)

        setupGlidePreloader()
    }

    private fun showThemeDialog() {
        val currentMode = sharedPreferences.getInt(
            StarGalleryApp.KEY_THEME_MODE,
            StarGalleryApp.DEFAULT_THEME_MODE
        )
        val checkedIndex = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> 0
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }

        val items = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme)
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val mode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                sharedPreferences.edit()
                    .putInt(StarGalleryApp.KEY_THEME_MODE, mode)
                    .apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                dialog.dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 刷新数据
     * 触发增量扫描将 MediaStore 变化同步到 Room，Room Flow 自动推送更新
     */
    private fun refreshData() {
        lastExplicitRefreshTime = System.currentTimeMillis()
        viewModel.requestIncrementalScan()
    }

    private fun smoothRefreshItems(photoIds: Set<Long>) {
        val adapter = photoAdapter ?: return
        val firstVisible = gridLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val lastVisible = gridLayoutManager?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION

        if (firstVisible == RecyclerView.NO_POSITION) return

        val positions = mutableListOf<Int>()
        val searchEnd = minOf(lastVisible + 10, adapter.itemCount - 1)
        val snapshot = adapter.currentList

        for (i in maxOf(0, firstVisible - 10)..searchEnd) {
            val item = snapshot.getOrNull(i)
            if (item is PhotoModel.PhotoItem && item.photo.id in photoIds) {
                positions.add(i)
            }
        }

        if (positions.isNotEmpty()) {
            positions.forEach { position ->
                val holder = binding.rvPhotos.findViewHolderForAdapterPosition(position)
                holder?.itemView?.animate()
                    ?.alpha(0.7f)
                    ?.setDuration(150)
                    ?.withEndAction {
                        adapter.notifyItemChanged(position)
                        holder.itemView.animate()
                            ?.alpha(1f)
                            ?.setDuration(150)
                            ?.start()
                    }
                    ?.start()
            }
        }
    }

    private fun refreshVisibleItems() {
        val first = gridLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val last = gridLayoutManager?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            photoAdapter?.notifyItemRangeChanged(first, last - first + 1, PhotoListAdapter.PAYLOAD_SELECTION_CHANGED)
        }
    }

    private fun updateSubtitle() {
        val count = viewModel.getCurrentPhotoCount()
        val showFavoritesOnly = viewModel.showFavoritesOnly.value
        val searchQuery = viewModel.searchQuery.value
        val isSearching = !searchQuery.isNullOrBlank()

        // 普通/收藏副标题
        binding.tvSubtitle.text = if (showFavoritesOnly) {
            getString(R.string.favorite_count, count)
        } else {
            getString(R.string.photo_count, count)
        }

        // 搜索副标题
        if (isSearching) {
            binding.tvSearchSubtitle.text = getString(R.string.search_result, searchQuery, count)
        } else {
            binding.tvSearchSubtitle.text = ""
        }
    }

    private fun navigateToDetail(photo: Photo) {
        saveScrollPosition()

        // 把当前可见列表写入缓存，让详情页初始化时直接复用，避免重新查询/排序
        val currentPhotos = viewModel.photoListFlow.value
            .filterIsInstance<PhotoModel.PhotoItem>()
            .map { it.photo }
        photoDetailListCache.put(currentPhotos)

        val sortTypeValue = if (viewModel.currentSortType.value == MediaRepository.SortType.DATE_TAKEN) 0 else 1
        val action = PhotosFragmentDirections.actionPhotosFragmentToPhotoDetailFragment(
            initialPhoto = photo,
            photoId = photo.id,
            sortType = sortTypeValue,
            favoritesOnly = viewModel.showFavoritesOnly.value,
            filterCameraMake = viewModel.filterCameraMake.value.joinToString("\n").takeIf { it.isNotEmpty() },
            filterCameraModel = viewModel.filterCameraModel.value.joinToString("\n").takeIf { it.isNotEmpty() },
            filterLensModel = viewModel.filterLensModel.value.joinToString("\n").takeIf { it.isNotEmpty() }
        )
        findNavController().navigate(action)
    }

    /**
     * 保存当前滚动位置
     */
    private fun saveScrollPosition(): Int {
        val position = gridLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (position != RecyclerView.NO_POSITION) {
            val firstVisibleView = gridLayoutManager?.findViewByPosition(position)
            savedScrollOffset = firstVisibleView?.top ?: 0
        }
        savedScrollPosition = position
        return position
    }

    /**
     * 恢复滚动位置
     */
    private fun restoreScrollPosition() {
        val position = savedScrollPosition
        val offset = savedScrollOffset
        savedScrollPosition = -1
        if (position >= 0) {
            binding.rvPhotos.post {
                try {
                    gridLayoutManager?.scrollToPositionWithOffset(position, offset)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }

    private fun navigateToTrash() {
        saveScrollPosition()
        val action = PhotosFragmentDirections.actionPhotosFragmentToTrashFragment()
        findNavController().navigate(action)
    }

    private fun navigateToHidden() {
        saveScrollPosition()
        val action = PhotosFragmentDirections.actionPhotosFragmentToHiddenFragment()
        findNavController().navigate(action)
    }

    private fun showFilterSheet() {
        FilterBottomSheet().show(childFragmentManager, FilterBottomSheet.TAG)
    }

    private fun navigateToAbout() {
        saveScrollPosition()
        val action = PhotosFragmentDirections.actionPhotosFragmentToAboutFragment()
        findNavController().navigate(action)
    }

    private fun navigateToSettings() {
        saveScrollPosition()
        val action = PhotosFragmentDirections.actionPhotosFragmentToSettingsFragment()
        findNavController().navigate(action)
    }

    override fun onResume() {
        super.onResume()
        if (savedScrollPosition >= 0) {
            restoreScrollPosition()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // 重新按"用户在该方向设置优先"解析列数；
        // 解析出的值与当前不同才更新，避免重复 layout。
        // 使用 newConfig.orientation 而不是 resources.configuration.orientation，
        // 保证拿到旋转后的最新方向（resources 在某些场景下可能未及时更新）。
        // 旋转时不写 SharedPreferences（不属于用户设置），仅在内存中应用。
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val resolvedSpanCount = resolveSpanCountForOrientation(isLandscape)
        if (currentSpanCount != resolvedSpanCount) {
            applySpanCount(resolvedSpanCount, persist = false)
        } else {
            calculateItemSize()
            photoAdapter?.updateItemSize(itemSize)
        }
    }

    private fun calculateItemSize() {
        val spacingPx = GridSpanCalculator.dpToPx(2, resources.displayMetrics)
        itemSize = GridSpanCalculator.calculateItemSize(
            resources.displayMetrics.widthPixels,
            currentSpanCount,
            spacingPx
        )
    }

    override fun onDestroyView() {
        mediaChangeDetector.destroy()
        selectionManager.clear()
        gridLayoutManager?.spanSizeLookup = null
        gridLayoutManager = null
        binding.rvPhotos.itemAnimator = null
        binding.rvPhotos.layoutManager = null
        binding.rvPhotos.adapter = null
        photoItemAnimator = null
        gridSpacingItemDecoration = null
        fastScrollerReady = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ITEM_VIEW_CACHE_SIZE = 24

        private const val SPAN_COUNT_PREFIX = "span_count"
        private const val KEY_SORT_TYPE = "sort_type"
        private const val KEY_GROUP_TYPE = "group_type"

        const val REQUEST_KEY_PHOTO_DELETED = "photo_deleted"
    }
}
