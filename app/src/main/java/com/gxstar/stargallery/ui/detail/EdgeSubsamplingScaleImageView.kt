package com.gxstar.stargallery.ui.detail

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.abs

/**
 * 支持边缘滑动检测的 SubsamplingScaleImageView
 * 当图片放大后滑动到边缘时，通知外部可以切换页面
 */
class EdgeSubsamplingScaleImageView @JvmOverloads constructor(
    context: Context,
    attr: AttributeSet? = null
) : SubsamplingScaleImageView(context, attr) {

    // 边缘检测回调
    var onEdgeSwipeListener: OnEdgeSwipeListener? = null
    
    // 触摸起始点
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    
    // 边缘检测阈值（像素）
    private val edgeThreshold = dpToPx(5)
    
    // 滑动距离阈值
    private val swipeThreshold = dpToPx(10)
    
    // 是否已经通知边缘滑动
    private var hasNotifiedEdgeSwipe = false
    
    // 上一次检测到的边缘方向
    private var lastEdgeDirection = 0  // 0: 无, 1: 左边缘, 2: 右边缘

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                hasNotifiedEdgeSwipe = false
                lastEdgeDirection = 0
            }
            
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalDx = event.x - downX
                
                // 检测是否为水平滑动
                if (abs(totalDx) > swipeThreshold) {
                    // 检测边缘状态
                    val edgeInfo = getEdgeInfo()
                    
                    // 向右滑动（手指向右移动，想看左边内容或切换到上一张）
                    if (dx > 0 && edgeInfo.isAtLeftEdge) {
                        if (lastEdgeDirection != 1 && !hasNotifiedEdgeSwipe) {
                            lastEdgeDirection = 1
                            hasNotifiedEdgeSwipe = true
                            onEdgeSwipeListener?.onEdgeSwipe(true)  // true = 向右滑 = 上一张
                        }
                    }
                    // 向左滑动（手指向左移动，想看右边内容或切换到下一张）
                    else if (dx < 0 && edgeInfo.isAtRightEdge) {
                        if (lastEdgeDirection != 2 && !hasNotifiedEdgeSwipe) {
                            lastEdgeDirection = 2
                            hasNotifiedEdgeSwipe = true
                            onEdgeSwipeListener?.onEdgeSwipe(false)  // false = 向左滑 = 下一张
                        }
                    }
                }
                
                lastX = event.x
                lastY = event.y
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hasNotifiedEdgeSwipe = false
                lastEdgeDirection = 0
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    /**
     * 获取边缘状态信息
     */
    private fun getEdgeInfo(): EdgeInfo {
        if (!isReady) return EdgeInfo(false, false)
        
        val scale = scale
        val minScale = minScale
        
        // 如果没有放大，认为两边都是边缘
        if (scale <= minScale * 1.01f) {
            return EdgeInfo(true, true)
        }
        
        // 使用 SubsamplingScaleImageView 的 center 来判断位置
        val center = center ?: return EdgeInfo(false, false)
        val sWidth = sWidth.toFloat()
        
        if (sWidth <= 0) return EdgeInfo(false, false)
        
        // 计算图片内容在视图中的边界
        // center 是图片坐标系中的中心点
        // viewWidth 是视图宽度
        val viewWidth = width.toFloat()
        
        // 图片内容左边在视图中的位置
        val contentLeft = (viewWidth - sWidth * scale) / 2f + (center.x - sWidth / 2f) * scale
        // 图片内容右边在视图中的位置
        val contentRight = contentLeft + sWidth * scale
        
        // 判断是否到达左边缘：图片左边 >= 视图左边
        val isAtLeftEdge = contentLeft >= -edgeThreshold
        
        // 判断是否到达右边缘：图片右边 <= 视图右边
        val isAtRightEdge = contentRight <= viewWidth + edgeThreshold
        
        return EdgeInfo(isAtLeftEdge, isAtRightEdge)
    }
    
    private data class EdgeInfo(
        val isAtLeftEdge: Boolean,
        val isAtRightEdge: Boolean
    )
    
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