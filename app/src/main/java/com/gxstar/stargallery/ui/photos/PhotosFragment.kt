package com.gxstar.stargallery.ui.photos

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
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.util.ViewPreloadSizeProvider
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.DialogColumnsBinding
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.permissionx.guolindev.PermissionX
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PhotosFragment : Fragment(), DragSelectReceiver, FastScrollRecyclerView.SectionedAdapter {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels()
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

    // 选中的照片 ID 集合
    private val selectedPhotoIds = mutableSetOf<Long>()

    // 照片ID到适配器位置的映射
    private val photoIdToPosition = mutableMapOf<Long, Int>()

    private var needsRefresh = false
    private var isSelectionMode = false

    // 删除请求的 launcher
    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            refreshData()
        }
        exitSelectionMode()
    }

    // 收藏请求的 launcher
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
        val savedSortType = loadSortType()
        viewModel.setSortType(savedSortType)
        val savedGroupType = loadGroupType()
        viewModel.setGroupType(savedGroupType)
        setupRecyclerView()
        setupClickListeners()
        observeData()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (needsRefresh) {
            refreshData()
            needsRefresh = false
        }
    }

    fun onBackPressed(): Boolean {
        return if (isSelectionMode) {
            exitSelectionMode()
            true
        } else {
            false
        }
    }

    private fun loadSpanCount() {
        currentSpanCount = sharedPreferences.getInt(KEY_SPAN_COUNT, 4)
    }

    private fun saveSpanCount(spanCount: Int) {
        sharedPreferences.edit().putInt(KEY_SPAN_COUNT, spanCount).apply()
        currentSpanCount = spanCount
    }

    private fun calculateItemSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemSpacing = dpToPx(2) * (currentSpanCount + 1)
        itemSize = (screenWidth - itemSpacing) / currentSpanCount
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
                    // 权限授予后立即开始观察数据
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

        mediaRepository.deletePhotos(photos)?.let { intentSender ->
            try {
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        } ?: run {
            Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
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

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSortType = when (which) {
                    0 -> MediaRepository.SortType.DATE_TAKEN
                    1 -> MediaRepository.SortType.DATE_ADDED
                    else -> MediaRepository.SortType.DATE_TAKEN
                }
                android.util.Log.d("PhotosFragment", "Changing sort type from $currentSortType to $newSortType")
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
        sharedPreferences.edit().putInt(KEY_SORT_TYPE, if (sortType == MediaRepository.SortType.DATE_TAKEN) 0 else 1).apply()
    }

    private fun showGroupDialog() {
        val currentGroupType = viewModel.currentGroupType.value
        val options = arrayOf(getString(R.string.group_by_day), getString(R.string.group_by_month), getString(R.string.group_by_year))
        val checkedItem = when (currentGroupType) {
            GroupType.DAY -> 0
            GroupType.MONTH -> 1
            GroupType.YEAR -> 2
        }

        AlertDialog.Builder(requireContext())
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

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedPhotoIds.clear()
        binding.normalToolbar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
        binding.tvSelectionCount.text = getString(R.string.selected, 0)
        refreshVisibleItems()
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

    // ========== FastScrollRecyclerView.SectionedAdapter ==========
    override fun getSectionName(position: Int): String = photoAdapter.getDateText(position)

    private fun showColumnsDialog() {
        val dialogBinding = DialogColumnsBinding.inflate(layoutInflater)
        when (currentSpanCount) {
            3 -> dialogBinding.rb3.isChecked = true
            4 -> dialogBinding.rb4.isChecked = true
            5 -> dialogBinding.rb5.isChecked = true
            6 -> dialogBinding.rb6.isChecked = true
            7 -> dialogBinding.rb7.isChecked = true
            8 -> dialogBinding.rb8.isChecked = true
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newSpan = when {
                    dialogBinding.rb3.isChecked -> 3
                    dialogBinding.rb4.isChecked -> 4
                    dialogBinding.rb5.isChecked -> 5
                    dialogBinding.rb6.isChecked -> 6
                    dialogBinding.rb7.isChecked -> 7
                    dialogBinding.rb8.isChecked -> 8
                    else -> 4
                }
                if (newSpan != currentSpanCount) {
                    saveSpanCount(newSpan)
                    currentSpanCount = newSpan
                    calculateItemSize()
                    // 重新设置RecyclerView
                    setupRecyclerView()
                    observePagingData()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
        // 设置SpanSizeLookup：header占据整行，照片占1列
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (photoAdapter.getItemViewType(position) == 0) currentSpanCount else 1
            }
        }

        dragSelectTouchListener = DragSelectTouchListener.create(requireContext(), this) {
            hotspotHeight = dpToPx(56)
        }

        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = photoAdapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.addOnItemTouchListener(dragSelectTouchListener)

        // 性能优化
        binding.rvPhotos.setHasFixedSize(true)
        binding.rvPhotos.setItemViewCacheSize(24)
        binding.rvPhotos.isNestedScrollingEnabled = false

        // 设置 Glide Preloader
        val glideRequest = Glide.with(this)
        val preloadSizeProvider = ViewPreloadSizeProvider<Uri>()
        val preloader = RecyclerViewPreloader(
            glideRequest,
            PhotoPreloadModelProvider(glideRequest) {
                val first = gridLayoutManager.findFirstVisibleItemPosition()
                val last = gridLayoutManager.findLastVisibleItemPosition()
                val uris = mutableListOf<Uri>()
                for (i in (first - 10).coerceAtLeast(0)..(last + 10).coerceAtMost(photoAdapter.itemCount - 1)) {
                    photoAdapter.getPhoto(i)?.uri?.let { uris.add(it) }
                }
                uris
            },
            preloadSizeProvider,
            20
        )
        binding.rvPhotos.addOnScrollListener(preloader)

        // 滑动状态监听：暂停/恢复Glide
        binding.rvPhotos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_IDLE -> Glide.with(this@PhotosFragment).resumeRequests()
                    RecyclerView.SCROLL_STATE_DRAGGING, RecyclerView.SCROLL_STATE_SETTLING -> Glide.with(this@PhotosFragment).pauseRequests()
                }
            }
        })

        // 监听Adapter数据变化，更新位置映射
        photoAdapter.addOnPagesUpdatedListener {
            updatePositionMap()
        }
    }

    private fun updatePositionMap() {
        photoIdToPosition.clear()
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
    }

    private fun observePagingData() {
        pagingDataJob?.cancel()
        pagingDataJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoPagingFlow.collectLatest { pagingData ->
                    // 清空旧的位置映射，避免在数据更新期间使用旧映射
                    photoIdToPosition.clear()
                    photoAdapter.submitData(pagingData)
                }
            }
        }
    }

    private fun refreshData() {
        viewModel.refresh()
        // 使用 adapter.refresh() 刷新数据，保持滚动位置
        photoAdapter.refresh()
    }

    private fun navigateToDetail(photo: Photo) {
        val sortTypeValue = if (viewModel.currentSortType.value == MediaRepository.SortType.DATE_TAKEN) 0 else 1
        val action = PhotosFragmentDirections.actionPhotosFragmentToPhotoDetailFragment(photo.id, sortTypeValue)
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
        private const val KEY_SORT_TYPE = "sort_type"
        private const val KEY_GROUP_TYPE = "group_type"
    }
}