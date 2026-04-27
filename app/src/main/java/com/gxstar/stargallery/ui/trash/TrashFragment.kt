package com.gxstar.stargallery.ui.trash

import android.content.res.Configuration
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
import androidx.recyclerview.selection.SelectionTracker
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
    private lateinit var gridLayoutManager: GridLayoutManager
    private var selectionTracker: SelectionTracker<Long>? = null

    @Inject
    lateinit var mediaRepository: MediaRepository

    private var isSelectionMode = false
    private var currentSpanCount = 4
    private var itemSize = 0

    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
            viewModel.loadTrashedPhotos()
        }
        exitSelectionMode()
    }

    private val restoreRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.restored, Toast.LENGTH_SHORT).show()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        calculateItemSize()
        adapter.updateItemSize(itemSize)
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
                    toggleSelection(photo.id)
                } else {
                    showPhotoPreview(photo)
                }
            },
            onPhotoLongClick = { photo ->
                if (!isSelectionMode) enterSelectionMode()
                toggleSelection(photo.id)
                true
            },
            isSelectionModeProvider = { isSelectionMode },
            isSelectedProvider = { id -> selectionTracker?.isSelected(id) ?: false }
        )

        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)

        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = adapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.setHasFixedSize(true)

        // 初始化 SelectionTracker
        selectionTracker = SelectionTracker.Builder(
            "trash-selection",
            binding.rvPhotos,
            TrashItemKeyProvider(adapter),
            TrashItemDetailsLookup(binding.rvPhotos),
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
                    } else if (count > 0) {
                        binding.tvSelectionCount.text = getString(R.string.selected, count)
                    }
                    // 不在这里调用 refreshVisibleItems()，避免无限递归
                }
            })
        }
    }

    private fun toggleSelection(photoId: Long) {
        selectionTracker?.let {
            if (it.isSelected(photoId)) {
                it.deselect(photoId)
            } else {
                it.select(photoId)
            }
        }
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
        val layoutManager = binding.rvPhotos.layoutManager as? GridLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            adapter.notifyItemRangeChanged(first, last - first + 1)
        }
    }

    private fun showPhotoPreview(photo: Photo) {
        TrashPhotoPreviewDialog.newInstance(photo) {
            viewModel.loadTrashedPhotos()
        }.show(childFragmentManager, TrashPhotoPreviewDialog.TAG)
    }

    private fun restoreSelectedPhotos() {
        val selectedIds = selectionTracker?.selection?.toSet() ?: emptySet()
        if (selectedIds.isEmpty()) return

        val photos = viewModel.photos.value.filter { selectedIds.contains(it.id) }
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
        val selectedIds = selectionTracker?.selection?.toSet() ?: emptySet()
        if (selectedIds.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_permanently_confirm_title)
            .setMessage(getString(R.string.delete_permanently_confirm_message, selectedIds.size))
            .setPositiveButton(R.string.delete_permanently) { _, _ ->
                deleteSelectedPhotos()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSelectedPhotos() {
        val selectedIds = selectionTracker?.selection?.toSet() ?: emptySet()
        val photos = viewModel.photos.value.filter { selectedIds.contains(it.id) }
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
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        _binding = null
        super.onDestroyView()
    }
}
