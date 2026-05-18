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
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.gxstar.stargallery.ui.photos.filter.FilterBottomSheet
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import com.gxstar.stargallery.ui.common.GridSpanCalculator
import com.gxstar.stargallery.ui.photos.action.BatchActionHandler
import com.gxstar.stargallery.ui.photos.animation.PhotoItemAnimator
import com.gxstar.stargallery.ui.photos.launcher.IntentSenderManager
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.photos.refresh.MediaChangeDetector
import com.gxstar.stargallery.ui.photos.selection.PhotoSelectionManager
import dagger.hilt.android.AndroidEntryPoint
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

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    // 状态
    private var currentSpanCount = GridSpanCalculator.MIN_SPAN_COUNT
    private var itemSize = 0
    private var savedScrollPosition = -1
    private var savedScrollOffset = 0

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
                refreshData()
            },
            shouldSkipRefresh = {
                System.currentTimeMillis() - lastExplicitRefreshTime < 1000
            }
        )
    }

    private var lastExplicitRefreshTime = 0L

    private fun setupSettings() {
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

    private fun calculateOptimalSpanCount(): Int {
        return GridSpanCalculator.calculateOptimalSpanCount(resources.displayMetrics)
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
            initialPrefetchItemCount = PREFETCH_ITEM_COUNT
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
            addItemDecoration(GridSpacingItemDecoration(currentSpanCount, GridSpanCalculator.dpToPx(2, resources.displayMetrics), true))
        }

        selectionManager = PhotoSelectionManager(binding.rvPhotos, photoListAdapter)
        selectionManager.init()
        bindSelectionProviders()

        setupGlidePreloader()

        setupFastScroller()
    }

    private fun setupFastScroller() {
        val context = requireContext()
        FastScrollerBuilder(binding.rvPhotos)
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
                popupView.setTextColor(0xFFFFFFFF.toInt())
                popupView.textSize = 12f
                popupView.includeFontPadding = false
                popupView.translationY = -(16 * context.resources.displayMetrics.density)
            }
            .setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.fastscroll_track)!!)
            .setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.fastscroll_thumb)!!)
            .build()
    }

    private fun setupGlidePreloader() {
        val adapter = photoAdapter ?: return
        val glideRequest = Glide.with(this)
        val preloadSizeProvider = ViewPreloadSizeProvider<Uri>()
        val preloader = RecyclerViewPreloader(
            glideRequest,
            PhotoPreloadModelProvider(
                glideRequest,
                { position -> photoAdapter?.currentList?.getOrNull(position) as? PhotoModel.PhotoItem },
                itemSize
            ),
            preloadSizeProvider,
            PRELOAD_ITEM_COUNT
        )
        binding.rvPhotos.addOnScrollListener(preloader)
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
        Toast.makeText(requireContext(), R.string.hidden_success, Toast.LENGTH_SHORT).show()
        viewModel.updateHidden(selectedIds, true)
        selectionManager.exitSelectionMode()
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

        intentSenderManager.setTrashCallback { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                selectionManager.exitSelectionMode()
                viewModel.deletePhotos(selectedIds)
                // 抑制 ContentObserver 延迟 500ms 后的重复刷新
                lastExplicitRefreshTime = System.currentTimeMillis()
            }
        }

        intentSenderManager.setDeleteCallback { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                selectionManager.exitSelectionMode()
                viewModel.deletePhotos(selectedIds)
                // 抑制 ContentObserver 延迟 500ms 后的重复刷新
                lastExplicitRefreshTime = System.currentTimeMillis()
            }
        }

        batchActionHandler.showDeleteOptions(
            photos,
            intentSenderManager.trashLauncher,
            intentSenderManager.deleteLauncher
        ) {
            selectionManager.exitSelectionMode()
        }
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
        // 合并扫描状态和照片数据，统一管理 UI 状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.isScanning,
                    viewModel.photoListFlow
                ) { isScanning, photoModels ->
                    Pair(isScanning, photoModels)
                }.collect { (isScanning, photoModels) ->
                    photoAdapter?.submitList(photoModels)

                    val isEmpty = photoModels.isEmpty()

                    binding.scanningView.visibility = if (isScanning && !isEmpty) View.VISIBLE else View.GONE
                    binding.progressBar.visibility = if (isScanning && isEmpty) View.VISIBLE else View.GONE
                    binding.emptyStateView.visibility = if (!isScanning && isEmpty) View.VISIBLE else View.GONE

                    // 扫描时禁用动画，避免数据快速刷新导致界面乱跳和残影
                    if (isScanning) {
                        if (binding.rvPhotos.itemAnimator != null) {
                            binding.rvPhotos.itemAnimator = null
                        }
                    } else {
                        if (binding.rvPhotos.itemAnimator == null && photoItemAnimator != null) {
                            binding.rvPhotos.itemAnimator = photoItemAnimator
                        }
                    }
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
                viewModel.isExtractingExif.collect { extracting ->
                    binding.exifProgressBar.visibility = if (extracting) View.VISIBLE else View.GONE
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
                R.id.action_trash -> { navigateToTrash(); true }
                R.id.action_hidden -> { navigateToHidden(); true }
                R.id.action_about -> { navigateToAbout(); true }
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
        currentSpanCount = newSpanCount
        sharedPreferences.edit().putInt(KEY_SPAN_COUNT, newSpanCount).apply()

        calculateItemSize()
        gridLayoutManager?.spanCount = newSpanCount
        photoAdapter?.updateItemSize(itemSize)

        while (binding.rvPhotos.itemDecorationCount > 0) {
            binding.rvPhotos.removeItemDecorationAt(0)
        }
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(newSpanCount, GridSpanCalculator.dpToPx(2, resources.displayMetrics), true))
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

    override fun onResume() {
        super.onResume()
        if (savedScrollPosition >= 0) {
            restoreScrollPosition()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val optimalSpanCount = calculateOptimalSpanCount()

        if (currentSpanCount != optimalSpanCount) {
            updateSpanCount(optimalSpanCount)
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
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ITEM_VIEW_CACHE_SIZE = 24
        private const val PRELOAD_ITEM_COUNT = 6
        private const val PREFETCH_ITEM_COUNT = 12

        private const val KEY_SPAN_COUNT = "span_count"
        private const val KEY_SORT_TYPE = "sort_type"
        private const val KEY_GROUP_TYPE = "group_type"

        const val REQUEST_KEY_PHOTO_DELETED = "photo_deleted"
    }
}
