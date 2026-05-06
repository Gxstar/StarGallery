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
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.ItemDateHeaderBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.gxstar.stargallery.ui.photos.model.PhotoModel

class PhotoListAdapter(
    private var itemSize: Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: (Int) -> Unit,
    private val isSelectionModeProvider: () -> Boolean = { false },
    private val isSelectedProvider: (Int) -> Boolean = { false }
) : PagingDataAdapter<PhotoModel, RecyclerView.ViewHolder>(PHOTO_DIFF_CALLBACK) {

    private var currentSortType = MediaRepository.SortType.DATE_TAKEN
    private var currentGroupType = GroupType.DAY

    fun updateItemSize(newItemSize: Int) {
        if (itemSize != newItemSize) {
            itemSize = newItemSize
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun updateSortAndGroupType(sortType: MediaRepository.SortType, groupType: GroupType) {
        currentSortType = sortType
        currentGroupType = groupType
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
                PhotoViewHolder(
                    binding,
                    { itemSize },
                    onPhotoClick,
                    onPhotoLongClick,
                    isSelectionModeProvider,
                    isSelectedProvider
                )
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
                holder.bind(item.photo, position)
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
            holder.updateSelectionState(position)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is PhotoViewHolder) {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                holder.updateSelectionState(position)
            }
        }
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
    private val itemSizeProvider: () -> Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: (Int) -> Unit,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Int) -> Boolean
) : RecyclerView.ViewHolder(binding.root) {

    private var currentPhoto: Photo? = null
    private var isClickProcessing = false

    fun bind(photo: Photo, position: Int) {
        currentPhoto = photo
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(position)

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
            onPhotoLongClick(position)
            true
        }
    }

    fun updateSelectionState(position: Int) {
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(position)
        currentPhoto?.let { updateSelectionUI(isSelectionMode, isSelected, it) }
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
            if (photo.isRaw) {
                binding.tvFormatTag.visibility = View.VISIBLE
                binding.tvFormatTag.text = "RAW"
            } else {
                binding.tvFormatTag.visibility = View.GONE
            }
        }
    }
}
