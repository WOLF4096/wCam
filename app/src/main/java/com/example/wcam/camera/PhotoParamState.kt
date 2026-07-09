package com.example.wcam.camera

import android.util.Range

const val PARAM_TEXT_AUTO_COLOR = "#FFFFFF"
const val PARAM_TEXT_MANUAL_COLOR = "#FFCC00"

enum class ParamKey {
    EV,
    WB,
    ISO,
    SHUTTER,
    AF,
}

data class CameraCapabilities(
    val aeCompensationRange: Range<Int>?,
    val aeCompensationStep: Float?,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val minimumFocusDistance: Float?,
    val supportsManualSensor: Boolean,
    val supportsManualWhiteBalance: Boolean,
    val aeFpsRanges: List<Range<Int>>?,
) {
    companion object {
        val EMPTY = CameraCapabilities(
            aeCompensationRange = null,
            aeCompensationStep = null,
            isoRange = null,
            exposureTimeRange = null,
            minimumFocusDistance = null,
            supportsManualSensor = false,
            supportsManualWhiteBalance = false,
            aeFpsRanges = null,
        )
    }
}

data class ParamValue<T>(
    val isAuto: Boolean,
    val displayValue: String,
    val manualValue: T?,
)

data class PhotoParamState(
    val ev: ParamValue<Int>,
    val wb: ParamValue<Int>,
    val iso: ParamValue<Int>,
    val shutter: ParamValue<Long>,
    val af: ParamValue<Float>,
) {
    companion object {
        fun default() = PhotoParamState(
            ev = ParamValue(isAuto = true, displayValue = "0.0", manualValue = null),
            wb = ParamValue(isAuto = true, displayValue = "5200K", manualValue = null),
            iso = ParamValue(isAuto = true, displayValue = "100", manualValue = null),
            shutter = ParamValue(isAuto = true, displayValue = "1/100s", manualValue = null),
            af = ParamValue(isAuto = true, displayValue = "0.5", manualValue = null),
        )
    }
}
