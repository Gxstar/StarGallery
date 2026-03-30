package com.gxstar.stargallery.ui.photos

import android.view.View
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.ItemDateHeaderBinding
import com.xwray.groupie.viewbinding.BindableItem

class DateHeaderItem(
    val date: String
) : BindableItem<ItemDateHeaderBinding>() {

    override fun getLayout(): Int = R.layout.item_date_header

    override fun initializeViewBinding(view: View): ItemDateHeaderBinding {
        return ItemDateHeaderBinding.bind(view)
    }

    override fun bind(viewBinding: ItemDateHeaderBinding, position: Int) {
        viewBinding.tvDateHeader.text = date
    }

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean {
        return other is DateHeaderItem && other.date == date
    }

    override fun hasSameContentAs(other: com.xwray.groupie.Item<*>): Boolean {
        return other is DateHeaderItem && other.date == date
    }
}
