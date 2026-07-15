package com.example.wcam.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object CaptureFileNames {
    fun photoDisplayName(
        timestampMillis: Long = System.currentTimeMillis(),
        extension: String,
    ): String = displayName(timestampMillis, extension)

    fun videoDisplayName(
        timestampMillis: Long = System.currentTimeMillis(),
        extension: String,
    ): String = displayName(timestampMillis, extension)

    private fun displayName(
        timestampMillis: Long,
        extension: String,
    ): String {
        val normalizedExtension = extension.trim().trimStart('.')
        require(normalizedExtension.isNotBlank()) { "File extension must not be blank" }

        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "${formatter.format(Date(timestampMillis))}.$normalizedExtension"
    }
}

internal fun createPhotoDisplayName(
    timestampMillis: Long = System.currentTimeMillis(),
    extension: String,
): String = CaptureFileNames.photoDisplayName(timestampMillis, extension)

internal fun createVideoDisplayName(
    timestampMillis: Long = System.currentTimeMillis(),
    extension: String,
): String = CaptureFileNames.videoDisplayName(timestampMillis, extension)

internal fun createRawDisplayName(timestampMillis: Long = System.currentTimeMillis()): String {
    return createPhotoDisplayName(timestampMillis, "dng")
}
