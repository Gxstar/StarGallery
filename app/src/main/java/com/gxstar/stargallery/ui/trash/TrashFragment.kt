package com.gxstar.stargallery.ui.trash

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentTrashBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.gxstar.stargallery.ui.photos.GridSpacingItemDecoration
import com.gxstar.stargallery.ui.photos.PhotosFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrashViewModel by viewModels()
    private lateinit var adapter: TrashAdapter
    
    @Inject
    lateinit var mediaRepository: MediaRepository

    private val selectedPhotoIds = mutableSetOf<Long>()
    private var isSelectionMode = false
    private var currentSpanCount = 4
    private var itemSize = 0

    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            // 通知首页刷新
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
            viewModel.loadTrashedPhotos()
        }
        exitSelectionMode()
    }

    private val restoreRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.restored, Toast.LENGTH_SHORT).show()
            // 通知首页刷新
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
            viewModel.loadTrashedPhotos()
        }
        exitSelectionMode()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        calculateItemSize()
        setupRecyclerView()
        setupClickListeners()
        observeData()
    }

    private fun calculateItemSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemSpacing = dpToPx(2) * (currentSpanCount + 1)
        itemSize = (screenWidth - itemSpacing) / currentSpanCount
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun setupRecyclerView() {
        adapter = TrashAdapter(
            itemSize = itemSize,
            onPhotoClick = { photo ->
                if (isSelectionMode) {
                    togglePhotoSelection(photo)
                }
            },
            onPhotoLongClick = { photo ->
                if (!isSelectionMode) {
                    enterSelectionMode()
                    togglePhotoSelection(photo)
                }
                true
            },
            isSelectionModeProvider = { isSelectionMode },
            isSelectedProvider = { id -> selectedPhotoIds.contains(id) }
        )

        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), currentSpanCount)
        binding.rvPhotos.adapter = adapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.setHasFixedSize(true)
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnCancel.setOnClickListener {
            exitSelectionMode()
        }

        binding.btnRestore.setOnClickListener {
            restoreSelectedPhotos()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photos.collect { photos ->
                    adapter.submitList(photos)
                    binding.tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvPhotos.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photos.collect {
                    binding.tvSubtitle.text = getString(R.string.trash_count, viewModel.getPhotoCount())
                }
            }
        }
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
        binding.normalToolbar.visibility = View.VISIBLE
        binding.selectionToolbar.visibility = View.GONE
        refreshVisibleItems()
    }

    private fun togglePhotoSelection(photo: Photo) {
        if (selectedPhotoIds.contains(photo.id)) {
            selectedPhotoIds.remove(photo.id)
        } else {
            selectedPhotoIds.add(photo.id)
        }
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        if (selectedPhotoIds.isEmpty()) {
            exitSelectionMode()
        } else {
            binding.tvSelectionCount.text = getString(R.string.selected, selectedPhotoIds.size)
        }
    }

    private fun refreshVisibleItems() {
        val layoutManager = binding.rvPhotos.layoutManager as? GridLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            adapter.notifyItemRangeChanged(first, last - first + 1)
        }
    }

    private fun restoreSelectedPhotos() {
        if (selectedPhotoIds.isEmpty()) return

        val photos = viewModel.photos.value.filter { selectedPhotoIds.contains(it.id) }
        if (photos.isEmpty()) return

        mediaRepository.restorePhotos(photos)?.let { intentSender ->
            try {
                restoreRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
        } ?: run {
            Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }

    private fun showDeleteConfirmDialog() {
        if (selectedPhotoIds.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_permanently_confirm_title)
            .setMessage(getString(R.string.delete_permanently_confirm_message, selectedPhotoIds.size))
            .setPositiveButton(R.string.delete_permanently) { _, _ ->
                deleteSelectedPhotos()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSelectedPhotos() {
        val photos = viewModel.photos.value.filter { selectedPhotoIds.contains(it.id) }
        if (photos.isEmpty()) return

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class TrashAdapter(
    private val itemSize: Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: (Photo) -> Boolean,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Long) -> Boolean
) : RecyclerView.Adapter<TrashAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<Photo>()

    fun submitList(photos: List<Photo>) {
        items.clear()
        items.addAll(photos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding, itemSize, onPhotoClick, onPhotoLongClick, isSelectionModeProvider, isSelectedProvider)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class PhotoViewHolder(
        private val binding: ItemPhotoBinding,
        private val itemSize: Int,
        private val onPhotoClick: (Photo) -> Unit,
        private val onPhotoLongClick: (Photo) -> Boolean,
        private val isSelectionModeProvider: () -> Boolean,
        private val isSelectedProvider: (Long) -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: Photo) {
            val isSelectionMode = isSelectionModeProvider()
            val isSelected = isSelectedProvider(photo.id)

            // 设置固定大小
            binding.root.layoutParams.width = itemSize
            binding.root.layoutParams.height = itemSize

            Glide.with(binding.ivPhoto.context)
                .load(photo.uri)
                .placeholder(android.R.color.darker_gray)
                .centerCrop()
                .override(itemSize, itemSize)
                .into(binding.ivPhoto)

            if (isSelectionMode) {
                binding.ivSelected.visibility = View.VISIBLE
                binding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.ivSelected.setImageResource(
                    if (isSelected) R.drawable.ic_selected_filled else R.drawable.ic_selected
                )
                binding.ivPhoto.alpha = if (isSelected) 0.7f else 1.0f
            } else {
                binding.ivSelected.visibility = View.GONE
                binding.selectionOverlay.visibility = View.GONE
                binding.ivPhoto.alpha = 1.0f
            }

            binding.root.setOnClickListener { onPhotoClick(photo) }
            binding.root.setOnLongClickListener { onPhotoLongClick(photo) }
        }
    }
}
