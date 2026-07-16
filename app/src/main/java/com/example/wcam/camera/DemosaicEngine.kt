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
        shadingMap: FloatArray?, // 新增
        shadingMapWidth: Int,     // 新增
        shadingMapHeight: Int,    // 新增
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        lscStrength: Float
    )

    fun process(
        image: Image,
        algorithm: String,
        wbGains: FloatArray,
        ccm: FloatArray,
        shadingMap: FloatArray?,
        shadingMapSize: android.util.Size?,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        lscStrength: Float = 0.0f
    ): Bitmap {
        val plane = image.planes[0]
        return processFromBuffer(
            plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
            algorithm, wbGains, ccm, shadingMap, shadingMapSize, bayerPattern, blackLevel, whiteLevel, lscStrength
        )
    }

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
        lscStrength: Float = 0.0f
    ): Bitmap {
        Log.d("DemosaicEngine", "Algo: $algorithm, WB: ${wbGains.joinToString()}, BL: $blackLevel, WL: $whiteLevel, LSC: $lscStrength, Map: ${shadingMapSize?.width}x${shadingMapSize?.height}")
        
        if (!buffer.isDirect) {
            throw IllegalArgumentException("Image buffer must be direct")
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        try {
            nativeProcess(
                buffer, width, height, rowStride, pixelStride,
                pixels, algorithm, wbGains, ccm, 
                shadingMap, shadingMapSize?.width ?: 0, shadingMapSize?.height ?: 0,
                bayerPattern, blackLevel, whiteLevel,
                lscStrength
            )
        } catch (e: Exception) {
            Log.e("DemosaicEngine", "Native process failed", e)
            throw e
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
