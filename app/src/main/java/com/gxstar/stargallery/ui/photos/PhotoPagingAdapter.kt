package com.gxstar.stargallery.ui.photos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemDateHeaderBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
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
    private val isSelectionModeProvider: () -> Boolean = { false },
    private val isSelectedProvider: (Long) -> Boolean = { false }
) : PagingDataAdapter<PhotoModel, RecyclerView.ViewHolder>(PHOTO_DIFF_CALLBACK),
    PopupTextProvider {

    private var cachedPhotoCount = -1

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

    fun onPagesUpdated() {
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

    /**
     * 公开方法供 KeyProvider 使用
     */
    fun getPhotoKey(position: Int): Long {
        val item = getItem(position) ?: return RecyclerView.NO_ID
        return when (item) {
            is PhotoModel.PhotoItem -> item.photo.id
            is PhotoModel.SeparatorItem -> item.dateText.hashCode().toLong()
        }
    }

    /**
     * 根据 photo id 获取位置
     */
    fun getPhotoPosition(photoId: Long): Int {
        val snapshot = snapshot()
        for (i in 0 until snapshot.size) {
            val item = snapshot[i]
            if (item is PhotoModel.PhotoItem && item.photo.id == photoId) {
                return i
            }
        }
        return RecyclerView.NO_POSITION
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
                PhotoViewHolder(binding, { itemSize }, onPhotoClick, isSelectionModeProvider, isSelectedProvider)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        when {
            holder is HeaderViewHolder && item is PhotoModel.SeparatorItem -> {
                holder.bind(item.dateText)
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

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        // ViewHolder 从缓存恢复时更新选择状态
        if (holder is PhotoViewHolder) {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                val item = getItem(position) as? PhotoModel.PhotoItem
                if (item != null) {
                    holder.updateSelectionState(item.photo)
                }
            }
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
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Long) -> Boolean
) : RecyclerView.ViewHolder(binding.root) {

    private var currentPhoto: Photo? = null
    private var isClickProcessing = false

    fun bind(photo: Photo) {
        currentPhoto = photo
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(photo.id)

        loadImage(photo)
        updateSelectionUI(isSelectionMode, isSelected, photo)

        binding.photoContainer.setOnClickListener {
            if (isClickProcessing) {
                isClickProcessing = false
                return@setOnClickListener
            }
            onPhotoClick(photo)
        }

        binding.photoContainer.setOnLongClickListener {
            isClickProcessing = true
            false
        }
    }

    /**
     * Selection Library 的 ItemDetails 提供者
     */
    fun getItemDetails(): ItemDetailsLookup.ItemDetails<Long> {
        return object : ItemDetailsLookup.ItemDetails<Long>() {
            override fun getPosition(): Int = bindingAdapterPosition
            override fun getSelectionKey(): Long? = currentPhoto?.id
        }
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