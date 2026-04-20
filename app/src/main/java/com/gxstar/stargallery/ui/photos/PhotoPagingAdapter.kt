package com.gxstar.stargallery.ui.photos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemDateHeaderBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.gxstar.stargallery.ui.common.DragSelectHelper
import me.zhanghai.android.fastscroll.PopupTextProvider
import android.content.Context
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.util.DateUtils

// ==================== UI 模型 ====================

/**
 * 用于RecyclerView的UI模型
 * 支持照片项和日期分隔符两种类型
 */
sealed class PhotoModel {
    /** 照片项 */
    data class PhotoItem(val photo: Photo) : PhotoModel()
    /** 日期分隔符 */
    data class SeparatorItem(val dateText: String) : PhotoModel()
}

// ==================== 适配器 ====================

/**
 * Paging 3 照片适配器
 * 使用Paging 3官方推荐的insertSeparators方式
 * 支持日期header占据整行
 * 优化：缓存照片数量和分隔符位置，减少遍历开销
 */
class PhotoPagingAdapter(
    private var itemSize: Int,
    private var spanCount: Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: ((Photo) -> Boolean)? = null,
    private val isSelectionModeProvider: () -> Boolean = { false },
    private val isSelectedProvider: (Long) -> Boolean = { false }
) : PagingDataAdapter<PhotoModel, RecyclerView.ViewHolder>(PHOTO_DIFF_CALLBACK), 
    DragSelectHelper.PhotoProvider, PopupTextProvider {

    // 性能优化：缓存照片数量
    private var cachedPhotoCount = -1
    // 性能优化：缓存分隔符位置 -> 日期文本
    private var separatorCache = mutableMapOf<Int, String>()
    
    // 排序和分组类型，用于格式化快速滑动时的日期弹出框
    private var currentSortType = MediaRepository.SortType.DATE_TAKEN
    private var currentGroupType = GroupType.DAY

    /**
     * 更新配置（列数和图片大小）
     * 用于动态切换列数时避免重建整个 RecyclerView
     */
    fun updateConfig(newItemSize: Int, newSpanCount: Int) {
        if (itemSize != newItemSize || spanCount != newSpanCount) {
            itemSize = newItemSize
            spanCount = newSpanCount
            // 通知所有照片项更新（不包含 header）
            notifyItemRangeChanged(0, itemCount)
        }
    }

    /**
     * 更新排序和分组设置，确保快速滑动时的日期显示准确
     */
    fun updateSortAndGroupType(sortType: MediaRepository.SortType, groupType: GroupType) {
        currentSortType = sortType
        currentGroupType = groupType
    }
    
    /**
     * 清除缓存（数据刷新时调用）
     */
    fun clearCache() {
        cachedPhotoCount = -1
        separatorCache.clear()
    }
    
    /**
     * 数据更新后调用，增量更新缓存
     */
    fun onPagesUpdated() {
        // 不立即计算，延迟到需要时再计算
        cachedPhotoCount = -1
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"

        private val PHOTO_DIFF_CALLBACK = object : DiffUtil.ItemCallback<PhotoModel>() {
            override fun areItemsTheSame(oldItem: PhotoModel, newItem: PhotoModel): Boolean {
                return when {
                    oldItem is PhotoModel.PhotoItem && newItem is PhotoModel.PhotoItem -> 
                        oldItem.photo.id == newItem.photo.id
                    oldItem is PhotoModel.SeparatorItem && newItem is PhotoModel.SeparatorItem -> 
                        oldItem.dateText == newItem.dateText
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: PhotoModel, newItem: PhotoModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (position < 0 || position >= itemCount) return TYPE_PHOTO
        return when (getItem(position)) {
            is PhotoModel.SeparatorItem -> TYPE_HEADER
            is PhotoModel.PhotoItem -> TYPE_PHOTO
            null -> TYPE_PHOTO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val binding = ItemDateHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemPhotoBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                // 传入获取 itemSize 的函数，支持动态更新
                PhotoViewHolder(binding, { itemSize }, onPhotoClick, onPhotoLongClick, isSelectionModeProvider, isSelectedProvider)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        when {
            holder is HeaderViewHolder && item is PhotoModel.SeparatorItem -> {
                holder.bind(item.dateText)
                // 更新分隔符缓存
                separatorCache[position] = item.dateText
            }
            holder is PhotoViewHolder && item is PhotoModel.PhotoItem ->
                holder.bind(item.photo)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (holder is PhotoViewHolder) {
            val item = getItem(position) as? PhotoModel.PhotoItem ?: return
            holder.updateSelectionState(item.photo)
        }
    }

    override fun getPhoto(position: Int): Photo? {
        if (position < 0 || position >= itemCount) return null
        val item = getItem(position) ?: return null
        return if (item is PhotoModel.PhotoItem) item.photo else null
    }

    // ========== DragSelectHelper.PhotoProvider 实现 ==========
    override fun notifyItemNeedsUpdate(position: Int) {
        if (position >= 0 && position < itemCount) {
            notifyItemChanged(position)
        }
    }

    fun getDateText(context: Context, position: Int): String {
        if (position < 0 || position >= itemCount) return ""
        val item = getItem(position) ?: return ""
        
        return when (item) {
            is PhotoModel.SeparatorItem -> item.dateText
            is PhotoModel.PhotoItem -> DateUtils.formatDateText(context, item.photo, currentSortType, currentGroupType)
        }
    }

    // ========== PopupTextProvider ==========
    override fun getPopupText(view: View, position: Int): CharSequence {
        return getDateText(view.context, position)
    }

    /**
     * 获取实际照片数量（不含header）
     * 使用缓存优化，避免每次遍历
     */
    fun getPhotoCount(): Int {
        // 如果缓存有效，直接返回
        if (cachedPhotoCount >= 0) return cachedPhotoCount
        
        // 计算并缓存
        var count = 0
        for (i in 0 until itemCount) {
            if (getItem(i) is PhotoModel.PhotoItem) count++
        }
        cachedPhotoCount = count
        return count
    }
}

// ==================== ViewHolder ====================

class HeaderViewHolder(
    private val binding: ItemDateHeaderBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(dateText: String) {
        binding.tvDate.text = dateText
    }
}

class PhotoViewHolder(
    private val binding: ItemPhotoBinding,
    private val itemSizeProvider: () -> Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: ((Photo) -> Boolean)?,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Long) -> Boolean
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(photo: Photo) {
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(photo.id)

        // 加载图片
        loadImage(photo)

        // 更新选择状态UI
        updateSelectionUI(isSelectionMode, isSelected, photo)

        // 设置点击事件
        binding.photoContainer.setOnClickListener { onPhotoClick(photo) }
        binding.photoContainer.setOnLongClickListener { onPhotoLongClick?.invoke(photo) ?: false }
    }

    /**
     * 仅更新选择状态，不重新加载图片（使用 payload 时调用）
     */
    fun updateSelectionState(photo: Photo) {
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(photo.id)
        updateSelectionUI(isSelectionMode, isSelected, photo)
    }

    private fun loadImage(photo: Photo) {
        val itemSize = itemSizeProvider()
        val requestBuilder = Glide.with(binding.ivPhoto.context)
            .load(photo.uri)
            .placeholder(R.drawable.ic_photo_placeholder)
            .error(R.drawable.ic_photo_error)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .skipMemoryCache(false)
            .dontAnimate()

        if (itemSize > 0) {
            requestBuilder.override(itemSize, itemSize)
        }

        requestBuilder.into(binding.ivPhoto)
    }

    private fun updateSelectionUI(isSelectionMode: Boolean, isSelected: Boolean, photo: Photo) {
        if (isSelectionMode) {
            binding.ivSelected.visibility = View.VISIBLE
            binding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivSelected.setImageResource(
                if (isSelected) R.drawable.ic_selected_filled else R.drawable.ic_selected
            )
            binding.ivFavorite.visibility = View.GONE
            binding.ivVideoIndicator.visibility = View.GONE
            binding.tvFormatTag.visibility = View.GONE
            binding.ivPhoto.alpha = if (isSelected) 0.7f else 1.0f
        } else {
            binding.ivSelected.visibility = View.GONE
            binding.selectionOverlay.visibility = View.GONE
            binding.ivPhoto.alpha = 1.0f
            binding.ivFavorite.visibility = if (photo.isFavorite) View.VISIBLE else View.GONE
            binding.ivVideoIndicator.visibility = if (photo.isVideo) View.VISIBLE else View.GONE
            // RAW 格式标签
            if (photo.isRaw) {
                binding.tvFormatTag.visibility = View.VISIBLE
                binding.tvFormatTag.text = "RAW"
            } else {
                binding.tvFormatTag.visibility = View.GONE
            }
        }
    }
}