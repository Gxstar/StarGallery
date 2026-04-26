package com.gxstar.stargallery.ui.photos

import android.Manifest
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.gxstar.stargallery.ui.photos.action.BatchActionHandler
import com.gxstar.stargallery.ui.photos.animation.PhotoItemAnimator
import com.gxstar.stargallery.ui.photos.launcher.IntentSenderManager
import com.gxstar.stargallery.ui.photos.refresh.MediaChangeDetector
import com.gxstar.stargallery.ui.photos.selection.PhotoSelectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
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

    // ContentObserver 用于监听 MediaStore 变化
    private val mediaContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            // MediaStore 发生变化，触发增量扫描
            viewModel.requestIncrementalScan()
        }
    }

    // UI 组件
    private lateinit var photoAdapter: PhotoPagingAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    // 状态
    private var currentSpanCount = MIN_SPAN_COUNT
    private var itemSize = 0
    private var savedScrollPosition = -1
    private var savedScrollOffset = 0

    // 收藏操作类型
    private var pendingFavoriteAction = BatchActionHandler.FAVORITE_ACTION_NONE

    // Adapter provider
    private var isSelectionModeProvider: () -> Boolean = { false }
    private var isSelectedProvider: (Long) -> Boolean = { false }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectionManager.exitSelectionMode()
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
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    private fun bindSelectionProviders() {
        isSelectionModeProvider = { selectionManager.isInSelectionMode() }
        isSelectedProvider = { id -> selectionManager.isSelected(id) }
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
                // MediaStore 是实时数据源，直接刷新 PagingSource 即可
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
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val spanCount = (screenWidthDp / MIN_CELL_WIDTH_DP).toInt()
        return spanCount.coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)
    }

    private fun setupRecyclerView() {
        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (position < 0 || position >= photoAdapter.itemCount) return 1
                    return if (photoAdapter.getItemViewType(position) == 0) currentSpanCount else 1
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
            adapter = photoAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE)
            itemAnimator = PhotoItemAnimator()
            addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
            // 注意：不需要手动添加 ItemTouchListener，SelectionTracker 会自动处理
        }

        // 初始化选择管理器（必须在设置好 adapter 之后）
        selectionManager = PhotoSelectionManager(binding.rvPhotos, photoAdapter)
        selectionManager.init()
        bindSelectionProviders()

        setupGlidePreloader()
        setupFastScroller()

        photoAdapter.addOnPagesUpdatedListener {
            photoAdapter.onPagesUpdated()
        }
    }

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

    private fun setupClickListeners() {
        binding.btnMore.setOnClickListener { showPopupMenu(it) }
        binding.btnFilter.setOnClickListener { viewModel.toggleFavoritesOnly() }
        binding.btnSearch.setOnClickListener { showSearchDialog() }
        binding.btnBack.setOnClickListener { selectionManager.exitSelectionMode() }
        binding.btnShare.setOnClickListener { handleShareAction() }
        binding.btnFavorite.setOnClickListener { handleFavoriteAction() }
        binding.btnDelete.setOnClickListener { handleDeleteAction() }
    }

    /**
     * 显示搜索对话框
     */
    private fun showSearchDialog() {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.search_hint)
            setText(viewModel.searchQuery.value ?: "")
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search)
            .setView(editText)
            .setPositiveButton(R.string.search) { _, _ ->
                val query = editText.text.toString().trim()
                viewModel.setSearchQuery(query)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // 取消搜索
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                // 清除搜索
                viewModel.setSearchQuery(null)
            }
            .show()
    }

    private fun handlePhotoClick(photo: Photo) {
        if (selectionManager.isSelectionMode.value) {
            selectionManager.toggleSelection(photo)
        } else {
            navigateToDetail(photo)
        }
    }

    private fun handlePhotoLongClick(photo: Photo): Boolean {
        // 找到照片在 adapter 中的位置
        val position = findPhotoPosition(photo.id)
        if (position != RecyclerView.NO_POSITION) {
            selectionManager.startDragSelection(position)
            return true
        }
        return false
    }

    /**
     * 根据照片 ID 找到其在 RecyclerView 中的位置
     */
    private fun findPhotoPosition(photoId: Long): Int {
        val snapshot = photoAdapter.snapshot()
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

    private fun handleFavoriteAction() {
        val photos = getSelectedPhotosOrShowToast() ?: return

        pendingFavoriteAction = calculateFavoriteAction(photos)
        val selectedIds = photos.map { it.id }.toSet()

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
                // 收藏操作成功后触发增量扫描同步数据库
                viewModel.requestIncrementalScan()
            }
            pendingFavoriteAction = BatchActionHandler.FAVORITE_ACTION_NONE
        }

        val hasRequest = batchActionHandler.favoritePhotos(
            photos,
            intentSenderManager.favoriteLauncher,
            pendingFavoriteAction
        )

        if (!hasRequest) {
            smoothRefreshItems(selectedIds)
            selectionManager.exitSelectionMode()
        }
    }

    private fun handleDeleteAction() {
        val photos = getSelectedPhotosOrShowToast() ?: return

        val selectedIds = photos.map { it.id }.toSet()

        intentSenderManager.setTrashCallback { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                selectionManager.exitSelectionMode()
                // 删除操作成功后触发增量扫描同步数据库
                viewModel.requestIncrementalScan()
            }
        }

        intentSenderManager.setDeleteCallback { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                selectionManager.exitSelectionMode()
                // 删除操作成功后触发增量扫描同步数据库
                viewModel.requestIncrementalScan()
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
        val snapshot = photoAdapter.snapshot()
        for (i in 0 until snapshot.size) {
            val item = snapshot[i]
            if (item is PhotoModel.PhotoItem && item.photo.id == id) {
                return item.photo
            }
        }
        return null
    }

    private fun setupFragmentResultListener() {
        setFragmentResultListener(REQUEST_KEY_PHOTO_DELETED) { _, _ ->
            refreshData()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoPagingFlow.collectLatest { pagingData ->
                    photoAdapter.submitData(pagingData)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                photoAdapter.loadStateFlow.collect { loadStates ->
                    val isInitialLoading = loadStates.refresh is LoadState.Loading
                    val isEmpty = loadStates.refresh is LoadState.NotLoading && photoAdapter.itemCount == 0
                    val hasError = loadStates.refresh is LoadState.Error

                    binding.progressBar.visibility = if (isInitialLoading) View.VISIBLE else View.GONE
                    binding.emptyStateView.visibility = if (isEmpty && !isInitialLoading) View.VISIBLE else View.GONE
                    binding.rvPhotos.visibility = if (isInitialLoading || hasError) View.GONE else View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isScanning.collect { isScanning ->
                    binding.scanningView.visibility = if (isScanning) View.VISIBLE else View.GONE
                    binding.emptyStateView.visibility = if (isScanning) View.GONE else binding.emptyStateView.visibility
                    binding.progressBar.visibility = if (isScanning) View.GONE else binding.progressBar.visibility
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoCount.collect { updateSubtitle() }
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

        com.permissionx.guolindev.PermissionX.init(this)
            .permissions(*permissions)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    // 权限获取后重新加载照片数量（解决初次显示为0的问题）
                    viewModel.loadCounts()
                    if (photoAdapter.itemCount == 0) {
                        refreshData()
                    }
                }
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
        gridLayoutManager.spanCount = newSpanCount
        photoAdapter.updateConfig(itemSize, newSpanCount)

        while (binding.rvPhotos.itemDecorationCount > 0) {
            binding.rvPhotos.removeItemDecorationAt(0)
        }
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(newSpanCount, dpToPx(2), true))
    }

    /**
     * 刷新数据
     * 直接让 PagingSource 重新加载，数据源是 MediaStore，实时反映媒体库变化
     */
    private fun refreshData() {
        photoAdapter.refresh()
    }

    private fun smoothRefreshItems(photoIds: Set<Long>) {
        val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = gridLayoutManager.findLastVisibleItemPosition()

        if (firstVisible == RecyclerView.NO_POSITION) return

        val positions = mutableListOf<Int>()
        val searchEnd = minOf(lastVisible + 10, photoAdapter.itemCount - 1)
        val snapshot = photoAdapter.snapshot()

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
                        photoAdapter.notifyItemChanged(position)
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
        val first = gridLayoutManager.findFirstVisibleItemPosition()
        val last = gridLayoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            photoAdapter.notifyItemRangeChanged(first, last - first + 1, PhotoPagingAdapter.PAYLOAD_SELECTION_CHANGED)
        }
    }

    private fun updateSubtitle() {
        val count = viewModel.getCurrentPhotoCount()
        val showFavoritesOnly = viewModel.showFavoritesOnly.value
        binding.tvSubtitle.text = if (showFavoritesOnly) {
            getString(R.string.favorite_count, count)
        } else {
            getString(R.string.photo_count, count)
        }
    }

    private fun navigateToDetail(photo: Photo) {
        // 保存当前位置，用于从详情页返回时恢复
        saveScrollPosition()

        val sortTypeValue = if (viewModel.currentSortType.value == MediaRepository.SortType.DATE_TAKEN) 0 else 1
        val action = PhotosFragmentDirections.actionPhotosFragmentToPhotoDetailFragment(photo, photo.id, sortTypeValue)
        findNavController().navigate(action)
    }

    /**
     * 保存当前滚动位置
     */
    private fun saveScrollPosition(): Int {
        val position = gridLayoutManager.findFirstVisibleItemPosition()
        if (position != RecyclerView.NO_POSITION) {
            val firstVisibleView = gridLayoutManager.findViewByPosition(position)
            savedScrollOffset = firstVisibleView?.top ?: 0
        }
        savedScrollPosition = position
        return position
    }

    /**
     * 恢复滚动位置
     */
    private fun restoreScrollPosition() {
        if (savedScrollPosition >= 0) {
            binding.rvPhotos.post {
                try {
                    gridLayoutManager.scrollToPositionWithOffset(savedScrollPosition, savedScrollOffset)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
        savedScrollPosition = -1
    }

    private fun navigateToTrash() {
        val action = PhotosFragmentDirections.actionPhotosFragmentToTrashFragment()
        findNavController().navigate(action)
    }

    private fun navigateToAbout() {
        val action = PhotosFragmentDirections.actionPhotosFragmentToAboutFragment()
        findNavController().navigate(action)
    }

    override fun onResume() {
        super.onResume()
        // 注册 ContentObserver 监听 MediaStore 变化
        requireContext().contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,  // notifyForDescendants
            mediaContentObserver
        )
        // 从详情页返回时恢复位置，不刷新数据
        // 从后台恢复时由 MediaChangeDetector 触发刷新
        if (savedScrollPosition >= 0) {
            restoreScrollPosition()
            savedScrollPosition = -1  // 重置，避免再次恢复
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val optimalSpanCount = calculateOptimalSpanCount()

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

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        // 注销 ContentObserver
        try {
            requireContext().contentResolver.unregisterContentObserver(mediaContentObserver)
        } catch (e: Exception) {
            // ignore if not registered
        }
    }

    override fun onDestroyView() {
        selectionManager.clear()
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        gridLayoutManager.spanSizeLookup = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val MIN_CELL_WIDTH_DP = 80
        private const val MIN_SPAN_COUNT = 3
        private const val MAX_SPAN_COUNT = 10

        private const val ITEM_VIEW_CACHE_SIZE = 24
        private const val PRELOAD_ITEM_COUNT = 6
        private const val PREFETCH_ITEM_COUNT = 12

        private const val KEY_SPAN_COUNT = "span_count"
        private const val KEY_SORT_TYPE = "sort_type"
        private const val KEY_GROUP_TYPE = "group_type"

        const val REQUEST_KEY_PHOTO_DELETED = "photo_deleted"

        // 增量扫描最小间隔（毫秒），避免频繁扫描
        private const val MIN_INCREMENTAL_SCAN_INTERVAL_MS = 60_000L // 1分钟
    }
}
