package com.example.wcam.camera

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer

object DemosaicEngine {
    init {
        System.loadLibrary("wcam")
    }

    private external fun nativeProcess(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        outputPixels: IntArray,
        algorithm: String,
        wbGainsArray: FloatArray,
        ccmArray: FloatArray,
        shadingMap: FloatArray?,
        smWidth: Int,
        smHeight: Int,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        lscStrength: Float,
        cropLeft: Int,      // 新增
        cropTop: Int,       // 新增
        cropWidth: Int,     // 新增
        cropHeight: Int,    // 新增
        rotation: Int       // 新增
    )

    fun processFromBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        algorithm: String,
        wbGains: FloatArray,
        ccm: FloatArray,
        shadingMap: FloatArray? = null,
        shadingMapSize: android.util.Size? = null,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        lscStrength: Float = 0.0f,
        cropRect: android.graphics.Rect? = null, // 新增
        rotation: Int = 0 // 新增
    ): Bitmap {
        val cRect = cropRect ?: android.graphics.Rect(0, 0, width, height)
        // 确保坐标在范围内并对齐 2x2
        val left = (cRect.left.coerceIn(0, width - 2) / 2) * 2
        val top = (cRect.top.coerceIn(0, height - 2) / 2) * 2
        
        var step = 1
        when (algorithm) {
            "Bin4" -> step = 2
            "Bin9" -> step = 3
            "Bin16" -> step = 4
        }
        
        // Ensure crop dimensions are multiples of the binning step and 2 for Bayer
        val rawCropW = (cRect.width().coerceAtMost(width - left) / (2 * step)) * (2 * step)
        val rawCropH = (cRect.height().coerceAtMost(height - top) / (2 * step)) * (2 * step)
        
        var outW = rawCropW / step
        var outH = rawCropH / step

        val resW: Int
        val resH: Int
        
        resW = if (rotation % 180 == 0) outW else outH
        resH = if (rotation % 180 == 0) outH else outW
        
        val bitmap = Bitmap.createBitmap(resW, resH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(resW * resH)
        
        try {
            nativeProcess(
                buffer, width, height, rowStride, pixelStride,
                pixels, algorithm, wbGains, ccm, 
                shadingMap, shadingMapSize?.width ?: 0, shadingMapSize?.height ?: 0,
                bayerPattern, blackLevel, whiteLevel,
                lscStrength,
                left, top, rawCropW, rawCropH, rotation
            )
        } catch (e: Exception) {
            Log.e("DemosaicEngine", "Native process failed", e)
            throw e
        }
        
        bitmap.setPixels(pixels, 0, resW, 0, 0, resW, resH)
        return bitmap
    }
}
