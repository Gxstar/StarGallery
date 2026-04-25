package com.gxstar.stargallery.ui.trash

import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.widget.RecyclerView

/**
 * 回收站列表的 Key Provider
 */
class TrashItemKeyProvider(
    private val adapter: TrashAdapter
) : ItemKeyProvider<Long>(SCOPE_MAPPED) {

    override fun getKey(position: Int): Long? {
        return adapter.getItemId(position)
    }

    override fun getPosition(key: Long): Int {
        for (i in 0 until adapter.itemCount) {
            val photo = adapter.getItem(i)
            if (photo?.id == key) {
                return i
            }
        }
        return RecyclerView.NO_POSITION
    }
}