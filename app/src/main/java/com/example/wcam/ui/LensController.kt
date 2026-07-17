package com.example.wcam.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.wcam.util.ConfigManager

/**
 * Manages lens selection UI and logic dynamically based on configuration.
 */
class LensController(
    private val container: LinearLayout,
    private val listener: LensListener
) {

    interface LensListener {
        fun onLensSelected(logicalId: String, physicalId: String?)
    }

    private val buttonMap = mutableMapOf<String, TextView>() // Key is physicalId or logicalId if physical is null

    fun setupLenses(cameras: List<ConfigManager.CameraConfig>, order: List<String>) {
        container.removeAllViews()
        buttonMap.clear()

        // Sort cameras based on the provided order list
        val sortedCameras = if (order.isNotEmpty()) {
            cameras.sortedBy { camera ->
                val index = order.indexOf(camera.name)
                if (index != -1) index else Int.MAX_VALUE
            }.filter { order.contains(it.name) }
        } else {
            cameras
        }

        sortedCameras.forEach { camera ->
            val tv = TextView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = camera.name
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = 14f
                setOnClickListener { listener.onLensSelected(camera.logicalId, camera.id) }
            }
            
            container.addView(tv)
            // Use physical ID as key, fallback to logical ID for front camera
            val key = if (camera.logicalId == "1") "FRONT_${camera.id}" else camera.id
            buttonMap[key] = tv
        }
    }

    fun refreshUi(currentLogicalId: String, currentPhysicalId: String?) {
        buttonMap.values.forEach {
            it.setTextColor(Color.parseColor("#B3FFFFFF"))
            it.textSize = 14f
            it.setTypeface(null, Typeface.NORMAL)
        }

        val key = if (currentLogicalId == "1") {
            // Find the front camera key
            buttonMap.keys.find { it.startsWith("FRONT_") }
        } else {
            currentPhysicalId
        }

        buttonMap[key]?.apply {
            setTextColor(Color.parseColor("#FFCC00"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
    }
}
