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
import com.gxstar.stargallery.ui.common.PhotoSelectionTracker
import com.gxstar.stargallery.ui.photos.GridSpacingItemDecoration
import com.gxstar.stargallery.ui.photos.GroupType
import com.gxstar.stargallery.ui.photos.model.PhotoModel
import com.gxstar.stargallery.ui.photos.PhotoPreloadModelProvider
import com.permissionx.guolindev.PermissionX
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

    private lateinit var photoAdapter: AlbumDetailAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var selectionTracker: PhotoSelectionTracker

    private var pagingDataJob: Job? = null

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    private var currentSpanCount = 4
    private var itemSize = 0

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectionTracker.exitSelectionMode()
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
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
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
        photoAdapter.updateItemSize(itemSize)
    }

    private fun loadSpanCount() {
        currentSpanCount = sharedPreferences.getInt(KEY_SPAN_COUNT, 4)
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
                    observePhotoList()
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
        val selectedIds = selectionTracker.selectedIds
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
        val selectedIds = selectionTracker.selectedIds
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
        val selectedIds = selectionTracker.selectedIds
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

    private fun enterSelectionMode() {
        selectionTracker.enterSelectionMode()
        backPressedCallback.isEnabled = true
        binding.normalToolbar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
        binding.tvSelectionCount.text = getString(R.string.selected, 0)
        refreshVisibleItems()
    }

    private fun exitSelectionMode() {
        selectionTracker.exitSelectionMode()
        backPressedCallback.isEnabled = false
        binding.normalToolbar.visibility = View.VISIBLE
        binding.selectionToolbar.visibility = View.GONE
        refreshVisibleItems()
    }

    private fun refreshVisibleItems() {
        val first = gridLayoutManager.findFirstVisibleItemPosition()
        val last = gridLayoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            photoAdapter.notifyItemRangeChanged(first, last - first + 1)
        }
    }

    private fun togglePhotoSelection(photoId: Long) {
        selectionTracker.toggleSelection(photoId)
        binding.tvSelectionCount.text = getString(R.string.selected, selectionTracker.selectedCountFlow.value)
        if (selectionTracker.selectedCountFlow.value == 0) {
            exitSelectionMode()
        }
    }

    private fun findPhotoById(id: Long): Photo? {
        return photoAdapter.currentList.filterIsInstance<PhotoModel.PhotoItem>()
            .find { it.photo.id == id }?.photo
    }

    private fun setupRecyclerView() {
        photoAdapter = AlbumDetailAdapter(
            itemSize = itemSize,
            onPhotoClick = { photo ->
                if (selectionTracker.isSelectionMode.value) togglePhotoSelection(photo.id)
                else navigateToDetail(photo)
            },
            isSelectionModeProvider = { selectionTracker.isSelectionMode.value },
            isSelectedProvider = { id -> selectionTracker.isSelected(id) }
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
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, GridSpanCalculator.dpToPx(2, resources.displayMetrics), true))
        binding.rvPhotos.setHasFixedSize(true)
        binding.rvPhotos.setItemViewCacheSize(24)
        binding.rvPhotos.isNestedScrollingEnabled = false

        // 初始化 SelectionTracker
        selectionTracker = PhotoSelectionTracker()

        val glideRequest = Glide.with(this)
        val preloadSizeProvider = ViewPreloadSizeProvider<Uri>()
        val preloader = RecyclerViewPreloader(
            glideRequest,
            PhotoPreloadModelProvider(
                glideRequest,
                { position -> photoAdapter.currentList.getOrNull(position) as? PhotoModel.PhotoItem },
                itemSize
            ),
            preloadSizeProvider,
            20
        )
        binding.rvPhotos.addOnScrollListener(preloader)
    }

    private fun findPhotoPosition(photoId: Long): Int {
        return photoAdapter.getPhotoPosition(photoId)
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

    private fun observePhotoList() {
        pagingDataJob?.cancel()
        pagingDataJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoListFlow.collectLatest { list ->
                    photoAdapter.submitList(list)
                }
            }
        }
    }

    private fun refreshData() {
        viewModel.refresh()
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
                    photoAdapter.updateItemSize(itemSize)

                    while (binding.rvPhotos.itemDecorationCount > 0) {
                        binding.rvPhotos.removeItemDecorationAt(0)
                    }
                    binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(newSpanCount, GridSpanCalculator.dpToPx(2, resources.displayMetrics), true))
                }
                dialog?.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        pagingDataJob?.cancel()
        pagingDataJob = null
        selectionTracker.clear()
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