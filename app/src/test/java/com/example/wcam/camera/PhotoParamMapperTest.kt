package com.example.wcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoParamMapperTest {
    @Test
    fun parseShutterLabel_convertsFractionalSecondsToNanos() {
        assertEquals(10_000_000L, PhotoParamMapper.parseShutterLabel("1/100s"))
        assertEquals(125_000_000L, PhotoParamMapper.parseShutterLabel("1/8s"))
    }

    @Test
    fun clampIso_respectsSupportedRange() {
        assertEquals(100, PhotoParamMapper.clampIso(50, 100..6400))
        assertEquals(6400, PhotoParamMapper.clampIso(12800, 100..6400))
    }

    @Test
    fun clampExposureTime_respectsSupportedRange() {
        assertEquals(10_000_000L, PhotoParamMapper.clampExposureTime(1_000_000L, 10_000_000L..100_000_000L))
        assertEquals(100_000_000L, PhotoParamMapper.clampExposureTime(300_000_000L, 10_000_000L..100_000_000L))
    }

    @Test
    fun normalizedFocusProgress_mapsIntoSupportedDistanceRange() {
        assertEquals(0.0f, PhotoParamMapper.mapAfProgressToDistance(1.0f, 0.0f), 0.0001f)
        assertEquals(5.0f, PhotoParamMapper.mapAfProgressToDistance(0.0f, 5.0f), 0.0001f)
        assertEquals(2.5f, PhotoParamMapper.mapAfProgressToDistance(0.5f, 5.0f), 0.0001f)
    }

    @Test
    fun evLabelToCompensationIndex_respectsCompensationStepAndRange() {
        assertEquals(2, PhotoParamMapper.evLabelToCompensationIndex("+1.0", 0.5f, -4..4))
        assertEquals(-4, PhotoParamMapper.evLabelToCompensationIndex("-4.0", 0.5f, -4..4))
    }

    @Test
    fun mapAfProgressToDistance_returnsZeroWhenLensDoesNotSupportManualFocus() {
        assertEquals(0.0f, PhotoParamMapper.mapAfProgressToDistance(0.7f, 0f), 0.0001f)
    }
}
