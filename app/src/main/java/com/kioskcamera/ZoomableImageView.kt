package com.kioskcamera

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val scroller = OverScroller(context)

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1f, 8f)
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scaleFactor < 1.05f) resetTransform()
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.05f) {
                    resetTransform()
                } else {
                    scaleFactor = 3f
                    val centerX = width / 2f
                    val centerY = height / 2f
                    panX = (centerX - e.x) * (scaleFactor - 1)
                    panY = (centerY - e.y) * (scaleFactor - 1)
                    clampPan()
                    applyTransform()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (scaleFactor <= 1.05f) return false

                val maxPanX = (width * (scaleFactor - 1) / 2).toInt()
                val maxPanY = (height * (scaleFactor - 1) / 2).toInt()

                scroller.fling(
                    panX.toInt(), panY.toInt(),
                    velocityX.toInt(), velocityY.toInt(),
                    -maxPanX, maxPanX,
                    -maxPanY, maxPanY,
                    40, 40
                )
                postOnAnimation(flingRunnable)
                return true
            }
        })

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                panX = scroller.currX.toFloat()
                panY = scroller.currY.toFloat()
                applyTransform()
                postOnAnimation(this)
            }
        }
    }

    fun resetTransform() {
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
    }

    private fun applyTransform() {
        scaleX = scaleFactor
        scaleY = scaleFactor
        translationX = panX
        translationY = panY
    }

    private fun clampPan() {
        val maxPanX = width * (scaleFactor - 1) / 2
        val maxPanY = height * (scaleFactor - 1) / 2
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (scaleFactor <= 1.05f) return false
        val maxPanX = width * (scaleFactor - 1) / 2
        return if (direction > 0) panX > -maxPanX + 1
        else panX < maxPanX - 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                removeCallbacks(flingRunnable)
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleFactor > 1.05f && !scaleDetector.isInProgress) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        panX += x - lastTouchX
                        panY += y - lastTouchY
                        clampPan()
                        applyTransform()
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val newIdx = event.actionIndex
                activePointerId = event.getPointerId(newIdx)
                lastTouchX = event.getX(newIdx)
                lastTouchY = event.getY(newIdx)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIdx = event.actionIndex
                if (event.getPointerId(upIdx) == activePointerId) {
                    val newIdx = if (upIdx == 0) 1 else 0
                    if (newIdx < event.pointerCount) {
                        activePointerId = event.getPointerId(newIdx)
                        lastTouchX = event.getX(newIdx)
                        lastTouchY = event.getY(newIdx)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        // Request parent (ViewPager2) not to intercept when we're zoomed and can pan
        if (scaleFactor > 1.05f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        return true
    }
}
