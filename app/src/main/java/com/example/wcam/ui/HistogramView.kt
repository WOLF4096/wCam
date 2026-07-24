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

    // Smoothed data for rendering
    private var rSmooth = FloatArray(256)
    private var gSmooth = FloatArray(256)
    private var bSmooth = FloatArray(256)
    
    private val temporalAlpha = 0.65f // 0.0 (no smoothing) to 1.0 (frozen)
    
    private val path = Path()

    private fun createPaint(colorInt: Int): Paint = Paint().apply {
        color = colorInt
        style = Paint.Style.FILL
        isAntiAlias = true
        // Screen mode makes overlapping RGB areas appear brighter/whiter (grey)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    fun updateData(r: IntArray, g: IntArray, b: IntArray) {
        // 1. Temporal Smoothing (EMA)
        for (i in 0 until 256) {
            rSmooth[i] = rSmooth[i] * temporalAlpha + r[i].toFloat() * (1f - temporalAlpha)
            gSmooth[i] = gSmooth[i] * temporalAlpha + g[i].toFloat() * (1f - temporalAlpha)
            bSmooth[i] = bSmooth[i] * temporalAlpha + b[i].toFloat() * (1f - temporalAlpha)
        }
        
        // 2. Spatial Smoothing (3-tap box filter)
        rSmooth = applySpatialSmoothing(rSmooth)
        gSmooth = applySpatialSmoothing(gSmooth)
        bSmooth = applySpatialSmoothing(bSmooth)

        postInvalidateOnAnimation()
    }

    private fun applySpatialSmoothing(data: FloatArray): FloatArray {
        val out = FloatArray(256)
        for (i in 0 until 256) {
            val prev = if (i > 0) data[i - 1] else data[i]
            val next = if (i < 255) data[i + 1] else data[i]
            out[i] = (prev + data[i] + next) / 3f
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        // Use a common max for all channels to maintain relative scale
        var maxVal = 10f
        for (i in 0 until 256) {
            maxVal = maxOf(maxVal, rSmooth[i], gSmooth[i], bSmooth[i])
        }

        // Draw fills with SCREEN mode
        drawChannel(canvas, rSmooth, rPaint, maxVal, w, h, fill = true)
        drawChannel(canvas, gSmooth, gPaint, maxVal, w, h, fill = true)
        drawChannel(canvas, bSmooth, bPaint, maxVal, w, h, fill = true)
        
        // Draw strokes on top
        drawChannel(canvas, rSmooth, strokePaint, maxVal, w, h, fill = false)
        drawChannel(canvas, gSmooth, strokePaint, maxVal, w, h, fill = false)
        drawChannel(canvas, bSmooth, strokePaint, maxVal, w, h, fill = false)
    }

    private fun drawChannel(canvas: Canvas, data: FloatArray, paint: Paint, maxVal: Float, w: Float, h: Float, fill: Boolean) {
        path.reset()
        
        val step = w / 255f
        for (i in 0 until 256) {
            val x = i * step
            // Non-linear scaling: Power of 1.2 to suppress noise floor and highlight peaks
            val ratio = data[i] / maxVal
            val normalized = Math.pow(ratio.toDouble(), 1.2).toFloat()
            
            val pixelH = (normalized * (h - 12f)).coerceAtLeast(0f)
            val y = h - pixelH - 4f
            
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        
        if (fill) {
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            canvas.drawPath(path, paint)
        } else {
            canvas.drawPath(path, paint)
        }
    }
}
