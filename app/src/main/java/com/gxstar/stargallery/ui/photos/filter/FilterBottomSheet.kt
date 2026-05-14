package com.gxstar.stargallery.ui.photos.filter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.LayoutBottomSheetFilterBinding
import com.gxstar.stargallery.ui.photos.PhotosViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutBottomSheetFilterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels({ requireParentFragment() })

    private var currentDimension = FilterDimension.NONE

    private enum class FilterDimension { NONE, CAMERA_MAKE, CAMERA_MODEL, LENS }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutBottomSheetFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnClose.setOnClickListener { dismiss() }

        binding.rowCameraMake.setOnClickListener {
            showListView(FilterDimension.CAMERA_MAKE)
        }
        binding.rowCameraModel.setOnClickListener {
            showListView(FilterDimension.CAMERA_MODEL)
        }
        binding.rowLens.setOnClickListener {
            showListView(FilterDimension.LENS)
        }

        binding.btnBackFromList.setOnClickListener {
            showMainView()
        }

        binding.btnClear.setOnClickListener {
            viewModel.clearExifFilters()
        }
    }

    private var cachedCameraMakes: List<PhotosViewModel.FilterOption> = emptyList()
    private var cachedCameraModels: List<PhotosViewModel.FilterOption> = emptyList()
    private var cachedLensModels: List<PhotosViewModel.FilterOption> = emptyList()

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filterCameraMake.collect { updateMainViewValues() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filterCameraModel.collect { updateMainViewValues() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filterLensModel.collect { updateMainViewValues() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cameraMakeOptions.collect { options ->
                    cachedCameraMakes = options
                    if (currentDimension == FilterDimension.CAMERA_MAKE) rebuildCurrentList()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cameraModelOptions.collect { options ->
                    cachedCameraModels = options
                    if (currentDimension == FilterDimension.CAMERA_MODEL) rebuildCurrentList()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lensModelOptions.collect { options ->
                    cachedLensModels = options
                    if (currentDimension == FilterDimension.LENS) rebuildCurrentList()
                }
            }
        }
    }

    private fun showMainView() {
        currentDimension = FilterDimension.NONE
        binding.mainView.visibility = View.VISIBLE
        binding.listView.visibility = View.GONE
        binding.mainView.alpha = 0f
        binding.mainView.animate().alpha(1f).setDuration(200).start()
        updateMainViewValues()
    }

    private fun showListView(dimension: FilterDimension) {
        currentDimension = dimension
        binding.mainView.visibility = View.GONE
        binding.listView.visibility = View.VISIBLE
        binding.listView.alpha = 0f
        binding.listView.animate().alpha(1f).setDuration(200).start()

        val title = when (dimension) {
            FilterDimension.CAMERA_MAKE -> getString(R.string.filter_camera_make)
            FilterDimension.CAMERA_MODEL -> getString(R.string.filter_camera_model)
            FilterDimension.LENS -> getString(R.string.filter_lens)
            else -> ""
        }
        binding.tvListTitle.text = title

        val options = when (dimension) {
            FilterDimension.CAMERA_MAKE -> cachedCameraMakes
            FilterDimension.CAMERA_MODEL -> cachedCameraModels
            FilterDimension.LENS -> cachedLensModels
            else -> emptyList()
        }
        val selected = when (dimension) {
            FilterDimension.CAMERA_MAKE -> viewModel.filterCameraMake.value
            FilterDimension.CAMERA_MODEL -> viewModel.filterCameraModel.value
            FilterDimension.LENS -> viewModel.filterLensModel.value
            else -> null
        }
        buildOptionsList(options, selected)
    }

    private fun buildOptionsList(options: List<PhotosViewModel.FilterOption>, selectedKey: String?) {
        val group = binding.cgOptions
        group.removeAllViews()
        group.isSingleSelection = true

        options.forEach { option ->
            val chip = createChip(option, selectedKey == option.key)
            chip.setOnClickListener {
                if (currentDimension == FilterDimension.CAMERA_MAKE) {
                    viewModel.setFilterCameraMake(if (chip.isChecked) option.key else null)
                } else if (currentDimension == FilterDimension.CAMERA_MODEL) {
                    viewModel.setFilterCameraModel(if (chip.isChecked) option.key else null)
                } else if (currentDimension == FilterDimension.LENS) {
                    viewModel.setFilterLensModel(if (chip.isChecked) option.key else null)
                }
                showMainView()
            }
            group.addView(chip)
        }
    }

    private fun createChip(option: PhotosViewModel.FilterOption, checked: Boolean): Chip {
        val chip = Chip(requireContext(), null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
        chip.text = "${option.display}  ${option.count}"
        chip.tag = option.key
        chip.isCheckable = true
        chip.isChecked = checked

        val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val textPrimary = ContextCompat.getColor(requireContext(), R.color.text_primary)

        val states = arrayOf(
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(-android.R.attr.state_selected)
        )

        chip.setTextColor(
            android.content.res.ColorStateList(
                states, intArrayOf(android.graphics.Color.WHITE, textPrimary)
            )
        )

        chip.chipBackgroundColor = android.content.res.ColorStateList(
            states, intArrayOf(accentColor, ContextCompat.getColor(requireContext(), R.color.background_card))
        )

        chip.chipStrokeColor = android.content.res.ColorStateList(
            states, intArrayOf(accentColor, ContextCompat.getColor(requireContext(), R.color.divider))
        )

        return chip
    }

    private fun updateMainViewValues() {
        val make = viewModel.filterCameraMake.value
        val model = viewModel.filterCameraModel.value
        val lens = viewModel.filterLensModel.value

        updateValueText(binding.tvCameraMakeValue, make)
        updateValueText(binding.tvCameraModelValue, model)
        updateValueText(binding.tvLensValue, lens)
    }

    private fun updateValueText(textView: android.widget.TextView, value: String?) {
        val display = when {
            value == null -> getString(R.string.filter_all)
            value.isEmpty() -> getString(R.string.filter_unknown_device)
            else -> value
        }
        textView.text = display
        textView.setTextColor(
            if (value != null) {
                ContextCompat.getColor(requireContext(), R.color.accent)
            } else {
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            }
        )
    }

    private fun rebuildCurrentList() {
        val options = when (currentDimension) {
            FilterDimension.CAMERA_MAKE -> cachedCameraMakes
            FilterDimension.CAMERA_MODEL -> cachedCameraModels
            FilterDimension.LENS -> cachedLensModels
            else -> emptyList()
        }
        val selected = when (currentDimension) {
            FilterDimension.CAMERA_MAKE -> viewModel.filterCameraMake.value
            FilterDimension.CAMERA_MODEL -> viewModel.filterCameraModel.value
            FilterDimension.LENS -> viewModel.filterLensModel.value
            else -> null
        }
        buildOptionsList(options, selected)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "FilterBottomSheet"
    }
}
