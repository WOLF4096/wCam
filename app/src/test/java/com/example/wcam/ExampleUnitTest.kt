package com.example.wcam

import com.example.wcam.camera.createRawDisplayName
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun createRawDisplayName_usesTimestampPatternAndDngExtension() {
        val fileName = createRawDisplayName(1_719_676_800_000L)

        assertTrue(fileName.endsWith("_IMG.dng"))
        assertTrue(fileName.matches(Regex("\\d{8}_\\d{6}_IMG\\.dng")))
    }
}
