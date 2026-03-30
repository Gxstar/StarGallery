package com.gxstar.stargallery.ui.detail

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewParent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.abs

/**
 * 支持边缘滑动检测的 SubsamplingScaleImageView
 * 当图片放大后滑动到边缘时，允许 ViewPager2 接管滑动
 */
class EdgeSubsamplingScaleImageView @JvmOverloads constructor(
    context: Context,
    attr: AttributeSet? = null
) : SubsamplingScaleImageView(context, attr) {

    // 边缘检测回调
    var onEdgeSwipeListener: OnEdgeSwipeListener? = null
    
    // 触摸起始点
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    
    // 是否正在检测边缘滑动
    private var isEdgeSwiping = false
    
    // 边缘检测阈值（像素）
    private val edgeThreshold = dpToPx(10)
    
    // 滑动距离阈值
    private val swipeThreshold = dpToPx(8)
    
    // 是否启用边缘滑动
    var isEdgeSwipeEnabled = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isEdgeSwiping = false
            }
            
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                
                // 检测是否为水平滑动
                if (abs(totalDx) > abs(totalDy) && abs(totalDx) > swipeThreshold) {
                    // 检测是否到达边缘
                    if (isAtEdge(totalDx)) {
                        isEdgeSwiping = true
                        // 通知父控件（ViewPager2）可以接管滑动
                        onEdgeSwipeListener?.onEdgeSwipe(totalDx > 0)
                        parent.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                }
                
                lastX = event.x
                lastY = event.y
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isEdgeSwiping = false
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    /**
     * 检测是否到达图片边缘
     * @param swipeDirection 滑动方向，正数表示向右滑（显示左边内容），负数表示向左滑（显示右边内容）
     */
    private fun isAtEdge(swipeDirection: Float): Boolean {
        if (!isReady || !isEdgeSwipeEnabled) return false
        
        // 获取当前缩放比例
        val scale = scale
        val minScale = minScale
        
        // 如果没有放大，允许 ViewPager2 接管
        if (scale <= minScale * 1.01f) {
            return true
        }
        
        // 获取图片的可视区域
        val center = center ?: return false
        val sCenter = PointF(center.x, center.y)
        
        // 获取图片的边界
        val sWidth = sWidth.toFloat()
        val sHeight = sHeight.toFloat()
        
        if (sWidth <= 0 || sHeight <= 0) return false
        
        // 计算当前显示的图片区域
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        // 计算图片在视图中的位置
        val leftEdge = (viewWidth - sWidth * scale) / 2f + center.x * scale - viewWidth / 2f
        val rightEdge = leftEdge + sWidth * scale
        
        // 向右滑动（想看左边内容）时，检查是否已到达左边缘
        if (swipeDirection > 0) {
            // 左边缘检测：图片左边是否贴近视图左边
            if (leftEdge >= -edgeThreshold) {
                return true
            }
        }
        
        // 向左滑动（想看右边内容）时，检查是否已到达右边缘
        if (swipeDirection < 0) {
            // 右边缘检测：图片右边是否贴近视图右边
            if (rightEdge <= viewWidth + edgeThreshold) {
                return true
            }
        }
        
        return false
    }
    
    private fun dpToPx(dp: Int): Float {
        return dp * resources.displayMetrics.density
    }
    
    /**
     * 边缘滑动监听接口
     */
    interface OnEdgeSwipeListener {
        /**
         * 当检测到边缘滑动时回调
         * @param isSwipeRight true 表示向右滑动（切换到上一张），false 表示向左滑动（切换到下一张）
         */
        fun onEdgeSwipe(isSwipeRight: Boolean)
    }
}
