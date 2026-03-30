package com.gxstar.stargallery.ui.photos

import android.view.View
import com.bumptech.glide.Glide
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.xwray.groupie.viewbinding.BindableItem

class PhotoItem(
    val photo: Photo,
    private val isSelectionModeProvider: () -> Boolean,
    private val isSelectedProvider: () -> Boolean,
    private val onPhotoClick: (Photo) -> Unit = {},
    private val onPhotoLongClick: ((Photo) -> Boolean)? = null
) : BindableItem<ItemPhotoBinding>() {

    override fun getLayout(): Int = R.layout.item_photo

    override fun initializeViewBinding(view: View): ItemPhotoBinding {
        return ItemPhotoBinding.bind(view)
    }

    override fun bind(viewBinding: ItemPhotoBinding, position: Int) {
        // 动态获取当前状态
        val isSelectionMode = isSelectionModeProvider()
        val isSelected = isSelectedProvider()
        
        Glide.with(viewBinding.ivPhoto.context)
            .load(photo.uri)
            .centerCrop()
            .into(viewBinding.ivPhoto)

        if (isSelectionMode) {
            // 选择模式：显示 CheckBox 和遮罩
            viewBinding.ivSelected.visibility = View.VISIBLE
            viewBinding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            viewBinding.ivSelected.setImageResource(
                if (isSelected) R.drawable.ic_selected_filled else R.drawable.ic_selected
            )
            // 选择模式下隐藏收藏图标
            viewBinding.ivFavorite.visibility = View.GONE
            // 选中时降低图片透明度，提供视觉反馈
            viewBinding.ivPhoto.alpha = if (isSelected) 0.7f else 1.0f
        } else {
            // 普通模式：隐藏选择相关 UI
            viewBinding.ivSelected.visibility = View.GONE
            viewBinding.selectionOverlay.visibility = View.GONE
            viewBinding.ivPhoto.alpha = 1.0f
            // 显示收藏图标
            viewBinding.ivFavorite.visibility = if (photo.isFavorite) View.VISIBLE else View.GONE
        }

        viewBinding.root.setOnClickListener { onPhotoClick(photo) }
        viewBinding.root.setOnLongClickListener { onPhotoLongClick?.invoke(photo) ?: false }
    }

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean {
        return other is PhotoItem && other.photo.id == photo.id
    }

    override fun hasSameContentAs(other: com.xwray.groupie.Item<*>): Boolean {
        // 只比较 photo，其他状态由 provider 动态获取
        return other is PhotoItem && other.photo == photo
    }
}