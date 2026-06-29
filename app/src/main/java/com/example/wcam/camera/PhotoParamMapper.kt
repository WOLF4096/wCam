package com.example.wcam.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

object PhotoParamMapper {

    fun parseShutterLabel(label: String): Long {
        val normalized = label.removeSuffix("s")
        return if (normalized.contains("/")) {
            val parts = normalized.split("/")
            if (parts.size == 2) {
                val numerator = parts[0].toDoubleOrNull() ?: 1.0
                val denominator = parts[1].toDoubleOrNull() ?: 1.0
                ((numerator / denominator) * 1_000_000_000L).toLong()
            } else {
                100_000_000L // Fallback 1/10s
            }
        } else {
            val seconds = normalized.toDoubleOrNull() ?: 0.1
            (seconds * 1_000_000_000L).toLong()
        }
    }

    fun parseIsoLabel(label: String): Int {
        return if (label.endsWith("k")) {
            (label.removeSuffix("k").toFloat() * 1000).toInt()
        } else {
            label.toInt()
        }
    }

    fun clampIso(value: Int, range: IntRange): Int = value.coerceIn(range)

    fun clampExposureTime(value: Long, range: LongRange): Long = value.coerceIn(range)

    fun mapAfProgressToDistance(progress: Float, minimumFocusDistance: Float): Float {
        if (minimumFocusDistance <= 0f) return 0f
        val clamped = progress.coerceIn(0f, 1f)
        return minimumFocusDistance * (1f - clamped)
    }

    fun evLabelToCompensationIndex(label: String, step: Float, range: IntRange): Int {
        if (step <= 0f) return 0
        val ev = label.toFloat()
        val rawIndex = (ev / step).roundToInt()
        return rawIndex.coerceIn(range)
    }

    fun kelvinLabelToInt(label: String): Int {
        return label.removeSuffix("K").toInt()
    }

    fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val normalized = kelvin.coerceIn(2000, 8000)
        val temperature = normalized / 100f

        val red = if (temperature <= 66f) {
            2.0f
        } else {
            (1.2929362f * (temperature - 60f).toDouble().pow(-0.13320476).toFloat()).coerceIn(0.5f, 2.5f)
        }

        val blue = if (temperature >= 66f) {
            2.0f
        } else if (temperature <= 19f) {
            0.5f
        } else {
            (0.5432068f * (temperature - 10f).toDouble().pow(0.07551485).toFloat()).coerceIn(0.5f, 2.5f)
        }

        val green = 1.0f
        return RggbChannelVector(red, green, green, blue)
    }

    fun formatEvDisplay(compensationIndex: Int, step: Float): String {
        val value = compensationIndex * step
        return String.format(Locale.US, "%+.1f", value)
    }

    fun formatShutterDisplay(exposureTimeNanos: Long): String {
        if (exposureTimeNanos <= 0L) return "0s"
        val seconds = exposureTimeNanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            if (seconds == seconds.toLong().toDouble()) {
                String.format(Locale.US, "%ds", seconds.toLong())
            } else {
                String.format(Locale.US, "%.1fs", seconds)
            }
        } else {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            "1/${denominator}s"
        }
    }

    fun formatFocusDistanceDisplay(focusDistance: Float): String {
        if (focusDistance <= 0.01f) return "∞"
        val meters = 1f / focusDistance
        return if (meters < 1.0f) {
            String.format(Locale.US, "%dcm", (meters * 100).toInt())
        } else {
            String.format(Locale.US, "%.1fm", meters)
        }
    }

    fun gainsToKelvinLabel(gains: RggbChannelVector, supportedValues: List<Int>): String {
        if (supportedValues.isEmpty()) return "5200K"

        val blueRatio = (gains.blue / maxOf(gains.greenEven, 0.001f)).coerceAtLeast(0.1f)
        val redRatio = (gains.red / maxOf(gains.greenEven, 0.001f)).coerceAtLeast(0.1f)
        val scoreForKelvin = { kelvin: Int ->
            val reference = kelvinToRggb(kelvin)
            abs(reference.red - redRatio) + abs(reference.blue - blueRatio)
        }

        val bestKelvin = supportedValues.minByOrNull(scoreForKelvin) ?: supportedValues.first()
        return "${bestKelvin}K"
    }

    fun createManualStateForLabel(
        key: ParamKey,
        label: String,
        capabilities: CameraCapabilities,
        currentState: PhotoParamState,
    ): PhotoParamState {
        return when (key) {
            ParamKey.EV -> {
                val range = capabilities.aeCompensationRange?.let { it.lower..it.upper }
                val step = capabilities.aeCompensationStep
                if (range == null || step == null || step <= 0f) {
                    currentState
                } else {
                    currentState.copy(
                        ev = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = evLabelToCompensationIndex(label, step, range),
                        ),
                    )
                }
            }

            ParamKey.WB -> {
                if (!capabilities.supportsManualWhiteBalance) {
                    currentState
                } else {
                    currentState.copy(
                        wb = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = kelvinLabelToInt(label),
                        ),
                    )
                }
            }

            ParamKey.ISO -> {
                val range = capabilities.isoRange?.let { it.lower..it.upper }
                if (!capabilities.supportsManualSensor || range == null) {
                    currentState
                } else {
                    currentState.copy(
                        iso = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = clampIso(parseIsoLabel(label), range),
                        ),
                    )
                }
            }

            ParamKey.SHUTTER -> {
                val range = capabilities.exposureTimeRange?.let { it.lower..it.upper }
                if (!capabilities.supportsManualSensor || range == null) {
                    currentState
                } else {
                    currentState.copy(
                        shutter = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = clampExposureTime(parseShutterLabel(label), range),
                        ),
                    )
                }
            }

            ParamKey.AF -> {
                val minDistance = capabilities.minimumFocusDistance ?: 0f
                if (minDistance <= 0f) {
                    currentState
                } else {
                    val focusDistance = if (label == "∞") 0f else {
                        val meters = if (label.endsWith("cm")) {
                            label.removeSuffix("cm").toFloat() / 100f
                        } else {
                            label.removeSuffix("m").toFloat()
                        }
                        1f / meters
                    }
                    currentState.copy(
                        af = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = focusDistance.coerceIn(0f, minDistance),
                        ),
                    )
                }
            }
        }
    }

    fun restoreAutoState(key: ParamKey, currentState: PhotoParamState): PhotoParamState {
        return when (key) {
            ParamKey.EV -> currentState.copy(ev = currentState.ev.copy(isAuto = true, manualValue = null))
            ParamKey.WB -> currentState.copy(wb = currentState.wb.copy(isAuto = true, manualValue = null))
            ParamKey.ISO -> currentState.copy(iso = currentState.iso.copy(isAuto = true, manualValue = null))
            ParamKey.SHUTTER -> currentState.copy(shutter = currentState.shutter.copy(isAuto = true, manualValue = null))
            ParamKey.AF -> currentState.copy(af = currentState.af.copy(isAuto = true, manualValue = null))
        }
    }

    fun applyPhotoParamsToRequest(
        builder: CaptureRequest.Builder,
        state: PhotoParamState,
        capabilities: CameraCapabilities,
        fallbackAutoIso: Int?,
        fallbackAutoShutterNanos: Long?,
    ) {
        val manualExposure = !state.iso.isAuto || !state.shutter.isAuto

        if (manualExposure && capabilities.supportsManualSensor) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val isoValue = state.iso.manualValue ?: fallbackAutoIso ?: capabilities.isoRange?.lower
            val shutterValue = state.shutter.manualValue ?: fallbackAutoShutterNanos ?: capabilities.exposureTimeRange?.lower
            isoValue?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            shutterValue?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        state.ev.manualValue?.takeIf { !state.ev.isAuto }?.let {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
        }

        if (!state.wb.isAuto && capabilities.supportsManualWhiteBalance) {
            state.wb.manualValue?.let { kelvin ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(kelvin))
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        if (!state.af.isAuto && (capabilities.minimumFocusDistance ?: 0f) > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            state.af.manualValue?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }
}
