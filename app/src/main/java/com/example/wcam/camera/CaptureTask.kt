package com.example.wcam.camera

import android.graphics.Rect
import java.nio.ByteBuffer

/**
 * Encapsulates all data needed to process a single capture in the background.
 */
data class CaptureTask(
    val rawBuffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val captureStartTime: Long,
    val metadata: CaptureMetadata,
    val algoKey: String,
    val savedFormat: String,
    val rotationDegrees: Int,
    val exifOrientation: Int,
    val cropRect: Rect?,
    val location: android.location.Location?
)

data class CaptureMetadata(
    val iso: Int,
    val wbArray: FloatArray,
    val ccm: FloatArray, // CCM 矩阵
    val shadingMap: FloatArray?, // 新增 LensShadingMap 数据
    val shadingMapSize: android.util.Size?, // 新增 Map 尺寸
    val bayerPattern: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    val lscStrength: Float
)
