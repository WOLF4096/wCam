package com.example.wcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.util.Size
import android.view.ScaleGestureDetector
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.math.abs

import com.example.wcam.camera.CameraCapabilities
import com.example.wcam.camera.CaptureFileNames
import com.example.wcam.camera.CaptureOrientationCalculator
import com.example.wcam.camera.DeviceOrientationTracker
import com.example.wcam.camera.EXIF_ORIENTATION_NORMAL
import com.example.wcam.camera.PARAM_TEXT_AUTO_COLOR
import com.example.wcam.camera.PARAM_TEXT_MANUAL_COLOR
import com.example.wcam.camera.ParamKey
import com.example.wcam.camera.FlashMode
import com.example.wcam.camera.PhotoParamMapper
import com.example.wcam.camera.PhotoParamState
import com.example.wcam.camera.RawMediaStore
import com.example.wcam.ui.FormatPanelController
import com.example.wcam.ui.HistogramView
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var btnShutter: View
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var btnFlash: TextView
    private lateinit var btnHistogram: TextView
    private lateinit var btnGridLines: TextView
    private lateinit var btnResolution: TextView
    private lateinit var btnFormat: TextView
    private lateinit var btnModeSwitch: TextView
    private lateinit var btnGallery: TextView

    private lateinit var viewGridLines: GridLineView
    private lateinit var viewHistogram: HistogramView
    private lateinit var viewCropOverlay: CropOverlayView
    private lateinit var tvPhysicalRes: TextView
    private lateinit var viewFocusRing: View

    private lateinit var btnLensFront: TextView
    private lateinit var btnLensWide: TextView
    private lateinit var btnLensMain: TextView
    private lateinit var btnLensTele: TextView

    private lateinit var panelFormatSettings: LinearLayout
    private lateinit var spinnerMainFormat: Spinner
    private lateinit var layoutJpgAdvancedOptions: LinearLayout
    private lateinit var spinnerDemosaicAlgo: Spinner
    private lateinit var tvJpgQualityTitle: TextView
    private lateinit var seekbarJpgQuality: SeekBar
    private lateinit var formatPanelController: FormatPanelController
    private lateinit var rawMediaStore: RawMediaStore
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var deviceOrientationTracker: DeviceOrientationTracker
    private lateinit var tvValEv: TextView
    private lateinit var tvValWb: TextView
    private lateinit var tvValIso: TextView
    private lateinit var tvValS: TextView
    private lateinit var tvValAf: TextView

    private var currentLogicalCameraId = "0"
    private var currentPhysicalCameraId: String? = "2"
    private var activeCameraCharacteristics: CameraCharacteristics? = null
    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    private var histogramImageReader: ImageReader? = null
    private var pendingRawImage: Image? = null
    private var pendingJpegImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var isCaptureInProgress = false
    private var isRawCaptureComplete = false
    private var isJpegCaptureComplete = false

    private var selectedResolution: Size? = null
    private var isFollowZoom = true // Now repurposed for maximized crop

    private var cropScale = 1.0f
    private var cropRatio = 4f / 3f
    private var customRatioW = 4
    private var customRatioH = 3

    private var photoParamState: PhotoParamState = PhotoParamState.default()
    private var previewAutoIso: Int? = null
    private var previewAutoShutterNanos: Long? = null
    private var previewAutoCompensation: Int? = null
    private var previewAutoKelvinLabel: String? = null
    private var previewAutoFocusDistance: Float? = null
    private var cameraCapabilities: CameraCapabilities = CameraCapabilities.EMPTY
    private var recentPhotoUri: Uri? = null
    private var currentFlashMode: FlashMode = FlashMode.OFF
    private var currentFlashSupported: Boolean = false
    private var currentSensorOrientation: Int = 0
    private var currentLensFacing: Int? = null

    private var isGridVisible = false
    @Volatile
    private var isHistogramVisible = false
    private var isPhotoMode = true
    private var isFormatPanelOpen = false

    private var selectedVideoRatioStr: String = "16:9"
    private var selectedVideoSizeStr: String = "1920x1080"
    private var selectedVideoFps: Int = 30
    private var allVideoCapabilities = mutableMapOf<String, List<VideoSizeInfo>>()

    private var aeState = CameraMetadata.CONTROL_AE_STATE_INACTIVE
    private var isPrecaptureCallbackActive = false
    private var isAeAfLocked = false

    private var currentZoomRatio = 1.0f
    private var maxZoomRatio = 10.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private external fun nativeCalculateHistogram(
        pixels: IntArray,
        width: Int,
        height: Int,
        rHistogram: IntArray,
        gHistogram: IntArray,
        bHistogram: IntArray
    )

    data class VideoSizeInfo(val width: Int, val height: Int, val maxFps: Int, val isHighSpeed: Boolean = false) {
        val ratio: Float get() = width.toFloat() / height
        val ratioStr: String get() {
            val r = width.toFloat() / height
            return when {
                abs(r - 1.0f) < 0.01f -> "1:1"
                abs(r - 1.333f) < 0.01f || abs(r - 0.75f) < 0.01f -> "4:3"
                abs(r - 1.777f) < 0.01f || abs(r - 0.5625f) < 0.01f -> "16:9"
                abs(r - 2.0f) < 0.01f || abs(r - 0.5f) < 0.01f -> "2:1"
                abs(r - 2.333f) < 0.01f -> "21:9"
                else -> "${width}:${height}"
            }
        }
    }

    private val histogramTask = object : Runnable {
        override fun run() {
            if (isHistogramVisible && textureView.isAvailable) {
                try {
                    // Sample a small version of the preview for performance
                    val bitmap = textureView.getBitmap(160, 120)
                    if (bitmap != null) {
                        val pixels = IntArray(160 * 120)
                        bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 120)
                        
                        val rHist = IntArray(256)
                        val gHist = IntArray(256)
                        val bHist = IntArray(256)
                        
                        nativeCalculateHistogram(pixels, 160, 120, rHist, gHist, bHist)
                        
                        runOnUiThread { 
                            viewHistogram.updateData(rHist, gHist, bHist) 
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HistogramDebug", "Failed to sample preview", e)
                }
            }
            backgroundHandler?.postDelayed(this, 100)
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1
        
        init {
            System.loadLibrary("wcam")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        logAllCameraStreams()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        initViews()
        setupListeners()
        setupParamGestures()
        setupZoomGesture()
        loadSettings()
    }

    private fun loadSettings() {
        val lastLens = preferenceManager.getString(PreferenceManager.KEY_LENS_ID, "0")
        val lastPhysical = preferenceManager.getString(PreferenceManager.KEY_PHYSICAL_LENS_ID, "2")
        currentLogicalCameraId = lastLens
        currentPhysicalCameraId = lastPhysical
        
        isGridVisible = preferenceManager.getBoolean(PreferenceManager.KEY_GRID_VISIBLE, false)
        viewGridLines.visibility = if (isGridVisible) View.VISIBLE else View.GONE
        btnGridLines.setTextColor(if (isGridVisible) Color.parseColor("#FFCC00") else Color.WHITE)

        isHistogramVisible = preferenceManager.getBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, false)
        viewHistogram.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
        tvPhysicalRes.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
        btnHistogram.setTextColor(if (isHistogramVisible) Color.parseColor("#FFCC00") else Color.WHITE)
        
        isPhotoMode = preferenceManager.getBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, true)
        updateModeUI()

        selectedVideoRatioStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RATIO, "16:9")
        selectedVideoSizeStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RESOLUTION, "1920x1080")
        selectedVideoFps = preferenceManager.getInt(PreferenceManager.KEY_VIDEO_FPS, 30)

        if (isPhotoMode) {
            btnResolution.text = "4:3"
        } else {
            btnResolution.text = "$selectedVideoSizeStr@$selectedVideoFps"
        }
        updateCropOverlayUI()
    }

    private fun updateModeUI() {
        if (isPhotoMode) {
            btnModeSwitch.text = "录像"
            btnResolution.text = "4:3"
        } else {
            btnModeSwitch.text = "照片"
            btnResolution.text = "$selectedVideoSizeStr@$selectedVideoFps"
        }
        if (isFormatPanelOpen) {
            updateFormatPanelUI()
        }
    }

    private fun saveCurrentLensSettings() {
        preferenceManager.saveString(PreferenceManager.KEY_LENS_ID, currentLogicalCameraId)
        currentPhysicalCameraId?.let { preferenceManager.saveString(PreferenceManager.KEY_PHYSICAL_LENS_ID, it) }
    }

    private fun setupZoomGesture() {
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                triggerFocusAndMetering(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                toggleAeAfLock()
            }
        })

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cropScale = (cropScale * detector.scaleFactor).coerceIn(0.1f, 1.0f)
                updateCropOverlayUI()
                return true
            }
        })

        textureView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isFormatPanelOpen) {
                    val location = IntArray(2)
                    panelFormatSettings.getLocationOnScreen(location)
                    val x = event.rawX
                    val y = event.rawY
                    if (x < location[0] || x > location[0] + panelFormatSettings.width ||
                        y < location[1] || y > location[1] + panelFormatSettings.height) {
                        isFormatPanelOpen = false
                        panelFormatSettings.visibility = View.GONE
                        btnFormat.setTextColor(Color.WHITE)
                    }
                }
            }
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun triggerFocusAndMetering(x: Float, y: Float) {
        val sensorArraySize = activeCameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        
        // Visual indicator
        viewFocusRing.x = x - viewFocusRing.width / 2
        viewFocusRing.y = y - viewFocusRing.height / 2 + textureView.top
        viewFocusRing.visibility = View.VISIBLE
        viewFocusRing.alpha = 1.0f
        viewFocusRing.scaleX = 1.2f
        viewFocusRing.scaleY = 1.2f
        viewFocusRing.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.5f).setDuration(300).withEndAction {
            viewFocusRing.postDelayed({ viewFocusRing.visibility = View.GONE }, 1000)
        }.start()

        // Map touch coordinates to sensor coordinates
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        
        // Use transform-aware mapping (simplified for typical landscape sensor)
        val sensorX = (y / viewH * sensorArraySize.width()).toInt()
        val sensorY = ((1.0f - x / viewW) * sensorArraySize.height()).toInt()
        
        val halfRectSize = 100
        val focusRect = android.graphics.Rect(
            (sensorX - halfRectSize).coerceIn(0, sensorArraySize.width()),
            (sensorY - halfRectSize).coerceIn(0, sensorArraySize.height()),
            (sensorX + halfRectSize).coerceIn(0, sensorArraySize.width()),
            (sensorY + halfRectSize).coerceIn(0, sensorArraySize.height())
        )
        
        val meteringRect = android.hardware.camera2.params.MeteringRectangle(focusRect, 1000)
        
        previewRequestBuilder?.apply {
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }
        
        try {
            captureSession?.capture(previewRequestBuilder!!.build(), previewCaptureCallback, backgroundHandler)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            resumePreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleAeAfLock() {
        isAeAfLocked = !isAeAfLocked
        previewRequestBuilder?.apply {
            if (isAeAfLocked) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_EDOF) // Locks focus
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                showToast("AE/AF 已锁定")
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                showToast("AE/AF 已解锁")
            }
        }
        applyPhotoParamsToPreview()
    }

    private var currentCropRectInSensor: android.graphics.Rect? = null

    private fun updateCropOverlayUI() {
        val characteristics = activeCameraCharacteristics ?: return
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        
        val sensorW = sensorSize.width()
        val sensorH = sensorSize.height()
        val sensorRatio = sensorW.toFloat() / sensorH

        // Calculate max possible size within sensor for the given cropRatio
        val maxW: Float
        val maxH: Float
        if (cropRatio > sensorRatio) {
            maxW = sensorW.toFloat()
            maxH = sensorW / cropRatio
        } else {
            maxH = sensorH.toFloat()
            maxW = sensorH * cropRatio
        }

        val currentW = maxW * cropScale
        val currentH = maxH * cropScale

        // Display resolution
        val mp = (currentW * currentH) / 1_000_000.0
        val resText = "${currentW.toInt()}x${currentH.toInt()}@${String.format("%.1f", mp)}MP"
        runOnUiThread {
            tvPhysicalRes.text = resText
            tvPhysicalRes.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
        }

        // Map to preview coordinates
        // Store for capture
        val sensorLeft = (sensorW - currentW) / 2
        val sensorTop = (sensorH - currentH) / 2
        currentCropRectInSensor = android.graphics.Rect(
            sensorLeft.toInt(),
            sensorTop.toInt(),
            (sensorLeft + currentW).toInt(),
            (sensorTop + currentH).toInt()
        )
        
        // Relative coordinates for the overlay view
        // Swap mapping for portrait display (assuming 90 deg sensor orientation)
        val viewRelW = currentH / sensorH
        val viewRelH = currentW / sensorW

        val relLeft = (1.0f - viewRelW) / 2
        val relTop = (1.0f - viewRelH) / 2
        val relRight = relLeft + viewRelW
        val relBottom = relTop + viewRelH

        runOnUiThread {
            viewCropOverlay.setCropRect(relLeft, relTop, relRight, relBottom)
        }
    }

    private fun initViews() {
        textureView = findViewById(R.id.camera_preview)
        btnShutter = findViewById(R.id.btn_shutter)
        btnFlash = findViewById(R.id.btn_flash)
        btnHistogram = findViewById(R.id.btn_histogram)
        btnGridLines = findViewById(R.id.btn_grid_lines)
        btnResolution = findViewById(R.id.btn_resolution)
        btnFormat = findViewById(R.id.btn_format)
        btnModeSwitch = findViewById(R.id.btn_mode_switch)
        btnGallery = findViewById(R.id.btn_gallery)

        viewGridLines = findViewById(R.id.view_grid_lines)
        viewHistogram = findViewById(R.id.view_histogram)
        viewCropOverlay = findViewById(R.id.view_crop_overlay)
        tvPhysicalRes = findViewById(R.id.tv_physical_res)
        viewFocusRing = findViewById(R.id.view_focus_ring)

        btnLensFront = findViewById(R.id.btn_lens_front)
        btnLensWide = findViewById(R.id.btn_lens_wide)
        btnLensMain = findViewById(R.id.btn_lens_main)
        btnLensTele = findViewById(R.id.btn_lens_tele)

        panelFormatSettings = findViewById(R.id.panel_format_settings)
        spinnerMainFormat = findViewById(R.id.spinner_main_format)
        layoutJpgAdvancedOptions = findViewById(R.id.layout_jpg_advanced_options)
        spinnerDemosaicAlgo = findViewById(R.id.spinner_demosaic_algo)
        tvJpgQualityTitle = findViewById(R.id.tv_jpg_quality_title)
        seekbarJpgQuality = findViewById(R.id.seekbar_jpg_quality)
        formatPanelController = FormatPanelController(
            context = this,
            spinnerMainFormat = spinnerMainFormat,
            layoutJpgAdvancedOptions = layoutJpgAdvancedOptions,
            spinnerDemosaicAlgo = spinnerDemosaicAlgo,
        )
        rawMediaStore = RawMediaStore(this)
        preferenceManager = PreferenceManager(this)
        deviceOrientationTracker = DeviceOrientationTracker(this)
        btnFlash.text = currentFlashMode.label
        btnFlash.setTextColor(currentFlashMode.textColor)
        tvValEv = findViewById(R.id.tv_val_ev)
        tvValWb = findViewById(R.id.tv_val_wb)
        tvValIso = findViewById(R.id.tv_val_iso)
        tvValS = findViewById(R.id.tv_val_s)
        tvValAf = findViewById(R.id.tv_val_af)
    }

    private fun setupListeners() {
        setupLensSelector()

        btnShutter.setOnClickListener {
            if (isRawOnly()) {
                captureRawPhoto()
            } else {
                captureJpgPhoto()
            }
        }

        btnFlash.setOnClickListener { view ->
            val options = listOf("关闭", "打开", "常亮")
            showStyledPopup(view, options) { selected ->
                val requestedMode = when (selected) {
                    "打开" -> FlashMode.SINGLE
                    "常亮" -> FlashMode.TORCH
                    else -> FlashMode.OFF
                }
                currentFlashMode = if (requestedMode != FlashMode.OFF && !currentFlashSupported) {
                    showToast("当前设备不支持闪光灯")
                    FlashMode.OFF
                } else {
                    requestedMode
                }
                btnFlash.text = currentFlashMode.label
                btnFlash.setTextColor(currentFlashMode.textColor)
                applyPhotoParamsToPreview()
            }
        }

        btnHistogram.setOnClickListener {
            isHistogramVisible = !isHistogramVisible
            android.util.Log.i("HistogramDebug", "Button clicked, isVisible: $isHistogramVisible")
            viewHistogram.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
            tvPhysicalRes.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
            btnHistogram.setTextColor(if (isHistogramVisible) Color.parseColor("#FFCC00") else Color.WHITE)
            preferenceManager.saveBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, isHistogramVisible)
            updateCropOverlayUI()
        }

        btnGridLines.setOnClickListener {
            isGridVisible = !isGridVisible
            viewGridLines.visibility = if (isGridVisible) View.VISIBLE else View.GONE
            btnGridLines.setTextColor(if (isGridVisible) Color.parseColor("#FFCC00") else Color.WHITE)
            preferenceManager.saveBoolean(PreferenceManager.KEY_GRID_VISIBLE, isGridVisible)
        }

        btnResolution.setOnClickListener { view ->
            if (isPhotoMode) {
                showRatioPopup(view)
            } else {
                showVideoParamsPopup(view)
            }
        }

        btnFormat.setOnClickListener {
            isFormatPanelOpen = !isFormatPanelOpen
            if (isFormatPanelOpen) {
                updateFormatPanelUI()
                panelFormatSettings.visibility = View.VISIBLE
                btnFormat.setTextColor(Color.parseColor("#FFCC00"))
            } else {
                panelFormatSettings.visibility = View.GONE
                btnFormat.setTextColor(Color.WHITE)
            }
        }

        btnModeSwitch.setOnClickListener {
            isPhotoMode = !isPhotoMode
            updateModeUI()
            preferenceManager.saveBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, isPhotoMode)
            closeCamera()
            openCamera()
        }

        btnModeSwitch.setOnLongClickListener {
            preferenceManager.clear()
            showToast("设置已重置")
            loadSettings()
            closeCamera()
            openCamera()
            true
        }

        btnGallery.setOnClickListener {
            openRecentPhotoInGallery()
        }

        seekbarJpgQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvJpgQualityTitle.text = "JPG 质量: $progress%"
                if (fromUser) {
                    preferenceManager.saveInt(PreferenceManager.KEY_JPG_QUALITY, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupParamGestures() {
        val evValues = listOf("-2.0", "-1.5", "-1.0", "-0.5", "0.0", "+0.5", "+1.0", "+1.5", "+2.0")
        val wbValues = listOf("3200K", "4000K", "5200K", "6000K", "7000K")
        val isoValues = listOf("50", "100", "200", "400", "800", "1600", "3200", "6400")
        val sValues = listOf("1/1000s", "1/500s", "1/250s", "1/125s", "1/100s", "1/60s", "1/30s", "1/8s")
        val afValues = listOf("0.1", "0.3", "0.5", "0.7", "0.9", "1.0")

        ParamController(R.id.layout_param_ev, tvValEv, evValues, 4, ParamKey.EV).setup()
        ParamController(R.id.layout_param_wb, tvValWb, wbValues, 2, ParamKey.WB).setup()
        ParamController(R.id.layout_param_iso, tvValIso, isoValues, 1, ParamKey.ISO).setup()
        ParamController(R.id.layout_param_s, tvValS, sValues, 4, ParamKey.SHUTTER).setup()
        ParamController(R.id.layout_param_af, tvValAf, afValues, 2, ParamKey.AF).setup()
        refreshParamViews()
    }

    private fun onParamManualStep(key: ParamKey, selectedLabel: String) {
        photoParamState = PhotoParamMapper.createManualStateForLabel(
            key = key,
            label = selectedLabel,
            capabilities = cameraCapabilities,
            currentState = photoParamState,
        )
        refreshParamViews()
        applyPhotoParamsToPreview()
    }

    private fun onParamAutoReset(key: ParamKey) {
        photoParamState = PhotoParamMapper.restoreAutoState(key, photoParamState)
        refreshParamViews()
        applyPhotoParamsToPreview()
    }

    private fun refreshParamViews() {
        val evDisplay = if (photoParamState.ev.isAuto) {
            previewAutoCompensation?.let { compensation ->
                val step = cameraCapabilities.aeCompensationStep ?: 0f
                PhotoParamMapper.formatEvDisplay(compensation, step)
            } ?: photoParamState.ev.displayValue
        } else {
            photoParamState.ev.displayValue
        }
        setParamText(tvValEv, evDisplay, photoParamState.ev.isAuto)

        val wbDisplay = if (photoParamState.wb.isAuto) {
            previewAutoKelvinLabel ?: photoParamState.wb.displayValue
        } else {
            photoParamState.wb.displayValue
        }
        setParamText(tvValWb, wbDisplay, photoParamState.wb.isAuto)

        val isoDisplay = if (photoParamState.iso.isAuto) {
            previewAutoIso?.toString() ?: photoParamState.iso.displayValue
        } else {
            photoParamState.iso.displayValue
        }
        setParamText(tvValIso, isoDisplay, photoParamState.iso.isAuto)

        val shutterDisplay = if (photoParamState.shutter.isAuto) {
            previewAutoShutterNanos?.let(PhotoParamMapper::formatShutterDisplay) ?: photoParamState.shutter.displayValue
        } else {
            photoParamState.shutter.displayValue
        }
        setParamText(tvValS, shutterDisplay, photoParamState.shutter.isAuto)

        val afDisplay = if (photoParamState.af.isAuto) {
            previewAutoFocusDistance?.let(PhotoParamMapper::formatFocusDistanceDisplay) ?: photoParamState.af.displayValue
        } else {
            photoParamState.af.displayValue
        }
        setParamText(tvValAf, afDisplay, photoParamState.af.isAuto)
    }

    private fun setParamText(view: TextView, text: String, isAuto: Boolean) {
        view.text = text
        view.setTextColor(Color.parseColor(if (isAuto) PARAM_TEXT_AUTO_COLOR else PARAM_TEXT_MANUAL_COLOR))
    }

    inner class ParamController(
        private val layoutId: Int,
        private val tvValue: TextView,
        private val values: List<String>,
        private var currentIndex: Int,
        private val key: ParamKey,
    ) {
        @SuppressLint("ClickableViewAccessibility")
        fun setup() {
            val layout = findViewById<LinearLayout>(layoutId)
            var startY = 0f
            val scrollSensitivity = 40f

            layout.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.y - startY
                        if (abs(deltaY) > scrollSensitivity) {
                            currentIndex = if (deltaY < 0) {
                                (currentIndex + 1).coerceAtMost(values.size - 1)
                            } else {
                                (currentIndex - 1).coerceAtLeast(0)
                            }
                            onParamManualStep(key, values[currentIndex])
                            startY = event.y
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.y - startY) < 10f) {
                            onParamAutoReset(key)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun setupLensSelector() {
        val buttons = listOf(btnLensFront, btnLensWide, btnLensMain, btnLensTele)

        val switchLens = { selectedBtn: TextView, logicalId: String, physicalId: String? ->
            buttons.forEach {
                it.setTextColor(Color.parseColor("#B3FFFFFF"))
                it.textSize = 14f
                it.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            selectedBtn.setTextColor(Color.parseColor("#FFCC00"))
            selectedBtn.textSize = 18f
            selectedBtn.setTypeface(null, android.graphics.Typeface.BOLD)

            if (currentLogicalCameraId != logicalId) {
                currentLogicalCameraId = logicalId
                currentPhysicalCameraId = physicalId
                saveCurrentLensSettings()
                closeCamera()
                if (textureView.isAvailable) {
                    openCamera()
                }
            } else if (currentPhysicalCameraId != physicalId) {
                currentPhysicalCameraId = physicalId
                saveCurrentLensSettings()
                refreshActiveCameraCharacteristics()
                refreshCameraCapabilities()
                configureImageReaders()
                if (cameraDevice == null) {
                    openCamera()
                } else {
                    createCameraPreviewSession()
                }
            }
        }

        btnLensFront.setOnClickListener { switchLens(btnLensFront, "1", null) }
        btnLensWide.setOnClickListener { switchLens(btnLensWide, "0", "4") }
        btnLensMain.setOnClickListener { switchLens(btnLensMain, "0", "2") }
        btnLensTele.setOnClickListener { switchLens(btnLensTele, "0", "3") }
    }

    private fun updateFormatPanelUI() {
        formatPanelController.update(isPhotoMode)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        backgroundHandler?.post(histogramTask)
        deviceOrientationTracker.enable()
        rawMediaStore.findLatestRecentPhotoUri()?.let(::updateRecentPhotoPreview)
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        deviceOrientationTracker.disable()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return
        }

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            openCamera()
        } else {
            showToast("缺少相机或存储权限")
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private fun openCamera() {
        if (!hasAllRequiredPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_CAMERA_PERMISSION)
            return
        }

        try {
            refreshActiveCameraCharacteristics()
            refreshCameraCapabilities()
            if (!configureImageReaders()) {
                showToast("当前镜头不支持预览")
                return
            }
            cameraManager().openCamera(currentLogicalCameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            showToast("打开相机失败")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            showToast("相机连接失败")
            finish()
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: CameraMetadata.CONTROL_AE_STATE_INACTIVE
            updateAutoValuesFromResult(result)
            runOnUiThread {
                refreshParamViews()
            }
        }
    }

    private fun updateAutoValuesFromResult(result: CaptureResult) {
        result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)?.let {
            previewAutoCompensation = it
        }
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
            previewAutoIso = it
        }
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
            previewAutoShutterNanos = it
        }
        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
            previewAutoFocusDistance = it
        }
        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
            previewAutoKelvinLabel = PhotoParamMapper.gainsToKelvinLabel(gains, supportedKelvinValues())
        }
    }

    private fun applyPhotoParamsToPreview() {
        previewRequestBuilder?.let { builder ->
            applyPhotoParamsToRequest(builder)
        }
        resumePreview()
    }

    private fun createCameraPreviewSession() {
        val camera = cameraDevice ?: return
        val rawReader = rawImageReader ?: return
        val jpegReader = jpegImageReader ?: return

        try {
            captureSession?.close()

            val texture = textureView.surfaceTexture ?: return
            
            val characteristics = activeCameraCharacteristics
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedSizes = map?.getOutputSizes(SurfaceTexture::class.java)

            val previewSize: Size
            if (isPhotoMode) {
                // Explicitly target 1920x1440 (4:3) for preview stream
                val targetPreviewW = 1920
                val targetPreviewH = 1440
                
                // Check if 1920x1440 is supported, otherwise fallback to a close 4:3 size
                previewSize = supportedSizes?.find { it.width == targetPreviewW && it.height == targetPreviewH }
                    ?: supportedSizes?.filter { Math.abs(it.width.toFloat() / it.height - 4f/3f) < 0.01 }?.maxByOrNull { it.width }
                    ?: Size(targetPreviewW, targetPreviewH)
            } else {
                // Video Mode: Use selected resolution
                val dimensions = selectedVideoSizeStr.split("x")
                val w = dimensions[0].toInt()
                val h = dimensions[1].toInt()
                previewSize = Size(w, h)
            }

            android.util.Log.i("CameraPreview", "Selected preview size: ${previewSize.width}x${previewSize.height} (Mode: ${if(isPhotoMode) "Photo" else "Video"})")

            // Configure TextureView transformation to avoid stretching
            val viewW = textureView.width
            val viewH = textureView.height
            
            if (viewW > 0 && viewH > 0) {
                val matrix = android.graphics.Matrix()
                val centerX = viewW / 2f
                val centerY = viewH / 2f
                
                val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val rotatedStreamW: Int
                val rotatedStreamH: Int
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    rotatedStreamW = previewSize.height
                    rotatedStreamH = previewSize.width
                } else {
                    rotatedStreamW = previewSize.width
                    rotatedStreamH = previewSize.height
                }

                val streamRatio = rotatedStreamW.toFloat() / rotatedStreamH
                val viewRatio = viewW.toFloat() / viewH
                
                var scaleX = 1f
                var scaleY = 1f
                
                if (isPhotoMode) {
                    // Center Crop logic to ensure fill for 3:4 view
                    if (streamRatio > viewRatio) {
                        scaleX = (viewH * streamRatio) / viewW
                    } else {
                        scaleY = (viewW / streamRatio) / viewH
                    }
                } else {
                    // Center Inside logic (Letterbox) for Video Mode
                    if (streamRatio > viewRatio) {
                        // Stream is wider than view -> Fit width, scale down height
                        scaleY = (viewW / streamRatio) / viewH
                    } else {
                        // Stream is taller than view -> Fit height, scale down width
                        scaleX = (viewH * streamRatio) / viewW
                    }
                }
                
                matrix.setScale(scaleX, scaleY, centerX, centerY)
                textureView.setTransform(matrix)
            }

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface?.release()
            previewSurface = Surface(texture)
            val surface = previewSurface ?: return

            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                if (isPhotoMode) {
                    applyPhotoParamsToRequest(this)
                } else {
                    // Video mode basic request settings
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(selectedVideoFps, selectedVideoFps))
                }
            }

            val previewStateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) {
                        return
                    }
                    captureSession = session
                    resumePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    showToast("预览会话配置失败")
                }
            }

            val outputSurfaces = mutableListOf(surface, rawReader.surface, jpegReader.surface)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = outputSurfaces.map { s ->
                    OutputConfiguration(s).apply {
                        currentPhysicalCameraId?.let { setPhysicalCameraId(it) }
                    }
                }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    Executor { command -> backgroundHandler?.post(command) },
                    previewStateCallback
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                camera.createCaptureSession(
                    outputSurfaces,
                    previewStateCallback,
                    backgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            showToast("创建预览失败")
        }
    }

    private fun captureRawPhoto() {
        if (!isPhotoMode) {
            showToast("请先切换到拍照模式")
            return
        }
        if (isCaptureInProgress) {
            return
        }

        val camera = cameraDevice
        val session = captureSession
        val imageReader = rawImageReader
        val surface = previewSurface
        if (camera == null || session == null || imageReader == null) {
            showToast("拍照尚未就绪")
            return
        }

        isCaptureInProgress = true
        isRawCaptureComplete = false
        isJpegCaptureComplete = true
        btnShutter.isEnabled = false
        pendingCaptureResult = null
        pendingRawImage?.close()
        pendingRawImage = null

        if (currentFlashMode == FlashMode.SINGLE) {
            runPrecaptureSequence {
                executeStillCapture(camera, session, imageReader.surface, surface)
            }
        } else {
            executeStillCapture(camera, session, imageReader.surface, surface)
        }
    }

    private fun executeStillCapture(
        camera: CameraDevice,
        session: CameraCaptureSession,
        targetSurface: Surface,
        previewSurface: Surface?
    ) {
        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(targetSurface)
                previewSurface?.let { addTarget(it) }
                if (isJpgAndRaw() || (!isRawOnly() && !isPhotoMode)) { // Logic for JPG from RAW
                     rawImageReader?.surface?.let { addTarget(it) }
                }
                applyPhotoParamsToRequest(this)
                currentFlashMode.applyToRequest(this, isStillCapture = true)
                set(
                    CaptureRequest.JPEG_ORIENTATION,
                    CaptureOrientationCalculator.captureRotation(
                        sensorOrientation = currentSensorOrientation,
                        lensFacing = currentLensFacing,
                        deviceRotationDegrees = deviceOrientationTracker.deviceRotationDegrees,
                    ).degrees
                )
            }

            session.stopRepeating()
            session.capture(captureBuilder.build(), stillCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            finishCaptureWithError("拍照失败")
        }
    }

    private fun runPrecaptureSequence(onReady: () -> Unit) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    onReady()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            onReady()
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            pendingCaptureResult = result
            maybePersistPendingRaw()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            finishCaptureWithError("RAW 拍照失败")
        }
    }

    private fun maybePersistPendingRaw() {
        val image = pendingRawImage ?: return
        val captureResult = pendingCaptureResult ?: return
        val characteristics = activeCameraCharacteristics
        if (characteristics == null) {
            image.close()
            pendingRawImage = null
            return
        }

        pendingRawImage = null
        backgroundHandler?.post {
            try {
                val rotation = CaptureOrientationCalculator.captureRotation(
                    sensorOrientation = currentSensorOrientation,
                    lensFacing = currentLensFacing,
                    deviceRotationDegrees = deviceOrientationTracker.deviceRotationDegrees,
                )

                if (isRawOnly() || isJpgAndRaw()) {
                    rawMediaStore.saveRawImage(image, characteristics, captureResult, rotation.exifOrientation)
                }
                
                if (!isRawOnly()) {
                    // Demosaic RAW to JPG
                    val algo = spinnerDemosaicAlgo.selectedItem?.toString() ?: "Fast"
                    
                    val wbGains = captureResult.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    val wbArray = if (wbGains != null) {
                        floatArrayOf(wbGains.red, wbGains.greenEven, wbGains.greenOdd, wbGains.blue)
                    } else {
                        floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
                    }
                    
                    val bayerPattern = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                    val blackLevel = 64 // Simplified black level
                    val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                    
                    var bitmap = com.example.wcam.camera.DemosaicEngine.process(
                        image, algo, wbArray, bayerPattern, blackLevel, whiteLevel
                    )

                    // Crop according to the green rectangle
                    currentCropRectInSensor?.let { rect ->
                        val left = rect.left.coerceIn(0, bitmap.width - 1)
                        val top = rect.top.coerceIn(0, bitmap.height - 1)
                        val width = rect.width().coerceAtMost(bitmap.width - left)
                        val height = rect.height().coerceAtMost(bitmap.height - top)
                        
                        if (width > 0 && height > 0) {
                            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                            bitmap.recycle()
                            bitmap = cropped
                        }
                    }

                    val savedUri = saveJpegBitmap(bitmap, rotation.degrees)
                    updateRecentPhotoPreview(savedUri)
                    bitmap.recycle()
                }

                image.close()
                isRawCaptureComplete = true
                isJpegCaptureComplete = true
                checkCaptureCompletion()
            } catch (e: Exception) {
                image.close()
                finishCaptureWithError("照片保存失败")
            }
        }
    }

    private fun finishCaptureSuccessfully() {
        showToast("RAW 已保存到相册")
        endCaptureAndResumePreview()
    }

    private fun finishCaptureWithError(message: String) {
        showToast(message)
        endCaptureAndResumePreview()
    }

    private fun endCaptureAndResumePreview() {
        isCaptureInProgress = false
        runOnUiThread {
            btnShutter.isEnabled = true
        }
        resumePreview()
    }

    private fun openRecentPhotoInGallery() {
        val targetUri = recentPhotoUri ?: rawMediaStore.findLatestRecentPhotoUri()
        if (targetUri == null) {
            showToast("还没有可查看的照片")
            return
        }

        recentPhotoUri = targetUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            showToast("无法打开相册")
        }
    }

    private fun updateRecentPhotoPreview(uri: Uri) {
        recentPhotoUri = uri
        val thumbnail = rawMediaStore.loadThumbnailBitmap(uri)
        runOnUiThread {
            if (thumbnail != null) {
                btnGallery.background = BitmapDrawable(resources, thumbnail)
                btnGallery.text = ""
            } else {
                btnGallery.setBackgroundResource(R.drawable.bg_gallery_modern)
                btnGallery.text = "相册"
            }
        }
    }

    private fun resumePreview() {
        val session = captureSession ?: return
        val previewRequest = previewRequestBuilder?.build() ?: return
        try {
            session.setRepeatingRequest(previewRequest, previewCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            showToast("恢复预览失败")
        }
    }

    private fun configureImageReaders(): Boolean {
        val characteristics = activeCameraCharacteristics ?: return false
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        
        // RAW
        val largestRawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        
        if (largestRawSize != null) {
            if (rawImageReader == null || rawImageReader!!.width != largestRawSize.width || rawImageReader!!.height != largestRawSize.height) {
                rawImageReader?.close()
                rawImageReader = ImageReader.newInstance(largestRawSize.width, largestRawSize.height, ImageFormat.RAW_SENSOR, 2)
            }
            rawImageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                pendingRawImage?.close()
                pendingRawImage = image
                maybePersistPendingRaw()
            }, backgroundHandler)
        }

        // JPEG/YUV for processing
        val largestJpgSize = streamMap.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return false

        if (jpegImageReader == null || jpegImageReader!!.width != largestJpgSize.width || jpegImageReader!!.height != largestJpgSize.height) {
            jpegImageReader?.close()
            jpegImageReader = ImageReader.newInstance(largestJpgSize.width, largestJpgSize.height, ImageFormat.JPEG, 2)
        }
        jpegImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.close() // Hardware JPEGs are no longer used for persistence
        }, backgroundHandler)

        return true
    }

    private fun checkCaptureCompletion() {
        if (isRawCaptureComplete && isJpegCaptureComplete) {
            pendingCaptureResult = null
            finishCaptureSuccessfully()
        }
    }

    private fun saveJpegBitmap(bitmap: Bitmap, rotationDegrees: Int): Uri {
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        
        val fileName = createPhotoDisplayName(extension = "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/wCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        val quality = seekbarJpgQuality.progress
        contentResolver.openOutputStream(uri)?.use { os ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
        } ?: throw IOException("Failed to open output stream")
        
        // Write EXIF
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                writeExifMetadata(exif, rotationDegrees)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    private fun writeExifMetadata(exif: ExifInterface, rotation: Int) {
        val result = pendingCaptureResult ?: return
        val characteristics = activeCameraCharacteristics ?: return

        // Basic settings
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "wCam 1.0")
        
        // Exposure settings
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)

        iso?.let { exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
        exposureTime?.let {
            val exposureInSeconds = it / 1_000_000_000.0
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureInSeconds.toString())
        }
        aperture?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
        focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
        
        // Date Time
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        exif.setAttribute(ExifInterface.TAG_DATETIME, sdf.format(java.util.Date()))
        
        // Flash
        val flashState = result.get(CaptureResult.FLASH_STATE)
        val flashFired = flashState == CaptureResult.FLASH_STATE_FIRED || flashState == CaptureResult.FLASH_STATE_PARTIAL
        exif.setAttribute(ExifInterface.TAG_FLASH, if (flashFired) "1" else "0")
    }

    private fun createPhotoDisplayName(extension: String): String {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return "IMG_$timestamp.$extension"
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
        isCaptureInProgress = false
        releaseImageResources()
    }

    private fun releaseImageResources() {
        pendingRawImage?.close()
        pendingRawImage = null
        pendingJpegImage?.close()
        pendingJpegImage = null
        pendingCaptureResult = null
        rawImageReader?.close()
        rawImageReader = null
        jpegImageReader?.close()
        jpegImageReader = null
        histogramImageReader?.close()
        histogramImageReader = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            showToast("后台线程停止失败")
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isRawOnly(): Boolean {
        val selected = spinnerMainFormat.selectedItem?.toString() ?: ""
        return selected == "RAW"
    }

    private fun isJpgAndRaw(): Boolean {
        val selected = spinnerMainFormat.selectedItem?.toString() ?: ""
        return selected == "JPG + RAW"
    }

    private fun showVideoParamsPopup(view: View) {
        val currentId = activeMetadataCameraId()
        val caps = allVideoCapabilities[currentId] ?: emptyList()
        if (caps.isEmpty()) {
            showToast("无法获取视频能力")
            return
        }

        val ratios = caps.map { it.ratioStr }.distinct().sorted()
        showStyledPopup(view, ratios) { ratioStr ->
            selectedVideoRatioStr = ratioStr
            preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RATIO, ratioStr)
            
            // Second step: Select Resolution & FPS (Prioritize FPS as requested)
            val filteredCaps = caps.filter { it.ratioStr == ratioStr }
            val options = filteredCaps.sortedWith(compareByDescending<VideoSizeInfo> { it.maxFps }.thenByDescending { it.width * it.height })
                .map { "${it.width}x${it.height} @ ${it.maxFps}fps" }
            
            showStyledPopup(view, options) { selected ->
                val parts = selected.split(" @ ")
                selectedVideoSizeStr = parts[0]
                selectedVideoFps = parts[1].removeSuffix("fps").toInt()
                
                preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr)
                preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, selectedVideoFps)
                
                btnResolution.text = "$selectedVideoSizeStr@$selectedVideoFps"
                btnResolution.setTextColor(Color.parseColor("#FFCC00"))
                
                closeCamera()
                openCamera()
            }
        }
    }

    private fun showRatioPopup(view: View) {
        val options = listOf("1:1", "4:3", "16:9", "2:1", "自定义")
        showStyledPopup(view, options) { selected ->
            when (selected) {
                "1:1" -> {
                    cropRatio = 1.0f
                    btnResolution.text = "1:1"
                    updateCropOverlayUI()
                }
                "4:3" -> {
                    cropRatio = 4f / 3f
                    btnResolution.text = "4:3"
                    updateCropOverlayUI()
                }
                "16:9" -> {
                    cropRatio = 16f / 9f
                    btnResolution.text = "16:9"
                    updateCropOverlayUI()
                }
                "2:1" -> {
                    cropRatio = 2.0f
                    btnResolution.text = "2:1"
                    updateCropOverlayUI()
                }
                "自定义" -> {
                    showCustomRatioDialog()
                }
            }
            btnResolution.setTextColor(Color.parseColor("#FFCC00"))
        }
    }

    private fun showCustomRatioDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 40, 50, 10)
        }
        val etW = android.widget.EditText(this).apply {
            hint = "宽"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(customRatioW.toString())
        }
        val etH = android.widget.EditText(this).apply {
            hint = "高"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(customRatioH.toString())
        }
        layout.addView(etW)
        layout.addView(etH)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("自定义比例")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val w = etW.text.toString().toIntOrNull() ?: 4
                val h = etH.text.toString().toIntOrNull() ?: 3
                if (w > 0 && h > 0) {
                    customRatioW = w
                    customRatioH = h
                    cropRatio = w.toFloat() / h
                    btnResolution.text = "$w:$h"
                    updateCropOverlayUI()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit) {
        val listPopupWindow = ListPopupWindow(this)
        listPopupWindow.anchorView = anchor
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    // Reduce vertical padding by 50%
                    val newTopPadding = (paddingTop * 0.5f).toInt()
                    val newBottomPadding = (paddingBottom * 0.5f).toInt()
                    setPadding(paddingLeft, newTopPadding, paddingRight, newBottomPadding)
                }
                view.setBackgroundColor(Color.parseColor("#CC1A1A1A"))
                return view
            }
        }
        listPopupWindow.setAdapter(adapter)
        listPopupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#CC1A1A1A")))
        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            onSelected(options[position])
            listPopupWindow.dismiss()
        }
        listPopupWindow.width = 960
        listPopupWindow.show()
    }

    private fun captureJpgPhoto() {
        if (isCaptureInProgress) return
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val rawReader = rawImageReader ?: return
        val surface = previewSurface

        isCaptureInProgress = true
        isJpegCaptureComplete = false
        isRawCaptureComplete = false
        btnShutter.isEnabled = false
        pendingCaptureResult = null
        pendingJpegImage?.close()
        pendingJpegImage = null
        pendingRawImage?.close()
        pendingRawImage = null

        val captureAction = {
            try {
                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(rawReader.surface) // Always capture RAW for demosaicing
                    surface?.let { addTarget(it) }
                    applyPhotoParamsToRequest(this)
                    currentFlashMode.applyToRequest(this, isStillCapture = true)
                }

                session.stopRepeating()
                session.capture(captureBuilder.build(), stillCaptureCallback, backgroundHandler)
            } catch (e: CameraAccessException) {
                finishCaptureWithError("拍照失败")
            }
        }

        if (currentFlashMode == FlashMode.SINGLE) {
            runPrecaptureSequence { captureAction() }
        } else {
            captureAction()
        }
    }

    private fun activeMetadataCameraId(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            currentPhysicalCameraId ?: currentLogicalCameraId
        } else {
            currentLogicalCameraId
        }
    }

    private fun cameraManager(): CameraManager {
        return getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private fun refreshActiveCameraCharacteristics() {
        activeCameraCharacteristics = try {
            cameraManager().getCameraCharacteristics(activeMetadataCameraId()).also { characteristics ->
                currentSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                currentLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            }
        } catch (e: CameraAccessException) {
            null
        }
    }

    private fun refreshCameraCapabilities() {
        val characteristics = activeCameraCharacteristics ?: run {
            cameraCapabilities = CameraCapabilities.EMPTY
            return
        }

        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val supportsManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val supportsManualWhiteBalance = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        cameraCapabilities = CameraCapabilities(
            aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat(),
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            supportsManualSensor = supportsManualSensor,
            supportsManualWhiteBalance = supportsManualWhiteBalance,
        )
        currentFlashSupported = hasFlash
        maxZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 10.0f
        } else {
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
        }
        
        runOnUiThread {
            setupDynamicParamGestures()
            updateCropOverlayUI()
        }
    }

    private fun setupDynamicParamGestures() {
        val caps = cameraCapabilities
        
        val evRange = caps.aeCompensationRange?.let { it.lower..it.upper } ?: 0..0
        val evStep = caps.aeCompensationStep ?: 1f
        val evValues = if (evRange.first != evRange.last) {
             (evRange.first..evRange.last step 1).map { PhotoParamMapper.formatEvDisplay(it, evStep) }
        } else listOf("0.0")

        val wbValues = (2000..8000 step 100).map { "${it}K" }
        
        val isoRange = caps.isoRange?.let { it.lower..it.upper } ?: 100..6400
        val isoValues = generateLogValues(isoRange.first.toDouble(), isoRange.last.toDouble(), 40).map { it.toInt().toString() }
        
        val sRange = caps.exposureTimeRange?.let { it.lower..it.upper } ?: 1000000L..1000000000L
        val sValues = generateLogValues(sRange.first.toDouble(), sRange.last.toDouble(), 40).map { PhotoParamMapper.formatShutterDisplay(it.toLong()) }
        
        val afValues = (0..100).map { (it / 100.0).toString() }

        ParamController(R.id.layout_param_ev, tvValEv, evValues, evValues.indexOf("0.0").coerceAtLeast(0), ParamKey.EV).setup()
        ParamController(R.id.layout_param_wb, tvValWb, wbValues, wbValues.indexOf("5200K").coerceAtLeast(0), ParamKey.WB).setup()
        ParamController(R.id.layout_param_iso, tvValIso, isoValues, isoValues.indexOf("100").coerceAtLeast(0), ParamKey.ISO).setup()
        ParamController(R.id.layout_param_s, tvValS, sValues, sValues.indexOf("1/125s").coerceAtLeast(0), ParamKey.SHUTTER).setup()
        ParamController(R.id.layout_param_af, tvValAf, afValues, 50, ParamKey.AF).setup()
    }

    private fun generateLogValues(min: Double, max: Double, steps: Int): List<Double> {
        val logMin = Math.log(min.coerceAtLeast(1.0))
        val logMax = Math.log(max.coerceAtLeast(1.0))
        val delta = (logMax - logMin) / (steps - 1)
        return (0 until steps).map { Math.exp(logMin + it * delta) }
    }

    private fun supportedKelvinValues(): List<Int> = listOf(3200, 4000, 5200, 6000, 7000)

    private fun applyPhotoParamsToRequest(builder: CaptureRequest.Builder) {
        PhotoParamMapper.applyPhotoParamsToRequest(
            builder = builder,
            state = photoParamState,
            capabilities = cameraCapabilities,
            fallbackAutoIso = previewAutoIso,
            fallbackAutoShutterNanos = previewAutoShutterNanos,
        )
        currentFlashMode.applyToRequest(builder, isStillCapture = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            val characteristics = activeCameraCharacteristics
            val arrayRect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (arrayRect != null) {
                val cropW = (arrayRect.width() / currentZoomRatio).toInt()
                val cropH = (arrayRect.height() / currentZoomRatio).toInt()
                val left = (arrayRect.width() - cropW) / 2
                val top = (arrayRect.height() - cropH) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(left, top, left + cropW, top + cropH))
            }
        }
    }

    private fun logAllCameraStreams() {
        val manager = cameraManager()
        val physicalIds = listOf("2", "4", "3", "1")
        
        android.util.Log.i("CameraVideoLog", "--- 开始获取四个物理摄像头视频能力 ---")
        
        for (id in physicalIds) {
            try {
                val chars = manager.getCameraCharacteristics(id)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map == null) continue

                val capabilities = mutableListOf<VideoSizeInfo>()

                // Regular video sizes
                val videoSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                videoSizes?.forEach { size ->
                    val minDuration = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size)
                    val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 0
                    capabilities.add(VideoSizeInfo(size.width, size.height, maxFps))
                }

                // High speed video sizes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    map.highSpeedVideoSizes?.forEach { hsSize ->
                        val hsFpsRanges = map.getHighSpeedVideoFpsRangesFor(hsSize)
                        val maxHsFps = hsFpsRanges.maxOfOrNull { it.upper } ?: 0
                        capabilities.add(VideoSizeInfo(hsSize.width, hsSize.height, maxHsFps, isHighSpeed = true))
                    }
                }
                
                allVideoCapabilities[id] = capabilities

            } catch (e: Exception) {
                android.util.Log.e("CameraVideoLog", "无法读取摄像头 $id 的信息: ${e.message}")
            }
        }
        android.util.Log.i("CameraVideoLog", "--- 摄像头能力获取完毕 ---")
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
