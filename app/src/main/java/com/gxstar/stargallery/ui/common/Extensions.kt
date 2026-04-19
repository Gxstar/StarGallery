package com.gxstar.stargallery.ui.common

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

fun Context.dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

fun Context.showToast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).show()
}
