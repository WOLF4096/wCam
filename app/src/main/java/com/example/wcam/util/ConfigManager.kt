package com.example.wcam.util

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

class ConfigManager(context: Context) {
    private var root: JSONObject? = null
    private var defaultConfig: JSONObject? = null
    private var currentDeviceConfig: JSONObject? = null

    init {
        try {
            val jsonString = context.assets.open("configs.json").bufferedReader().use { it.readText() }
            root = JSONObject(jsonString)
            defaultConfig = root?.optJSONObject("default_config")
            
            val deviceConfigs = root?.optJSONObject("device_configs")
            if (deviceConfigs != null) {
                // Use model name as key
                currentDeviceConfig = deviceConfigs.optJSONObject(Build.MODEL)
                if (currentDeviceConfig != null) {
                    Log.i("ConfigManager", "Matched device config for ${Build.MODEL}")
                }
            }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to load configs.json", e)
        }
    }

    fun getString(key: String, fallback: String): String {
        return currentDeviceConfig?.optString(key) 
            ?: defaultConfig?.optString(key) 
            ?: fallback
    }

    fun getInt(key: String, fallback: Int): Int {
        return currentDeviceConfig?.optInt(key, -1).takeIf { it != -1 }
            ?: defaultConfig?.optInt(key, fallback)
            ?: fallback
    }

    fun getLong(key: String, fallback: Long): Long {
        return currentDeviceConfig?.optLong(key, -1L).takeIf { it != -1L }
            ?: defaultConfig?.optLong(key, fallback)
            ?: fallback
    }

    fun getDouble(key: String, fallback: Double): Double {
        return currentDeviceConfig?.optDouble(key, Double.NaN).takeIf { it?.isNaN() == false }
            ?: defaultConfig?.optDouble(key, fallback)
            ?: fallback
    }

    fun getFloat(key: String, fallback: Float): Float {
        return getDouble(key, fallback.toDouble()).toFloat()
    }

    fun getIntList(key: String): List<Int> {
        val list = mutableListOf<Int>()
        val array = currentDeviceConfig?.optJSONArray(key) ?: defaultConfig?.optJSONArray(key)
        if (array != null) {
            for (i in 0 until array.length()) {
                list.add(array.getInt(i))
            }
        }
        return list
    }

    fun getCameras(): List<CameraConfig> {
        val list = mutableListOf<CameraConfig>()
        val array = currentDeviceConfig?.optJSONArray("cameras")
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(CameraConfig(
                    id = obj.getString("id"),
                    name = obj.optString("name", "Unknown"),
                    logicalId = obj.optString("logical_id", "0"),
                    supportsRaw = obj.optBoolean("supports_raw", false),
                    previewSize = obj.optString("preview_size", null)
                ))
            }
        }
        return list
    }

    fun getCameraConfig(id: String): CameraConfig? {
        return getCameras().find { it.id == id }
    }

    data class CameraConfig(
        val id: String,
        val name: String,
        val logicalId: String,
        val supportsRaw: Boolean,
        val previewSize: String?
    )
}
