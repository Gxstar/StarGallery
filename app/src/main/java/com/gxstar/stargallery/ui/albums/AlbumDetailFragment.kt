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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import androidx.recyclerview.selection.SelectionTracker
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
import com.gxstar.stargallery.ui.photos.GridSpacingItemDecoration
import com.gxstar.stargallery.ui.photos.GroupType
import com.gxstar.stargallery.ui.photos.PhotoModel
import com.gxstar.stargallery.ui.photos.PhotoPagingAdapter
import com.gxstar.stargallery.ui.photos.PhotoPreloadModelProvider
import com.gxstar.stargallery.ui.photos.selection.PhotoItemKeyProvider
import com.gxstar.stargallery.ui.photos.selection.PhotoItemDetailsLookup
import com.permissionx.guolindev.PermissionX
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import javax.inject.Inject

@AndroidEntryPoint
class AlbumDetailFragment : Fragment() {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val args: AlbumDetailFragmentArgs by navArgs()
    private val viewModel: AlbumDetailViewModel by viewModels()

    private lateinit var photoAdapter: PhotoPagingAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var selectionTracker: SelectionTracker<Long>? = null

    private var pagingDataJob: Job? = null

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    private var currentSpanCount = 4
    private var itemSize = 0

    private var isSelectionMode = false

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
        calculateItemSize()
        photoAdapter.updateConfig(itemSize, currentSpanCount)
    }

    private fun loadSpanCount() {
        currentSpanCount = sharedPreferences.getInt(KEY_SPAN_COUNT, 4)
    }

    private fun calculateItemSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemSpacing = dpToPx(2) * (currentSpanCount + 1)
        itemSize = (screenWidth - itemSpacing) / currentSpanCount
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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        PermissionX.init(this)
            .permissions(*permissions)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    viewModel.setAlbumId(args.bucketId)
                    observePagingData()
                }
            }
    }

    private fun setupClickListeners() {
        binding.btnMore.setOnClickListener { view -> showPopupMenu(view) }
        binding.btnBack.setOnClickListener { exitSelectionMode() }
        binding.btnShare.setOnClickListener { shareSelectedPhotos() }
        binding.btnFavorite.setOnClickListener { favoriteSelectedPhotos() }
        binding.btnDelete.setOnClickListener { deleteSelectedPhotos() }
        binding.btnFilter.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE
    }

    private fun shareSelectedPhotos() {
        val selectedIds = selectionTracker?.selection?.toSet() ?: emptySet()
        if (selectedIds.isEmpty()) return

        val uris = ArrayList<Uri>()
        selectedIds.forEach { id ->
            findPhotoById(id)?.uri?.let { uris.add(it) }
        }

        if (uris.isEmpty()) return

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, getString(R.string.send)))
    }

    private fun favoriteSelectedPhotos() {
        val selectedIds = selectionTracker?.selection?.toSet() ?: emptySet()
        if (selectedIds.isEmpty()) return

        val photosToFavorite = mutableListOf<Photo>()
        val photosToUnfavorite = mutableListOf<Photo>()

        selectedIds.forEach { id ->
            findPhotoById(id)?.let { photo ->
                if (photo.isFavorite) photosToUnfavorite.add(photo)
                else photosToFavorite.add(photo)
            }
        }

        val hasFavorite = photosToFavorite.isNotEmpty()
        val hasUnfavorite = photosToUnfavorite.isNotEmpty()
        pendingFavoriteAction = when {
            hasFavorite && hasUnfavorite -> 3
            hasFavorite -> 1
            hasUnfavorite -> 2
            else -> 0
        }

        if (pendingFavoriteAction == 0) {
            exitSelectionMode()
            return
        }

        try {
            var hasRequest = false
            if (hasFavorite) {
                mediaRepository.setFavorite(photosToFavorite, true)?.let {
                    favoriteRequestLauncher.launch(IntentSenderRequest.Builder(it).build())
                    hasRequest = true
                }
            }
            if (hasUnfavorite) {
                mediaRepository.setFavorite(photosToUnfavorite, false)?.let {
                    favoriteRequestLauncher.launch(IntentSenderRequest.Builder(it).build())
                    hasRequest = true
                }
            }
            if (!hasRequest) {
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
        val selectedIds = selectionTracker?.selection?.toSet() ?: emptySet()
        if (selectedIds.isEmpty()) return

        val photos = mutableListOf<Photo>()
        selectedIds.forEach { id ->
            findPhotoById(id)?.let { photos.add(it) }
        }

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

    fun onBackPressed(): Boolean {
        return if (isSelectionMode) {
            exitSelectionMode()
            true
        } else {
            false
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        binding.normalToolbar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
        binding.tvSelectionCount.text = getString(R.string.selected, 0)
        // 使用 post 避免在 SelectionTracker 回调中直接刷新导致递归
        binding.rvPhotos.post { refreshVisibleItems() }
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectionTracker?.clearSelection()
        binding.normalToolbar.visibility = View.VISIBLE
        binding.selectionToolbar.visibility = View.GONE
        // 使用 post 避免在 SelectionTracker 回调中直接刷新导致递归
        binding.rvPhotos.post { refreshVisibleItems() }
    }

    private fun refreshVisibleItems() {
        val first = gridLayoutManager.findFirstVisibleItemPosition()
        val last = gridLayoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            photoAdapter.notifyItemRangeChanged(first, last - first + 1)
        }
    }

    private fun togglePhotoSelection(photoId: Long) {
        selectionTracker?.let {
            if (it.isSelected(photoId)) {
                it.deselect(photoId)
            } else {
                it.select(photoId)
            }
        }
    }

    private fun startDragSelection(position: Int) {
        if (!isSelectionMode) enterSelectionMode()

        val key = photoAdapter.getPhotoKey(position)
        if (key != RecyclerView.NO_ID && selectionTracker?.isSelected(key) == false) {
            selectionTracker?.select(key)
        }
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

    private fun setupRecyclerView() {
        photoAdapter = PhotoPagingAdapter(
            itemSize = itemSize,
            spanCount = currentSpanCount,
            onPhotoClick = { photo ->
                if (isSelectionMode) togglePhotoSelection(photo.id)
                else navigateToDetail(photo)
            },
            onPhotoLongClick = { photo ->
                val position = findPhotoPosition(photo.id)
                if (position != RecyclerView.NO_POSITION) {
                    startDragSelection(position)
                    true
                } else false
            },
            isSelectionModeProvider = { isSelectionMode },
            isSelectedProvider = { id -> selectionTracker?.isSelected(id) ?: false }
        )

        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (photoAdapter.getItemViewType(position) == 0) currentSpanCount else 1
            }
        }.apply {
            isSpanIndexCacheEnabled = true
        }

        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = photoAdapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.setHasFixedSize(true)
        binding.rvPhotos.setItemViewCacheSize(24)
        binding.rvPhotos.isNestedScrollingEnabled = false

        // 初始化 SelectionTracker
        selectionTracker = SelectionTracker.Builder(
            "album-detail-selection",
            binding.rvPhotos,
            PhotoItemKeyProvider(photoAdapter),
            PhotoItemDetailsLookup(binding.rvPhotos),
            androidx.recyclerview.selection.StorageStrategy.createLongStorage()
        ).withSelectionPredicate(
            object : SelectionTracker.SelectionPredicate<Long>() {
                override fun canSetStateForKey(key: Long, nextState: Boolean): Boolean = true
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = true
                override fun canSelectMultiple(): Boolean = true
            }
        ).build().apply {
            addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                override fun onSelectionChanged() {
                    val count = selection?.size() ?: 0
                    if (count == 0 && isSelectionMode) {
                        exitSelectionMode()
                    } else if (count > 0 && !isSelectionMode) {
                        enterSelectionMode()
                    }
                    if (isSelectionMode) {
                        binding.tvSelectionCount.text = getString(R.string.selected, count)
                    }
                    // 不在这里调用 refreshVisibleItems()，避免无限递归
                }
            })
        }

        val glideRequest = Glide.with(this)
        val preloadSizeProvider = ViewPreloadSizeProvider<Uri>()
        val preloader = RecyclerViewPreloader(
            glideRequest,
            PhotoPreloadModelProvider(glideRequest, photoAdapter, itemSize),
            preloadSizeProvider,
            20
        )
        binding.rvPhotos.addOnScrollListener(preloader)

        photoAdapter.addOnPagesUpdatedListener {
            // Paging 数据更新时不需要额外操作
        }

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

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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

    private fun observePagingData() {
        pagingDataJob?.cancel()
        pagingDataJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoPagingFlow.collectLatest { pagingData ->
                    photoAdapter.submitData(pagingData)
                }
            }
        }
    }

    private fun refreshData() {
        viewModel.refresh()
        photoAdapter.refresh()
    }

    private fun navigateToDetail(photo: Photo) {
        val sortTypeValue = if (viewModel.currentSortType.value == MediaRepository.SortType.DATE_TAKEN) 0 else 1
        val action = AlbumDetailFragmentDirections.actionAlbumDetailFragmentToPhotoDetailFragment(photo, photo.id, sortTypeValue, args.bucketId)
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
                    else -> GroupType.YEAR
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
        val options = arrayOf("3", "4", "5")
        val checkedItem = when (currentSpanCount) {
            3 -> 0
            4 -> 1
            else -> 2
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_columns)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSpanCount = when (which) {
                    0 -> 3
                    1 -> 4
                    else -> 5
                }
                if (newSpanCount != currentSpanCount) {
                    currentSpanCount = newSpanCount
                    sharedPreferences.edit().putInt(KEY_SPAN_COUNT, newSpanCount).apply()

                    gridLayoutManager.spanCount = newSpanCount
                    calculateItemSize()
                    photoAdapter.updateConfig(itemSize, newSpanCount)
                }
                dialog?.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        pagingDataJob?.cancel()
        pagingDataJob = null
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        gridLayoutManager.spanSizeLookup = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_SPAN_COUNT = "span_count"
        private const val KEY_SORT_TYPE_ALBUM = "sort_type_album"
        private const val KEY_GROUP_TYPE_ALBUM = "group_type_album"
    }
}