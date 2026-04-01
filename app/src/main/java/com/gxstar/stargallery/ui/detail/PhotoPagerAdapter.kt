package com.gxstar.stargallery.ui.detail

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo

/**
 * ViewPager2 的照片适配器
 */
class PhotoPagerAdapter(
    private val onEdgeSwipe: ((isSwipeRight: Boolean) -> Unit)? = null,
    private val viewPagerSwipeController: ((enabled: Boolean) -> Unit)? = null,
    private val onSingleTap: (() -> Unit)? = null
) : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

    private val photos = mutableListOf<Photo>()
    private val viewHolders = mutableMapOf<Int, PhotoPageViewHolder>()
    
    /**
     * 提交照片列表
     */
    fun submitList(newPhotos: List<Photo>) {
        photos.clear()
        photos.addAll(newPhotos)
        notifyDataSetChanged()
    }
    
    /**
     * 获取指定位置的照片
     */
    fun getPhoto(position: Int): Photo? {
        return if (position in photos.indices) photos[position] else null
    }
    
    /**
     * 获取照片总数
     */
    fun getPhotoCount(): Int = photos.size
    
    /**
     * 获取照片的位置
     */
    fun getPhotoPosition(photoId: Long): Int {
        return photos.indexOfFirst { it.id == photoId }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewHolder = PhotoPageViewHolder.create(parent, onEdgeSwipe, viewPagerSwipeController, onSingleTap)
        return ViewHolder(viewHolder)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        viewHolders[position] = holder.viewHolder
        holder.viewHolder.bind(photos[position])
    }
    
    override fun onViewRecycled(holder: ViewHolder) {
        holder.viewHolder.recycle()
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            viewHolders.remove(position)
        }
    }
    
    override fun getItemCount(): Int = photos.size
    
    /**
     * 清理所有资源
     */
    fun clear() {
        viewHolders.values.forEach { it.recycle() }
        viewHolders.clear()
        photos.clear()
    }
    
    /**
     * 获取当前可见的 ViewHolder
     */
    fun getCurrentViewHolder(): PhotoPageViewHolder? {
        // 找到第一个未回收的 ViewHolder
        return viewHolders.values.firstOrNull()
    }
    
    class ViewHolder(val viewHolder: PhotoPageViewHolder) : RecyclerView.ViewHolder(viewHolder.binding.root)
}
