package com.kioskcamera

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onSingleTapCallback: (() -> Unit)? = null

    private val mMatrix = Matrix()
    private val m = FloatArray(9)

    private var mode = NONE
    private val last = PointF()
    private val start = PointF()

    private var saveScale = 1f
    private var minScale = 1f
    private var maxScale = 8f

    private var viewWidth = 0
    private var viewHeight = 0
    private var origWidth = 0f
    private var origHeight = 0f
    private var oldMeasuredWidth = 0
    private var oldMeasuredHeight = 0

    private var scaleAnimator: ValueAnimator? = null
    private val scroller = OverScroller(context)

    // Manual double-tap detection
    private var lastClickTime = 0L
    private val lastClickPoint = PointF()
    private var downTime = 0L
    private var moved = false
    private val singleTapRunnable = Runnable { onSingleTapCallback?.invoke() }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = ZOOM
                scaleAnimator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleImageBy(detector.focusX, detector.focusY, detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                mode = DRAG
                if (saveScale <= minScale) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }).also {
        it.isQuickScaleEnabled = false
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                mMatrix.getValues(m)
                val dx = scroller.currX - m[Matrix.MTRANS_X]
                val dy = scroller.currY - m[Matrix.MTRANS_Y]
                mMatrix.postTranslate(dx, dy)
                fixTrans()
                imageMatrix = mMatrix
                postOnAnimation(this)
            }
        }
    }

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = mMatrix
    }

    private fun onDoubleTap(x: Float, y: Float) {
        scaleAnimator?.cancel()
        if (saveScale > 1.05f) {
            scaleAnimator = ValueAnimator.ofFloat(saveScale, 1f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    scaleImageTo(width / 2f, height / 2f, it.animatedValue as Float)
                }
                start()
            }
        } else {
            scaleAnimator = ValueAnimator.ofFloat(1f, 3f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    scaleImageTo(x, y, it.animatedValue as Float)
                }
                start()
            }
        }
    }

    private fun doFling(velocityX: Float, velocityY: Float) {
        if (saveScale <= 1.05f) return

        mMatrix.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]

        val contentWidth = origWidth * saveScale
        val contentHeight = origHeight * saveScale

        val minX = (viewWidth - contentWidth).toInt().coerceAtMost(0)
        val maxX = 0.coerceAtLeast((viewWidth - contentWidth).toInt())
        val minY = (viewHeight - contentHeight).toInt().coerceAtMost(0)
        val maxY = 0.coerceAtLeast((viewHeight - contentHeight).toInt())

        scroller.fling(
            transX.toInt(), transY.toInt(),
            velocityX.toInt(), velocityY.toInt(),
            minX, maxX, minY, maxY,
            30, 30
        )
        postOnAnimation(flingRunnable)
    }

    private fun scaleImageTo(pointX: Float, pointY: Float, newScale: Float) {
        val factor = newScale / saveScale
        scaleImageBy(pointX, pointY, factor)
    }

    private fun scaleImageBy(pointX: Float, pointY: Float, scaleFactor: Float) {
        val origScale = saveScale
        var factor = scaleFactor
        saveScale *= factor

        if (saveScale > maxScale) {
            saveScale = maxScale
            factor = maxScale / origScale
        } else if (saveScale < minScale) {
            saveScale = minScale
            factor = minScale / origScale
        }

        if (origWidth * saveScale <= viewWidth || origHeight * saveScale <= viewHeight) {
            mMatrix.postScale(factor, factor, viewWidth / 2f, viewHeight / 2f)
        } else {
            mMatrix.postScale(factor, factor, pointX, pointY)
        }

        fixTrans()
        imageMatrix = mMatrix

        if (saveScale > 1.05f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun fixTrans() {
        mMatrix.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]
        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), origWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), origHeight * saveScale)
        if (fixTransX != 0f || fixTransY != 0f) mMatrix.postTranslate(fixTransX, fixTransY)
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float
        if (contentSize <= viewSize) {
            minTrans = 0f
            maxTrans = viewSize - contentSize
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }
        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) 0f else delta
    }

    fun resetTransform() {
        scaleAnimator?.cancel()
        scroller.forceFinished(true)
        saveScale = 1f
        mMatrix.reset()
        oldMeasuredWidth = 0
        oldMeasuredHeight = 0
        imageMatrix = mMatrix
        requestLayout()
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (saveScale <= 1.05f) return false
        mMatrix.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val contentWidth = origWidth * saveScale
        return if (direction > 0) transX > viewWidth - contentWidth + 1
        else transX < -1
    }

    // Velocity tracking for fling
    private var velocityTracker: android.view.VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (velocityTracker == null) {
            velocityTracker = android.view.VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        val curr = PointF(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaleAnimator?.cancel()
                scroller.forceFinished(true)
                removeCallbacks(flingRunnable)
                last.set(curr)
                start.set(last)
                mode = DRAG
                downTime = System.currentTimeMillis()
                moved = false
                if (saveScale > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && saveScale > 1.05f && !scaleDetector.isInProgress) {
                    val dx = curr.x - last.x
                    val dy = curr.y - last.y

                    if (abs(dx) > 2 || abs(dy) > 2) moved = true

                    val fixTransX = getFixDragTrans(dx, viewWidth.toFloat(), origWidth * saveScale)
                    val fixTransY = getFixDragTrans(dy, viewHeight.toFloat(), origHeight * saveScale)

                    mMatrix.postTranslate(fixTransX, fixTransY)
                    fixTrans()
                    imageMatrix = mMatrix
                }
                if (mode == ZOOM) moved = true
                last.set(curr)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // When a finger lifts during multi-touch, stay in drag mode
                // Update last to remaining finger position to prevent jump
                val upIndex = event.actionIndex
                val remainingIndex = if (upIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    last.set(event.getX(remainingIndex), event.getY(remainingIndex))
                }
            }
            MotionEvent.ACTION_UP -> {
                mode = NONE

                val tapDuration = System.currentTimeMillis() - downTime
                val isTap = !moved && tapDuration < 300

                if (isTap) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < DOUBLE_TAP_DELAY) {
                        removeCallbacks(singleTapRunnable)
                        onDoubleTap(curr.x, curr.y)
                        lastClickTime = 0
                    } else {
                        lastClickTime = now
                        lastClickPoint.set(curr)
                        removeCallbacks(singleTapRunnable)
                        postDelayed(singleTapRunnable, DOUBLE_TAP_DELAY)
                    }
                } else if (saveScale > 1.05f) {
                    // Fling
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    if (abs(vx) > 50 || abs(vy) > 50) {
                        doFling(vx, vy)
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = null
            }
        }

        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        viewHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (oldMeasuredWidth == viewWidth && oldMeasuredHeight == viewHeight
            || viewWidth == 0 || viewHeight == 0) return

        oldMeasuredWidth = viewWidth
        oldMeasuredHeight = viewHeight

        if (saveScale == 1f) {
            val d = drawable ?: return
            val bmWidth = d.intrinsicWidth
            val bmHeight = d.intrinsicHeight
            if (bmWidth == 0 || bmHeight == 0) return

            val scaleX = viewWidth.toFloat() / bmWidth
            val scaleY = viewHeight.toFloat() / bmHeight
            val scale = scaleX.coerceAtMost(scaleY)

            mMatrix.setScale(scale, scale)

            var redundantXSpace = viewWidth - scale * bmWidth
            var redundantYSpace = viewHeight - scale * bmHeight
            redundantXSpace /= 2f
            redundantYSpace /= 2f

            mMatrix.postTranslate(redundantXSpace, redundantYSpace)
            origWidth = viewWidth - 2 * redundantXSpace
            origHeight = viewHeight - 2 * redundantYSpace
            imageMatrix = mMatrix
        }
        fixTrans()
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val CLICK_THRESHOLD = 10f
        private const val DOUBLE_TAP_DELAY = 300L
    }
}
