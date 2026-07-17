package com.example.wcam.ui

import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView

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

    init {
        buttons.front.setOnClickListener { listener.onLensSelected("1", null) }
        buttons.wide.setOnClickListener { listener.onLensSelected("0", "4") }
        buttons.main.setOnClickListener { listener.onLensSelected("0", "2") }
        buttons.tele.setOnClickListener { listener.onLensSelected("0", "3") }
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
