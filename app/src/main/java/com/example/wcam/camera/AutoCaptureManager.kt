package com.example.wcam.camera

import android.os.Handler
import android.os.Looper
import android.util.Log

class AutoCaptureManager(
    private val backgroundHandler: Handler,
    private val onTriggerCapture: () -> Unit
) {
    private var isRunning = false
    private var mode = "None" // "None", "Interval", "Continuous"
    
    // Interval params
    private var intervalMs = 5000L
    private var durationMs = 60000L
    private var startTime = 0L

    // Continuous params
    private var remainingCount = 0

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            
            if (mode == "Interval") {
                if (System.currentTimeMillis() - startTime < durationMs) {
                    onTriggerCapture()
                    backgroundHandler.postDelayed(this, intervalMs)
                } else {
                    stop()
                }
            }
        }
    }

    fun startInterval(intervalSec: Int, durationSec: Int) {
        stop()
        mode = "Interval"
        intervalMs = intervalSec * 1000L
        durationMs = durationSec * 1000L
        startTime = System.currentTimeMillis()
        isRunning = true
        backgroundHandler.post(captureRunnable)
    }

    fun startContinuous(count: Int) {
        stop()
        mode = "Continuous"
        remainingCount = count
        isRunning = true
        triggerNextContinuous()
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
        isRunning = false
        backgroundHandler.removeCallbacks(captureRunnable)
    }
}
