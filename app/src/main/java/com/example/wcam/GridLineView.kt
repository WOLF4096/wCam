// GridLineView.kt
package com.example.wcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.parseColor("#80FFFFFF") // 半透明白色，不遮挡画面
        strokeWidth = 3f // 线条粗细
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制两条竖线 (1/3 和 2/3 位置)
        canvas.drawLine(w / 3, 0f, w / 3, h, paint)
        canvas.drawLine(w * 2 / 3, 0f, w * 2 / 3, h, paint)

        // 绘制两条横线 (1/3 和 2/3 位置)
        canvas.drawLine(0f, h / 3, w, h / 3, paint)
        canvas.drawLine(0f, h * 2 / 3, w, h * 2 / 3, paint)
    }
}