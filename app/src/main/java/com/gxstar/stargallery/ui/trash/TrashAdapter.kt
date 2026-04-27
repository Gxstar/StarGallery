package com.gxstar.stargallery.ui.trash

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoBinding

/**
 * 回收站列表适配器
 */
class TrashAdapter(
    private var itemSize: Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: (Photo) -> Boolean,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Long) -> Boolean
) : RecyclerView.Adapter<TrashAdapter.TrashViewHolder>() {

    private val items = mutableListOf<Photo>()

    init {
        setHasStableIds(true)
    }

    fun submitList(photos: List<Photo>) {
        items.clear()
        items.addAll(photos)
        notifyDataSetChanged()
    }

    fun updateItemSize(newSize: Int) {
        itemSize = newSize
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        return if (position in items.indices) items[position].id else RecyclerView.NO_ID
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): Photo? {
        return if (position in items.indices) items[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrashViewHolder(binding, itemSize, onPhotoClick, onPhotoLongClick, isSelectionModeProvider, isSelectedProvider)
    }

    override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class TrashViewHolder(
        private val binding: ItemPhotoBinding,
        private val itemSize: Int,
        private val onPhotoClick: (Photo) -> Unit,
        private val onPhotoLongClick: (Photo) -> Boolean,
        private val isSelectionModeProvider: () -> Boolean,
        private val isSelectedProvider: (Long) -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentPhoto: Photo? = null
        private var isClickProcessing = false

        fun bind(photo: Photo) {
            currentPhoto = photo
            val isSelectionMode = isSelectionModeProvider()
            val isSelected = isSelectedProvider(photo.id)

            binding.root.layoutParams.width = itemSize
            binding.root.layoutParams.height = itemSize

            Glide.with(binding.ivPhoto.context)
                .load(photo.uri)
                .placeholder(android.R.color.darker_gray)
                .centerCrop()
                .override(itemSize, itemSize)
                .into(binding.ivPhoto)

            if (isSelectionMode) {
                binding.ivSelected.visibility = View.VISIBLE
                binding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.ivSelected.setImageResource(
                    if (isSelected) R.drawable.ic_selected_filled else R.drawable.ic_selected
                )
                binding.ivPhoto.alpha = if (isSelected) 0.7f else 1.0f
            } else {
                binding.ivSelected.visibility = View.GONE
                binding.selectionOverlay.visibility = View.GONE
                binding.ivPhoto.alpha = 1.0f
            }

            binding.photoContainer.setOnClickListener {
                if (isClickProcessing) {
                    isClickProcessing = false
                    return@setOnClickListener
                }
                onPhotoClick(photo)
            }

            binding.photoContainer.setOnLongClickListener {
                isClickProcessing = true
                onPhotoLongClick(photo)
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
    }
}