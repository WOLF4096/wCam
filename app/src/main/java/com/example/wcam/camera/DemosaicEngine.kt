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
        val cropW = (cRect.width().coerceAtMost(width - left) / 2) * 2
        val cropH = (cRect.height().coerceAtMost(height - top) / 2) * 2
        
        val resW: Int
        val resH: Int
        
        if (algorithm == "RgbCh") {
            // RGB通道模式：2x2 排列，每个格子的尺寸是旋转后的裁剪尺寸
            val rotW = if (rotation % 180 == 0) cropW else cropH
            val rotH = if (rotation % 180 == 0) cropH else cropW
            resW = rotW * 2
            resH = rotH * 2
        } else {
            resW = if (rotation % 180 == 0) cropW else cropH
            resH = if (rotation % 180 == 0) cropH else cropW
        }
        
        val bitmap = Bitmap.createBitmap(resW, resH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(resW * resH)
        
        try {
            nativeProcess(
                buffer, width, height, rowStride, pixelStride,
                pixels, algorithm, wbGains, ccm, 
                shadingMap, shadingMapSize?.width ?: 0, shadingMapSize?.height ?: 0,
                bayerPattern, blackLevel, whiteLevel,
                lscStrength,
                left, top, cropW, cropH, rotation
            )
        } catch (e: Exception) {
            Log.e("DemosaicEngine", "Native process failed", e)
            throw e
        }
        
        bitmap.setPixels(pixels, 0, resW, 0, 0, resW, resH)
        return bitmap
    }
}
