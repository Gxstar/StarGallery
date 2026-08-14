package com.gxstar.stargallery.ui.albums

import android.content.res.Configuration
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
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
import com.gxstar.stargallery.ui.common.DeleteOptionsBottomSheet
import com.gxstar.stargallery.ui.common.GridSpanCalculator
import com.gxstar.stargallery.ui.common.GridSpanPreferences
import com.gxstar.stargallery.ui.photos.GridSpacingItemDecoration
import com.gxstar.stargallery.ui.photos.GroupType
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.photos.PhotoPreloadModelProvider
import com.gxstar.stargallery.ui.detail.PhotoDetailListCache
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlbumDetailFragment : Fragment() {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val args: AlbumDetailFragmentArgs by navArgs()
    private val viewModel: AlbumDetailViewModel by viewModels()

    private var photoAdapter: AlbumDetailAdapter? = null
    private var gridLayoutManager: GridLayoutManager? = null
    private var gridSpacingItemDecoration: GridSpacingItemDecoration? = null
    private lateinit var selectionManager: AlbumSelectionManager

    private var pagingDataJob: Job? = null

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var photoDetailListCache: PhotoDetailListCache

    private var currentSpanCount = GridSpanCalculator.MIN_SPAN_COUNT
    private var itemSize = 0

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectionManager.exitSelectionMode()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.setAlbumId(args.bucketId)
            observePhotoList()
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            refreshData()
        }
        exitSelectionMode()
    }

    private val trashRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
            refreshData()
        }
        exitSelectionMode()
    }

    private var pendingFavoriteAction = 0
    private val favoriteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (pendingFavoriteAction) {
                1 -> Toast.makeText(requireContext(), R.string.added_to_favorite, Toast.LENGTH_SHORT).show()
                2 -> Toast.makeText(requireContext(), R.string.removed_from_favorite, Toast.LENGTH_SHORT).show()
                3 -> Toast.makeText(requireContext(), R.string.favorite_toggled, Toast.LENGTH_SHORT).show()
            }
            refreshData()
        }
        pendingFavoriteAction = 0
        exitSelectionMode()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        loadSpanCount()
        calculateItemSize()
        loadSortType()
        loadGroupType()

        binding.tvTitle.text = args.albumName
        setupClickListeners()
        setupRecyclerView()
        observeData()
        checkPermissions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val resolvedSpanCount = resolveSpanCountForCurrentOrientation()
        if (currentSpanCount != resolvedSpanCount) {
            applySpanCount(resolvedSpanCount, persist = false)
        } else {
            calculateItemSize()
            photoAdapter?.updateItemSize(itemSize)
        }
    }

    private fun isCurrentLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 按"用户在该方向设置优先"解析列数，与首页 PhotosFragment 共用同一套规则。
     * 1) 当前方向存了用户值 → 用
     * 2) 否则另一方向存了值 → 用另一方向
     * 3) 否则根据当前屏宽自动计算
     */
    private fun resolveSpanCountForCurrentOrientation(): Int {
        return GridSpanPreferences.resolveForOrientation(
            prefs = sharedPreferences,
            prefix = SPAN_COUNT_PREFIX,
            isLandscape = isCurrentLandscape(),
            fallback = GridSpanCalculator.calculateOptimalSpanCount(resources.displayMetrics)
        )
    }

    private fun loadSpanCount() {
        currentSpanCount = resolveSpanCountForCurrentOrientation()
    }

    private fun calculateItemSize() {
        val spacingPx = GridSpanCalculator.dpToPx(2, resources.displayMetrics)
        itemSize = GridSpanCalculator.calculateItemSize(
            resources.displayMetrics.widthPixels,
            currentSpanCount,
            spacingPx
        )
    }

    private fun loadSortType() {
        val savedSortType = when (sharedPreferences.getInt(KEY_SORT_TYPE_ALBUM, 0)) {
            0 -> MediaRepository.SortType.DATE_TAKEN
            else -> MediaRepository.SortType.DATE_ADDED
        }
        viewModel.setSortType(savedSortType)
    }

    private fun saveSortType(sortType: MediaRepository.SortType) {
        sharedPreferences.edit().putInt(KEY_SORT_TYPE_ALBUM, if (sortType == MediaRepository.SortType.DATE_TAKEN) 0 else 1).apply()
    }

    private fun loadGroupType() {
        val savedGroupType = when (sharedPreferences.getInt(KEY_GROUP_TYPE_ALBUM, 0)) {
            0 -> GroupType.DAY
            1 -> GroupType.MONTH
            2 -> GroupType.YEAR
            else -> GroupType.DAY
        }
        viewModel.setGroupType(savedGroupType)
    }

    private fun saveGroupType(groupType: GroupType) {
        val value = when (groupType) {
            GroupType.DAY -> 0
            GroupType.MONTH -> 1
            GroupType.YEAR -> 2
        }
        sharedPreferences.edit().putInt(KEY_GROUP_TYPE_ALBUM, value).apply()
    }

    private fun checkPermissions() {
        // minSdk 35：仅需 READ_MEDIA_*（Android 13+ 模型，不请求 VISUAL_USER_SELECTED，
        // 避免"仅部分照片"授权降级，详见 PhotosFragment.checkPermissions 注释）
        val permissions = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        val allGranted = permissions.all {
            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), it)
        }
        if (allGranted) {
            viewModel.setAlbumId(args.bucketId)
            observePhotoList()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun setupClickListeners() {
        binding.btnMore.setOnClickListener { view -> showPopupMenu(view) }
        binding.btnBack.setOnClickListener { exitSelectionMode() }
        binding.btnShare.setOnClickListener { shareSelectedPhotos() }
        binding.btnFavorite.setOnClickListener { favoriteSelectedPhotos() }
        binding.btnDelete.setOnClickListener { deleteSelectedPhotos() }
        binding.btnFilter.visibility = View.GONE
        binding.btnFilterExif.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE
    }

    private fun shareSelectedPhotos() {
        val photos = selectionManager.getSelectedPhotos()
        if (photos.isEmpty()) return

        val uris = ArrayList<Uri>()
        photos.forEach { photo -> uris.add(photo.uri) }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, getString(R.string.send)))
    }

    private fun favoriteSelectedPhotos() {
        val photos = selectionManager.getSelectedPhotos()
        if (photos.isEmpty()) return

        val photosToFavorite = photos.filter { !it.isFavorite }
        val photosToUnfavorite = photos.filter { it.isFavorite }

        val hasFavorite = photosToFavorite.isNotEmpty()
        val hasUnfavorite = photosToUnfavorite.isNotEmpty()

        if (!hasFavorite && !hasUnfavorite) {
            exitSelectionMode()
            return
        }

        // 混合方向时仅发起一次 IntentSender（取数量较多的方向），避免多次 launch 互相覆盖
        val (targetPhotos, targetState) = when {
            hasFavorite && hasUnfavorite -> {
                if (photosToFavorite.size >= photosToUnfavorite.size) photosToFavorite to true
                else photosToUnfavorite to false
            }
            hasFavorite -> photosToFavorite to true
            else -> photosToUnfavorite to false
        }

        pendingFavoriteAction = when {
            hasFavorite && hasUnfavorite -> 3
            hasFavorite -> 1
            else -> 2
        }

        try {
            mediaRepository.setFavorite(targetPhotos, targetState)?.let { intentSender ->
                favoriteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } ?: run {
                pendingFavoriteAction = 0
                Toast.makeText(requireContext(), R.string.add_to_favorite_failed, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            pendingFavoriteAction = 0
            Toast.makeText(requireContext(), R.string.add_to_favorite_failed, Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }

    private fun deleteSelectedPhotos() {
        val photos = selectionManager.getSelectedPhotos()
        if (photos.isEmpty()) return

        DeleteOptionsBottomSheet.newInstance(
            onMoveToTrash = { moveToTrash(photos) },
            onDeletePermanently = { deletePermanently(photos) }
        ).show(childFragmentManager, DeleteOptionsBottomSheet.TAG)
    }

    private fun moveToTrash(photos: List<Photo>) {
        mediaRepository.trashPhotos(photos)?.let { intentSender ->
            try {
                trashRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.move_to_trash_failed, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        } ?: run {
            Toast.makeText(requireContext(), R.string.move_to_trash_failed, Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }

    private fun deletePermanently(photos: List<Photo>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_permanently_confirm_title)
            .setMessage(getString(R.string.delete_permanently_confirm_message, photos.size))
            .setPositiveButton(R.string.delete_permanently) { _, _ ->
                mediaRepository.deletePhotos(photos)?.let { intentSender ->
                    try {
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        exitSelectionMode()
                    }
                } ?: run {
                    Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exitSelectionMode() {
        selectionManager.exitSelectionMode()
        backPressedCallback.isEnabled = false
        binding.normalToolbar.visibility = View.VISIBLE
        binding.selectionToolbar.visibility = View.GONE
        refreshVisibleItems()
    }

    private fun refreshVisibleItems() {
        val first = gridLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val last = gridLayoutManager?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            photoAdapter?.notifyItemRangeChanged(first, last - first + 1, AlbumDetailAdapter.PAYLOAD_SELECTION_CHANGED)
        }
    }

    private fun togglePhotoSelection(position: Int) {
        selectionManager.toggleSelection(position)
        binding.tvSelectionCount.text = getString(R.string.selected, selectionManager.selectedCount.value)
        if (selectionManager.selectedCount.value == 0) {
            exitSelectionMode()
        }
    }

    private fun setupRecyclerView() {
        photoAdapter = AlbumDetailAdapter(
            itemSize = itemSize,
            onPhotoClick = { photo ->
                val position = photoAdapter?.getPhotoPosition(photo.id) ?: RecyclerView.NO_POSITION
                if (position != RecyclerView.NO_POSITION && selectionManager.isInSelectionMode()) {
                    togglePhotoSelection(position)
                } else {
                    navigateToDetail(photo)
                }
            },
            onPhotoLongClick = { position -> selectionManager.startDragSelection(position) },
            isSelectionModeProvider = { selectionManager.isInSelectionMode() },
            isSelectedProvider = { position -> selectionManager.isSelectedPosition(position) }
        )

        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (photoAdapter?.getItemViewType(position) == 0) currentSpanCount else 1
                }
            }.apply {
                isSpanIndexCacheEnabled = true
            }
        }

        binding.rvPhotos.apply {
            layoutManager = gridLayoutManager
            adapter = photoAdapter
            val spacing = GridSpanCalculator.dpToPx(2, resources.displayMetrics)
            val decoration = GridSpacingItemDecoration(currentSpanCount, spacing, true)
            gridSpacingItemDecoration = decoration
            addItemDecoration(decoration)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            isNestedScrollingEnabled = false
        }

        selectionManager = AlbumSelectionManager(binding.rvPhotos, photoAdapter)
        selectionManager.init()

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
                    backPressedCallback.isEnabled = isSelectionMode
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
            20
        )
        binding.rvPhotos.addOnScrollListener(preloader)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoCount.collect { count ->
                    binding.tvSubtitle.text = getString(R.string.photo_count, count)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    // 可以添加加载指示器
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
    }

    private fun observePhotoList() {
        pagingDataJob?.cancel()
        pagingDataJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoListFlow.collectLatest { list ->
                    photoAdapter?.submitList(list)
                }
            }
        }
    }

    private fun refreshData() {
        viewModel.refresh()
    }

    private fun navigateToDetail(photo: Photo) {
        // 把当前可见列表写入缓存，让详情页初始化时直接复用
        val currentPhotos = viewModel.photoListFlow.value
            .filterIsInstance<PhotoModel.PhotoItem>()
            .map { it.photo }
        photoDetailListCache.put(currentPhotos)

        val sortTypeValue = if (viewModel.currentSortType.value == MediaRepository.SortType.DATE_TAKEN) 0 else 1
        val action = AlbumDetailFragmentDirections.actionAlbumDetailFragmentToPhotoDetailFragment(
            initialPhoto = photo,
            photoId = photo.id,
            sortType = sortTypeValue,
            bucketId = args.bucketId,
            favoritesOnly = false,
            filterCameraMake = null,
            filterCameraModel = null,
            filterLensModel = null
        )
        findNavController().navigate(action)
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photos, popupMenu.menu)

        popupMenu.menu.findItem(R.id.action_select)?.isVisible = false
        popupMenu.menu.findItem(R.id.action_trash)?.isVisible = false
        popupMenu.menu.findItem(R.id.action_about)?.isVisible = false

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_group -> { showGroupDialog(); true }
                R.id.action_columns -> { showColumnsDialog(); true }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun showSortDialog() {
        val currentSortType = viewModel.currentSortType.value
        val options = arrayOf(getString(R.string.sort_by_date_taken), getString(R.string.sort_by_date_added))
        val checkedItem = when (currentSortType) {
            MediaRepository.SortType.DATE_TAKEN -> 0
            MediaRepository.SortType.DATE_ADDED -> 1
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSortType = if (which == 0) MediaRepository.SortType.DATE_TAKEN else MediaRepository.SortType.DATE_ADDED
                if (newSortType != currentSortType) {
                    viewModel.setSortType(newSortType)
                    saveSortType(newSortType)
                    refreshData()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGroupDialog() {
        val currentGroupType = viewModel.currentGroupType.value
        val options = arrayOf(getString(R.string.group_by_day), getString(R.string.group_by_month), getString(R.string.group_by_year))
        val checkedItem = when (currentGroupType) {
            GroupType.DAY -> 0
            GroupType.MONTH -> 1
            GroupType.YEAR -> 2
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.group_by)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newGroupType = when (which) {
                    0 -> GroupType.DAY
                    1 -> GroupType.MONTH
                    2 -> GroupType.YEAR
                    else -> GroupType.DAY
                }
                if (newGroupType != currentGroupType) {
                    viewModel.setGroupType(newGroupType)
                    saveGroupType(newGroupType)
                    refreshData()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showColumnsDialog() {
        val maxSpan = GridSpanCalculator.MAX_SPAN_COUNT
        val minSpan = GridSpanCalculator.MIN_SPAN_COUNT
        val options = (minSpan..maxSpan).map { it.toString() }.toTypedArray()
        val checkedItem = (currentSpanCount - minSpan).coerceIn(0, maxSpan - minSpan)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_columns)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSpan = which + minSpan
                if (newSpan != currentSpanCount) {
                    updateSpanCount(newSpan)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSpanCount(newSpanCount: Int) {
        applySpanCount(newSpanCount, persist = true)
    }

    /**
     * 应用新的列数：
     *   - [persist] = true 时按当前方向写入偏好（用户主动改列数）
     *   - [persist] = false 时不写（旋转时按 resolver 计算出来，不算"用户设置"）
     */
    private fun applySpanCount(newSpanCount: Int, persist: Boolean) {
        currentSpanCount = newSpanCount
        if (persist) {
            GridSpanPreferences.save(
                prefs = sharedPreferences,
                prefix = SPAN_COUNT_PREFIX,
                isLandscape = isCurrentLandscape(),
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
    }

    override fun onDestroyView() {
        selectionManager.clear()
        gridLayoutManager?.spanSizeLookup = null
        gridLayoutManager = null
        gridSpacingItemDecoration = null
        binding.rvPhotos.layoutManager = null
        binding.rvPhotos.adapter = null
        photoAdapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val SPAN_COUNT_PREFIX = "album_span_count"
        private const val KEY_SORT_TYPE_ALBUM = "album_sort_type"
        private const val KEY_GROUP_TYPE_ALBUM = "album_group_type"
    }
}
