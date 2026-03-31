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
import me.zhanghai.android.fastscroll.PopupTextProvider

/**
 * Paging 3 照片适配器
 * 使用Paging 3官方推荐的insertSeparators方式
 * 支持日期header占据整行
 */
class PhotoPagingAdapter(
    private val itemSize: Int,
    private val spanCount: Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: ((Photo) -> Boolean)? = null,
    private val isSelectionModeProvider: () -> Boolean = { false },
    private val isSelectedProvider: (Long) -> Boolean = { false }
) : PagingDataAdapter<PhotoModel, RecyclerView.ViewHolder>(PHOTO_DIFF_CALLBACK), PopupTextProvider {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1

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
                PhotoViewHolder(binding, itemSize, onPhotoClick, onPhotoLongClick, isSelectionModeProvider, isSelectedProvider)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        when {
            holder is HeaderViewHolder && item is PhotoModel.SeparatorItem -> 
                holder.bind(item.dateText)
            holder is PhotoViewHolder && item is PhotoModel.PhotoItem -> 
                holder.bind(item.photo)
        }
    }

    fun getPhoto(position: Int): Photo? {
        if (position < 0 || position >= itemCount) return null
        val item = getItem(position) ?: return null
        return if (item is PhotoModel.PhotoItem) item.photo else null
    }

    fun getDateText(position: Int): String {
        if (position < 0 || position >= itemCount) return ""
        val item = getItem(position) ?: return ""
        
        // 如果当前是分隔符，直接返回日期文本
        if (item is PhotoModel.SeparatorItem) return item.dateText
        
        // 如果当前是照片，向前查找最近的分隔符
        if (item is PhotoModel.PhotoItem) {
            for (i in position downTo 0) {
                val prevItem = getItem(i) ?: continue
                if (prevItem is PhotoModel.SeparatorItem) {
                    return prevItem.dateText
                }
            }
        }
        return ""
    }

    // ========== PopupTextProvider ==========
    override fun getPopupText(view: View, position: Int): CharSequence {
        return getDateText(position)
    }

    /**
     * 获取实际照片数量（不含header）
     */
    fun getPhotoCount(): Int {
        var count = 0
        for (i in 0 until itemCount) {
            if (getItem(i) is PhotoModel.PhotoItem) count++
        }
        return count
    }
}

class HeaderViewHolder(
    private val binding: ItemDateHeaderBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(dateText: String) {
        binding.tvDate.text = dateText
    }
}

class PhotoViewHolder(
    private val binding: ItemPhotoBinding,
    private val itemSize: Int,
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

    private fun loadImage(photo: Photo) {
        val requestBuilder = Glide.with(binding.ivPhoto.context)
            .load(photo.uri)
            .placeholder(android.R.color.darker_gray)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)

        if (itemSize > 0) {
            val thumbnailSize = (itemSize * 0.1f).toInt().coerceAtLeast(50)
            requestBuilder
                .override(itemSize, itemSize)
                .thumbnail(
                    Glide.with(binding.ivPhoto.context)
                        .load(photo.uri)
                        .override(thumbnailSize, thumbnailSize)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                )
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
            binding.ivPhoto.alpha = if (isSelected) 0.7f else 1.0f
        } else {
            binding.ivSelected.visibility = View.GONE
            binding.selectionOverlay.visibility = View.GONE
            binding.ivPhoto.alpha = 1.0f
            binding.ivFavorite.visibility = if (photo.isFavorite) View.VISIBLE else View.GONE
            // 显示视频图标
            binding.ivVideoIndicator.visibility = if (photo.isVideo) View.VISIBLE else View.GONE
        }
    }
}
