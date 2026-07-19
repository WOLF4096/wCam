package com.example.wcam.camera

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.example.wcam.util.FileLogger
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
        outputBuffer: ByteBuffer, 
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
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotation: Int,
        highBitDepth: Boolean 
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
        cropRect: android.graphics.Rect? = null,
        rotation: Int = 0,
        highBitDepth: Boolean = false
    ): Bitmap {
        val cRect = cropRect ?: android.graphics.Rect(0, 0, width, height)
        val left = (cRect.left.coerceIn(0, width - 2) / 2) * 2
        val top = (cRect.top.coerceIn(0, height - 2) / 2) * 2
        
        var step = 1
        when (algorithm) {
            "Bin4" -> step = 2
            "Bin9" -> step = 3
            "Bin16", "Bright4" -> step = 4
        }
        
        val rawCropW = (cRect.width().coerceAtMost(width - left) / (2 * step)) * (2 * step)
        val rawCropH = (cRect.height().coerceAtMost(height - top) / (2 * step)) * (2 * step)
        
        var outW = rawCropW / step
        var outH = rawCropH / step

        val resW = if (rotation % 180 == 0) outW else outH
        val resH = if (rotation % 180 == 0) outH else outW
        
        val actualHighBitDepth = if (algorithm == "RgbCh") false else highBitDepth
        val config = if (actualHighBitDepth) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
        val bitmap = Bitmap.createBitmap(resW, resH, config)
        
        val pixelSize = if (actualHighBitDepth) 8 else 4
        val outputBuffer = ByteBuffer.allocateDirect(resW * resH * pixelSize)
        outputBuffer.order(java.nio.ByteOrder.nativeOrder())
        
        try {
            nativeProcess(
                buffer, width, height, rowStride, pixelStride,
                outputBuffer, algorithm, wbGains, ccm, 
                shadingMap, shadingMapSize?.width ?: 0, shadingMapSize?.height ?: 0,
                bayerPattern, blackLevel, whiteLevel,
                lscStrength,
                left, top, rawCropW, rawCropH, rotation,
                actualHighBitDepth
            )
            outputBuffer.rewind()
            bitmap.copyPixelsFromBuffer(outputBuffer)
        } catch (e: Exception) {
            FileLogger.e("DemosaicEngine", "Native process failed", e)
            throw e
        }
        
        return bitmap
    }
}
