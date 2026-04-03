package com.gxstar.stargallery.ui.albums

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
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
import com.permissionx.guolindev.PermissionX
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import javax.inject.Inject

@AndroidEntryPoint
class AlbumDetailFragment : Fragment(), DragSelectReceiver {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val args: AlbumDetailFragmentArgs by navArgs()
    private val viewModel: AlbumDetailViewModel by viewModels()

    private lateinit var photoAdapter: PhotoPagingAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var dragSelectTouchListener: DragSelectTouchListener

    private var pagingDataJob: Job? = null

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var mediaRepository: MediaRepository

    private var currentSpanCount = 4
    private var itemSize = 0

    private val selectedPhotoIds = mutableSetOf<Long>()
    private val photoIdToPosition = mutableMapOf<Long, Int>()

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
        if (selectedPhotoIds.isEmpty()) return

        val uris = ArrayList<Uri>()
        selectedPhotoIds.forEach { id ->
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
        if (selectedPhotoIds.isEmpty()) return

        val photosToFavorite = mutableListOf<Photo>()
        val photosToUnfavorite = mutableListOf<Photo>()

        selectedPhotoIds.forEach { id ->
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
        if (selectedPhotoIds.isEmpty()) return

        val photos = mutableListOf<Photo>()
        selectedPhotoIds.forEach { id ->
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

    private fun findPhotoById(id: Long): Photo? {
        for (i in 0 until photoAdapter.itemCount) {
            photoAdapter.getPhoto(i)?.let { if (it.id == id) return it }
        }
        return null
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photos, popupMenu.menu)

        // 隐藏不需要的菜单项
        popupMenu.menu.findItem(R.id.action_trash)?.isVisible = false

        val selectItem = popupMenu.menu.findItem(R.id.action_select)
        selectItem?.title = if (isSelectionMode) getString(R.string.cancel_select) else getString(R.string.select)

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_select -> {
                    if (isSelectionMode) exitSelectionMode() else enterSelectionMode()
                    true
                }
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
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSortType = when (which) {
                    0 -> MediaRepository.SortType.DATE_TAKEN
                    1 -> MediaRepository.SortType.DATE_ADDED
                    else -> MediaRepository.SortType.DATE_TAKEN
                }
                saveSortType(newSortType)
                viewModel.setSortType(newSortType)
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

    private fun showColumnsDialog() {
        val options = arrayOf("3", "4", "5", "6", "7", "8")
        val checkedItem = currentSpanCount - 3 // 3列对应索引0，4列对应索引1，以此类推

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_columns)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSpan = which + 3 // 索引0对应3列，索引1对应4列，以此类推
                if (newSpan != currentSpanCount) {
                    updateSpanCount(newSpan)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    /**
     * 更新列数（丝滑切换，不重建 RecyclerView）
     */
    private fun updateSpanCount(newSpanCount: Int) {
        // 保存新的列数
        sharedPreferences.edit().putInt(KEY_SPAN_COUNT, newSpanCount).apply()
        currentSpanCount = newSpanCount
        
        // 重新计算图片大小
        calculateItemSize()
        
        // 更新 LayoutManager 的列数
        gridLayoutManager.spanCount = newSpanCount
        
        // 更新适配器配置
        photoAdapter.updateConfig(itemSize, newSpanCount)
        
        // 更新 ItemDecoration（需要移除旧的再添加新的）
        while (binding.rvPhotos.itemDecorationCount > 0) {
            binding.rvPhotos.removeItemDecorationAt(0)
        }
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(newSpanCount, dpToPx(2), true))
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedPhotoIds.clear()
        binding.normalToolbar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
        binding.tvSelectionCount.text = getString(R.string.selected, 0)
        refreshVisibleItems()
    }

    fun onBackPressed(): Boolean {
        return if (isSelectionMode) {
            exitSelectionMode()
            true
        } else {
            false
        }
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedPhotoIds.clear()
        dragSelectTouchListener.setIsActive(false, -1)
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

    private fun togglePhotoSelection(photo: Photo) {
        val position = photoIdToPosition[photo.id] ?: return
        if (selectedPhotoIds.contains(photo.id)) selectedPhotoIds.remove(photo.id)
        else selectedPhotoIds.add(photo.id)
        photoAdapter.notifyItemChanged(position)
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        if (selectedPhotoIds.isEmpty()) exitSelectionMode()
        else binding.tvSelectionCount.text = getString(R.string.selected, selectedPhotoIds.size)
    }

    private fun startDragSelection(position: Int) {
        if (!isSelectionMode) enterSelectionMode()
        photoAdapter.getPhoto(position)?.let { photo ->
            if (!selectedPhotoIds.contains(photo.id)) {
                selectedPhotoIds.add(photo.id)
                photoAdapter.notifyItemChanged(position)
                updateSelectionUI()
            }
        }
        dragSelectTouchListener.setIsActive(true, position)
    }

    // ========== DragSelectReceiver ==========
    override fun setSelected(index: Int, selected: Boolean) {
        photoAdapter.getPhoto(index)?.let { photo ->
            val wasSelected = selectedPhotoIds.contains(photo.id)
            if (selected && !wasSelected) {
                selectedPhotoIds.add(photo.id)
                photoAdapter.notifyItemChanged(index)
            } else if (!selected && wasSelected) {
                selectedPhotoIds.remove(photo.id)
                photoAdapter.notifyItemChanged(index)
            }
            updateSelectionUI()
        }
    }

    override fun isSelected(index: Int): Boolean = photoAdapter.getPhoto(index)?.let { selectedPhotoIds.contains(it.id) } ?: false
    override fun isIndexSelectable(index: Int): Boolean = index >= 0 && index < photoAdapter.itemCount && photoAdapter.getPhoto(index) != null
    override fun getItemCount(): Int = photoAdapter.itemCount

    private fun setupRecyclerView() {
        photoAdapter = PhotoPagingAdapter(
            itemSize = itemSize,
            spanCount = currentSpanCount,
            onPhotoClick = { photo ->
                if (isSelectionMode) togglePhotoSelection(photo)
                else navigateToDetail(photo)
            },
            onPhotoLongClick = { photo ->
                photoIdToPosition[photo.id]?.let { startDragSelection(it); true } ?: false
            },
            isSelectionModeProvider = { isSelectionMode },
            isSelectedProvider = { id -> selectedPhotoIds.contains(id) }
        )

        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (photoAdapter.getItemViewType(position) == 0) currentSpanCount else 1
            }
        }.apply {
            isSpanIndexCacheEnabled = true
        }

        dragSelectTouchListener = DragSelectTouchListener.create(requireContext(), this) {
            hotspotHeight = dpToPx(56)
        }

        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = photoAdapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.addOnItemTouchListener(dragSelectTouchListener)

        binding.rvPhotos.setHasFixedSize(true)
        binding.rvPhotos.setItemViewCacheSize(24)
        binding.rvPhotos.isNestedScrollingEnabled = false

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
            updatePositionMap()
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

    private fun updatePositionMap() {
        for (i in 0 until photoAdapter.itemCount) {
            photoAdapter.getPhoto(i)?.let { photoIdToPosition[it.id] = i }
        }
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
        val action = AlbumDetailFragmentDirections.actionAlbumDetailFragmentToPhotoDetailFragment(photo.id, sortTypeValue)
        findNavController().navigate(action)
    }

    override fun onDestroyView() {
        pagingDataJob?.cancel()
        pagingDataJob = null
        binding.rvPhotos.removeOnItemTouchListener(dragSelectTouchListener)
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        gridLayoutManager.spanSizeLookup = null
        photoIdToPosition.clear()
        selectedPhotoIds.clear()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_SPAN_COUNT = "span_count"
        private const val KEY_SORT_TYPE_ALBUM = "sort_type_album"
        private const val KEY_GROUP_TYPE_ALBUM = "group_type_album"
    }
}