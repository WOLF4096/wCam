package com.example.wcam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

object FormatOptions {
    val PHOTO_FORMATS = listOf("JPG", "JPG + RAW", "RAW")
    val VIDEO_FORMATS = listOf("H264", "H265", "ProRes", "MPEG4")
    val DEMOSAIC_ALGORITHMS = listOf("AMaZE", "RCD", "DCB", "LMMSE", "IGV", "AHD", "EAHD", "HPHD", "VNG4", "Fast")

    fun showsJpgAdvancedOptions(format: String): Boolean {
        return format == "JPG" || format == "JPG + RAW"
    }
}

class FormatPanelController(
    private val context: Context,
    private val spinnerMainFormat: Spinner,
    private val layoutJpgAdvancedOptions: LinearLayout,
    private val spinnerDemosaicAlgo: Spinner,
) {
    fun update(isPhotoMode: Boolean) {
        val popupBg = ColorDrawable(Color.parseColor("#CC1A1A1A"))
        spinnerMainFormat.setPopupBackgroundDrawable(popupBg)
        spinnerDemosaicAlgo.setPopupBackgroundDrawable(popupBg)

        if (isPhotoMode) {
            spinnerMainFormat.adapter = createWhiteTextAdapter(FormatOptions.PHOTO_FORMATS)
            spinnerMainFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = FormatOptions.PHOTO_FORMATS[position]
                    if (FormatOptions.showsJpgAdvancedOptions(selected)) {
                        layoutJpgAdvancedOptions.visibility = View.VISIBLE
                        setupDemosaicSpinner()
                    } else {
                        layoutJpgAdvancedOptions.visibility = View.GONE
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        } else {
            spinnerMainFormat.adapter = createWhiteTextAdapter(FormatOptions.VIDEO_FORMATS)
            layoutJpgAdvancedOptions.visibility = View.GONE
            spinnerMainFormat.onItemSelectedListener = null
        }
    }

    private fun setupDemosaicSpinner() {
        spinnerDemosaicAlgo.adapter = createWhiteTextAdapter(FormatOptions.DEMOSAIC_ALGORITHMS)
        spinnerDemosaicAlgo.setSelection(0)
    }

    private fun createWhiteTextAdapter(data: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, data) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.WHITE)
                (view as? TextView)?.textSize = 14f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    // Reduce vertical padding by 50% to shrink spacing
                    val newTopPadding = (paddingTop * 0.5f).toInt()
                    val newBottomPadding = (paddingBottom * 0.5f).toInt()
                    setPadding(paddingLeft, newTopPadding, paddingRight, newBottomPadding)
                }
                // Set drop down background to match panel
                view.setBackgroundColor(Color.parseColor("#CC1A1A1A"))
                return view
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
}
