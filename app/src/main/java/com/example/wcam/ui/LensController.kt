package com.example.wcam.ui

import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView

import com.example.wcam.util.ConfigManager

/**
 * Manages lens selection UI and logic.
 */
class LensController(
    private val buttons: LensButtons,
    private val listener: LensListener
) {

    data class LensButtons(
        val front: TextView,
        val wide: TextView,
        val main: TextView,
        val tele: TextView
    )

    interface LensListener {
        fun onLensSelected(logicalId: String, physicalId: String?)
    }

    fun setupLenses(cameras: List<ConfigManager.CameraConfig>) {
        // Reset all to GONE first
        val allButtons = listOf(buttons.front, buttons.wide, buttons.main, buttons.tele)
        allButtons.forEach { it.visibility = android.view.View.GONE }

        cameras.forEach { camera ->
            val btn = when {
                camera.logicalId == "1" -> buttons.front
                camera.id == "4" -> buttons.wide
                camera.id == "2" -> buttons.main
                camera.id == "3" -> buttons.tele
                else -> null
            }

            btn?.apply {
                visibility = android.view.View.VISIBLE
                text = camera.name
                setOnClickListener { listener.onLensSelected(camera.logicalId, camera.id) }
            }
        }
    }

    fun refreshUi(currentLogicalId: String, currentPhysicalId: String?) {
        val allButtons = listOf(buttons.front, buttons.wide, buttons.main, buttons.tele)
        allButtons.forEach {
            it.setTextColor(Color.parseColor("#B3FFFFFF"))
            it.textSize = 14f
            it.setTypeface(null, Typeface.NORMAL)
        }

        val activeBtn = when {
            currentLogicalId == "1" -> buttons.front
            currentPhysicalId == "4" -> buttons.wide
            currentPhysicalId == "2" -> buttons.main
            currentPhysicalId == "3" -> buttons.tele
            else -> buttons.main
        }

        activeBtn.setTextColor(Color.parseColor("#FFCC00"))
        activeBtn.textSize = 18f
        activeBtn.setTypeface(null, Typeface.BOLD)
    }
}
