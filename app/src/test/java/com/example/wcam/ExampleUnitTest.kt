package com.example.wcam

import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun createRawDisplayName_usesTimestampPatternAndDngExtension() {
        val fileName = createRawDisplayName(1_719_676_800_000)

        assertTrue(fileName.startsWith("wcam_"))
        assertTrue(fileName.endsWith(".dng"))
        assertTrue(fileName.matches(Regex("wcam_\\d{8}_\\d{6}\\.dng")))
    }
}
