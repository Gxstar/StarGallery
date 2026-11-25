package com.gxstar.stargallery.ui.detail

import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.data.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewPager2 的照片适配器
 */
class PhotoPagerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val onEdgeSwipe: ((isSwipeRight: Boolean) -> Unit)? = null,
    private val viewPagerSwipeController: ((enabled: Boolean) -> Unit)? = null,
    private val onSingleTap: (() -> Unit)? = null,
    private val hdrDisplayEnabled: () -> Boolean = { true }
) : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

    private val photos = mutableListOf<Photo>()

    // M4 修复：以 photoId（而非 position）为 key。删除照片后列表位置会偏移，
    // 用 position 做 key 会让删除点之后的 ViewHolder 错位；photoId 恒定不变。
    private val viewHolders = mutableMapOf<Long, PhotoPageViewHolder>()

    /** 当前可见的页面位置 */
    var activePosition: Int = -1
        set(value) {
            if (field != value) {
                photos.getOrNull(field)?.id?.let { viewHolders[it]?.isActive = false }
                field = value
                photos.getOrNull(value)?.id?.let { viewHolders[it]?.isActive = true }
            }
        }

    /**
     * 提交照片列表，使用 DiffUtil 智能更新
     * DiffUtil 计算放在 Dispatchers.Default，避免主线程阻塞
     *
     * @param onCommit 列表真正提交到 adapter 后（主线程）执行的回调
     */
    fun submitList(newPhotos: List<Photo>, onCommit: (() -> Unit)? = null) {
        // 保存旧列表副本，用于 DiffUtil 比较
        val oldPhotos = photos.toList()
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = oldPhotos.size
            override fun getNewListSize() = newPhotos.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return oldPhotos[oldPos].id == newPhotos[newPos].id
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                // 对于已显示的当前页面，忽略非关键字段变化，避免重新加载图片
                val old = oldPhotos[oldPos]
                val new = newPhotos[newPos]
                return old.id == new.id &&
                       old.uri == new.uri &&
                       old.width == new.width &&
                       old.height == new.height &&
                       old.mimeType == new.mimeType
            }
        }
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            withContext(Dispatchers.Main) {
                photos.clear()
                photos.addAll(newPhotos)
                diffResult.dispatchUpdatesTo(this@PhotoPagerAdapter)
                // 清理已不在新列表中的 ViewHolder 引用（DiffUtil 移除的页面可能未走 onViewRecycled）
                val validIds = newPhotos.map { it.id }.toSet()
                viewHolders.entries.removeAll { it.key !in validIds }
                onCommit?.invoke()
            }
        }
    }
    
    /**
     * 移除指定位置的照片
     * 使用 notifyItemRemoved 实现更平滑的动画效果
     */
    fun removePhotoAt(position: Int) {
        if (position in photos.indices) {
            val removed = photos.removeAt(position)
            // 同步移除被删照片的 ViewHolder 引用，避免残留
            viewHolders.remove(removed.id)?.recycle()
            notifyItemRemoved(position)
            // 通知后续位置变化
            notifyItemRangeChanged(position, photos.size - position)
        }
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
        val viewHolder = PhotoPageViewHolder.create(
            parent,
            onEdgeSwipe,
            viewPagerSwipeController,
            onSingleTap,
            hdrDisplayEnabled
        )
        return ViewHolder(viewHolder)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val photo = photos[position]
        viewHolders[photo.id] = holder.viewHolder
        holder.viewHolder.isActive = (position == activePosition)
        holder.viewHolder.bind(photo, lifecycleOwner.lifecycleScope)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        // 页面被回收时重置缩放
        holder.viewHolder.resetZoom()
        holder.viewHolder.recycle()
        // 用对象引用反查 key，避免依赖可能因删除而失效的 position
        viewHolders.entries.firstOrNull { it.value === holder.viewHolder }?.let { entry ->
            viewHolders.remove(entry.key)
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
        photos.getOrNull(activePosition)?.id?.let { viewHolders[it]?.let { vh -> return vh } }
        // 兜底：activePosition 非法时取第一个未回收的
        return viewHolders.values.firstOrNull()
    }

    /**
     * 获取指定位置的 ViewHolder
     */
    fun getViewHolder(position: Int): PhotoPageViewHolder? {
        return photos.getOrNull(position)?.id?.let { viewHolders[it] }
    }

    class ViewHolder(val viewHolder: PhotoPageViewHolder) : RecyclerView.ViewHolder(viewHolder.binding.root)
}
