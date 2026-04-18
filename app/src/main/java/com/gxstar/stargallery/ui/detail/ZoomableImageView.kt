package com.gxstar.stargallery.ui.detail

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 支持双击缩放和捏合缩放的 ImageView
 * 用于显示 AVIF/HEIC/HDR 等现代图片格式
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    interface OnTapListener {
        fun onSingleTap()
    }

    var onTapListener: OnTapListener? = null

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val startPoint = PointF()
    private val midPoint = PointF()

    private var mode = Mode.NONE
    private var oldDist = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private var originalWidth = 0f
    private var originalHeight = 0f
    private var viewWidth = 0
    private var viewHeight = 0

    private var isInitialized = false
    private var pendingFitImage = false

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private enum class Mode {
        NONE, DRAG, ZOOM
    }

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        isInitialized = true

        if (drawable != null) {
            fitImageToView()
        } else {
            pendingFitImage = true
        }
    }

    private fun fitImageToView() {
        val drawable = drawable ?: return
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()

        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        originalWidth = imageWidth
        originalHeight = imageHeight

        // 计算让图片完全显示的缩放比例（与 SubsamplingScaleImageView 一致）
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        minScale = min(scaleX, scaleY)

        matrix.reset()
        matrix.postScale(minScale, minScale)
        matrix.postTranslate(
            (viewWidth - imageWidth * minScale) / 2,
            (viewHeight - imageHeight * minScale) / 2
        )

        imageMatrix = matrix
        currentScale = minScale
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)

        if (drawable != null && isInitialized) {
            fitImageToView()
            pendingFitImage = false
        } else if (drawable != null) {
            pendingFitImage = true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                startPoint.set(event.x, event.y)
                mode = Mode.DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(midPoint, event)
                    mode = Mode.ZOOM
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG && currentScale > minScale * 1.01f) {
                    matrix.set(savedMatrix)
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y
                    matrix.postTranslate(dx, dy)
                    constrainMatrix()
                } else if (mode == Mode.ZOOM) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        val scaleFactor = newDist / oldDist
                        val newScale = currentScale * scaleFactor

                        if (newScale in minScale..maxScale) {
                            matrix.set(savedMatrix)
                            matrix.postScale(scaleFactor, scaleFactor, midPoint.x, midPoint.y)
                            currentScale = newScale
                        } else if (newScale < minScale) {
                            // 允许缩小但限制在 minScale
                            val allowedScale = minScale / currentScale
                            matrix.set(savedMatrix)
                            matrix.postScale(allowedScale, allowedScale, midPoint.x, midPoint.y)
                            currentScale = minScale
                        } else if (newScale > maxScale) {
                            // 限制最大缩放
                            val allowedScale = maxScale / currentScale
                            matrix.set(savedMatrix)
                            matrix.postScale(allowedScale, allowedScale, midPoint.x, midPoint.y)
                            currentScale = maxScale
                        }
                        constrainMatrix()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.NONE
            }
        }

        imageMatrix = matrix
        return true
    }

    private fun constrainMatrix() {
        val values = FloatArray(9)
        matrix.getValues(values)

        val scale = values[Matrix.MSCALE_X]
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]

        val drawable = drawable ?: return
        val imageWidth = drawable.intrinsicWidth * scale
        val imageHeight = drawable.intrinsicHeight * scale

        var dx = 0f
        var dy = 0f

        if (imageWidth <= viewWidth) {
            dx = (viewWidth - imageWidth) / 2 - transX
        } else {
            if (transX > 0) dx = -transX
            else if (transX + imageWidth < viewWidth) dx = viewWidth - transX - imageWidth
        }

        if (imageHeight <= viewHeight) {
            dy = (viewHeight - imageHeight) / 2 - transY
        } else {
            if (transY > 0) dy = -transY
            else if (transY + imageHeight < viewHeight) dy = viewHeight - transY - imageHeight
        }

        matrix.postTranslate(dx, dy)
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        point.set(
            (event.getX(0) + event.getX(1)) / 2,
            (event.getY(0) + event.getY(1)) / 2
        )
    }

    fun isZoomedOut(): Boolean = currentScale <= minScale * 1.01f

    fun resetZoom() {
        matrix.reset()
        fitImageToView()
        imageMatrix = matrix
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor

            when {
                newScale < minScale -> {
                    // 恢复到最小
                }
                newScale > maxScale -> {
                    // 限制最大
                }
                else -> {
                    matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    currentScale = newScale
                }
            }
            constrainMatrix()
            imageMatrix = matrix
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTapListener?.onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > minScale * 1.01f) {
                // 已经放大了，缩小回原始大小
                resetZoom()
            } else {
                // 放大到 2 倍（相对于 fit 状态）
                val targetScale = minScale * 2f
                matrix.postScale(
                    targetScale / currentScale,
                    targetScale / currentScale,
                    e.x,
                    e.y
                )
                currentScale = targetScale
                constrainMatrix()
                imageMatrix = matrix
            }
            return true
        }
    }
}