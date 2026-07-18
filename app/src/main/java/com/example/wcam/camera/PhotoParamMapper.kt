package com.example.wcam.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import android.hardware.camera2.CameraMetadata

object PhotoParamMapper {

    private var config: com.example.wcam.util.ConfigManager? = null
    
    fun init(configManager: com.example.wcam.util.ConfigManager) {
        config = configManager
    }

    val SUPPORTED_KELVIN_VALUES: List<Int>
        get() = config?.getIntList("supported_kelvin_values") ?: listOf(3200, 4000, 5200, 6000, 7000)

    fun formatIsoDisplay(iso: Int): String {
        return if (iso >= 1000) {
            val kValue = iso / 1000.0
            if (kValue == kValue.toInt().toDouble()) String.format(java.util.Locale.US, "%dK", kValue.toInt())
            else String.format(java.util.Locale.US, "%.1fK", kValue)
        } else iso.toString()
    }

    fun formatShutterDisplay(exposureTimeNanos: Long): String {
        if (exposureTimeNanos <= 0L) return "0"
        val seconds = exposureTimeNanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            if (seconds == seconds.toLong().toDouble()) {
                String.format(Locale.US, "%d", seconds.toLong())
            } else {
                String.format(Locale.US, "%.1f", seconds)
            }
        } else {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            if (denominator >= 1000) {
                val kValue = denominator / 1000.0
                if (kValue == kValue.toInt().toDouble()) {
                    "1/${kValue.toInt()}K"
                } else {
                    String.format(Locale.US, "1/%.1fK", kValue)
                }
            } else {
                "1/$denominator"
            }
        }
    }

    fun parseShutterLabel(label: String): Long {
        val normalized = label.uppercase(Locale.US)
        return if (normalized.contains("/")) {
            val parts = normalized.split("/")
            if (parts.size == 2) {
                val numerator = parts[0].toDoubleOrNull() ?: 1.0
                val denominatorStr = parts[1]
                val denominator = if (denominatorStr.endsWith("K")) {
                    (denominatorStr.removeSuffix("K").toDoubleOrNull() ?: 1.0) * 1000
                } else {
                    denominatorStr.toDoubleOrNull() ?: 1.0
                }
                ((numerator / denominator) * 1_000_000_000L).toLong()
            } else {
                100_000_000L // Fallback 1/10s
            }
        } else {
            val seconds = normalized.toDoubleOrNull() ?: 0.1
            (seconds * 1_000_000_000L).toLong()
        }
    }

    fun formatEvDisplay(compensationIndex: Int, step: Float): String {
        val value = compensationIndex * step
        return String.format(Locale.US, "%+.1f", value)
    }

    fun formatFocusDistanceDisplay(focusDistance: Float): String {
        return formatDiopterDisplay(focusDistance)
    }

    fun formatDiopterDisplay(diopter: Float): String {
        if (diopter < 0.0001f) return "0"
        return if (diopter >= 1.0f) {
            String.format(Locale.US, "%.1f", diopter)
        } else {
            val denominator = (1.0f / diopter).roundToInt()
            if (denominator >= 1000) {
                val kValue = denominator / 1000.0
                if (kValue == kValue.toInt().toDouble()) {
                    "1/${kValue.toInt()}K"
                } else {
                    String.format(Locale.US, "1/%.1fK", kValue)
                }
            } else {
                "1/$denominator"
            }
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
                    val ev = label.toFloat()
                    val rawIndex = (ev / step).roundToInt()
                    currentState.copy(
                        ev = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = rawIndex.coerceIn(range),
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
                            manualValue = label.removeSuffix("K").toInt(),
                        ),
                    )
                }
            }

            ParamKey.ISO -> {
                val hardwareRange = capabilities.isoRange?.let { it.lower..it.upper }
                
                if (!capabilities.supportsManualSensor || hardwareRange == null) {
                    currentState
                } else {
                    val isoMax = config?.getInt("iso_max", 64000) ?: 64000
                    val extendedRange = hardwareRange.first..isoMax
                    val clean = label.uppercase(Locale.US).trim()
                    val parsedValue = if (clean.endsWith("K")) {
                        (clean.removeSuffix("K").toFloat() * 1000).toInt()
                    } else {
                        clean.toIntOrNull() ?: 100
                    }
                    val clampedValue = parsedValue.coerceIn(extendedRange)
                    
                    currentState.copy(
                        iso = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = clampedValue,
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
                            manualValue = parseShutterLabel(label).coerceIn(range),
                        ),
                    )
                }
            }

            ParamKey.AF -> {
                val minDistance = capabilities.minimumFocusDistance ?: 0f
                if (minDistance <= 0f) {
                    currentState
                } else {
                    val diopter = if (label == "0") 0f else if (label.contains("/")) {
                        val parts = label.split("/")
                        if (parts.size == 2) {
                            val denStr = parts[1].uppercase(Locale.US)
                            val den = if (denStr.endsWith("K")) {
                                (denStr.removeSuffix("K").toFloatOrNull() ?: 1.0f) * 1000f
                            } else {
                                denStr.toFloatOrNull() ?: 1.0f
                            }
                            1f / den
                        } else 0f
                    } else {
                        label.toFloatOrNull() ?: 0f
                    }
                    val diopterMax = config?.getFloat("default_max_diopter", 50.0f) ?: 50.0f
                    currentState.copy(
                        af = ParamValue(
                            isAuto = false,
                            displayValue = label,
                            manualValue = diopter.coerceIn(0f, diopterMax),
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

    fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val k = kelvin.coerceIn(2000, 8000).toFloat()
        val red = (1.0f + 2.0f * (1.0f - (k - 2000f) / 6000f).pow(1.5f))
        val blue = (0.6f + 2.4f * ((k - 2000f) / 6000f).pow(1.5f))
        val green = 1.0f
        return RggbChannelVector(red.coerceIn(0.5f, 4.0f), green, green, blue.coerceIn(0.5f, 4.0f))
    }

    fun applyPhotoParamsToRequest(
        builder: CaptureRequest.Builder,
        state: PhotoParamState,
        capabilities: CameraCapabilities,
        fallbackAutoIso: Int?,
        fallbackAutoShutterNanos: Long?,
        isStream: Boolean = false,
    ) {
        val manualExposure = !state.iso.isAuto || !state.shutter.isAuto

        if (manualExposure && capabilities.supportsManualSensor) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val isoValue = state.iso.manualValue ?: fallbackAutoIso ?: capabilities.isoRange?.lower
            var shutterValue = state.shutter.manualValue ?: fallbackAutoShutterNanos ?: capabilities.exposureTimeRange?.lower
            
            // Critical fix: ensure we only apply values if they are actually manually set
            // or if the overall exposure is manual.
            if (!state.iso.isAuto) {
                isoValue?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            } else {
                fallbackAutoIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            }

            if (!state.shutter.isAuto) {
                shutterValue?.let { 
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it)
                }
            } else {
                fallbackAutoShutterNanos?.let { 
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it)
                }
            }

            val shutterCap = config?.getLong("preview_shutter_cap_nanos", 200_000_000L) ?: 200_000_000L
            if (isStream && shutterValue != null && shutterValue > shutterCap) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterCap)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, shutterCap)
            }
            
            capabilities.aeFpsRanges?.minByOrNull { it.lower }?.let { range ->
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        if (!state.ev.isAuto) {
            state.ev.manualValue?.let {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
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

        builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
    }
}
