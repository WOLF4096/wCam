package com.example.wcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFileNamesTest {
    @Test
    fun photoDisplayName_usesImgTokenAndRequestedExtension() {
        val name = CaptureFileNames.photoDisplayName(1_719_676_800_000L, "dng")

        assertTrue(name.matches(Regex("\\d{8}_\\d{6}_IMG\\.dng")))
        assertTrue(name.endsWith("_IMG.dng"))
    }

    @Test
    fun videoDisplayName_usesVidTokenAndRequestedExtension() {
        val name = CaptureFileNames.videoDisplayName(1_719_676_800_000L, ".mp4")

        assertTrue(name.matches(Regex("\\d{8}_\\d{6}_VID\\.mp4")))
        assertTrue(name.endsWith("_VID.mp4"))
    }

    @Test
    fun createRawDisplayName_keepsDngPhotoNaming() {
        val name = createRawDisplayName(1_719_676_800_000L)

        assertTrue(name.endsWith("_IMG.dng"))
    }

    @Test
    fun blankExtension_isRejected() {
        try {
            CaptureFileNames.photoDisplayName(1_719_676_800_000L, " ")
        } catch (error: IllegalArgumentException) {
            assertEquals("File extension must not be blank", error.message)
            return
        }

        throw AssertionError("Expected blank extension to be rejected")
    }
}
