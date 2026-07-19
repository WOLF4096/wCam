package com.example.wcam.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.addTextChangedListener
import com.example.wcam.PreferenceManager
import com.example.wcam.R
import com.example.wcam.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferenceManager: PreferenceManager

    private val simpleAlgos = listOf("RGB通道", "原始拜尔", "灰度", "4合1", "9合1", "16合1", "4倍亮度")
    private val demosaicAlgos = listOf("AMaZE", "RCD", "DCB", "LMMSE", "IGV", "AHD", "EAHD", "HPHD", "VNG4", "Fast")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        
        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        // Format Chips (Multi-select)
        setupFormatChip(binding.chipRaw, "RAW")
        setupFormatChip(binding.chipJpg, "JPG")
        setupFormatChip(binding.chipHeif, "HEIF")

        // JPG Quality
        binding.seekbarQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvQualityVal.text = "${progress}%"
                if (fromUser) preferenceManager.saveInt(PreferenceManager.KEY_JPG_QUALITY, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Location Switch
        binding.switchLocation.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.saveBoolean("save_location", isChecked)
        }

        // Algo Segments
        binding.segSimple.setOnClickListener {
            selectSegment(true)
            preferenceManager.saveBoolean("settings_algo_is_demosaic", false)
        }
        binding.segDemosaic.setOnClickListener {
            selectSegment(false)
            preferenceManager.saveBoolean("settings_algo_is_demosaic", true)
        }

        // Populate Algorithm Grids
        populateAlgoGrid(binding.gridAlgoNone, simpleAlgos, false)
        populateAlgoGrid(binding.gridAlgoDemosaic, demosaicAlgos, true)

        // Histogram Switch
        binding.switchHistogram.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.saveBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, isChecked)
        }

        // Watermark
        binding.segWmNone.setOnClickListener { selectWmMode("None") }
        binding.segWmInternal.setOnClickListener { selectWmMode("Internal") }
        binding.segWmExternal.setOnClickListener { selectWmMode("External") }

        binding.chipWmTime.setOnClickListener { toggleWmOption("wm_show_time", it as TextView) }
        binding.chipWmParams.setOnClickListener { toggleWmOption("wm_show_params", it as TextView) }
        binding.chipWmText.setOnClickListener { 
            val newState = toggleWmOption("wm_show_text", it as TextView)
            binding.etWmCustom.visibility = if (newState) View.VISIBLE else View.GONE
        }
        binding.etWmCustom.addTextChangedListener { preferenceManager.saveString("wm_custom_text", it.toString()) }
    }

    private fun setupFormatChip(chip: TextView, formatName: String) {
        chip.setOnClickListener {
            val formatString = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
            val formats = formatString.split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (chip.isSelected) {
                if (formats.size > 1) { // Keep at least one
                    formats.remove(formatName)
                    chip.isSelected = false
                }
            } else {
                formats.add(formatName)
                chip.isSelected = true
            }
            val newFormatString = formats.joinToString(",")
            preferenceManager.saveString(PreferenceManager.KEY_OUTPUT_FORMAT, newFormatString)
            updateVisibilityByFormats(formats)
        }
    }

    private fun loadSettings() {
        // Load Formats
        val formatString = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        val formats = formatString.split(",").toSet()
        binding.chipRaw.isSelected = formats.contains("RAW")
        binding.chipJpg.isSelected = formats.contains("JPG")
        binding.chipHeif.isSelected = formats.contains("HEIF")
        updateVisibilityByFormats(formats)

        // Load JPG Quality
        val quality = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
        binding.seekbarQuality.progress = quality
        binding.tvQualityVal.text = "${quality}%"

        // Location
        binding.switchLocation.isChecked = preferenceManager.getBoolean("save_location", true)

        // Algo Segment
        val isDemosaic = preferenceManager.getBoolean("settings_algo_is_demosaic", true)
        selectSegment(!isDemosaic)

        // Load specific algo choices
        val lastSimple = preferenceManager.getString("algo_simple_choice", "RGB通道")
        val lastDemosaic = preferenceManager.getString("algo_demosaic_choice", "Fast")
        selectAlgoInGrid(binding.gridAlgoNone, lastSimple)
        selectAlgoInGrid(binding.gridAlgoDemosaic, lastDemosaic)

        // Histogram
        binding.switchHistogram.isChecked = preferenceManager.getBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, false)

        // Watermark
        selectWmMode(preferenceManager.getString("wm_mode", "None"))
        binding.chipWmTime.isSelected = preferenceManager.getBoolean("wm_show_time", true)
        binding.chipWmParams.isSelected = preferenceManager.getBoolean("wm_show_params", true)
        binding.chipWmText.isSelected = preferenceManager.getBoolean("wm_show_text", false)
        binding.etWmCustom.setText(preferenceManager.getString("wm_custom_text", "wCam"))
        binding.etWmCustom.visibility = if (binding.chipWmText.isSelected) View.VISIBLE else View.GONE
    }

    private fun populateAlgoGrid(grid: GridLayout, algos: List<String>, isDemosaicList: Boolean) {
        grid.removeAllViews()
        val context = grid.context
        val density = context.resources.displayMetrics.density
        val margin = (4 * density).toInt()

        algos.forEach { algo ->
            val tv = TextView(context).apply {
                text = algo
                background = ResourcesCompat.getDrawable(resources, R.drawable.sl_settings_chip, null)
                setTextColor(ResourcesCompat.getColorStateList(resources, R.color.sl_settings_chip_text, null))
                gravity = Gravity.CENTER
                val py = (8 * density).toInt()
                setPadding(0, py, 0, py)
                textSize = 13f
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(margin, margin, margin, margin)
                }
                isClickable = true
                setOnClickListener {
                    clearAlgoSelection(grid)
                    isSelected = true
                    if (isDemosaicList) {
                        preferenceManager.saveString("algo_demosaic_choice", algo)
                        if (preferenceManager.getBoolean("settings_algo_is_demosaic", true)) {
                            preferenceManager.saveString(PreferenceManager.KEY_DEMOSAIC_ALGO, algo)
                        }
                    } else {
                        preferenceManager.saveString("algo_simple_choice", algo)
                        if (!preferenceManager.getBoolean("settings_algo_is_demosaic", true)) {
                            preferenceManager.saveString(PreferenceManager.KEY_DEMOSAIC_ALGO, algo)
                        }
                    }
                }
            }
            grid.addView(tv)
        }
    }

    private fun selectSegment(isSimple: Boolean) {
        binding.segSimple.isSelected = isSimple
        binding.segDemosaic.isSelected = !isSimple
        binding.gridAlgoNone.visibility = if (isSimple) View.VISIBLE else View.GONE
        binding.gridAlgoDemosaic.visibility = if (isSimple) View.GONE else View.VISIBLE
        
        val algoKey = if (isSimple) "algo_simple_choice" else "algo_demosaic_choice"
        val fallback = if (isSimple) "RGB通道" else "Fast"
        preferenceManager.saveString(PreferenceManager.KEY_DEMOSAIC_ALGO, preferenceManager.getString(algoKey, fallback))
    }

    private fun selectAlgoInGrid(grid: GridLayout, name: String) {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) as? TextView
            if (child?.text == name) {
                child.isSelected = true
                break
            }
        }
    }

    private fun clearAlgoSelection(grid: GridLayout) {
        for (i in 0 until grid.childCount) {
            grid.getChildAt(i).isSelected = false
        }
    }

    private fun selectWmMode(mode: String) {
        binding.segWmNone.isSelected = mode == "None"
        binding.segWmInternal.isSelected = mode == "Internal"
        binding.segWmExternal.isSelected = mode == "External"
        
        binding.panelWmOptions.visibility = if (mode != "None") View.VISIBLE else View.GONE
        preferenceManager.saveString("wm_mode", mode)
    }

    private fun toggleWmOption(prefKey: String, tv: TextView): Boolean {
        val newState = !tv.isSelected
        tv.isSelected = newState
        preferenceManager.saveBoolean(prefKey, newState)
        return newState
    }

    private fun updateVisibilityByFormats(formats: Set<String>) {
        val hasJpg = formats.contains("JPG")
        binding.layoutJpgQuality.visibility = if (hasJpg) View.VISIBLE else View.GONE
        
        // Hide location if ONLY HEIF is selected
        val onlyHeif = formats.size == 1 && formats.contains("HEIF")
        binding.layoutLocation.visibility = if (onlyHeif) View.GONE else View.VISIBLE

        val isRawOnly = formats.size == 1 && formats.contains("RAW")
        binding.cardAlgo.visibility = if (isRawOnly) View.GONE else View.VISIBLE
        binding.cardWatermark.visibility = if (isRawOnly) View.GONE else View.VISIBLE
    }
}
