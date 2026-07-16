package com.example.wcam.camera

import android.graphics.Bitmap
import android.os.Handler
import android.util.Log
import android.view.TextureView

/**
 * Monitor for camera stream health (freeze detection) and UI tasks (histogram).
 */
class CameraStreamMonitor(
    private val backgroundHandler: Handler,
    private val listener: MonitorListener
) {

    interface MonitorListener {
        fun onStreamFrozen()
        fun onHistogramUpdate(r: IntArray, g: IntArray, b: IntArray)
        fun isHistogramVisible(): Boolean
        fun getPreviewTexture(): TextureView?
        fun isCaptureInProgress(): Boolean
    }

    private var lastPreviewFrameTime = 0L

    private val histogramTask = object : Runnable {
        override fun run() {
            val textureView = listener.getPreviewTexture()
            if (listener.isHistogramVisible() && textureView?.isAvailable == true) {
                try {
                    val bitmap = textureView.getBitmap(160, 120)
                    if (bitmap != null) {
                        val pixels = IntArray(160 * 120)
                        bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 120)
                        
                        val rHist = IntArray(256)
                        val gHist = IntArray(256)
                        val bHist = IntArray(256)
                        
                        nativeCalculateHistogram(pixels, 160, 120, rHist, gHist, bHist)
                        listener.onHistogramUpdate(rHist, gHist, bHist)
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("CameraStreamMonitor", "Failed to sample preview", e)
                }
            }
            backgroundHandler.postDelayed(this, 100)
        }
    }

    private val freezeWatchdog = object : Runnable {
        override fun run() {
            if (lastPreviewFrameTime > 0 && !listener.isCaptureInProgress()) {
                val diff = System.currentTimeMillis() - lastPreviewFrameTime
                if (diff > 2000) {
                    Log.w("CameraStreamMonitor", "Stream frozen for $diff ms")
                    listener.onStreamFrozen()
                    lastPreviewFrameTime = System.currentTimeMillis()
                }
            }
            backgroundHandler.postDelayed(this, 1000)
        }
    }

    fun start() {
        backgroundHandler.post(histogramTask)
        backgroundHandler.post(freezeWatchdog)
    }

    fun notifyFrameReceived() {
        lastPreviewFrameTime = System.currentTimeMillis()
    }

    private external fun nativeCalculateHistogram(
        pixels: IntArray, width: Int, height: Int,
        rHist: IntArray, gHist: IntArray, bHist: IntArray
    )

    companion object {
        init {
            System.loadLibrary("wcam")
        }
    }
}
