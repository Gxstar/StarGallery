package com.gxstar.stargallery.ui.photos.filter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.ItemFilterRowBinding
import com.gxstar.stargallery.databinding.LayoutBottomSheetFilterBinding
import com.gxstar.stargallery.ui.common.constrainBottomSheetWidth
import com.gxstar.stargallery.ui.photos.PhotosViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 统一筛选面板
 *
 * 维度行按 [FilterDimensions] 注册表动态生成，不再有写死的行布局与 when 分支，
 * 新增筛选维度只需在注册表增加一项。
 */
@AndroidEntryPoint
class FilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutBottomSheetFilterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotosViewModel by viewModels({ requireParentFragment() })

    /** 当前展开的维度，null 表示停留在主视图 */
    private var currentDimension: FilterDimension? = null

    /** 打开时直达的维度（来自 chip 条点击），null 表示停留在主视图 */
    private var startingDimensionId: FilterDimensionId? = null

    /** 各维度行的 binding，按注册顺序动态创建 */
    private val dimensionRows = linkedMapOf<FilterDimensionId, ItemFilterRowBinding>()

    /** 当前列表视图中 key 到 chip 的映射，用于只同步选中态而不重建视图 */
    private val chipByKey = linkedMapOf<String, Chip>()

    /** 已渲染的选项快照，内容不变则跳过重建，避免 chip 闪烁 */
    private var renderedOptions: List<FilterOption>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutBottomSheetFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startingDimensionId = arguments
            ?.getString(ARG_DIMENSION_ID)
            ?.let { runCatching { FilterDimensionId.valueOf(it) }.getOrNull() }
        buildDimensionRows()
        setupListeners()
        observeViewModel()
        startingDimensionId?.let { showListView(FilterDimensions.of(it)) }
    }

    /**
     * 按注册表生成维度行
     */
    private fun buildDimensionRows() {
        val container = binding.dimensionContainer
        container.removeAllViews()
        dimensionRows.clear()

        val horizontalMargin = dpToPx(16)
        val rowBottomMargin = dpToPx(2)

        FilterDimensions.ALL.forEach { dimension ->
            val row = ItemFilterRowBinding.inflate(layoutInflater, container, false)
            row.ivIcon.setImageResource(dimension.iconRes)
            row.tvTitle.text = getString(dimension.titleRes)
            row.root.setOnClickListener { showListView(dimension) }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(60)
            ).apply {
                marginStart = horizontalMargin
                marginEnd = horizontalMargin
                bottomMargin = rowBottomMargin
            }
            container.addView(row.root, params)
            dimensionRows[dimension.id] = row
        }
    }

    private fun setupListeners() {
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnBackFromList.setOnClickListener { showMainView() }
        binding.btnClear.setOnClickListener { viewModel.clearAllFilters() }
        binding.rowFavorites.setOnClickListener { viewModel.toggleFavoritesOnly() }
        binding.btnClearDimension.setOnClickListener {
            currentDimension?.let { viewModel.clearDimension(it.id) }
        }
    }

    private fun observeViewModel() {
        // 筛选状态：更新主视图各行取值、收藏开关，并同步列表页 chip 选中态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filterState.collect { state ->
                    binding.switchFavorites.isChecked = state.favoritesOnly
                    updateRowValues(state)
                    currentDimension?.let { dimension ->
                        val selected = state.selectionOf(dimension.id)
                        syncChipChecks(selected)
                        binding.btnClearDimension.visibility =
                            if (selected.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }

        // 选项与计数：仅当当前维度的选项内容真的变化时才重建 chip
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filterOptions.collect { optionsMap ->
                    val dimension = currentDimension ?: return@collect
                    val options = optionsMap[dimension.id] ?: emptyList()
                    if (options != renderedOptions) {
                        renderOptions(dimension, options)
                    }
                    syncChipChecks(viewModel.filterState.value.selectionOf(dimension.id))
                }
            }
        }

        // 相册排除属于隐式过滤，这里给出可见提示
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeConditions.collect { conditions ->
                    val hasExcluded = conditions.any { it is ActiveCondition.ExcludedAlbums }
                    binding.tvExcludedHint.visibility =
                        if (hasExcluded) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showMainView() {
        currentDimension = null
        renderedOptions = null
        chipByKey.clear()
        binding.listView.visibility = View.GONE
        binding.mainView.visibility = View.VISIBLE
        binding.mainView.alpha = 0f
        binding.mainView.animate().alpha(1f).setDuration(200).start()
        updateRowValues(viewModel.filterState.value)
    }

    private fun showListView(dimension: FilterDimension) {
        currentDimension = dimension
        renderedOptions = null
        binding.mainView.visibility = View.GONE
        binding.listView.visibility = View.VISIBLE
        binding.listView.alpha = 0f
        binding.listView.animate().alpha(1f).setDuration(200).start()

        binding.tvListTitle.text = getString(dimension.titleRes)

        val options = viewModel.filterOptions.value[dimension.id] ?: emptyList()
        renderOptions(dimension, options)

        val selected = viewModel.filterState.value.selectionOf(dimension.id)
        syncChipChecks(selected)
        binding.btnClearDimension.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderOptions(dimension: FilterDimension, options: List<FilterOption>) {
        val group = binding.cgOptions
        group.removeAllViews()
        chipByKey.clear()

        options.forEach { option ->
            val chip = createChip(option)
            chip.setOnClickListener { viewModel.toggleDimension(dimension.id, option.key) }
            chipByKey[option.key] = chip
            group.addView(chip)
        }
        renderedOptions = options
    }

    /**
     * 只同步选中态，不重建视图
     *
     * 选中态一律以 ViewModel 状态为唯一依据，
     * 因此不会再出现 chip 视觉已取消、实际过滤条件却仍生效的情况。
     */
    private fun syncChipChecks(selected: Set<String>) {
        chipByKey.forEach { (key, chip) ->
            val shouldCheck = key in selected
            if (chip.isChecked != shouldCheck) {
                chip.isChecked = shouldCheck
            }
        }
    }

    private fun createChip(option: FilterOption): Chip {
        val chip = Chip(requireContext(), null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
        chip.text = getString(R.string.filter_option_count, option.display, option.count)
        chip.isCheckable = true

        val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val textPrimary = ContextCompat.getColor(requireContext(), R.color.text_primary)

        // 注意：Material Chip 勾选时设置的是 state_checked，不是 state_selected，
        // 用 state_selected 会导致选中态永远走"未选中"分支、chip 无任何高亮。
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
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

    private fun updateRowValues(state: FilterState) {
        dimensionRows.forEach { (id, row) ->
            val selected = state.selectionOf(id)
            row.tvValue.text = when {
                selected.isEmpty() -> "—"
                selected.size == 1 -> selected.first().takeIf { it != UNKNOWN_KEY }
                    ?: getString(R.string.filter_unknown)
                else -> getString(R.string.filter_selected_count, selected.size)
            }
            row.tvValue.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected.isNotEmpty()) R.color.accent else R.color.text_secondary
                )
            )
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        // 选项列表较多，平板上限宽 560dp 并居中，避免横向拉伸
        constrainBottomSheetWidth(560)
    }

    override fun onDestroyView() {
        dimensionRows.clear()
        chipByKey.clear()
        renderedOptions = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "FilterBottomSheet"
        private const val ARG_DIMENSION_ID = "dimension_id"

        /**
         * @param dimensionId 打开时直达的维度（如从 chip 条点击进入），null 表示主视图
         */
        fun newInstance(dimensionId: FilterDimensionId? = null): FilterBottomSheet =
            FilterBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_DIMENSION_ID, dimensionId?.name)
                }
            }
    }
}
