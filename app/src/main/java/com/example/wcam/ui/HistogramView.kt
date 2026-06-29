package com.example.wcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View

class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rPaint = createPaint(Color.argb(140, 255, 50, 50))
    private val gPaint = createPaint(Color.argb(140, 50, 255, 50))
    private val bPaint = createPaint(Color.argb(140, 50, 50, 255))
    
    private val strokePaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private var rData: IntArray? = null
    private var gData: IntArray? = null
    private var bData: IntArray? = null
    
    private val path = Path()

    private fun createPaint(colorInt: Int): Paint = Paint().apply {
        color = colorInt
        style = Paint.Style.FILL
        isAntiAlias = true
        // Screen mode makes overlapping RGB areas appear brighter/whiter (grey)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    fun updateData(r: IntArray, g: IntArray, b: IntArray) {
        rData = r
        gData = g
        bData = b
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = rData ?: return
        val g = gData ?: return
        val b = bData ?: return
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        // Use a common max for all channels to maintain relative scale
        val maxVal = maxOf(
            r.maxOrNull() ?: 1,
            g.maxOrNull() ?: 1,
            b.maxOrNull() ?: 1
        ).toFloat().coerceAtLeast(10f)

        // Draw fills with SCREEN mode
        drawChannel(canvas, r, rPaint, maxVal, w, h, fill = true)
        drawChannel(canvas, g, gPaint, maxVal, w, h, fill = true)
        drawChannel(canvas, b, bPaint, maxVal, w, h, fill = true)
        
        // Draw strokes on top
        drawChannel(canvas, r, strokePaint, maxVal, w, h, fill = false)
        drawChannel(canvas, g, strokePaint, maxVal, w, h, fill = false)
        drawChannel(canvas, b, strokePaint, maxVal, w, h, fill = false)
    }

    private fun drawChannel(canvas: Canvas, data: IntArray, paint: Paint, maxVal: Float, w: Float, h: Float, fill: Boolean) {
        path.reset()
        
        val step = w / 255f
        for (i in 0 until 256) {
            val x = i * step
            val normalized = Math.sqrt(data[i].toDouble()) / Math.sqrt(maxVal.toDouble())
            val pixelH = (normalized.toFloat() * (h - 12f)).coerceAtLeast(0f)
            val y = h - pixelH - 4f
            
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        
        if (fill) {
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            canvas.drawPath(path, paint)
        } else {
            // Just draw the line on top
            canvas.drawPath(path, paint)
        }
    }
}
