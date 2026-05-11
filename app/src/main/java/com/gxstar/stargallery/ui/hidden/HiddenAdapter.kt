package com.gxstar.stargallery.ui.hidden

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.gxstar.stargallery.ui.common.PhotoGridViewHolder

class HiddenAdapter(
    private var itemSize: Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: (Int) -> Unit,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Int) -> Boolean
) : ListAdapter<Photo, HiddenAdapter.HiddenViewHolder>(HiddenDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    fun updateItemSize(newSize: Int) {
        itemSize = newSize
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HiddenViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HiddenViewHolder(
            binding,
            { itemSize },
            onPhotoClick,
            onPhotoLongClick,
            isSelectionModeProvider,
            isSelectedProvider,
            PhotoGridViewHolder.ViewHolderConfig(
                fixedSize = true,
                itemSize = itemSize,
                useClickProcessing = false,
                showFavorite = false,
                showVideoIndicator = false,
                showFormatTag = false,
                showExpirationTag = false
            )
        )
    }

    override fun onBindViewHolder(holder: HiddenViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: HiddenViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        holder.updateSelectionState(position)
    }

    class HiddenViewHolder(
        private val binding: ItemPhotoBinding,
        private val itemSizeProvider: () -> Int,
        private val onPhotoClick: (Photo) -> Unit,
        private val onPhotoLongClick: (Int) -> Unit,
        private val isSelectionModeProvider: () -> Boolean,
        private val isSelectedProvider: (Int) -> Boolean,
        private val config: PhotoGridViewHolder.ViewHolderConfig
    ) : RecyclerView.ViewHolder(binding.root) {

        private val viewHolder = PhotoGridViewHolder(
            binding,
            itemSizeProvider,
            onPhotoClick,
            onPhotoLongClick,
            isSelectionModeProvider,
            isSelectedProvider,
            config
        )

        fun bind(photo: Photo, position: Int) {
            viewHolder.bind(photo, position)
        }

        fun updateSelectionState(position: Int) {
            viewHolder.updateSelectionState(position)
        }
    }
}

class HiddenDiffCallback : DiffUtil.ItemCallback<Photo>() {
    override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean {
        return oldItem == newItem
    }
}
