package com.example.wcam.camera

import android.os.Handler
import android.os.Looper
import android.util.Log

class AutoCaptureManager(
    private val backgroundHandler: Handler,
    private val onTriggerCapture: () -> Unit
) {
    interface Listener {
        fun onIntervalUpdate(elapsedMs: Long, totalMs: Long, nextCaptureInMs: Long, intervalMs: Long)
        fun onContinuousUpdate(current: Int, total: Int)
        fun onStarted(mode: String)
        fun onStopped()
    }

    private var listener: Listener? = null
    private var isRunning = false
    private var mode = "None" // "None", "Interval", "Continuous"
    private val uiHandler = Handler(Looper.getMainLooper())
    
    // Interval params
    private var intervalMs = 5000L
    private var durationMs = 60000L
    private var startTime = 0L
    private var nextCaptureTime = 0L

    // Continuous params
    private var totalCount = 0
    private var remainingCount = 0

    fun setListener(l: Listener?) {
        listener = l
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            
            if (mode == "Interval") {
                val now = System.currentTimeMillis()
                if (now - startTime < durationMs) {
                    onTriggerCapture()
                    nextCaptureTime = now + intervalMs
                    backgroundHandler.postDelayed(this, intervalMs)
                } else {
                    stop()
                }
            }
        }
    }

    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            
            val l = listener ?: return
            if (mode == "Interval") {
                val now = System.currentTimeMillis()
                val elapsed = (now - startTime).coerceAtMost(durationMs)
                val nextIn = (nextCaptureTime - now).coerceAtLeast(0)
                l.onIntervalUpdate(elapsed, durationMs, nextIn, intervalMs)
            } else if (mode == "Continuous") {
                l.onContinuousUpdate(totalCount - remainingCount + 1, totalCount)
            }
            uiHandler.postDelayed(this, 100)
        }
    }

    fun startInterval(intervalSec: Int, durationSec: Int) {
        stop()
        mode = "Interval"
        intervalMs = intervalSec * 1000L
        durationMs = durationSec * 1000L
        startTime = System.currentTimeMillis()
        nextCaptureTime = startTime
        isRunning = true
        listener?.onStarted(mode)
        backgroundHandler.post(captureRunnable)
        uiHandler.post(uiUpdateRunnable)
    }

    fun startContinuous(count: Int) {
        stop()
        mode = "Continuous"
        totalCount = count
        remainingCount = count
        isRunning = true
        listener?.onStarted(mode)
        triggerNextContinuous()
        uiHandler.post(uiUpdateRunnable)
    }

    fun onFrameProcessed() {
        if (isRunning && mode == "Continuous") {
            remainingCount--
            if (remainingCount > 0) {
                // Delay 50ms after RAW processed as requested
                backgroundHandler.postDelayed({
                    triggerNextContinuous()
                }, 50)
            } else {
                stop()
            }
        }
    }

    private fun triggerNextContinuous() {
        if (isRunning && mode == "Continuous") {
            onTriggerCapture()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        backgroundHandler.removeCallbacks(captureRunnable)
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.post { listener?.onStopped() }
    }

    fun isRunning() = isRunning
}
