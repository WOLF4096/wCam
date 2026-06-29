package com.example.wcam

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wcam_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_LENS_ID = "lens_id"
        const val KEY_PHYSICAL_LENS_ID = "physical_lens_id"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_GRID_VISIBLE = "grid_visible"
        const val KEY_HISTOGRAM_VISIBLE = "histogram_visible"
        const val KEY_JPG_QUALITY = "jpg_quality"
        const val KEY_IS_PHOTO_MODE = "is_photo_mode"
        const val KEY_VIDEO_RATIO = "video_ratio"
        const val KEY_VIDEO_RESOLUTION = "video_resolution"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_VIDEO_BITRATE = "video_bitrate"
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun saveInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun saveFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return prefs.getFloat(key, defaultValue)
    }
}
