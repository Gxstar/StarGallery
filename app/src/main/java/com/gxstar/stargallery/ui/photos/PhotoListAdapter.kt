package com.gxstar.stargallery.ui.photos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.ItemDateHeaderBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.gxstar.stargallery.ui.common.PhotoGridViewHolder
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
                PhotoGridViewHolder(
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
        val item = getItem(position)
        if (item == null) return
        when {
            holder is HeaderViewHolder && item is PhotoModel.SeparatorItem -> {
                holder.bind(item.dateText)
            }
            holder is PhotoGridViewHolder && item is PhotoModel.PhotoItem ->
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
        if (holder is PhotoGridViewHolder) {
            holder.updateSelectionState(position)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is PhotoGridViewHolder) {
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
