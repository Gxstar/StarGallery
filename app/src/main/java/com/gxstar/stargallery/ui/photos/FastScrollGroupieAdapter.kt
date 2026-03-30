package com.gxstar.stargallery.ui.photos

import android.view.ViewGroup
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import com.xwray.groupie.Group
import com.xwray.groupie.GroupieAdapter

/**
 * 支持快速滚动的 GroupieAdapter
 * 实现FastScrollRecyclerView.SectionedAdapter接口，在滚动时显示日期标题
 */
class FastScrollGroupieAdapter : GroupieAdapter(), FastScrollRecyclerView.SectionedAdapter {

    override fun getSectionName(position: Int): String {
        // 从当前位置向上查找最近的日期标题
        for (i in position downTo 0) {
            val item = getItem(i)
            if (item is DateHeaderItem) {
                return item.date
            }
        }
        return ""
    }
}
