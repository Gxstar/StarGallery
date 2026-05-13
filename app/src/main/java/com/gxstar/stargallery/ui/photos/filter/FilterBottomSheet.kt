package com.gxstar.stargallery.ui.photos.filter

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.LayoutBottomSheetFilterBinding
import com.gxstar.stargallery.ui.photos.PhotosViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutBottomSheetFilterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels({ requireParentFragment() })

    private var currentDimension = FilterDimension.NONE
    private var isBuildingOptions = false

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

        binding.rgOptions.setOnCheckedChangeListener { _, checkedId ->
            if (isBuildingOptions) return@setOnCheckedChangeListener
            val radio = binding.rgOptions.findViewById<RadioButton>(checkedId)
            val key = radio?.tag as? String?
            when (currentDimension) {
                FilterDimension.CAMERA_MAKE -> viewModel.setFilterCameraMake(key)
                FilterDimension.CAMERA_MODEL -> viewModel.setFilterCameraModel(key)
                FilterDimension.LENS -> viewModel.setFilterLensModel(key)
                else -> {}
            }
            showMainView()
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
        updateMainViewValues()
    }

    private fun showListView(dimension: FilterDimension) {
        currentDimension = dimension
        binding.mainView.visibility = View.GONE
        binding.listView.visibility = View.VISIBLE

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
        isBuildingOptions = true
        val group = binding.rgOptions
        group.removeAllViews()

        options.forEach { option ->
            val text = "${option.display} (${option.count})"
            group.addView(createRadioButton(text, option.key, selectedKey == option.key))
        }

        isBuildingOptions = false
    }

    private fun createRadioButton(text: String, key: String?, checked: Boolean): RadioButton {
        val radio = RadioButton(requireContext())
        radio.text = text
        radio.tag = key
        radio.isChecked = checked
        radio.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(
                    ContextCompat.getColor(requireContext(), R.color.accent),
                    ContextCompat.getColor(requireContext(), R.color.text_primary)
                )
            )
        )
        radio.setPadding(0, dpToPx(12), 0, dpToPx(12))
        radio.layoutParams = RadioGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return radio
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

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "FilterBottomSheet"
    }
}
