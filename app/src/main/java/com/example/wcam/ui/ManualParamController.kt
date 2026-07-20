package com.example.wcam.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.wcam.camera.PARAM_TEXT_AUTO_COLOR
import com.example.wcam.camera.PARAM_TEXT_MANUAL_COLOR
import com.example.wcam.camera.ParamKey
import com.example.wcam.camera.PhotoParamMapper
import com.example.wcam.camera.PhotoParamState
import com.example.wcam.camera.CameraCapabilities
import java.util.Locale
import kotlin.math.abs

/**
 * Controller for manual camera parameters (EV, WB, ISO, Shutter, AF).
 * Handles gestures and updates the UI display for these parameters.
 */
class ManualParamController(
    private val listener: ParamChangeListener
) {

    interface ParamChangeListener {
        fun onParamManualStep(key: ParamKey, selectedLabel: String)
        fun onParamAutoReset(key: ParamKey)
        fun getPhotoParamState(): PhotoParamState
        fun getPreviewAutoValues(): PreviewAutoValues
        fun getCameraCapabilities(): CameraCapabilities
    }

    data class PreviewAutoValues(
        val evCompensation: Int?,
        val evStep: Float,
        val wbKelvin: String?,
        val iso: Int?,
        val shutterNanos: Long?,
        val afDistance: Float?
    )

    /**
     * Internal class to handle swipe and double-tap gestures for a specific parameter.
     */
    inner class ParamGestureHandler(
        private val layout: LinearLayout,
        private val key: ParamKey,
        private val values: List<String>
    ) {
        private var currentIndex: Int = 0

        @SuppressLint("ClickableViewAccessibility")
        fun setup() {
            val scrollSensitivity = 30f
            var startY = 0f

            val gestureDetector = GestureDetector(layout.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    listener.onParamAutoReset(key)
                    return true
                }
            })

            layout.setOnTouchListener { _, event ->
                if (key == ParamKey.AF) {
                    val caps = listener.getCameraCapabilities()
                    if ((caps.minimumFocusDistance ?: 0f) <= 0f) {
                        return@setOnTouchListener false
                    }
                }

                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.y
                        val state = listener.getPhotoParamState()
                        val paramVal = when (key) {
                            ParamKey.EV -> state.ev
                            ParamKey.WB -> state.wb
                            ParamKey.ISO -> state.iso
                            ParamKey.SHUTTER -> state.shutter
                            ParamKey.AF -> state.af
                        }

                        val currentDisplay = if (paramVal.isAuto) {
                            val autoVals = listener.getPreviewAutoValues()
                            when (key) {
                                ParamKey.EV -> autoVals.evCompensation?.let { 
                                    PhotoParamMapper.formatEvDisplay(it, autoVals.evStep) 
                                }
                                ParamKey.WB -> autoVals.wbKelvin
                                ParamKey.ISO -> autoVals.iso?.let { formatIso(it) }
                                ParamKey.SHUTTER -> autoVals.shutterNanos?.let { PhotoParamMapper.formatShutterDisplay(it) }
                                ParamKey.AF -> autoVals.afDistance?.let { PhotoParamMapper.formatFocusDistanceDisplay(it) }
                            } ?: paramVal.displayValue
                        } else {
                            paramVal.displayValue
                        }
                        
                        currentIndex = findClosestIndex(currentDisplay, values)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.y - startY
                        if (abs(deltaY) > scrollSensitivity) {
                            currentIndex = if (deltaY < 0) {
                                (currentIndex + 1).coerceAtMost(values.size - 1)
                            } else {
                                (currentIndex - 1).coerceAtLeast(0)
                            }
                            listener.onParamManualStep(key, values[currentIndex])
                            startY = event.y
                        }
                        true
                    }
                    else -> true
                }
            }
        }
    }

    fun refreshViews(
        tvEv: TextView,
        tvWb: TextView,
        tvIso: TextView,
        tvS: TextView,
        tvAf: TextView,
        state: PhotoParamState,
        auto: PreviewAutoValues
    ) {
        val evDisplay = if (state.ev.isAuto) {
            auto.evCompensation?.let { PhotoParamMapper.formatEvDisplay(it, auto.evStep) } ?: state.ev.displayValue
        } else state.ev.displayValue
        setParamText(tvEv, evDisplay, state.ev.isAuto)

        val wbDisplay = if (state.wb.isAuto) {
            auto.wbKelvin ?: state.wb.displayValue
        } else state.wb.displayValue
        setParamText(tvWb, wbDisplay, state.wb.isAuto)

        val isoDisplay = if (state.iso.isAuto) {
            auto.iso?.let { formatIso(it) } ?: state.iso.displayValue
        } else state.iso.displayValue
        setParamText(tvIso, isoDisplay, state.iso.isAuto)

        val shutterDisplay = if (state.shutter.isAuto) {
            auto.shutterNanos?.let { PhotoParamMapper.formatShutterDisplay(it) } ?: state.shutter.displayValue
        } else state.shutter.displayValue
        setParamText(tvS, shutterDisplay, state.shutter.isAuto)

        val caps = listener.getCameraCapabilities()
        val afSupported = (caps.minimumFocusDistance ?: 0f) > 0f
        
        if (!afSupported) {
            tvAf.text = "不支持"
            tvAf.setTextColor(Color.GRAY)
            tvAf.alpha = 0.5f
        } else {
            val afDisplay = if (state.af.isAuto) {
                auto.afDistance?.let { PhotoParamMapper.formatFocusDistanceDisplay(it) } ?: state.af.displayValue
            } else state.af.displayValue
            setParamText(tvAf, afDisplay, state.af.isAuto)
            tvAf.alpha = 1.0f
        }
    }

    private fun setParamText(view: TextView, text: String, isAuto: Boolean) {
        view.text = text
        view.setTextColor(Color.parseColor(if (isAuto) PARAM_TEXT_AUTO_COLOR else PARAM_TEXT_MANUAL_COLOR))
    }

    private fun formatIso(iso: Int): String {
        return if (iso >= 1000) {
            val kValue = iso / 1000.0
            if (kValue == kValue.toInt().toDouble()) String.format(Locale.US, "%dK", kValue.toInt())
            else String.format(Locale.US, "%.1fK", kValue)
        } else iso.toString()
    }

    private fun findClosestIndex(target: String, values: List<String>): Int {
        val targetNum = extractNumber(target) ?: return 0
        var minDiff = Float.MAX_VALUE
        var bestIdx = 0
        for (i in values.indices) {
            val valNum = extractNumber(values[i]) ?: continue
            val diff = abs(targetNum - valNum)
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun extractNumber(s: String): Float? {
        return try {
            val upper = s.uppercase(Locale.US)
            if (upper.contains("/")) {
                val parts = upper.replace(Regex("[^0-9./K]"), "").split("/")
                if (parts.size == 2) {
                    val num = parsePart(parts[0])
                    val den = parsePart(parts[1])
                    num / den
                } else parsePart(parts[0])
            } else {
                parsePart(upper.replace(Regex("[^0-9.-K]"), ""))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePart(p: String): Float {
        val clean = p.trim()
        if (clean.isEmpty() || clean == "K") return 0f
        return if (clean.endsWith("K")) {
            clean.removeSuffix("K").toFloatOrNull()?.let { it * 1000f } ?: 0f
        } else {
            clean.toFloatOrNull() ?: 0f
        }
    }
}
