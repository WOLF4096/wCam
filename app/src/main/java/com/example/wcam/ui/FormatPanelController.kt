package com.example.wcam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private val layoutVideoAdvancedOptions: LinearLayout,
    private val spinnerVideoFps: Spinner,
    private val spinnerVideoRes: Spinner,
    private val seekbarVideoBitrate: SeekBar,
    private val tvVideoBitrateTitle: TextView,
) {
    interface VideoSettingsListener {
        fun onVideoParamsChanged(fps: Int, res: String, bitrateMbps: Double)
        fun getVideoCapabilities(): List<Any> // Actually VideoSizeInfo
        fun getCurrentRatio(): String
    }

    interface PhotoSettingsListener {
        fun onPhotoParamsChanged(format: String, demosaic: String)
    }

    private var videoListener: VideoSettingsListener? = null
    private var photoListener: PhotoSettingsListener? = null

    fun setVideoSettingsListener(listener: VideoSettingsListener) {
        this.videoListener = listener
    }

    fun setPhotoSettingsListener(listener: PhotoSettingsListener) {
        this.photoListener = listener
    }

    fun update(isPhotoMode: Boolean, currentFormat: String = "", currentDemosaic: String = "") {
        val popupBg = ColorDrawable(Color.parseColor("#CC1A1A1A"))
        spinnerMainFormat.setPopupBackgroundDrawable(popupBg)
        spinnerDemosaicAlgo.setPopupBackgroundDrawable(popupBg)
        spinnerVideoFps.setPopupBackgroundDrawable(popupBg)
        spinnerVideoRes.setPopupBackgroundDrawable(popupBg)

        if (isPhotoMode) {
            layoutVideoAdvancedOptions.visibility = View.GONE
            val formats = FormatOptions.PHOTO_FORMATS
            spinnerMainFormat.adapter = createWhiteTextAdapter(formats)
            if (currentFormat.isNotEmpty()) {
                val idx = formats.indexOf(currentFormat)
                if (idx != -1) spinnerMainFormat.setSelection(idx)
            }

            spinnerMainFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = formats[position]
                    if (FormatOptions.showsJpgAdvancedOptions(selected)) {
                        layoutJpgAdvancedOptions.visibility = View.VISIBLE
                        setupDemosaicSpinner(currentDemosaic)
                    } else {
                        layoutJpgAdvancedOptions.visibility = View.GONE
                        photoListener?.onPhotoParamsChanged(selected, "")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        } else {
            layoutJpgAdvancedOptions.visibility = View.GONE
            layoutVideoAdvancedOptions.visibility = View.VISIBLE
            spinnerMainFormat.adapter = createWhiteTextAdapter(FormatOptions.VIDEO_FORMATS)
            spinnerMainFormat.onItemSelectedListener = null
            // Selection for video format should also be handled if needed, but for now we focus on the requested ones
        }
    }

    private fun setupDemosaicSpinner(currentDemosaic: String) {
        val algos = FormatOptions.DEMOSAIC_ALGORITHMS
        spinnerDemosaicAlgo.adapter = createWhiteTextAdapter(algos)
        
        val initialIdx = if (currentDemosaic.isNotEmpty()) {
            algos.indexOf(currentDemosaic).coerceAtLeast(0)
        } else {
            algos.indexOf("Fast").coerceAtLeast(0)
        }
        spinnerDemosaicAlgo.setSelection(initialIdx)

        spinnerDemosaicAlgo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val format = spinnerMainFormat.selectedItem.toString()
                val demosaic = algos[position]
                photoListener?.onPhotoParamsChanged(format, demosaic)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    fun setupVideoSpinners(fpsList: List<Int>, resList: List<String>, currentFps: Int, currentRes: String, currentBitrate: Double) {
        spinnerVideoFps.adapter = createWhiteTextAdapter(fpsList.map { it.toString() })
        val fpsIdx = fpsList.indexOf(currentFps).coerceAtLeast(0)
        spinnerVideoFps.setSelection(fpsIdx)

        spinnerVideoRes.adapter = createWhiteTextAdapter(resList)
        val resIdx = resList.indexOf(currentRes).coerceAtLeast(0)
        spinnerVideoRes.setSelection(resIdx)

        tvVideoBitrateTitle.text = "码率: ${String.format("%.1f", currentBitrate)} Mbps"
        val logMin = Math.log(0.1)
        val logMax = Math.log(100.0)
        val progress = ((Math.log(currentBitrate) - logMin) / (logMax - logMin) * 1000).toInt()
        seekbarVideoBitrate.progress = progress

        seekbarVideoBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val bitrate = Math.exp(logMin + (logMax - logMin) * progress / 1000.0)
                tvVideoBitrateTitle.text = "码率: ${String.format("%.1f", bitrate)} Mbps"
                if (fromUser) {
                    videoListener?.onVideoParamsChanged(
                        spinnerVideoFps.selectedItem?.toString()?.toIntOrNull() ?: 30,
                        spinnerVideoRes.selectedItem?.toString() ?: "",
                        bitrate
                    )
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val itemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val fps = spinnerVideoFps.selectedItem?.toString()?.toIntOrNull() ?: 30
                val res = spinnerVideoRes.selectedItem?.toString() ?: ""
                val bitrate = Math.exp(logMin + (logMax - logMin) * seekbarVideoBitrate.progress / 1000.0)
                videoListener?.onVideoParamsChanged(fps, res, bitrate)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spinnerVideoFps.onItemSelectedListener = itemSelectedListener
        spinnerVideoRes.onItemSelectedListener = itemSelectedListener
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
                    val newTopPadding = (paddingTop * 0.5f).toInt()
                    val newBottomPadding = (paddingBottom * 0.5f).toInt()
                    setPadding(paddingLeft, newTopPadding, paddingRight, newBottomPadding)
                }
                view.setBackgroundColor(Color.parseColor("#CC1A1A1A"))
                return view
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
}
