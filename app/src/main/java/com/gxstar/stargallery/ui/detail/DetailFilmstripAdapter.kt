package com.gxstar.stargallery.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.ItemDetailFilmstripBinding
import java.io.File
import kotlin.math.roundToInt

/**
 * 详情页底部胶片条（仿 iOS 相册）。
 *
 * - 单项高度固定 44dp，宽度按图片宽高比自适应（裁剪在 0.62~1.55 之间，避免极端长条）
 * - 缩略图优先用 ThumbnailManager 生成的缓存文件，没有再退回 content uri
 * - 当前项只用一个 1.5dp 细描边定位，不做缩放/放大（iOS 也是等尺寸滚动）
 */
class DetailFilmstripAdapter : ListAdapter<Photo, DetailFilmstripAdapter.VH>(DIFF) {

    private var selectedId: Long = -1L

    /** 点击回调：参数为适配器位置，由 Fragment 直接 setCurrentItem */
    var onItemClick: ((Int) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDetailFilmstripBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val photo = getItem(position)
        holder.bind(photo, photo.id == selectedId)
    }

    /** 切换当前项：只刷新两条，不整体重绑 */
    fun setSelected(photoId: Long) {
        if (photoId == selectedId) return
        val previousId = selectedId
        selectedId = photoId
        indexOf(previousId)?.let { notifyItemChanged(it) }
        indexOf(photoId)?.let { notifyItemChanged(it) }
    }

    private fun indexOf(id: Long): Int? {
        if (id <= 0) return null
        val index = currentList.indexOfFirst { it.id == id }
        return if (index >= 0) index else null
    }

    inner class VH(private val binding: ItemDetailFilmstripBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onItemClick?.invoke(position)
            }
        }

        fun bind(photo: Photo, selected: Boolean) {
            val ctx = binding.root.context
            val heightPx = (ITEM_HEIGHT_DP * ctx.resources.displayMetrics.density).roundToInt()
            val widthPx = widthFor(photo, heightPx)

            val params = binding.root.layoutParams
            if (params.width != widthPx) {
                params.width = widthPx
                binding.root.layoutParams = params
            }

            binding.selectionRing.visibility =
                if (selected) android.view.View.VISIBLE else android.view.View.INVISIBLE

            val thumbFile = photo.thumbnailPath?.let { File(it) }
            val model: Any = if (thumbFile?.exists() == true) thumbFile else photo.uri

            Glide.with(ctx)
                .load(model)
                .centerCrop()
                .override(widthPx, heightPx)
                .placeholder(R.color.card_secondary_bg)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .dontAnimate()
                .into(binding.ivThumb)
        }
    }

    companion object {
        const val ITEM_HEIGHT_DP = 44f
        private const val MIN_RATIO = 0.62f
        private const val MAX_RATIO = 1.55f

        private val DIFF = object : DiffUtil.ItemCallback<Photo>() {
            override fun areItemsTheSame(oldItem: Photo, newItem: Photo) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Photo, newItem: Photo) = oldItem == newItem
        }

        /** 按宽高比算单项宽度；90/270 度旋转的图需要交换宽高 */
        fun widthFor(photo: Photo, heightPx: Int): Int {
            val rotated = photo.orientation == 90 || photo.orientation == 270
            val w = if (rotated) photo.height else photo.width
            val h = if (rotated) photo.width else photo.height
            val ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f
            return (heightPx * ratio.coerceIn(MIN_RATIO, MAX_RATIO)).roundToInt()
        }
    }
}
