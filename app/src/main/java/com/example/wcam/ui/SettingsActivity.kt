package com.example.wcam.ui

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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

        // Camera Info
        binding.btnGetCameraInfo.setOnClickListener { getCameraDetailedInfo() }
        binding.btnCopyCameraInfo.setOnClickListener {
            val info = binding.etCameraInfo.text.toString()
            if (info.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Camera Info", info)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCameraDetailedInfo() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        
        val allIds = mutableSetOf<String>()
        try { allIds.addAll(cameraManager.cameraIdList) } catch (_: Exception) {}
        for (i in 0..63) { allIds.add(i.toString()) }

        allIds.sortedBy { it.toIntOrNull() ?: 999 }.forEach { id ->
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isLogical = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                
                sb.append("==============================\n")
                sb.append("摄像头ID: $id ${if (isLogical) "[逻辑多摄]" else "[物理镜片/独立]"}\n")
                
                // 1. 基本方位与硬件等级
                val facing = when(chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> "后置 (BACK)"
                    CameraMetadata.LENS_FACING_FRONT -> "前置 (FRONT)"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "外部 (EXTERNAL)"
                    else -> "未知"
                }
                val hwLevel = when(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "Unknown"
                }
                sb.append("    方位: $facing\n")
                sb.append("    硬件等级: $hwLevel\n")
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.isNotEmpty()) sb.append("    物理子摄列表: ${physicalIds.joinToString(", ")}\n")
                }

                // 2. 传感器核心参数
                sb.append("\n    [传感器参数]\n")
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                sb.append("    像素阵列: ${pixelArray?.width}x${pixelArray?.height}\n")
                sb.append("    有效区域: ${activeArray?.left},${activeArray?.top},${activeArray?.right},${activeArray?.bottom}\n")
                sb.append("    物理尺寸: ${sensorSize?.width} x ${sensorSize?.height} mm\n")
                
                val bayer = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                val bayerStr = when(bayer) {
                    0 -> "RGGB" ; 1 -> "GRBG" ; 2 -> "GBRG" ; 3 -> "BGGR" ; 4 -> "RGB" ; else -> "未知($bayer)"
                }
                sb.append("    拜尔阵列 (CFA): $bayerStr\n")
                
                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
                val blackLevels = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                sb.append("    白点值: $whiteLevel\n")
                sb.append("    黑电平阵列: ${blackLevels?.toString() ?: "N/A"}\n")

                // 3. 镜头与曝光参数
                sb.append("\n    [镜头与曝光]\n")
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                sb.append("    光圈: f/${apertures?.joinToString(", f/") ?: "N/A"}\n")
                sb.append("    焦距: ${focalLengths?.joinToString(", ") ?: "N/A"} mm\n")
                
                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                sb.append("    ISO范围: ${isoRange?.lower} ~ ${isoRange?.upper}\n")
                sb.append("    快门范围: ${expRange?.lower?.div(1000000.0)}ms ~ ${expRange?.upper?.div(1000000000.0)}s\n")

                // 4. 特性能力支持
                sb.append("\n    [支持特性]\n")
                capabilities.forEach { cap ->
                    val capStr = when(cap) {
                        0 -> "BACKWARD_COMPATIBLE"
                        1 -> "MANUAL_SENSOR"
                        2 -> "MANUAL_POST_PROCESSING"
                        3 -> "RAW"
                        4 -> "PRIVATE_REPROCESS"
                        5 -> "READ_SENSOR_SETTINGS"
                        6 -> "BURST_CAPTURE"
                        7 -> "YUV_REPROCESS"
                        8 -> "DEPTH_OUTPUT"
                        9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"
                        10 -> "LOGICAL_MULTI_CAMERA"
                        11 -> "MONOCHROME"
                        12 -> "SECURE_IMAGE_DATA"
                        else -> "OTHER($cap)"
                    }
                    sb.append("    - $capStr\n")
                }

                // 5. 完整流配置 (全部分辨率展开)
                sb.append("\n    [流配置与分辨率详情]\n")
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                map?.outputFormats?.forEach { format ->
                    val formatName = when (format) {
                        ImageFormat.JPEG -> "JPEG"
                        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                        ImageFormat.RAW10 -> "RAW10"
                        ImageFormat.RAW12 -> "RAW12"
                        ImageFormat.YUV_420_888 -> "YUV_420_888"
                        ImageFormat.HEIC -> "HEIF/HEIC"
                        ImageFormat.PRIVATE -> "PRIVATE/Surface"
                        else -> "0x${Integer.toHexString(format).uppercase()}"
                    }
                    val outputSizes = map.getOutputSizes(format)
                    sb.append("    ● $formatName:\n")
                    outputSizes?.forEach { size ->
                        val fps = map.getOutputMinFrameDuration(format, size)
                        val fpsStr = if (fps > 0) " (MinDuration: ${String.format(Locale.US, "%.1f", 1e9/fps)}fps)" else ""
                        sb.append("        - ${size.width} x ${size.height}$fpsStr\n")
                    }
                }
                sb.append("\n")
            } catch (_: Exception) {}
        }
        
        if (sb.isEmpty()) {
            binding.etCameraInfo.setText("未获取到任何有效的摄像头信息，请检查权限。")
        } else {
            binding.etCameraInfo.setText(sb.toString())
        }
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
