package com.example.wcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 1f // 1px
        style = Paint.Style.STROKE
    }

    private var cropRect = RectF()

    /**
     * Set crop rectangle relative to view dimensions (0.0 to 1.0)
     */
    fun setCropRect(left: Float, top: Float, right: Float, bottom: Float) {
        cropRect.set(left, top, right, bottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.isEmpty) return

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(
            cropRect.left * w,
            cropRect.top * h,
            cropRect.right * w,
            cropRect.bottom * h,
            paint
        )
    }
}