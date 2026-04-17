package com.gxstar.stargallery.ui.photos.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * 自定义照片列表 ItemAnimator
 * 提供删除时的滑出/渐隐动画，让操作更丝滑
 */
class PhotoItemAnimator : DefaultItemAnimator() {

    init {
        // 缩短动画时间，保持流畅感
        moveDuration = 200L
        changeDuration = 150L
        addDuration = 150L
        removeDuration = 200L
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        return if (holder.itemViewType == TYPE_PHOTO) {
            animatePhotoRemove(holder)
            true
        } else {
            super.animateRemove(holder)
        }
    }

    /**
     * 照片删除动画：缩小 + 渐隐
     */
    private fun animatePhotoRemove(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView

        view.animate()
            .scaleX(0.8f)
            .scaleY(0.8f)
            .alpha(0f)
            .setDuration(removeDuration)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    dispatchRemoveStarting(holder)
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.alpha = 1f
                    dispatchRemoveFinished(holder)
                }
            })
            .start()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
    }
}
