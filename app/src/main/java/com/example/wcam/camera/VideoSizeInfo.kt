package com.example.wcam.camera

/**
 * Data class representing video stream capabilities.
 */
data class VideoSizeInfo(
    val width: Int, 
    val height: Int, 
    val maxFps: Int, 
    val isHighSpeed: Boolean = false
) {
    val ratio: Float get() = width.toFloat() / height
    
    val ratioStr: String get() {
        val common = gcd(width, height)
        return "${height / common}:${width / common}"
    }
    
    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }
}
