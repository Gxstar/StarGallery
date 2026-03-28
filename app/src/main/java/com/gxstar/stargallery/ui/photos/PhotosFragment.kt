package com.gxstar.stargallery.ui.photos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
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
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.DialogColumnsBinding
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.gxstar.stargallery.databinding.ItemDateHeaderBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.permissionx.guolindev.PermissionX
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PhotosFragment : Fragment() {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels()
    private lateinit var adapter: PhotoAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var tracker: SelectionTracker<Long>? = null
    
    @Inject
    lateinit var sharedPreferences: SharedPreferences
    
    @Inject
    lateinit var mediaRepository: MediaRepository
    
    private var currentSpanCount = 4
    
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
        setupRecyclerView()
        setupClickListeners()
        observeData()
        checkPermissions()
        
        // 恢复选择状态
        if (savedInstanceState != null) {
            tracker?.onRestoreInstanceState(savedInstanceState)
        }
    }
    
    // 处理返回键
    fun onBackPressed(): Boolean {
        return if (isSelectionMode) {
            exitSelectionMode()
            true // 消费了返回事件
        } else {
            false // 未消费，交给系统处理
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
        // 正常模式菜单
        binding.btnMore.setOnClickListener { view ->
            showPopupMenu(view)
        }
        
        // 选择模式返回按钮
        binding.btnBack.setOnClickListener {
            exitSelectionMode()
        }
        
        // 分享按钮
        binding.btnShare.setOnClickListener {
            shareSelectedPhotos()
        }
        
        // 收藏按钮
        binding.btnFavorite.setOnClickListener {
            favoriteSelectedPhotos()
        }
        
        // 删除按钮
        binding.btnDelete.setOnClickListener {
            deleteSelectedPhotos()
        }
    }
    
    private fun shareSelectedPhotos() {
        val selectedIds = tracker?.selection ?: return
        if (selectedIds.isEmpty()) return
        
        val uris = ArrayList<Uri>()
        selectedIds.forEach { id ->
            val photo = findPhotoById(id)
            photo?.uri?.let { uris.add(it) }
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
        val selectedIds = tracker?.selection ?: return
        if (selectedIds.isEmpty()) return
        
        val photosToFavorite = mutableListOf<Photo>()
        val photosToUnfavorite = mutableListOf<Photo>()
        
        selectedIds.forEach { id ->
            findPhotoById(id)?.let { photo ->
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
        val selectedIds = tracker?.selection ?: return
        if (selectedIds.isEmpty()) return
        
        val photos = mutableListOf<Photo>()
        selectedIds.forEach { id ->
            findPhotoById(id)?.let { photos.add(it) }
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
    
    private fun findPhotoById(id: Long): Photo? {
        for (i in 0 until adapter.itemCount) {
            val item = adapter.getItemAt(i)
            if (item is Photo && item.id == id) {
                return item
            }
        }
        return null
    }
    
    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_photos, popupMenu.menu)
        
        // 根据选择模式状态改变菜单项文字
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
                R.id.action_columns -> {
                    showColumnsDialog()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }
    
    private fun enterSelectionMode() {
        isSelectionMode = true
        adapter.isSelectionMode = true
        
        // 切换顶部栏
        binding.normalToolbar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
        
        adapter.notifyDataSetChanged()
    }
    
    private fun exitSelectionMode() {
        isSelectionMode = false
        tracker?.clearSelection()
        adapter.isSelectionMode = false
        
        // 切换顶部栏
        binding.normalToolbar.visibility = View.VISIBLE
        binding.selectionToolbar.visibility = View.GONE
        
        adapter.notifyDataSetChanged()
    }
    
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
        
        val dialog = AlertDialog.Builder(requireContext())
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
            .create()
        
        dialog.show()
    }
    
    private fun updateSpanCount(spanCount: Int) {
        if (spanCount != currentSpanCount) {
            saveSpanCount(spanCount)
            gridLayoutManager.spanCount = spanCount
            adapter.notifyDataSetChanged()
        }
    }

    private fun setupRecyclerView() {
        adapter = PhotoAdapter(
            onPhotoClick = { photo ->
                if (!isSelectionMode) {
                    navigateToDetail(photo)
                }
            },
            onPhotoLongClick = { photo, _ ->
                if (!isSelectionMode) {
                    enterSelectionMode()
                }
                tracker?.select(photo.id)
                true
            }
        )
        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)
        
        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = adapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        
        // 设置日期标题占满整行
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == 0) {
                    currentSpanCount
                } else {
                    1
                }
            }
        }
        
        // 设置选择追踪器
        setupSelectionTracker()
    }
    
    private fun setupSelectionTracker() {
        tracker = SelectionTracker.Builder(
            "photo-selection",
            binding.rvPhotos,
            PhotoKeyProvider(adapter),
            PhotoItemDetailsLookup(binding.rvPhotos, adapter),
            StorageStrategy.createLongStorage()
        )
            .withSelectionPredicate(SelectionPredicates.createSelectAnything())
            .build()
        
        adapter.tracker = tracker
        
        tracker?.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
            override fun onSelectionChanged() {
                if (!isSelectionMode) return
                
                val selectedCount = tracker?.selection?.size() ?: 0
                if (selectedCount > 0) {
                    binding.tvSelectionCount.text = getString(R.string.selected, selectedCount)
                } else {
                    // 当取消所有选择后自动退出选择模式
                    exitSelectionMode()
                }
            }
        })
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoGroups.collect { groups ->
                    adapter.submitList(groups)
                }
            }
        }
    }

    private fun navigateToDetail(photo: Photo) {
        val action = PhotosFragmentDirections.actionPhotosFragmentToPhotoDetailFragment(photo.id)
        findNavController().navigate(action)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tracker?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        private const val KEY_SPAN_COUNT = "span_count"
    }
}

// ItemKeyProvider - 提供项目的唯一键
class PhotoKeyProvider(
    private val adapter: PhotoAdapter
) : ItemKeyProvider<Long>(SCOPE_MAPPED) {
    
    override fun getKey(position: Int): Long? {
        return try {
            val item = adapter.getItemAt(position)
            if (item is Photo) item.id else null
        } catch (e: Exception) {
            null
        }
    }
    
    override fun getPosition(key: Long): Int {
        return try {
            adapter.findPositionById(key)
        } catch (e: Exception) {
            RecyclerView.NO_POSITION
        }
    }
}

// ItemDetailsLookup - 提供项目的详细信息
class PhotoItemDetailsLookup(
    private val recyclerView: RecyclerView,
    private val adapter: PhotoAdapter
) : ItemDetailsLookup<Long>() {
    
    override fun getItemDetails(event: MotionEvent): ItemDetails<Long>? {
        return try {
            val view = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
            val viewHolder = recyclerView.getChildViewHolder(view)
            
            if (viewHolder is PhotoAdapter.PhotoViewHolder) {
                val position = viewHolder.absoluteAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                
                val photoId = viewHolder.photoId ?: return null
                
                object : ItemDetails<Long>() {
                    override fun getPosition(): Int = position
                    override fun getSelectionKey(): Long = photoId
                    override fun inSelectionHotspot(e: MotionEvent): Boolean = adapter.isSelectionMode
                    override fun inDragRegion(e: MotionEvent): Boolean = adapter.isSelectionMode
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

class PhotoAdapter(
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: ((Photo, Int) -> Boolean)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Any>()
    var tracker: SelectionTracker<Long>? = null
    var isSelectionMode: Boolean = false

    fun submitList(groups: List<PhotoGroup>) {
        items.clear()
        groups.forEach { group ->
            items.add(group.date)
            items.addAll(group.photos)
        }
        notifyDataSetChanged()
    }
    
    fun getItemAt(position: Int): Any? {
        return if (position in items.indices) items[position] else null
    }
    
    fun findPositionById(id: Long): Int {
        return items.indexOfFirst { it is Photo && it.id == id }
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is String) 0 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            DateHeaderViewHolder(
                ItemDateHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        } else {
            PhotoViewHolder(
                ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onPhotoClick,
                onPhotoLongClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            when (val item = items[position]) {
                is String -> (holder as DateHeaderViewHolder).bind(item)
                is Photo -> {
                    val isSelected = tracker?.isSelected(item.id) == true
                    (holder as PhotoViewHolder).bind(item, isSelected, isSelectionMode)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getItemCount(): Int = items.size

    class DateHeaderViewHolder(private val binding: ItemDateHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(date: String) {
            binding.tvDateHeader.text = date
        }
    }

    class PhotoViewHolder(
        private val binding: ItemPhotoBinding,
        private val onPhotoClick: (Photo) -> Unit,
        private val onPhotoLongClick: ((Photo, Int) -> Boolean)?
    ) : RecyclerView.ViewHolder(binding.root) {
        
        var photoId: Long? = null
            private set
        
        fun bind(photo: Photo, isSelected: Boolean, isSelectionMode: Boolean) {
            photoId = photo.id
            
            Glide.with(binding.ivPhoto.context)
                .load(photo.uri)
                .centerCrop()
                .into(binding.ivPhoto)
            
            // 选择模式相关
            if (isSelectionMode) {
                // 显示选择圈
                binding.ivSelected.visibility = View.VISIBLE
                binding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.ivSelected.setImageResource(
                    if (isSelected) R.drawable.ic_selected_filled else R.drawable.ic_selected
                )
                // 隐藏收藏红心
                binding.ivFavorite.visibility = View.GONE
            } else {
                // 隐藏选择相关
                binding.ivSelected.visibility = View.GONE
                binding.selectionOverlay.visibility = View.GONE
                // 显示收藏红心
                binding.ivFavorite.visibility = if (photo.isFavorite) View.VISIBLE else View.GONE
            }
            
            binding.root.setOnClickListener { 
                onPhotoClick(photo) 
            }
            
            binding.root.setOnLongClickListener {
                onPhotoLongClick?.invoke(photo, absoluteAdapterPosition) ?: false
            }
        }
    }
}