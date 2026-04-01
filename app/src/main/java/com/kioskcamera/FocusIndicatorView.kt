package com.kioskcamera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class FocusIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var animAlpha = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun showAt(x: Float, y: Float) {
        cx = x
        cy = y

        ValueAnimator.ofFloat(80f, 50f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                radius = it.animatedValue as Float
                animAlpha = 255
                invalidate()
            }
            start()
        }

        postDelayed({
            ValueAnimator.ofInt(255, 0).apply {
                duration = 500
                addUpdateListener {
                    animAlpha = it.animatedValue as Int
                    invalidate()
                }
                start()
            }
        }, 800)
    }

    override fun onDraw(canvas: Canvas) {
        if (animAlpha > 0) {
            paint.alpha = animAlpha
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}
