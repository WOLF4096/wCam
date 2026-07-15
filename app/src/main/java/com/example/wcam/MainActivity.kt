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
import android.widget.ProgressBar
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import android.location.Location
import android.location.LocationManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.Executor
import kotlin.math.abs

import com.example.wcam.camera.CameraCapabilities
import com.example.wcam.camera.CaptureFileNames
import com.example.wcam.camera.CaptureOrientationCalculator
import com.example.wcam.camera.DeviceOrientationTracker
import com.example.wcam.camera.EXIF_ORIENTATION_NORMAL
import com.example.wcam.camera.PARAM_TEXT_AUTO_COLOR
import com.example.wcam.camera.PARAM_TEXT_MANUAL_COLOR
import com.example.wcam.camera.CaptureTask
import com.example.wcam.camera.CaptureMetadata
import com.example.wcam.camera.DemosaicEngine
import com.example.wcam.camera.ParamKey
import com.example.wcam.camera.ParamValue
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
    private lateinit var pbShutterProgress: ProgressBar
    private lateinit var tvShutterCountdown: TextView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private lateinit var captureExecutor: java.util.concurrent.ExecutorService

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
    private lateinit var previewContainer: ViewGroup
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

    private lateinit var layoutVideoAdvancedOptions: LinearLayout
    private lateinit var spinnerVideoFps: Spinner
    private lateinit var spinnerVideoRes: Spinner
    private lateinit var seekbarVideoBitrate: SeekBar
    private lateinit var tvVideoBitrateTitle: TextView

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
    private var lscStrength = 1.30f
    private var pendingJpegImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    @Volatile
    private var isCaptureInProgress = false
    @Volatile
    private var isRawCaptureComplete = false
    @Volatile
    private var isJpegCaptureComplete = false
    private var captureStartTime = 0L

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
    private var selectedVideoBitrate: Float = 20.0f
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
            val common = gcd(width, height)
            return "${height / common}:${width / common}"
        }
        
        private fun gcd(a: Int, b: Int): Int {
            return if (b == 0) a else gcd(b, a % b)
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

    private var lastPreviewFrameTime = 0L
    private val freezeWatchdog = object : Runnable {
        override fun run() {
            if (cameraDevice != null && lastPreviewFrameTime > 0 && !isCaptureInProgress) {
                val diff = System.currentTimeMillis() - lastPreviewFrameTime
                if (diff > 2000) { // 2 seconds of no frames
                    android.util.Log.w("CameraWatchdog", "Stream frozen for $diff ms, reconnecting...")
                    runOnUiThread {
                        closeCamera()
                        backgroundHandler?.postDelayed({
                            openCamera()
                        }, 500)
                    }
                    lastPreviewFrameTime = System.currentTimeMillis() // Reset to avoid loop
                }
            }
            backgroundHandler?.postDelayed(this, 1000)
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
        verifyHighResRawSupport()
        dumpVendorKeys()

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
        isPhotoMode = preferenceManager.getBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, true)
        currentLogicalCameraId = preferenceManager.getString(PreferenceManager.KEY_LENS_ID, "0")
        currentPhysicalCameraId = preferenceManager.getString(PreferenceManager.KEY_PHYSICAL_LENS_ID, "2")
        
        isGridVisible = preferenceManager.getBoolean(PreferenceManager.KEY_GRID_VISIBLE, false)
        viewGridLines.visibility = if (isGridVisible) View.VISIBLE else View.GONE
        btnGridLines.setTextColor(if (isGridVisible) Color.parseColor("#FFCC00") else Color.WHITE)

        isHistogramVisible = preferenceManager.getBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, false)
        viewHistogram.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
        tvPhysicalRes.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
        btnHistogram.setTextColor(if (isHistogramVisible) Color.parseColor("#FFCC00") else Color.WHITE)
        
        refreshLensUI()
        updateModeUI()

        selectedVideoRatioStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RATIO, "16:9")
        selectedVideoSizeStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RESOLUTION, "1920x1080")
        selectedVideoFps = preferenceManager.getInt(PreferenceManager.KEY_VIDEO_FPS, 30)
        selectedVideoBitrate = preferenceManager.getFloat(PreferenceManager.KEY_VIDEO_BITRATE, 20.0f)

        val jpgQual = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
        seekbarJpgQuality.progress = jpgQual
        tvJpgQualityTitle.text = "JPG 质量: $jpgQual%"

        btnResolution.text = if (isPhotoMode) "4:3" else selectedVideoRatioStr
        updateCropOverlayUI()
        restorePhotoParamState()
    }

    private fun restorePhotoParamState() {
        val evAuto = preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_EV_AUTO, true)
        val evVal = preferenceManager.getString(PreferenceManager.KEY_PARAM_EV_VAL, "+0.0")
        
        val wbAuto = preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_WB_AUTO, true)
        val wbVal = preferenceManager.getString(PreferenceManager.KEY_PARAM_WB_VAL, "5200K")
        
        val isoAuto = preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_ISO_AUTO, true)
        val isoVal = preferenceManager.getString(PreferenceManager.KEY_PARAM_ISO_VAL, "100")
        
        val sAuto = preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_S_AUTO, true)
        val sVal = preferenceManager.getString(PreferenceManager.KEY_PARAM_S_VAL, "1/125")
        
        val afAuto = preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_AF_AUTO, true)
        val afVal = preferenceManager.getString(PreferenceManager.KEY_PARAM_AF_VAL, "0")

        photoParamState = PhotoParamState(
            ev = ParamValue(evAuto, evVal, null),
            wb = ParamValue(wbAuto, wbVal, null),
            iso = ParamValue(isoAuto, isoVal, null),
            shutter = ParamValue(sAuto, sVal, null),
            af = ParamValue(afAuto, afVal, null)
        )
    }

    private fun updateModeUI() {
        val rootLayout = findViewById<ConstraintLayout>(R.id.main)
        val set = ConstraintSet()
        set.clone(rootLayout)
        
        // 核心修改：预览容器始终固定为 3:4 比例
        set.setDimensionRatio(R.id.preview_container, "3:4")
        
        if (isPhotoMode) {
            btnResolution.text = "4:3"
            btnShutter.setBackgroundResource(R.drawable.bg_shutter_modern)
            viewCropOverlay.visibility = View.VISIBLE
            btnModeSwitch.text = "切换\n录像"
        } else {
            btnResolution.text = selectedVideoRatioStr
            btnShutter.setBackgroundResource(R.drawable.bg_shutter_video)
            viewCropOverlay.visibility = View.GONE
            btnModeSwitch.text = "切换\n拍照"
        }
        set.applyTo(rootLayout)
        rootLayout.requestLayout()
        
        btnModeSwitch.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
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
                val characteristics = activeCameraCharacteristics ?: return false
                val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return false
                
                // Minimum long side 64px
                val minScale = 64f / Math.max(sensorSize.width(), sensorSize.height())
                
                cropScale = (cropScale * detector.scaleFactor).coerceIn(minScale, 1.0f)
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
        val resText = if (isPhotoMode) {
            "${currentW.toInt()}x${currentH.toInt()}@${String.format("%.1f", mp)}MP"
        } else {
            "$selectedVideoSizeStr@${selectedVideoFps}fps"
        }

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
        pbShutterProgress = findViewById(R.id.pb_shutter_progress)
        tvShutterCountdown = findViewById(R.id.tv_shutter_countdown)
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
        previewContainer = findViewById(R.id.preview_container)
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

        layoutVideoAdvancedOptions = findViewById(R.id.layout_video_advanced_options)
        spinnerVideoFps = findViewById(R.id.spinner_video_fps)
        spinnerVideoRes = findViewById(R.id.spinner_video_res)
        seekbarVideoBitrate = findViewById(R.id.seekbar_video_bitrate)
        tvVideoBitrateTitle = findViewById(R.id.tv_video_bitrate_title)

        formatPanelController = FormatPanelController(
            context = this,
            spinnerMainFormat = spinnerMainFormat,
            layoutJpgAdvancedOptions = layoutJpgAdvancedOptions,
            spinnerDemosaicAlgo = spinnerDemosaicAlgo,
            layoutVideoAdvancedOptions = layoutVideoAdvancedOptions,
            spinnerVideoFps = spinnerVideoFps,
            spinnerVideoRes = spinnerVideoRes,
            seekbarVideoBitrate = seekbarVideoBitrate,
            tvVideoBitrateTitle = tvVideoBitrateTitle
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
        // Handled dynamically in setupDynamicParamGestures() once camera is ready
        refreshParamViews()
    }

    private fun onParamManualStep(key: ParamKey, selectedLabel: String) {
        android.util.Log.d("MainActivity", "onParamManualStep: key=$key, label=$selectedLabel")
        photoParamState = PhotoParamMapper.createManualStateForLabel(
            key = key,
            label = selectedLabel,
            capabilities = cameraCapabilities,
            currentState = photoParamState,
        )
        saveParamState(key)
        refreshParamViews()
        applyPhotoParamsToPreview()
    }

    private fun onParamAutoReset(key: ParamKey) {
        if (key == ParamKey.EV) {
            // EV从手动恢复自动时还原为0
            onParamManualStep(ParamKey.EV, "+0.0")
        }
        photoParamState = PhotoParamMapper.restoreAutoState(key, photoParamState)
        saveParamState(key)
        refreshParamViews()
        applyPhotoParamsToPreview()
    }

    private fun saveParamState(key: ParamKey) {
        when (key) {
            ParamKey.EV -> {
                preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_EV_AUTO, photoParamState.ev.isAuto)
                preferenceManager.saveString(PreferenceManager.KEY_PARAM_EV_VAL, photoParamState.ev.displayValue)
            }
            ParamKey.WB -> {
                preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_WB_AUTO, photoParamState.wb.isAuto)
                preferenceManager.saveString(PreferenceManager.KEY_PARAM_WB_VAL, photoParamState.wb.displayValue)
            }
            ParamKey.ISO -> {
                preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_ISO_AUTO, photoParamState.iso.isAuto)
                preferenceManager.saveString(PreferenceManager.KEY_PARAM_ISO_VAL, photoParamState.iso.displayValue)
            }
            ParamKey.SHUTTER -> {
                preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_S_AUTO, photoParamState.shutter.isAuto)
                preferenceManager.saveString(PreferenceManager.KEY_PARAM_S_VAL, photoParamState.shutter.displayValue)
            }
            ParamKey.AF -> {
                preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_AF_AUTO, photoParamState.af.isAuto)
                preferenceManager.saveString(PreferenceManager.KEY_PARAM_AF_VAL, photoParamState.af.displayValue)
            }
        }
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
            previewAutoIso?.let { iso ->
                if (iso >= 1000) {
                    val kValue = iso / 1000.0
                    if (kValue == kValue.toInt().toDouble()) String.format(java.util.Locale.US, "%dK", kValue.toInt())
                    else String.format(java.util.Locale.US, "%.1fK", kValue)
                } else iso.toString()
            } ?: photoParamState.iso.displayValue
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

    private fun findClosestIndex(target: String, values: List<String>): Int {
        val targetNum = extractNumber(target) ?: return 0
        var minDiff = Float.MAX_VALUE
        var bestIdx = 0
        for (i in values.indices) {
            val valNum = extractNumber(values[i]) ?: continue
            val diff = abs(targetNum - valNum)
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun extractNumber(s: String): Float? {
        return try {
            val upper = s.uppercase(java.util.Locale.US)
            if (upper.contains("/")) {
                val parts = upper.replace(Regex("[^0-9./K]"), "").split("/")
                if (parts.size == 2) {
                    val num = parsePart(parts[0])
                    val den = parsePart(parts[1])
                    num / den
                } else parsePart(parts[0])
            } else {
                parsePart(upper.replace(Regex("[^0-9.-K]"), ""))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePart(p: String): Float {
        val clean = p.trim()
        if (clean.isEmpty() || clean == "K") return 0f
        return if (clean.endsWith("K")) {
            clean.removeSuffix("K").toFloatOrNull()?.let { it * 1000f } ?: 0f
        } else {
            clean.toFloatOrNull() ?: 0f
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
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
            val scrollSensitivity = 30f

            val gestureDetector = android.view.GestureDetector(layout.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onParamAutoReset(key)
                    return true
                }
            })

            layout.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.y
                        val paramVal = when(key) {
                            ParamKey.EV -> photoParamState.ev
                            ParamKey.WB -> photoParamState.wb
                            ParamKey.ISO -> photoParamState.iso
                            ParamKey.SHUTTER -> photoParamState.shutter
                            ParamKey.AF -> photoParamState.af
                        }
                        if (paramVal.isAuto) {
                            val currentDisplay = when(key) {
                                ParamKey.EV -> previewAutoCompensation?.let { compensation ->
                                    val step = cameraCapabilities.aeCompensationStep ?: 0f
                                    PhotoParamMapper.formatEvDisplay(compensation, step)
                                }
                                ParamKey.WB -> previewAutoKelvinLabel
                                ParamKey.ISO -> previewAutoIso?.let { iso ->
                                    if (iso >= 1000) {
                                        val kValue = iso / 1000.0
                                        if (kValue == kValue.toInt().toDouble()) String.format(java.util.Locale.US, "%dK", kValue.toInt())
                                        else String.format(java.util.Locale.US, "%.1fK", kValue)
                                    } else iso.toString()
                                }
                                ParamKey.SHUTTER -> previewAutoShutterNanos?.let(PhotoParamMapper::formatShutterDisplay)
                                ParamKey.AF -> previewAutoFocusDistance?.let(PhotoParamMapper::formatFocusDistanceDisplay)
                            } ?: paramVal.displayValue
                            
                            currentIndex = findClosestIndex(currentDisplay, values)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.y - startY
                        if (abs(deltaY) > scrollSensitivity) {
                            // Slide UP (deltaY < 0) increments index.
                            // For WB/ISO/S/EV (sorted small to large), this increases value.
                            // For AF (sorted 50 to 0), this decreases value.
                            // This matches the requirement: AF slide up decreases, others increase.
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
                    else -> true
                }
            }
        }
    }

    private fun setupLensSelector() {
        btnLensFront.setOnClickListener { switchLens(btnLensFront, "1", null) }
        btnLensWide.setOnClickListener { switchLens(btnLensWide, "0", "4") }
        btnLensMain.setOnClickListener { switchLens(btnLensMain, "0", "2") }
        btnLensTele.setOnClickListener { switchLens(btnLensTele, "0", "3") }
        refreshLensUI()
    }

    private fun refreshLensUI() {
        val buttons = listOf(btnLensFront, btnLensWide, btnLensMain, btnLensTele)
        buttons.forEach {
            it.setTextColor(Color.parseColor("#B3FFFFFF"))
            it.textSize = 14f
            it.setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        val activeBtn = when {
            currentLogicalCameraId == "1" -> btnLensFront
            currentPhysicalCameraId == "4" -> btnLensWide
            currentPhysicalCameraId == "2" -> btnLensMain
            currentPhysicalCameraId == "3" -> btnLensTele
            else -> btnLensMain
        }

        activeBtn.setTextColor(Color.parseColor("#FFCC00"))
        activeBtn.textSize = 18f
        activeBtn.setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun switchLens(selectedBtn: TextView, logicalId: String, physicalId: String?) {
        if (currentLogicalCameraId != logicalId || currentPhysicalCameraId != physicalId) {
            currentLogicalCameraId = logicalId
            currentPhysicalCameraId = physicalId
            saveCurrentLensSettings()
            refreshLensUI()

            // Refresh video params for the new lens
            if (!isPhotoMode) {
                val newId = physicalId ?: logicalId
                val caps = allVideoCapabilities[newId] ?: emptyList()
                
                val supported = caps.find { 
                    "${it.width}x${it.height}" == selectedVideoSizeStr && it.maxFps >= selectedVideoFps 
                }
                
                if (supported == null) {
                    // Try fallback to 1080p@60
                    val fallback = caps.find { it.width == 1920 && it.height == 1080 && it.maxFps >= 60 }
                    if (fallback != null) {
                        selectedVideoSizeStr = "1920x1080"
                        selectedVideoFps = 60
                        selectedVideoRatioStr = fallback.ratioStr
                    } else {
                        // Last resort: 30fps max res for current ratio or any ratio
                        val ratioCaps = caps.filter { it.ratioStr == selectedVideoRatioStr }
                        val best = ratioCaps.filter { it.maxFps >= 30 }
                            .sortedWith(compareByDescending<VideoSizeInfo> { it.width * it.height }.thenBy { Math.abs(it.maxFps - 30) })
                            .firstOrNull() ?: caps.maxByOrNull { it.width * it.height }
                        
                        if (best != null) {
                            selectedVideoSizeStr = "${best.width}x${best.height}"
                            selectedVideoFps = if (best.maxFps >= 30) 30 else best.maxFps
                            selectedVideoRatioStr = best.ratioStr
                        }
                    }
                    preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr)
                    preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, selectedVideoFps)
                    preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RATIO, selectedVideoRatioStr)
                }
                btnResolution.text = selectedVideoRatioStr
            }

            closeCamera()
            if (isFormatPanelOpen) updateFormatPanelUI()
            if (textureView.isAvailable) {
                openCamera()
            }
        }
    }

    private fun updateFormatPanelUI() {
        val currentPhotoFormat = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        val currentDemosaic = preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast")
        
        formatPanelController.update(isPhotoMode, currentPhotoFormat, currentDemosaic)
        
        if (isPhotoMode) {
            formatPanelController.setPhotoSettingsListener(object : FormatPanelController.PhotoSettingsListener {
                override fun onPhotoParamsChanged(format: String, demosaic: String) {
                    preferenceManager.saveString(PreferenceManager.KEY_OUTPUT_FORMAT, format)
                    if (demosaic.isNotEmpty()) {
                        preferenceManager.saveString(PreferenceManager.KEY_DEMOSAIC_ALGO, demosaic)
                    }
                }
            })
        } else {
            val currentId = activeMetadataCameraId()
            val caps = allVideoCapabilities[currentId] ?: emptyList()
            
            // Available FPS for current ratio
            val availableFps = caps.filter { it.ratioStr == selectedVideoRatioStr }
                .map { it.maxFps }.distinct().sortedDescending()
            
            // Available resolutions for current ratio and selected FPS
            val availableRes = caps.filter { it.ratioStr == selectedVideoRatioStr && it.maxFps == selectedVideoFps }
                .map { "${it.width}x${it.height}" }.distinct()

            formatPanelController.setVideoSettingsListener(object : FormatPanelController.VideoSettingsListener {
                override fun onVideoParamsChanged(fps: Int, res: String, bitrateMbps: Double) {
                    var changed = false
                    if (selectedVideoFps != fps) {
                        selectedVideoFps = fps
                        // When FPS changes, default to the highest resolution for this FPS
                        val bestForFps = caps.filter { it.ratioStr == selectedVideoRatioStr && it.maxFps == fps }
                            .maxByOrNull { it.width * it.height }
                        if (bestForFps != null) {
                            selectedVideoSizeStr = "${bestForFps.width}x${bestForFps.height}"
                        }
                        
                        preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, fps)
                        preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr)
                        changed = true
                    } else if (selectedVideoSizeStr != res) {
                        selectedVideoSizeStr = res
                        preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, res)
                        changed = true
                    }
                    
                    if (selectedVideoBitrate != bitrateMbps.toFloat()) {
                        selectedVideoBitrate = bitrateMbps.toFloat()
                        preferenceManager.saveFloat(PreferenceManager.KEY_VIDEO_BITRATE, selectedVideoBitrate)
                    }
                    
                    if (changed) {
                        runOnUiThread {
                            btnResolution.text = selectedVideoRatioStr
                            updateFormatPanelUI()
                        }
                        closeCamera()
                        openCamera()
                    }
                }

                override fun getVideoCapabilities(): List<Any> = caps
                override fun getCurrentRatio(): String = selectedVideoRatioStr
            })

            formatPanelController.setupVideoSpinners(
                fpsList = availableFps,
                resList = availableRes,
                currentFps = selectedVideoFps,
                currentRes = selectedVideoSizeStr,
                currentBitrate = selectedVideoBitrate.toDouble()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        backgroundHandler?.post(histogramTask)
        backgroundHandler?.post(freezeWatchdog)
        deviceOrientationTracker.enable()
        rawMediaStore.findLatestRecentPhotoUri()?.let(::updateRecentPhotoPreview)
        
        updateModeUI()
        updateCropOverlayUI()

        // 关键修复：强制等待布局稳定后再开启相机
        textureView.post {
            if (textureView.isAvailable) {
                openCamera()
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
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

    @SuppressLint("MissingPermission")
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
            lastPreviewFrameTime = System.currentTimeMillis()
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
            PhotoParamMapper.applyPhotoParamsToRequest(
                builder = builder,
                state = photoParamState,
                capabilities = cameraCapabilities,
                fallbackAutoIso = previewAutoIso,
                fallbackAutoShutterNanos = previewAutoShutterNanos,
                isStream = true
            )
            currentFlashMode.applyToRequest(builder, isStillCapture = false)
            applyZoomToRequest(builder)
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

            // 确定的分辨率选择逻辑
            val previewSize = if (isPhotoMode) {
                val targetRatio = 1.333f // 4:3
                supportedSizes?.filter { 
                    val r = it.width.toFloat() / it.height
                    Math.abs(r - targetRatio) < 0.1 
                }?.sortedByDescending { it.width * it.height }
                 ?.find { it.width <= 2500 } 
                 ?: Size(1440, 1080)
            } else {
                // 录像模式下优先直接匹配手动选择的特定分辨率
                val dims = selectedVideoSizeStr.split("x")
                if (dims.size == 2) {
                    val targetW = dims[0].toInt()
                    val targetH = dims[1].toInt()
                    // 在支持列表中找，同时兼容横竖屏声明差异
                    supportedSizes?.find { 
                        (it.width == targetW && it.height == targetH) || 
                        (it.width == targetH && it.height == targetW) 
                    } ?: Size(targetW, targetH)
                } else {
                    Size(1920, 1080)
                }
            }

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            runOnUiThread { configureTransform(previewSize.width, previewSize.height) }
            
            previewSurface?.release()
            previewSurface = Surface(texture)
            val surface = previewSurface ?: return

            // 1. 创建带有 Session Parameters 的请求模板 (回归到 TEMPLATE_PREVIEW 以保证稳定性)
            val sessionTemplate = CameraDevice.TEMPLATE_PREVIEW
            previewRequestBuilder = camera.createCaptureRequest(sessionTemplate).apply {
                addTarget(surface)
                PhotoParamMapper.applyPhotoParamsToRequest(this, photoParamState, cameraCapabilities, previewAutoIso, previewAutoShutterNanos, isStream = true)
                currentFlashMode.applyToRequest(this, isStillCapture = false)
                applyZoomToRequest(this)
                
                // 在预览请求中也注入高像素 Key 尝试激活 HAL 状态
                injectHighResVendorKeys(this)
            }

            val previewStateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    resumePreview()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    android.util.Log.e("HighResAttack", "Session 配置失败！尺寸或模式可能不受支持。")
                    showToast("高像素模式启动失败，尝试回退...")
                }
            }

            val outputSurfaces = mutableListOf(surface, rawReader.surface, jpegReader.surface)
            val outputConfigs = outputSurfaces.map { s ->
                OutputConfiguration(s).apply { 
                    currentPhysicalCameraId?.let { setPhysicalCameraId(it) }
                    // 尝试注入 Stream Use Case 为 STILL_CAPTURE (2)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s == rawReader.surface) {
                        try {
                            streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()
                            android.util.Log.i("HighResAttack", "成功设置 StreamUseCase = STILL_CAPTURE")
                        } catch (e: Exception) {
                            android.util.Log.w("HighResAttack", "设置 StreamUseCase 失败")
                        }
                    }
                }
            }

            // 2. 盲测 Session 模式：Vivo 高像素模式通常是 0x8004 或 0x8001
            val sessionMode = if (currentPhysicalCameraId == "2") {
                android.util.Log.i("HighResAttack", "使用 Vivo 私有 Session 模式: 0x8004")
                0x8004 
            } else {
                SessionConfiguration.SESSION_REGULAR
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sessionConfig = SessionConfiguration(
                    sessionMode,
                    outputConfigs,
                    Executor { command -> backgroundHandler?.post(command) },
                    previewStateCallback
                )
                // 3. 将注入了 Key 的请求设为 Session Parameters (部分 MTK 设备必须这样)
                sessionConfig.sessionParameters = previewRequestBuilder!!.build()
                camera.createCaptureSession(sessionConfig)
            } else {
                camera.createCaptureSession(outputSurfaces, previewStateCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            android.util.Log.e("HighResAttack", "创建预览异常: ${e.message}")
        }
    }

    private fun captureRawPhoto() {
        android.util.Log.d("CapturePerformance", "按下快门")
        captureStartTime = System.currentTimeMillis()
        
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

        val shutterNanos = if (photoParamState.shutter.isAuto) {
            previewAutoShutterNanos ?: 100_000_000L
        } else {
            photoParamState.shutter.manualValue ?: 100_000_000L
        }
        startShutterCountdown(shutterNanos)

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
                
                // 强行注入 Remosaic 控制键
                injectHighResVendorKeys(this)
                
                PhotoParamMapper.applyPhotoParamsToRequest(
                    builder = this,
                    state = photoParamState,
                    capabilities = cameraCapabilities,
                    fallbackAutoIso = previewAutoIso,
                    fallbackAutoShutterNanos = previewAutoShutterNanos,
                    isStream = false 
                )
                
                currentFlashMode.applyToRequest(this, isStillCapture = true)
                applyZoomToRequest(this)
                
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

    private fun injectHighResVendorKeys(builder: CaptureRequest.Builder) {
        val currentId = activeMetadataCameraId()
        if (currentId != "2") return

        android.util.Log.i("HighResAttack", "注入盲测解封指令集...")
        
        val blindTestKeys = mapOf(
            "com.vivo.cfaRemosaicType" to 4,
            "com.vivo.sensor.worktype" to 2,
            "vivo.control.ultra_highresolution" to 1,
            "vivo.control.ai_highresolution" to 1,
            "com.mediatek.control.capture.remosaicenable" to 1,
            "com.mediatek.control.capture.highres.enable" to 1,
            "vendor.vivo.remosaic.enable" to 1,
            "com.vivo.chi.override.remosaic.enable" to 1
        )

        for ((name, value) in blindTestKeys) {
            try {
                val keyConstructor = CaptureRequest.Key::class.java.getConstructor(String::class.java, Class::class.java)
                val manualKey = keyConstructor.newInstance(name, Int::class.javaObjectType)
                builder.set(manualKey as CaptureRequest.Key<Int>, value)
                android.util.Log.i("HighResAttack", "注入成功: $name = $value")
            } catch (e: Exception) {
                // Ignore failure for individual keys
            }
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
            android.util.Log.d("CapturePerformance", "OnCaptureCompleted (Metadata Ready): ${System.currentTimeMillis() - captureStartTime}ms")
            pendingCaptureResult = result
            runOnUiThread {
                resumePreview()
            }
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
        android.util.Log.d("CapturePerformance", "Data Received (RAW Image): ${System.currentTimeMillis() - captureStartTime}ms")
        val captureResult = pendingCaptureResult ?: return
        val characteristics = activeCameraCharacteristics
        if (characteristics == null) {
            image.close()
            pendingRawImage = null
            return
        }
        
        android.util.Log.d("CapturePerformance", "Metadata extraction + post start: ${System.currentTimeMillis() - captureStartTime}ms")

        // Capture all necessary state
        val savedFormat = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        val savedAlgoFull = preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast")
        
        val iso = captureResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val baseLsc = lscStrength
        val lscWeight = when {
            iso <= 100 -> 1.0f
            iso >= 3200 -> 0.5f
            else -> 1.0f - ((iso - 100).toFloat() / (3200 - 100) * 0.5f)
        }
        val currentLsc = baseLsc * lscWeight
        
        val algoKey = when {
            savedAlgoFull.startsWith("RGB通道") -> "RgbCh"
            savedAlgoFull.startsWith("灰度") -> "Mono"
            savedAlgoFull.startsWith("无") -> "None"
            else -> savedAlgoFull.split('：', ':')[0].trim()
        }
        
        val rotation = CaptureOrientationCalculator.captureRotation(
            sensorOrientation = currentSensorOrientation,
            lensFacing = currentLensFacing,
            deviceRotationDegrees = deviceOrientationTracker.deviceRotationDegrees,
        )

        val bayerPattern = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val blackLevelPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevel = if (blackLevelPattern != null) {
            (blackLevelPattern.getOffsetForIndex(0, 0) + 
             blackLevelPattern.getOffsetForIndex(1, 0) + 
             blackLevelPattern.getOffsetForIndex(0, 1) + 
             blackLevelPattern.getOffsetForIndex(1, 1)) / 4
        } else 64
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        
        val wbGains = captureResult.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wbArray = if (wbGains != null) {
            floatArrayOf(wbGains.red, wbGains.greenEven, wbGains.greenOdd, wbGains.blue)
        } else {
            floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        }

        // --- NEW PERFORMANCE OPTIMIZATION: Immediate Decoupling ---
        val sClone = System.currentTimeMillis()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val height = image.height

        // Fix for Vivo X300 and some other Mediatek devices where DngCreator expects a slightly larger buffer
        // than the actual image data (typically one extra row). We allocate enough to satisfy it.
        val minSizeNeeded = rowStride * (height + 1)
        val capacity = Math.max(buffer.remaining(), minSizeNeeded)

        val cloneBuffer = ByteBuffer.allocateDirect(capacity)
        cloneBuffer.put(buffer)
        cloneBuffer.position(0)
        cloneBuffer.limit(capacity)

        val task = CaptureTask(
            rawBuffer = cloneBuffer,
            width = image.width,
            height = image.height,
            rowStride = rowStride,
            pixelStride = plane.pixelStride,
            captureStartTime = captureStartTime,
            metadata = CaptureMetadata(
                iso = iso,
                wbArray = wbArray,
                bayerPattern = bayerPattern,
                blackLevel = blackLevel,
                whiteLevel = whiteLevel,
                lscStrength = currentLsc
            ),
            algoKey = algoKey,
            savedFormat = savedFormat,
            rotationDegrees = rotation.degrees,
            exifOrientation = rotation.exifOrientation,
            cropRect = currentCropRectInSensor
        )
        
        android.util.Log.d("CapturePerformance", "RAW Data Cloned: ${System.currentTimeMillis() - sClone}ms (Total: ${System.currentTimeMillis() - captureStartTime}ms)")
        
        // Close original image and enable shutter immediately
        image.close()
        pendingRawImage = null
        isCaptureInProgress = false
        runOnUiThread {
            btnShutter.isEnabled = true
            stopShutterCountdown()
            android.util.Log.d("CapturePerformance", "Shutter Re-enabled: ${System.currentTimeMillis() - captureStartTime}ms")
        }

        // Submit to thread pool
        captureExecutor.submit {
            executeCaptureTask(task, characteristics, captureResult)
        }
    }

    private fun executeCaptureTask(
        task: CaptureTask,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult
    ) {
        val startProcess = System.currentTimeMillis()
        android.util.Log.d("CapturePerformance", "Processing Thread Start: ${System.currentTimeMillis() - task.captureStartTime}ms")
        try {
            if (task.savedFormat == "RAW" || task.savedFormat == "JPG + RAW") {
                val sRaw = System.currentTimeMillis()
                // NOTE: RawMediaStore might need a real Image to work best, but it usually takes ByteBuffer
                // We might need to refactor saveRawImage or use a proxy
                // For now, assume it can handle the task parameters
                rawMediaStore.saveRawImageFromBuffer(
                    task.rawBuffer, task.width, task.height, task.rowStride, 
                    characteristics, captureResult, task.exifOrientation
                )
                android.util.Log.i("CapturePerformance", "Save DNG: ${System.currentTimeMillis() - sRaw}ms (Total: ${System.currentTimeMillis() - task.captureStartTime}ms)")
            }
            
            if (task.savedFormat != "RAW") {
                if (task.algoKey == "RgbCh") {
                    val sRgb = System.currentTimeMillis()
                    saveRgbChannelsFromBuffer(
                        task.rawBuffer, task.width, task.height, task.rowStride, task.pixelStride,
                        task.metadata.bayerPattern, task.metadata.blackLevel, task.metadata.whiteLevel, 
                        task.rotationDegrees, task.metadata.lscStrength
                    )
                    android.util.Log.i("CapturePerformance", "RGB Channels Save: ${System.currentTimeMillis() - sRgb}ms")
                } else {
                    val sDemosaic = System.currentTimeMillis()
                    var bitmap = DemosaicEngine.processFromBuffer(
                        task.rawBuffer, task.width, task.height, task.rowStride, task.pixelStride,
                        task.algoKey, task.metadata.wbArray, task.metadata.bayerPattern, 
                        task.metadata.blackLevel, task.metadata.whiteLevel, task.metadata.lscStrength
                    )
                    android.util.Log.i("CapturePerformance", "Native Demosaic (Parallel): ${System.currentTimeMillis() - sDemosaic}ms")

                    val sCrop = System.currentTimeMillis()
                    task.cropRect?.let { rect ->
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
                    android.util.Log.i("CapturePerformance", "Crop + Bitmap Op: ${System.currentTimeMillis() - sCrop}ms")

                    val sSave = System.currentTimeMillis()
                    val savedUri = if (task.algoKey == "Mono" || task.algoKey == "None") {
                        savePngBitmap(bitmap, "", task.rotationDegrees)
                    } else {
                        saveJpegBitmap(bitmap, task.rotationDegrees)
                    }
                    android.util.Log.i("CapturePerformance", "Save image (${task.algoKey}): ${System.currentTimeMillis() - sSave}ms (Total: ${System.currentTimeMillis() - task.captureStartTime}ms)")
                    
                    val sThumb = System.currentTimeMillis()
                    updateRecentPhotoPreview(savedUri)
                    android.util.Log.i("CapturePerformance", "Update UI Thumb: ${System.currentTimeMillis() - sThumb}ms (Total: ${System.currentTimeMillis() - task.captureStartTime}ms)")
                    bitmap.recycle()
                    android.util.Log.d("CapturePerformance", "保存完成 (总耗时: ${System.currentTimeMillis() - task.captureStartTime}ms)")
                }
            }

            android.util.Log.i("CapturePerformance", "--- Task Finished Flow: ${System.currentTimeMillis() - startProcess}ms ---")
        } catch (e: Exception) {
            android.util.Log.e("CapturePerformance", "Task failed", e)
        }
    }


    private fun finishCaptureWithError(message: String) {
        stopShutterCountdown()
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
        // Use background thread to load thumbnail then update UI
        captureExecutor.submit {
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
    }

    private fun resumePreview() {
        lastPreviewFrameTime = System.currentTimeMillis()
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
        
        // RAW - 回退到物理报告的最大尺寸，确保 Session 能够建立
        val largestRawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        
        val rawWidth = largestRawSize?.width ?: 4080
        val rawHeight = largestRawSize?.height ?: 3072

        if (rawImageReader == null || rawImageReader!!.width != rawWidth || rawImageReader!!.height != rawHeight) {
            rawImageReader?.close()
            rawImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 2)
            android.util.Log.i("HighResAttack", "ImageReader 已恢复为标准尺寸: ${rawWidth}x${rawHeight}")
        }
        
        rawImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            android.util.Log.i("HighResAttack", "收到 RAW 图像: ${image.width}x${image.height}, Format: ${image.format}")
            pendingRawImage?.close()
            pendingRawImage = image
            maybePersistPendingRaw()
        }, backgroundHandler)

        // JPEG/YUV
        val largestJpgSize = streamMap.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return false

        if (jpegImageReader == null || jpegImageReader!!.width != largestJpgSize.width || jpegImageReader!!.height != largestJpgSize.height) {
            jpegImageReader?.close()
            jpegImageReader = ImageReader.newInstance(largestJpgSize.width, largestJpgSize.height, ImageFormat.JPEG, 2)
        }
        jpegImageReader?.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
        }, backgroundHandler)

        return true
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
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "wCam 1.1")
        
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
        
        // Advanced EXIF
        val expMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val expProg = if (expMode == CaptureResult.CONTROL_AE_MODE_OFF) "1" else "2" // 1: Manual, 2: Normal
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, expProg)
        
        val bias = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (bias != null && step != null) {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, (bias * step.toFloat()).toString())
        }
        
        val whiteBalance = result.get(CaptureResult.CONTROL_AWB_MODE)
        val wbAttr = if (whiteBalance == CaptureResult.CONTROL_AWB_MODE_OFF) "1" else "0" // 1: Manual, 0: Auto
        exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, wbAttr)
        
        val metering = result.get(CaptureResult.CONTROL_AE_REGIONS)
        if (metering != null && metering.isNotEmpty()) {
            exif.setAttribute(ExifInterface.TAG_METERING_MODE, "2") // 2: CenterWeighted (approximation)
        } else {
            exif.setAttribute(ExifInterface.TAG_METERING_MODE, "5") // 5: Pattern
        }
        
        // Zoom and 35mm equivalent
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1.0f
        } else {
            currentZoomRatio
        }
        exif.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, zoom.toString())
        
        // Focal Length in 35mm film
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (sensorSize != null && focalLength != null) {
            val diagSensor = Math.sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble())
            val diag35mm = 43.27 // Diagonal of 35mm film
            val focal35mm = (focalLength * (diag35mm / diagSensor)).toInt()
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focal35mm.toString())
        }

        // Date Time
        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        val now = java.util.Date()
        exif.setAttribute(ExifInterface.TAG_DATETIME, sdf.format(now))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(now))
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, sdf.format(now))
        
        // Flash
        val flashState = result.get(CaptureResult.FLASH_STATE)
        val flashFired = flashState == CaptureResult.FLASH_STATE_FIRED || flashState == CaptureResult.FLASH_STATE_PARTIAL
        exif.setAttribute(ExifInterface.TAG_FLASH, if (flashFired) "1" else "0")
        
        // Location (if permitted)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) ?: 
                          lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?:
                          lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                loc?.let {
                    exif.setLatLong(it.latitude, it.longitude)
                    it.altitude.takeIf { alt -> alt != 0.0 }?.let { alt -> exif.setAltitude(alt) }
                }
            } catch (e: SecurityException) {
                // Ignore
            }
        }
    }

    private fun savePngBitmap(bitmap: Bitmap, suffix: String, rotationDegrees: Int, timestamp: String? = null): Uri {
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        
        val ts = timestamp ?: java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        val fileName = if (suffix.isEmpty()) "$ts.png" else "${ts}_$suffix.png"
        
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/wCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { os ->
            rotatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        } ?: throw IOException("Failed to open output stream")
        
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }


    private fun saveRgbChannelsFromBuffer(
        buffer: ByteBuffer,
        sensorW: Int,
        sensorH: Int,
        rowStride: Int,
        pixelStride: Int,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        rotationDegrees: Int,
        lscStrength: Float
    ) {
        // 1. Determine crop bounds at full resolution, aligned to 2x2 blocks
        val rect = currentCropRectInSensor ?: android.graphics.Rect(0, 0, sensorW, sensorH)
        val left = (rect.left.coerceIn(0, sensorW - 2) / 2) * 2
        val top = (rect.top.coerceIn(0, sensorH - 2) / 2) * 2
        val cropW = (rect.width().coerceAtMost(sensorW - left) / 2) * 2
        val cropH = (rect.height().coerceAtMost(sensorH - top) / 2) * 2
        
        // 2. Output dimensions for each channel
        val outW = cropW / 2
        val outH = cropH / 2
        
        if (outW <= 0 || outH <= 0) {
            return
        }

        // 3. Define Gamma values for each channel
        val gammaR = 3.0
        val gammaG = 2.4
        val gammaB = 2.8

        val range = (whiteLevel - blackLevel).coerceAtLeast(1).toFloat()
        
        fun createGammaLut(gammaPower: Double): IntArray {
            val lut = IntArray(65536)
            val invGamma = 1.0 / gammaPower
            val exposureScale = 0.5f
            for (i in 0..65535) {
                val norm = (((i - blackLevel).toFloat() / range) * exposureScale).coerceIn(0f, 1f)
                val gamma = if (norm <= 0.0031308f) 12.92f * norm else (1.055f * Math.pow(norm.toDouble(), invGamma) - 0.055f).toFloat()
                val gray = (gamma * 255).toInt().coerceIn(0, 255)
                lut[i] = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
            return lut
        }

        val lutR = createGammaLut(gammaR)
        val lutG = createGammaLut(gammaG)
        val lutB = createGammaLut(gammaB)

        val opticalCenterX = sensorW / 2.0f
        val opticalCenterY = sensorH / 2.0f
        val maxDistSq = opticalCenterX * opticalCenterX + opticalCenterY * opticalCenterY

        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val fullShortBuffer = buffer.asShortBuffer()
        val strideInShorts = rowStride / 2
        val psInShorts = pixelStride / 2

        val pixR = IntArray(outW * outH)
        val pixG = IntArray(outW * outH)
        val pixB = IntArray(outW * outH)

        // Parallel processing: Divide the image into horizontal strips
        val numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads)
        val latch = java.util.concurrent.CountDownLatch(numThreads)
        val rowsPerChunk = outH / numThreads

        for (t in 0 until numThreads) {
            val startY = t * rowsPerChunk
            val endY = if (t == numThreads - 1) outH else (t + 1) * rowsPerChunk
            
            executor.execute {
                try {
                    val threadBuffer = fullShortBuffer.duplicate()
                    val row0 = ShortArray(strideInShorts)
                    val row1 = ShortArray(strideInShorts)
                    
                    for (y in startY until endY) {
                        val sensorY = top + y * 2
                        val rO0 = sensorY * strideInShorts
                        val rO1 = (sensorY + 1) * strideInShorts
                        
                        threadBuffer.position(rO0)
                        threadBuffer.get(row0)
                        threadBuffer.position(rO1)
                        threadBuffer.get(row1)
                        
                        val dy = sensorY - opticalCenterY
                        val dySq = dy * dy
                        
                        val baseIdx = y * outW
                        for (x in 0 until outW) {
                            val sensorX = left + x * 2
                            val sX0 = sensorX
                            val sX1 = sensorX + 1
                            
                            val dx = sensorX - opticalCenterX
                            val distSq = dx * dx + dySq
                            
                            // Advanced LSC Model: 1 + k * (r^2 + r^4 + r^6)
                            // We use a more aggressive polynomial to handle steep fall-offs
                            val r2 = distSq / maxDistSq
                            val r4 = r2 * r2
                            val r6 = r4 * r2
                            val lscGain = 1.0f + lscStrength * (r2 + r4 + r6)
                            
                            val v00 = (row0[sX0 * psInShorts].toInt() and 0xFFFF)
                            val v01 = (row0[sX1 * psInShorts].toInt() and 0xFFFF)
                            val v10 = (row1[sX0 * psInShorts].toInt() and 0xFFFF)
                            val v11 = row1[sX1 * psInShorts].toInt() and 0xFFFF

                            // Apply LSC Gain before Gamma (Apply to signal, not black level pedestal)
                            // Use a small epsilon to avoid multiplying zero signals in the corners
                            fun applyLsc(v: Int, gain: Float): Int {
                                val signal = (v - blackLevel).toFloat()
                                return (signal * gain + blackLevel).toInt().coerceIn(0, 65535)
                            }

                            val gv00 = applyLsc(v00, lscGain)
                            val gv01 = applyLsc(v01, lscGain)
                            val gv10 = applyLsc(v10, lscGain)
                            val gv11 = applyLsc(v11, lscGain)

                            var rVal = 0; var grVal = 0; var gbVal = 0; var bVal = 0
                            when (bayerPattern) {
                                0 -> { rVal = gv00; grVal = gv01; gbVal = gv10; bVal = gv11 } // RGGB
                                1 -> { grVal = gv00; rVal = gv01; bVal = gv10; gbVal = gv11 } // GRBG
                                2 -> { gbVal = gv00; bVal = gv01; rVal = gv10; grVal = gv11 } // GBRG
                                3 -> { bVal = gv00; gbVal = gv01; grVal = gv10; rVal = gv11 } // BGGR
                            }
                            
                            val gAvg = (grVal + gbVal) / 2
                            val idx = baseIdx + x
                            pixR[idx] = lutR[rVal]
                            pixG[idx] = lutG[gAvg]
                            pixB[idx] = lutB[bVal]
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        executor.shutdown()

        val bmpR = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply { setPixels(pixR, 0, outW, 0, 0, outW, outH) }
        val bmpG = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply { setPixels(pixG, 0, outW, 0, 0, outW, outH) }
        val bmpB = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply { setPixels(pixB, 0, outW, 0, 0, outW, outH) }

        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rR = Bitmap.createBitmap(bmpR, 0, 0, outW, outH, matrix, true)
        val rG = Bitmap.createBitmap(bmpG, 0, 0, outW, outH, matrix, true)
        val rB = Bitmap.createBitmap(bmpB, 0, 0, outW, outH, matrix, true)
        
        bmpR.recycle(); bmpG.recycle(); bmpB.recycle()
        
        val rw = rR.width
        val rh = rR.height
        val combinedBitmap = Bitmap.createBitmap(rw * 3, rh, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(combinedBitmap)
        canvas.drawBitmap(rR, 0f, 0f, null)
        canvas.drawBitmap(rG, rw.toFloat(), 0f, null)
        canvas.drawBitmap(rB, (rw * 2).toFloat(), 0f, null)

        // Save as JPG using the quality setting from the format panel
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        val fileName = "${timestamp}_RGB.jpg"
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
            combinedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
        } ?: throw IOException("Failed to open output stream")

        updateRecentPhotoPreview(uri)

        rR.recycle(); rG.recycle(); rB.recycle()
        combinedBitmap.recycle()
    }


    private fun createPhotoDisplayName(extension: String): String {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        return "$timestamp.$extension"
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
        // To prevent crash when taking photo and switching modes/closing,
        // we should ensure processing is finished before closing readers.
        val rReader = rawImageReader
        val jReader = jpegImageReader
        val hReader = histogramImageReader
        
        rawImageReader = null
        jpegImageReader = null
        histogramImageReader = null

        captureExecutor.submit {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingJpegImage?.close()
            pendingJpegImage = null
            pendingCaptureResult = null
            
            rReader?.close()
            jReader?.close()
            hReader?.close()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        // Use a fixed thread pool based on available processors
        val numCores = Runtime.getRuntime().availableProcessors()
        captureExecutor = java.util.concurrent.Executors.newFixedThreadPool(numCores)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            
            captureExecutor.shutdown()
        } catch (e: InterruptedException) {
            showToast("后台线程停止失败")
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val permissions = requiredPermissions()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("PermissionDebug", "Missing permission: $permission")
                return false
            }
        }
        return true
    }

    private fun isRawOnly(): Boolean {
        val selected = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        return selected == "RAW"
    }

    private fun isJpgAndRaw(): Boolean {
        val selected = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        return selected == "JPG + RAW"
    }

    private fun showVideoParamsPopup(view: View) {
        val currentId = activeMetadataCameraId()
        val caps = allVideoCapabilities[currentId] ?: emptyList()
        if (caps.isEmpty()) {
            showToast("无法获取视频能力")
            return
        }

        // Sort ratios: 3:4, 9:16 first, then others by numerical value
        val common = listOf("3:4", "9:16")
        val ratioMap = caps.groupBy { it.ratioStr }.mapValues { it.value.first().ratio }
        val sortedRatios = ratioMap.keys.toList().sortedWith { a, b ->
            val aIn = common.indexOf(a)
            val bIn = common.indexOf(b)
            if (aIn != -1 || bIn != -1) {
                if (aIn != -1 && bIn != -1) aIn.compareTo(bIn)
                else if (aIn != -1) -1 else 1
            } else {
                ratioMap[a]!!.compareTo(ratioMap[b]!!)
            }
        }

        showStyledPopup(view, sortedRatios) { ratioStr ->
            selectedVideoRatioStr = ratioStr
            preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RATIO, ratioStr)
            
            // Auto-select: default to 30fps and highest resolution for 30fps
            val ratioCaps = caps.filter { it.ratioStr == ratioStr }
            val bestFor30 = ratioCaps.filter { it.maxFps >= 30 }
                .sortedWith(compareByDescending<VideoSizeInfo> { it.width * it.height }.thenBy { Math.abs(it.maxFps - 30) })
                .firstOrNull() ?: ratioCaps.maxByOrNull { it.width * it.height }
            
            if (bestFor30 != null) {
                selectedVideoSizeStr = "${bestFor30.width}x${bestFor30.height}"
                selectedVideoFps = bestFor30.maxFps.coerceAtMost(30).coerceAtLeast(if(bestFor30.maxFps >= 30) 30 else bestFor30.maxFps)
                // Actually if maxFps >= 30, we want 30.
                selectedVideoFps = if (bestFor30.maxFps >= 30) 30 else bestFor30.maxFps

                preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr)
                preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, selectedVideoFps)
            }

            btnResolution.text = selectedVideoRatioStr
            btnResolution.setTextColor(Color.parseColor("#FFCC00"))
            
            updateFormatPanelUI() // Refresh lists and selection
            closeCamera()
            openCamera()
        }
    }

    private fun showRatioPopup(view: View) {
        val options = listOf("1:1", "3:4", "9:16", "1:2", "自定义")
        showStyledPopup(view, options) { selected ->
            when (selected) {
                "1:1" -> {
                    cropRatio = 1.0f
                    btnResolution.text = "1:1"
                    updateCropOverlayUI()
                }
                "3:4" -> {
                    cropRatio = 4f / 3f
                    btnResolution.text = "3:4"
                    updateCropOverlayUI()
                }
                "9:16" -> {
                    cropRatio = 16f / 9f
                    btnResolution.text = "9:16"
                    updateCropOverlayUI()
                }
                "1:2" -> {
                    cropRatio = 2.0f
                    btnResolution.text = "1:2"
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
                    btnResolution.text = "$h:$w"
                    updateCropOverlayUI()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var countdownTimer: android.os.CountDownTimer? = null

    private fun startShutterCountdown(shutterNanos: Long) {
        val durationMs = shutterNanos / 1_000_000L
        if (durationMs < 500) return

        runOnUiThread {
            pbShutterProgress.visibility = View.VISIBLE
            tvShutterCountdown.visibility = View.VISIBLE
            pbShutterProgress.progress = 0
            tvShutterCountdown.text = String.format(java.util.Locale.US, "%.1fs", durationMs / 1000.0)
        }

        countdownTimer?.cancel()
        countdownTimer = object : android.os.CountDownTimer(durationMs, 50) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = durationMs - millisUntilFinished
                val progress = (elapsed.toFloat() / durationMs * 1000).toInt()
                runOnUiThread {
                    pbShutterProgress.progress = progress
                    tvShutterCountdown.text = String.format(java.util.Locale.US, "%.1fs", millisUntilFinished / 1000.0)
                }
            }

            override fun onFinish() {
                runOnUiThread {
                    pbShutterProgress.progress = 1000
                    tvShutterCountdown.text = "0.0s"
                }
            }
        }.start()
    }

    private fun stopShutterCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
        runOnUiThread {
            pbShutterProgress.visibility = View.GONE
            tvShutterCountdown.visibility = View.GONE
        }
    }

    private fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit) {
        val listPopupWindow = ListPopupWindow(this)
        listPopupWindow.animationStyle = 0 // Disable animation
        listPopupWindow.anchorView = anchor
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val layout = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(32, 24, 32, 24)
                    setBackgroundColor(Color.parseColor("#CC1A1A1A"))
                }
                layout.removeAllViews()

                val ratioStr = options[position]
                if (ratioStr.contains(":")) {
                    val ratioView = View(context).apply {
                        val parts = ratioStr.split(":")
                        val w = parts[0].toFloatOrNull() ?: 1f
                        val h = parts[1].toFloatOrNull() ?: 1f
                        
                        val baseSize = 40f
                        val finalW: Float
                        val finalH: Float
                        if (w > h) {
                            finalW = baseSize
                            finalH = baseSize * (h / w)
                        } else {
                            finalH = baseSize
                            finalW = baseSize * (w / h)
                        }
                        
                        layoutParams = LinearLayout.LayoutParams(finalW.toInt(), finalH.toInt()).apply {
                            marginEnd = 24
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setStroke(2, Color.WHITE)
                            setColor(Color.TRANSPARENT)
                        }
                    }
                    layout.addView(ratioView)
                }

                val textView = TextView(context).apply {
                    text = ratioStr
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                layout.addView(textView)
                
                return layout
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
        android.util.Log.d("CapturePerformance", "按下快门")
        captureStartTime = System.currentTimeMillis()
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

        val shutterNanos = if (photoParamState.shutter.isAuto) {
            previewAutoShutterNanos ?: 100_000_000L
        } else {
            photoParamState.shutter.manualValue ?: 100_000_000L
        }
        startShutterCountdown(shutterNanos)

        val captureAction = {
            try {
                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(rawReader.surface) // Always capture RAW for demosaicing
                    surface?.let { addTarget(it) }
                    
                    PhotoParamMapper.applyPhotoParamsToRequest(
                        builder = this,
                        state = photoParamState,
                        capabilities = cameraCapabilities,
                        fallbackAutoIso = previewAutoIso,
                        fallbackAutoShutterNanos = previewAutoShutterNanos,
                        isStream = false
                    )
                    
                    currentFlashMode.applyToRequest(this, isStillCapture = true)
                    applyZoomToRequest(this)
                }

                session.stopRepeating()
                val sReq = System.currentTimeMillis()
                session.capture(captureBuilder.build(), stillCaptureCallback, backgroundHandler)
                android.util.Log.d("CapturePerformance", "Capture Request Issued: ${System.currentTimeMillis() - sReq}ms (Total from shutter: ${System.currentTimeMillis() - captureStartTime}ms)")
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
        
        // Use a more relaxed check: if AWB_MODE_OFF is supported, we can usually do manual WB
        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        val supportsManualWhiteBalance = awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)

        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        cameraCapabilities = CameraCapabilities(
            aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat(),
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            supportsManualSensor = supportsManualSensor,
            supportsManualWhiteBalance = supportsManualWhiteBalance,
            aeFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList(),
        )
        currentFlashSupported = hasFlash
        maxZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 10.0f
        } else {
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
        }
        
        runOnUiThread {
            reapplyRestoredManualValues()
            setupDynamicParamGestures()
            updateCropOverlayUI()
        }
    }

    private fun reapplyRestoredManualValues() {
        // This ensures manualValue is populated using the current cameraCapabilities
        if (!photoParamState.ev.isAuto) {
            onParamManualStep(ParamKey.EV, photoParamState.ev.displayValue)
        }
        if (!photoParamState.wb.isAuto) {
            onParamManualStep(ParamKey.WB, photoParamState.wb.displayValue)
        }
        if (!photoParamState.iso.isAuto) {
            onParamManualStep(ParamKey.ISO, photoParamState.iso.displayValue)
        }
        if (!photoParamState.shutter.isAuto) {
            onParamManualStep(ParamKey.SHUTTER, photoParamState.shutter.displayValue)
        }
        if (!photoParamState.af.isAuto) {
            onParamManualStep(ParamKey.AF, photoParamState.af.displayValue)
        }
    }

    private fun setupDynamicParamGestures() {
        val caps = cameraCapabilities
        
        // EV: -4.0 to +4.0, 0.1 step (Linear)
        val evValues = (-40..40).map { String.format(java.util.Locale.US, "%+.1f", it * 0.1f) }

        // WB: 2000K to 8000K, 100 step (Linear)
        val wbValues = (2000..8000 step 100).map { "${it}K" }
        
        // ISO: 50 to 64k (Logarithmic)
        val isoValues = generateLogValues(50.0, 64000.0, 150).map {
            val iso = Math.round(it).toInt()
            if (iso >= 1000) {
                val kValue = iso / 1000.0
                if (kValue == kValue.toInt().toDouble()) String.format(java.util.Locale.US, "%dK", kValue.toInt())
                else String.format(java.util.Locale.US, "%.1fK", kValue)
            } else iso.toString()
        }.distinct()
        
        // S: 1/12000s to 32s (Logarithmic)
        val sRangeMin = 1_000_000_000L / 12000
        val sRangeMax = 32_000_000_000L
        val sValues = generateLogValues(sRangeMin.toDouble(), sRangeMax.toDouble(), 150).map { 
            PhotoParamMapper.formatShutterDisplay(it.toLong()) 
        }.distinct()
        
        // AF: Diopter 50 to 0 (50% travel for 50-1, 50% for 1-0) - 128 steps
        val nearDiopters = generateLogValues(1.0, 50.0, 64).reversed()
        val farDiopters = generateLogValues(0.0001, 1.0, 64).reversed()
        val afValues = (nearDiopters + farDiopters).map { 
            PhotoParamMapper.formatDiopterDisplay(it.toFloat()) 
        }.distinct().toMutableList()
        if (!afValues.contains("0")) afValues.add("0")

        ParamController(R.id.layout_param_ev, tvValEv, evValues, findClosestIndex(photoParamState.ev.displayValue, evValues), ParamKey.EV).setup()
        ParamController(R.id.layout_param_wb, tvValWb, wbValues, findClosestIndex(photoParamState.wb.displayValue, wbValues), ParamKey.WB).setup()
        ParamController(R.id.layout_param_iso, tvValIso, isoValues, findClosestIndex(photoParamState.iso.displayValue, isoValues), ParamKey.ISO).setup()
        ParamController(R.id.layout_param_s, tvValS, sValues, findClosestIndex(photoParamState.shutter.displayValue, sValues), ParamKey.SHUTTER).setup()
        ParamController(R.id.layout_param_af, tvValAf, afValues, findClosestIndex(photoParamState.af.displayValue, afValues), ParamKey.AF).setup()
    }

    private fun generateLogValues(min: Double, max: Double, steps: Int): List<Double> {
        val logMin = Math.log(min.coerceAtLeast(1e-10))
        val logMax = Math.log(max.coerceAtLeast(1e-10))
        val delta = (logMax - logMin) / (steps - 1)
        return (0 until steps).map { Math.exp(logMin + it * delta) }
    }

    private fun supportedKelvinValues(): List<Int> = listOf(3200, 4000, 5200, 6000, 7000)

    private fun applyZoomToRequest(builder: CaptureRequest.Builder) {
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

    private fun verifyHighResRawSupport() {
        val manager = cameraManager()
        android.util.Log.i("HighResCheck", "--- 开始验证 2 亿像素 RAW 支持情况 (全量探测) ---")
        try {
            val allIds = manager.cameraIdList.toMutableList()
            // 尝试添加用户提到的物理 ID
            listOf("2", "3", "4", "5").forEach { if (!allIds.contains(it)) allIds.add(it) }

            for (id in allIds) {
                val chars = try {
                    manager.getCameraCharacteristics(id)
                } catch (e: Exception) {
                    android.util.Log.w("HighResCheck", "无法访问摄像头 ID: $id (${e.message})")
                    continue
                }
                
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isUltraHighResSupported = capabilities.any { it == 16 }
                
                android.util.Log.i("HighResCheck", "摄像头 ID: $id")
                android.util.Log.i("HighResCheck", "  支持等级: ${chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}")
                android.util.Log.i("HighResCheck", "  超高分辨率能力 (16): $isUltraHighResSupported")
                
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                android.util.Log.i("HighResCheck", "  物理像素数组尺寸 (pixelArraySize): ${pixelArray?.width} x ${pixelArray?.height}")

                // 检查 Maximum Resolution Map
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    if (maxResMap != null) {
                        val rawSizes = maxResMap.getOutputSizes(ImageFormat.RAW_SENSOR)
                        if (rawSizes != null && rawSizes.isNotEmpty()) {
                            android.util.Log.i("HighResCheck", "  [MAX_RES] RAW_SENSOR 尺寸:")
                            for (size in rawSizes) {
                                val mp = (size.width.toLong() * size.height) / 1_000_000.0
                                android.util.Log.i("HighResCheck", "    - ${size.width} x ${size.height} (~${String.format(java.util.Locale.US, "%.1f", mp)} MP)")
                            }
                        }
                    }
                }
                
                // 检查标准 Map
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val normalRawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                if (normalRawSizes != null && normalRawSizes.isNotEmpty()) {
                    android.util.Log.i("HighResCheck", "  [NORMAL_RES] RAW_SENSOR 尺寸:")
                    for (size in normalRawSizes) {
                        val mp = (size.width.toLong() * size.height) / 1_000_000.0
                        android.util.Log.i("HighResCheck", "    - ${size.width} x ${size.height} (~${String.format(java.util.Locale.US, "%.1f", mp)} MP)")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HighResCheck", "验证失败: ${e.message}")
        }
        android.util.Log.i("HighResCheck", "--- 验证结束 ---")
    }

    private fun dumpVendorKeys() {
        val manager = cameraManager()
        android.util.Log.i("VendorDeepDive", "--- 开始深度探测 Vivo/MTK 厂商私有键 (全量探测) ---")
        try {
            val allIds = manager.cameraIdList.toMutableList()
            listOf("2", "3", "4", "5").forEach { if (!allIds.contains(it)) allIds.add(it) }

            for (id in allIds) {
                val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
                android.util.Log.i("VendorDeepDive", "摄像头 ID: $id")
                
                // 打印所有包含有趣词汇的 Characteristics 键
                chars.keys.forEach { key ->
                    val name = key.name
                    if (name.contains("remosaic", true) || name.contains("highres", true) || name.contains("raw", true) || name.contains("pixel", true)) {
                        try {
                            val value = chars.get(key)
                            android.util.Log.i("VendorDeepDive", "  [Char Key] $name = ${formatValue(value)}")
                        } catch (e: Exception) {}
                    }
                }

                // 打印所有可用的 CaptureRequest 键（包含厂商键）
                chars.availableCaptureRequestKeys.forEach { key ->
                    val name = key.name
                    if (name.contains("remosaic", true) || name.contains("highres", true) || name.contains("ultra", true)) {
                        android.util.Log.i("VendorDeepDive", "  [Request Key Found] $name")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VendorDeepDive", "探测失败: ${e.message}")
        }
        android.util.Log.i("VendorDeepDive", "--- 深度探测结束 ---")
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            is IntArray -> value.joinToString(", ")
            is FloatArray -> value.joinToString(", ")
            is LongArray -> value.joinToString(", ")
            is Array<*> -> value.joinToString(", ")
            is android.util.Size -> "${value.width}x${value.height}"
            is android.graphics.Rect -> "${value.left},${value.top},${value.right},${value.bottom}"
            else -> value.toString()
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = android.graphics.Matrix()
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        
        if (viewW == 0f || viewH == 0f) return

        // 视频流在传感器坐标系下（通常是横屏）的宽高
        val streamW = viewWidth.toFloat()
        val streamH = viewHeight.toFloat()
        
        // 旋转后的视频流在屏幕坐标系下的宽高（假设 90 度旋转）
        val rotatedStreamW = streamH
        val rotatedStreamH = streamW
        
        val viewRatio = viewW / viewH // 0.75
        val streamRatio = rotatedStreamW / rotatedStreamH // 例如 16:9 是 0.5625
        
        var scaleX = 1.0f
        var scaleY = 1.0f
        
        // 抵消 TextureView 默认的 FILL 拉伸，实现 Center Inside
        if (streamRatio > viewRatio) {
            // 流比预览框更“胖”：宽度铺满，高度按比例缩小以保持不拉伸
            scaleY = (viewW / streamRatio) / viewH
        } else {
            // 流比预览框更“瘦”：高度铺满，宽度按比例缩小以保持不拉伸
            scaleX = (viewH * streamRatio) / viewW
        }
        
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        textureView.setTransform(matrix)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
