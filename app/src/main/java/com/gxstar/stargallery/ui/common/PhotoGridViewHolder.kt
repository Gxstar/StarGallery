package com.gxstar.stargallery.ui.common

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import java.io.File

/**
 * Unified ViewHolder for displaying photos in a grid.
 *
 * Supports:
 * - Item size configuration
 * - Selection mode (multi-select with visual feedback)
 * - Optional indicators: video, favorite, RAW format
 * - Click/long-click handling with selection mode support
 */
class PhotoGridViewHolder(
    private val binding: ItemPhotoBinding,
    private val itemSizeProvider: () -> Int,
    private val onPhotoClick: (Photo) -> Unit,
    private val onPhotoLongClick: (Int) -> Unit,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: (Int) -> Boolean,
    private val config: ViewHolderConfig = ViewHolderConfig.DEFAULT
) : RecyclerView.ViewHolder(binding.root) {

    private var currentPhoto: Photo? = null
    private var isClickProcessing = false

    fun bind(photo: Photo, position: Int) {
        currentPhoto = photo
        isClickProcessing = false

        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(position)

        loadImage(photo)
        updateSelectionUI(isSelectionMode, isSelected, photo)

        // Only apply size if parent has not already set it via layout params
        if (config.fixedSize) {
            binding.root.layoutParams.width = config.itemSize
            binding.root.layoutParams.height = config.itemSize
        }

        binding.photoContainer.setOnClickListener {
            if (isClickProcessing && config.useClickProcessing) {
                isClickProcessing = false
                return@setOnClickListener
            }
            onPhotoClick(photo)
        }

        binding.photoContainer.setOnLongClickListener {
            if (config.useClickProcessing) {
                isClickProcessing = true
            }
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPhotoLongClick(pos)
            }
            true
        }
    }

    fun updateSelectionState(position: Int) {
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider(position)
        val photo = currentPhoto ?: return
        updateSelectionUI(isSelectionMode, isSelected, photo)
    }

    private fun loadImage(photo: Photo) {
        val itemSize = itemSizeProvider()
        val ctx = binding.ivPhoto.context

        val thumbFile = photo.thumbnailPath?.let { File(it) }
        val loadUri = if (thumbFile?.exists() == true) thumbFile else photo.uri

        val requestBuilder = Glide.with(ctx)
            .load(loadUri)
            .placeholder(R.drawable.ic_photo_placeholder)
            .error(R.drawable.ic_photo_error)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .skipMemoryCache(false)
            .dontAnimate()

        if (itemSize > 0) {
            requestBuilder.override(itemSize, itemSize)
        }

        requestBuilder
            .addListener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e("PhotoGrid", "JXL/thumb load failed for ${photo.uri} mime=${photo.mimeType}", e)
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.d("PhotoGrid", "JXL/thumb loaded OK ${photo.uri} mime=${photo.mimeType} from=$dataSource")
                    return false
                }
            })
            .into(binding.ivPhoto)
    }

    private fun updateSelectionUI(isSelectionMode: Boolean, isSelected: Boolean, photo: Photo) {
        if (isSelectionMode) {
            binding.ivSelected.visibility = View.VISIBLE
            binding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivSelected.setImageResource(
                if (isSelected) R.drawable.ic_selected_filled else R.drawable.ic_selected
            )
            binding.ivPhoto.alpha = if (isSelected) 0.7f else 1.0f
            // Hide status indicators during selection
            binding.ivFavorite.visibility = View.GONE
            binding.ivVideoIndicator.visibility = View.GONE
            binding.tvFormatTag.visibility = View.GONE
            binding.ivHdrTag.visibility = View.GONE
            binding.tvExpirationTag.visibility = View.GONE
        } else {
            binding.ivSelected.visibility = View.GONE
            binding.selectionOverlay.visibility = View.GONE
            binding.ivPhoto.alpha = 1.0f

            // Show status indicators based on config
            if (config.showFavorite) {
                binding.ivFavorite.visibility = if (photo.isFavorite) View.VISIBLE else View.GONE
            } else {
                binding.ivFavorite.visibility = View.GONE
            }

            if (config.showVideoIndicator) {
                binding.ivVideoIndicator.visibility = if (photo.isVideo) View.VISIBLE else View.GONE
            } else {
                binding.ivVideoIndicator.visibility = View.GONE
            }

            if (config.showFormatTag && photo.isRaw) {
                binding.tvFormatTag.visibility = View.VISIBLE
                binding.tvFormatTag.text = "RAW"
            } else {
                binding.tvFormatTag.visibility = View.GONE
            }

            if (config.showHdrTag && photo.isHdr) {
                binding.ivHdrTag.visibility = View.VISIBLE
            } else {
                binding.ivHdrTag.visibility = View.GONE
            }

            if (config.showExpirationTag && photo.dateExpiration > 0) {
                val remainingDays = (photo.dateExpiration - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)
                when {
                    remainingDays > 1 -> binding.tvExpirationTag.text = "剩${remainingDays}天"
                    remainingDays == 1L -> binding.tvExpirationTag.text = "明天删除"
                    remainingDays == 0L -> binding.tvExpirationTag.text = "今天删除"
                    else -> binding.tvExpirationTag.text = "已过期"
                }
                binding.tvExpirationTag.visibility = View.VISIBLE
            } else {
                binding.tvExpirationTag.visibility = View.GONE
            }
        }
    }

    data class ViewHolderConfig(
        val fixedSize: Boolean = false,
        val itemSize: Int = 0,
        val useClickProcessing: Boolean = true,
        val showFavorite: Boolean = true,
        val showVideoIndicator: Boolean = true,
        val showFormatTag: Boolean = true,
        val showExpirationTag: Boolean = false,
        val showHdrTag: Boolean = true
    ) {
        companion object {
            val DEFAULT = ViewHolderConfig()
        }
    }
}