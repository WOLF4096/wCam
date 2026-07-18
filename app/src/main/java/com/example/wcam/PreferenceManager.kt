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
        const val KEY_DEMOSAIC_ALGO = "demosaic_algo"
        const val KEY_IS_PHOTO_MODE = "is_photo_mode"
        const val KEY_PHOTO_RATIO = "photo_ratio"
        const val KEY_PHOTO_RATIO_STR = "photo_ratio_str"
        const val KEY_VIDEO_RATIO = "video_ratio"
        const val KEY_VIDEO_RESOLUTION = "video_resolution"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_VIDEO_BITRATE = "video_bitrate"
        const val KEY_AE_AF_LOCKED = "ae_af_locked"
        const val KEY_ZOOM_SCALE = "zoom_scale"

        // Manual Param Keys
        const val KEY_PARAM_EV_AUTO = "param_ev_auto"
        const val KEY_PARAM_EV_VAL = "param_ev_val"
        const val KEY_PARAM_WB_AUTO = "param_wb_auto"
        const val KEY_PARAM_WB_VAL = "param_wb_val"
        const val KEY_PARAM_ISO_AUTO = "param_iso_auto"
        const val KEY_PARAM_ISO_VAL = "param_iso_val"
        const val KEY_PARAM_S_AUTO = "param_s_auto"
        const val KEY_PARAM_S_VAL = "param_s_val"
        const val KEY_PARAM_AF_AUTO = "param_af_auto"
        const val KEY_PARAM_AF_VAL = "param_af_val"
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
