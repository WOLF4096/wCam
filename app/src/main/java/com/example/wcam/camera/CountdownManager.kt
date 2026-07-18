package com.example.wcam.camera

import android.os.Handler
import android.view.View
import android.widget.TextView
import java.util.Locale

class CountdownManager(
    private val uiHandler: Handler,
    private val tvDisplay: TextView,
    private val onFinish: () -> Unit
) {
    private var remainingMs = 0L

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (remainingMs <= 0) {
                tvDisplay.visibility = View.GONE
                onFinish()
            } else {
                tvDisplay.text = String.format(Locale.US, "%.1fs", remainingMs / 1000.0)
                remainingMs -= 100
                uiHandler.postDelayed(this, 100)
            }
        }
    }

    fun start(secondsX10: Int) {
        if (secondsX10 <= 0) {
            onFinish()
            return
        }
        remainingMs = secondsX10 * 100L
        tvDisplay.visibility = View.VISIBLE
        tvDisplay.text = String.format(Locale.US, "%.1fs", remainingMs / 1000.0)
        uiHandler.postDelayed(countdownRunnable, 100)
    }

    fun cancel() {
        uiHandler.removeCallbacks(countdownRunnable)
        tvDisplay.visibility = View.GONE
    }
}
