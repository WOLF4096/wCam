package com.example.wcam.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CaptureMediaKind(val token: String) {
    PHOTO("IMG"),
    VIDEO("VID"),
}

object CaptureFileNames {
    fun photoDisplayName(
        timestampMillis: Long = System.currentTimeMillis(),
        extension: String,
    ): String = displayName(timestampMillis, CaptureMediaKind.PHOTO, extension)

    fun videoDisplayName(
        timestampMillis: Long = System.currentTimeMillis(),
        extension: String,
    ): String = displayName(timestampMillis, CaptureMediaKind.VIDEO, extension)

    private fun displayName(
        timestampMillis: Long,
        kind: CaptureMediaKind,
        extension: String,
    ): String {
        val normalizedExtension = extension.trim().trimStart('.')
        require(normalizedExtension.isNotBlank()) { "File extension must not be blank" }

        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "${formatter.format(Date(timestampMillis))}_${kind.token}.$normalizedExtension"
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
