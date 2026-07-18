package com.example.wcam.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.example.wcam.PreferenceManager
import com.example.wcam.R
import com.example.wcam.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferenceManager: PreferenceManager

    private val noneAlgos = listOf("RGB通道", "RGB合并", "原始拜尔", "灰度")
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

        // Format Chips
        val formatChips = listOf(binding.chipJpg, binding.chipPng, binding.chipRaw)
        formatChips.forEach { chip ->
            chip.setOnClickListener {
                formatChips.forEach { it.isSelected = false }
                chip.isSelected = true
                saveFormat(chip.text.toString())
                updateVisibilityByFormat(chip.text.toString())
            }
        }

        // JPG Quality
        binding.seekbarQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvQualityVal.text = "$progress%"
                if (fromUser) {
                    preferenceManager.saveInt(PreferenceManager.KEY_JPG_QUALITY, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Algo Segments
        binding.segNone.setOnClickListener {
            selectSegment(true)
            preferenceManager.saveBoolean("settings_algo_is_demosaic", false)
        }
        binding.segDemosaic.setOnClickListener {
            selectSegment(false)
            preferenceManager.saveBoolean("settings_algo_is_demosaic", true)
        }

        // Populate Algorithm Grids
        populateAlgoGrid(binding.gridAlgoNone, noneAlgos)
        populateAlgoGrid(binding.gridAlgoDemosaic, demosaicAlgos)
    }

    private fun loadSettings() {
        // Load Format
        val currentFormat = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        when (currentFormat) {
            "JPG" -> binding.chipJpg.isSelected = true
            "PNG" -> binding.chipPng.isSelected = true
            "RAW" -> binding.chipRaw.isSelected = true
            "JPG + RAW" -> {
                binding.chipJpg.isSelected = true
            }
        }
        updateVisibilityByFormat(currentFormat)

        // Load JPG Quality
        val quality = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
        binding.seekbarQuality.progress = quality
        binding.tvQualityVal.text = "$quality%"

        // Load Algo Segment
        val isDemosaic = preferenceManager.getBoolean("settings_algo_is_demosaic", true)
        selectSegment(!isDemosaic)

        // Load Active Algo
        val currentAlgo = preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast")
        val shortAlgo = currentAlgo.split('：', ':')[0].trim()
        
        // Find and select the chip in grids
        selectAlgoChip(shortAlgo)
    }

    private fun populateAlgoGrid(grid: GridLayout, algos: List<String>) {
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
                    clearAlgoSelection()
                    isSelected = true
                    preferenceManager.saveString(PreferenceManager.KEY_DEMOSAIC_ALGO, algo)
                }
            }
            grid.addView(tv)
        }
    }

    private fun selectSegment(isNone: Boolean) {
        binding.segNone.isSelected = isNone
        binding.segDemosaic.isSelected = !isNone
        binding.gridAlgoNone.visibility = if (isNone) View.VISIBLE else View.GONE
        binding.gridAlgoDemosaic.visibility = if (isNone) View.GONE else View.VISIBLE
    }

    private fun selectAlgoChip(algoName: String) {
        clearAlgoSelection()
        val grids = listOf(binding.gridAlgoNone, binding.gridAlgoDemosaic)
        for (grid in grids) {
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i) as? TextView
                if (child?.text == algoName) {
                    child.isSelected = true
                    return
                }
            }
        }
    }

    private fun clearAlgoSelection() {
        val grids = listOf(binding.gridAlgoNone, binding.gridAlgoDemosaic)
        grids.forEach { grid ->
            for (i in 0 until grid.childCount) {
                grid.getChildAt(i).isSelected = false
            }
        }
    }

    private fun saveFormat(format: String) {
        preferenceManager.saveString(PreferenceManager.KEY_OUTPUT_FORMAT, format)
    }

    private fun updateVisibilityByFormat(format: String) {
        val showJpg = format == "JPG" || format == "JPG + RAW"
        binding.layoutJpgQuality.visibility = if (showJpg) View.VISIBLE else View.GONE
        
        val isRawOnly = format == "RAW"
        binding.cardAlgo.visibility = if (isRawOnly) View.GONE else View.VISIBLE
    }
}
