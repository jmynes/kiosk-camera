package com.kioskcamera

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
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
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1f, 8f)
                clampPan()
                applyTransform()
                return true
            }
        }).also {
        it.isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.05f) {
                    animateToTransform(1f, 0f, 0f)
                } else {
                    val targetScale = 3f
                    // Pan so the tapped point stays roughly in place
                    val targetPanX = (width / 2f - e.x) * (targetScale - 1) / targetScale
                    val targetPanY = (height / 2f - e.y) * (targetScale - 1) / targetScale
                    animateToTransform(targetScale, targetPanX, targetPanY)
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

    private fun animateToTransform(targetScale: Float, targetPanX: Float, targetPanY: Float) {
        zoomAnimator?.cancel()
        val startScale = scaleFactor
        val startPanX = panX
        val startPanY = panY

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                scaleFactor = startScale + (targetScale - startScale) * t
                panX = startPanX + (targetPanX - startPanX) * t
                panY = startPanY + (targetPanY - startPanY) * t
                applyTransform()
            }
            start()
        }
    }

    fun resetTransform() {
        zoomAnimator?.cancel()
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
        if (scaleFactor <= 1f) {
            panX = 0f
            panY = 0f
            return
        }
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
                zoomAnimator?.cancel()
                scroller.forceFinished(true)
                removeCallbacks(flingRunnable)
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    if (scaleFactor > 1.05f && !scaleDetector.isInProgress) {
                        panX += x - lastTouchX
                        panY += y - lastTouchY
                        clampPan()
                        applyTransform()
                    }
                    lastTouchX = x
                    lastTouchY = y
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

        if (scaleFactor > 1.05f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        return true
    }
}
