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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.dragselectrecyclerview.DragSelectReceiver
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.DialogColumnsBinding
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.permissionx.guolindev.PermissionX
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PhotosFragment : Fragment(), DragSelectReceiver {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels()
    private lateinit var groupieAdapter: FastScrollGroupieAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var dragSelectTouchListener: DragSelectTouchListener
    
    @Inject
    lateinit var sharedPreferences: SharedPreferences
    
    @Inject
    lateinit var mediaRepository: MediaRepository
    
    private var currentSpanCount = 4
    
    // 选中的照片 ID 集合
    private val selectedPhotoIds = mutableSetOf<Long>()
    
    // 所有照片数据
    private var allPhotos: List<Photo> = emptyList()
    
    // 照片ID到适配器位置的映射
    private val photoIdToAdapterPosition = mutableMapOf<Long, Int>()
    
    // 适配器位置到照片ID的映射
    private val adapterPositionToPhotoId = mutableMapOf<Int, Long>()
    
    // 删除请求的 launcher
    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            viewModel.loadPhotos()
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
            viewModel.loadPhotos()
        }
        pendingFavoriteAction = 0
        exitSelectionMode()
    }
    
    private var isSelectionMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSpanCount()
        loadSortType().let { viewModel.setSortType(it) }
        setupRecyclerView()
        setupClickListeners()
        observeData()
        checkPermissions()
    }
    
    override fun onResume() {
        super.onResume()
        // 从详情页返回时刷新照片列表
        viewModel.loadPhotos()
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

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        PermissionX.init(this)
            .permissions(*permissions)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    viewModel.loadPhotos()
                }
            }
    }

    private fun setupClickListeners() {
        binding.btnMore.setOnClickListener { view ->
            showPopupMenu(view)
        }
        
        binding.btnBack.setOnClickListener {
            exitSelectionMode()
        }
        
        binding.btnShare.setOnClickListener {
            shareSelectedPhotos()
        }
        
        binding.btnFavorite.setOnClickListener {
            favoriteSelectedPhotos()
        }
        
        binding.btnDelete.setOnClickListener {
            deleteSelectedPhotos()
        }
    }
    
    private fun shareSelectedPhotos() {
        if (selectedPhotoIds.isEmpty()) return
        
        val uris = ArrayList<Uri>()
        selectedPhotoIds.forEach { id ->
            allPhotos.find { it.id == id }?.uri?.let { uris.add(it) }
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
            allPhotos.find { it.id == id }?.let { photo ->
                if (photo.isFavorite) {
                    photosToUnfavorite.add(photo)
                } else {
                    photosToFavorite.add(photo)
                }
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
                val intentSender = mediaRepository.setFavorite(photosToFavorite, true)
                if (intentSender != null) {
                    favoriteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    hasRequest = true
                }
            }
            if (hasUnfavorite) {
                val intentSender = mediaRepository.setFavorite(photosToUnfavorite, false)
                if (intentSender != null) {
                    favoriteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
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
            allPhotos.find { it.id == id }?.let { photos.add(it) }
        }
        
        if (photos.isEmpty()) return
        
        val intentSender = mediaRepository.deletePhotos(photos)
        if (intentSender != null) {
            try {
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        } else {
            Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }
    
    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photos, popupMenu.menu)
        
        val selectItem = popupMenu.menu.findItem(R.id.action_select)
        selectItem?.title = if (isSelectionMode) getString(R.string.cancel_select) else getString(R.string.select)
        
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_select -> {
                    if (isSelectionMode) {
                        exitSelectionMode()
                    } else {
                        enterSelectionMode()
                    }
                    true
                }
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                R.id.action_columns -> {
                    showColumnsDialog()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }
    
    private fun showSortDialog() {
        val currentSortType = viewModel.currentSortType.value
        val options = arrayOf(
            getString(R.string.sort_by_date_taken),
            getString(R.string.sort_by_date_modified)
        )
        val checkedItem = when (currentSortType) {
            MediaRepository.SortType.DATE_TAKEN -> 0
            MediaRepository.SortType.DATE_MODIFIED -> 1
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newSortType = when (which) {
                    0 -> MediaRepository.SortType.DATE_TAKEN
                    1 -> MediaRepository.SortType.DATE_MODIFIED
                    else -> MediaRepository.SortType.DATE_TAKEN
                }
                saveSortType(newSortType)
                viewModel.setSortType(newSortType)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun loadSortType(): MediaRepository.SortType {
        val sortTypeValue = sharedPreferences.getInt(KEY_SORT_TYPE, 0)
        return when (sortTypeValue) {
            0 -> MediaRepository.SortType.DATE_TAKEN
            1 -> MediaRepository.SortType.DATE_MODIFIED
            else -> MediaRepository.SortType.DATE_TAKEN
        }
    }
    
    private fun saveSortType(sortType: MediaRepository.SortType) {
        val sortTypeValue = when (sortType) {
            MediaRepository.SortType.DATE_TAKEN -> 0
            MediaRepository.SortType.DATE_MODIFIED -> 1
        }
        sharedPreferences.edit().putInt(KEY_SORT_TYPE, sortTypeValue).apply()
    }
    
    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedPhotoIds.clear()
        binding.normalToolbar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
        binding.tvSelectionCount.text = getString(R.string.selected, 0)
        // 只刷新可见项，而非重建整个列表
        refreshVisibleItems()
    }
    
    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedPhotoIds.clear()
        dragSelectTouchListener.setIsActive(false, -1)
        binding.normalToolbar.visibility = View.VISIBLE
        binding.selectionToolbar.visibility = View.GONE
        // 只刷新可见项，而非重建整个列表
        refreshVisibleItems()
    }
    
    /**
     * 刷新当前可见的 item，用于切换选择模式时更新 UI
     */
    private fun refreshVisibleItems() {
        val layoutManager = gridLayoutManager
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        
        if (firstVisible != RecyclerView.NO_POSITION && lastVisible != RecyclerView.NO_POSITION) {
            groupieAdapter.notifyItemRangeChanged(firstVisible, lastVisible - firstVisible + 1)
        }
    }
    
    private fun togglePhotoSelection(photo: Photo) {
        val adapterPosition = photoIdToAdapterPosition[photo.id] ?: return
        
        if (selectedPhotoIds.contains(photo.id)) {
            selectedPhotoIds.remove(photo.id)
        } else {
            selectedPhotoIds.add(photo.id)
        }
        
        // 刷新该项以更新选中状态 UI
        groupieAdapter.notifyItemChanged(adapterPosition)
        updateSelectionUI()
    }
    
    private fun updateSelectionUI() {
        if (selectedPhotoIds.isEmpty()) {
            exitSelectionMode()
        } else {
            binding.tvSelectionCount.text = getString(R.string.selected, selectedPhotoIds.size)
        }
    }
    
    /**
     * 启动拖动选择
     */
    private fun startDragSelection(adapterPosition: Int) {
        if (!isSelectionMode) {
            enterSelectionMode()
        }
        // 先选中当前项
        val photoId = adapterPositionToPhotoId[adapterPosition]
        if (photoId != null && !selectedPhotoIds.contains(photoId)) {
            selectedPhotoIds.add(photoId)
            groupieAdapter.notifyItemChanged(adapterPosition)
            updateSelectionUI()
        }
        // 启动拖动选择
        dragSelectTouchListener.setIsActive(true, adapterPosition)
    }
    
    // ========== DragSelectReceiver 实现 ==========
    
    override fun setSelected(index: Int, selected: Boolean) {
        val photoId = adapterPositionToPhotoId[index] ?: return
        val wasSelected = selectedPhotoIds.contains(photoId)
        
        if (selected && !wasSelected) {
            selectedPhotoIds.add(photoId)
            groupieAdapter.notifyItemChanged(index)
        } else if (!selected && wasSelected) {
            selectedPhotoIds.remove(photoId)
            groupieAdapter.notifyItemChanged(index)
        }
        
        updateSelectionUI()
    }
    
    override fun isSelected(index: Int): Boolean {
        val photoId = adapterPositionToPhotoId[index] ?: return false
        return selectedPhotoIds.contains(photoId)
    }
    
    override fun isIndexSelectable(index: Int): Boolean {
        // DateHeaderItem 不可选择
        if (index < 0 || index >= groupieAdapter.itemCount) return false
        val item = groupieAdapter.getItem(index)
        return item !is DateHeaderItem
    }
    
    override fun getItemCount(): Int {
        return groupieAdapter.itemCount
    }
    
    // ========== DragSelectReceiver 实现结束 ==========
    
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
                val selectedSpanCount = when {
                    dialogBinding.rb3.isChecked -> 3
                    dialogBinding.rb4.isChecked -> 4
                    dialogBinding.rb5.isChecked -> 5
                    dialogBinding.rb6.isChecked -> 6
                    dialogBinding.rb7.isChecked -> 7
                    dialogBinding.rb8.isChecked -> 8
                    else -> 4
                }
                updateSpanCount(selectedSpanCount)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun updateSpanCount(spanCount: Int) {
        if (spanCount != currentSpanCount) {
            saveSpanCount(spanCount)
            gridLayoutManager.spanCount = spanCount
            groupieAdapter.notifyDataSetChanged()
        }
    }

    private fun setupRecyclerView() {
        groupieAdapter = FastScrollGroupieAdapter()
        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)
        
        // 初始化拖动选择监听器
        dragSelectTouchListener = DragSelectTouchListener.create(requireContext(), this) {
            hotspotHeight = dpToPx(56)
        }
        
        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = groupieAdapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.addOnItemTouchListener(dragSelectTouchListener)
        
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (position >= 0 && position < groupieAdapter.itemCount) {
                    val item = groupieAdapter.getItem(position)
                    if (item is DateHeaderItem) currentSpanCount else 1
                } else {
                    1
                }
            }
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoGroups.collect { groups ->
                    allPhotos = groups.flatMap { it.photos }
                    updateAdapter()
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoCount.collect { count ->
                    binding.tvSubtitle.text = getString(R.string.photo_count, count)
                }
            }
        }
    }
    
    private fun updateAdapter() {
        photoIdToAdapterPosition.clear()
        adapterPositionToPhotoId.clear()
        val items = mutableListOf<com.xwray.groupie.Group>()
        val groups = viewModel.photoGroups.value
        var adapterPosition = 0
        
        groups.forEach { group ->
            items.add(DateHeaderItem(group.date))
            adapterPosition++
            
            group.photos.forEach { photo ->
                photoIdToAdapterPosition[photo.id] = adapterPosition
                adapterPositionToPhotoId[adapterPosition] = photo.id
                
                val photoItem = PhotoItem(
                    photo = photo,
                    isSelectionModeProvider = { isSelectionMode },
                    isSelectedProvider = { selectedPhotoIds.contains(photo.id) },
                    onPhotoClick = { clickedPhoto ->
                        if (isSelectionMode) {
                            togglePhotoSelection(clickedPhoto)
                        } else {
                            navigateToDetail(clickedPhoto)
                        }
                    },
                    onPhotoLongClick = { clickedPhoto ->
                        val position = photoIdToAdapterPosition[clickedPhoto.id] ?: 0
                        startDragSelection(position)
                        true
                    }
                )
                items.add(photoItem)
                adapterPosition++
            }
        }
        
        groupieAdapter.updateAsync(items)
    }

    private fun navigateToDetail(photo: Photo) {
        val sortTypeValue = when (viewModel.currentSortType.value) {
            MediaRepository.SortType.DATE_TAKEN -> 0
            MediaRepository.SortType.DATE_MODIFIED -> 1
        }
        val action = PhotosFragmentDirections.actionPhotosFragmentToPhotoDetailFragment(photo.id, sortTypeValue)
        findNavController().navigate(action)
    }

    override fun onDestroyView() {
        // 清理 RecyclerView 相关资源
        binding.rvPhotos.removeOnItemTouchListener(dragSelectTouchListener)
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        
        // 清理 spanSizeLookup 引用
        gridLayoutManager.spanSizeLookup = null
        
        // 清理数据映射
        photoIdToAdapterPosition.clear()
        adapterPositionToPhotoId.clear()
        selectedPhotoIds.clear()
        allPhotos = emptyList()
        
        _binding = null
        super.onDestroyView()
    }
    
    companion object {
        private const val KEY_SPAN_COUNT = "span_count"
        private const val KEY_SORT_TYPE = "sort_type"
    }
}