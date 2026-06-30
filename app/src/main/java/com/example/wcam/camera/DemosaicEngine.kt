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
        wbGains: FloatArray,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int
    )

    fun process(
        image: Image,
        algorithm: String,
        wbGains: FloatArray,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int
    ): Bitmap {
        Log.d("DemosaicEngine", "Algo: $algorithm, WB: ${wbGains.joinToString()}, BL: $blackLevel, WL: $whiteLevel")
        
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        
        // Use a direct buffer and ensure it remains valid during the call
        val buffer = plane.buffer
        if (!buffer.isDirect) {
            throw IllegalArgumentException("Image buffer must be direct")
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        try {
            nativeProcess(
                buffer, width, height, plane.rowStride, plane.pixelStride, 
                pixels, algorithm, wbGains, bayerPattern, blackLevel, whiteLevel
            )
        } catch (e: Exception) {
            Log.e("DemosaicEngine", "Native process failed", e)
            throw e
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
