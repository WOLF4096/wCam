package com.example.wcam.ui

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Handles zoom, focus, and long-press lock gestures.
 */
class GestureManager(
    private val context: Context,
    private val listener: GestureListener
) {

    interface GestureListener {
        fun onSingleTap(x: Float, y: Float)
        fun onLongPress()
        fun onZoomChanged(scale: Float)
        fun onScroll(distanceX: Float, distanceY: Float)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            listener.onSingleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            listener.onLongPress()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            listener.onScroll(distanceX, distanceY)
            return true
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            listener.onZoomChanged(detector.scaleFactor)
            return true
        }
    })

    fun handleTouch(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
