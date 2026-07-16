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
import java.util.concurrent.ConcurrentHashMap
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
import java.nio.ByteBuffer
import com.example.wcam.camera.ParamValue
import com.example.wcam.camera.FlashMode
import com.example.wcam.camera.PhotoParamMapper
import com.example.wcam.camera.PhotoParamState
import com.example.wcam.camera.RawMediaStore
import com.example.wcam.camera.CameraDebugger
import com.example.wcam.camera.CameraStreamMonitor
import com.example.wcam.camera.VideoSizeInfo
import com.example.wcam.camera.CaptureProcessor
import com.example.wcam.camera.CaptureSessionManager
import com.example.wcam.camera.CameraManager as WCameraManager
import com.example.wcam.ui.CameraUiController
import com.example.wcam.ui.FormatPanelController
import com.example.wcam.ui.ManualParamController
import com.example.wcam.ui.OverlayController
import com.example.wcam.ui.HistogramView

class MainActivity : AppCompatActivity(),
    ManualParamController.ParamChangeListener,
    CameraUiController.UiEventListener,
    WCameraManager.CameraListener,
    CaptureSessionManager.SessionListener,
    CameraStreamMonitor.MonitorListener {

    private lateinit var cameraManager: WCameraManager
    private lateinit var sessionManager: CaptureSessionManager
    private lateinit var captureProcessor: CaptureProcessor
    private lateinit var uiController: CameraUiController
    private lateinit var manualParamController: ManualParamController
    private lateinit var overlayController: OverlayController
    private lateinit var streamMonitor: CameraStreamMonitor

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
    private val pendingRawImages = ConcurrentHashMap<Long, Image>()
    private val pendingCaptureResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val captureStartTimeMap = ConcurrentHashMap<Long, Long>()
    private var lscStrength = 1.30f
    private var pendingJpegImage: Image? = null
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

    private var selectedPhotoRatioStr: String = "4:3"
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


    private var lastPreviewFrameTime = 0L

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

        CameraDebugger.logAllCameraStreams(this, allVideoCapabilities)
        CameraDebugger.verifyHighResRawSupport(this)
        CameraDebugger.dumpVendorKeys(this)

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
        
        selectedPhotoRatioStr = preferenceManager.getString(PreferenceManager.KEY_PHOTO_RATIO_STR, "4:3")
        cropRatio = preferenceManager.getFloat(PreferenceManager.KEY_PHOTO_RATIO, 4f / 3f)
        
        updateModeUI()

        selectedVideoRatioStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RATIO, "16:9")
        selectedVideoSizeStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RESOLUTION, "1920x1080")
        selectedVideoFps = preferenceManager.getInt(PreferenceManager.KEY_VIDEO_FPS, 30)
        selectedVideoBitrate = preferenceManager.getFloat(PreferenceManager.KEY_VIDEO_BITRATE, 20.0f)

        val jpgQual = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
        seekbarJpgQuality.progress = jpgQual
        tvJpgQualityTitle.text = "JPG 质量: $jpgQual%"

        btnResolution.text = if (isPhotoMode) selectedPhotoRatioStr else selectedVideoRatioStr
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
            uiController.updateResolutionText(btnResolution, selectedPhotoRatioStr, selectedPhotoRatioStr != "4:3")
            btnShutter.setBackgroundResource(R.drawable.bg_shutter_modern)
            overlayController.setCropOverlayVisible(true)
            btnModeSwitch.text = "切换\n录像"
        } else {
            uiController.updateResolutionText(btnResolution, selectedVideoRatioStr, true)
            btnShutter.setBackgroundResource(R.drawable.bg_shutter_video)
            overlayController.setCropOverlayVisible(false)
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
            overlayController.updatePhysicalResText(resText)
            overlayController.setHistogramVisible(isHistogramVisible) // Updates visibility together with physicalRes
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
            overlayController.updateCrop(relLeft, relTop, relRight, relBottom)
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

        initControllers()
    }

    private fun initControllers() {
        cameraManager = WCameraManager(this, this)
        sessionManager = CaptureSessionManager(this)
        captureProcessor = CaptureProcessor(contentResolver, rawMediaStore)
        uiController = CameraUiController(this)
        manualParamController = ManualParamController(this)
        overlayController = OverlayController(viewGridLines, viewHistogram, viewCropOverlay, tvPhysicalRes)
        
        // Initialized with a dummy handler, will be properly post-looper in onResume
        streamMonitor = CameraStreamMonitor(Handler(mainLooper), this)
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
                val mode = if (requestedMode != FlashMode.OFF && !currentFlashSupported) {
                    showToast("当前设备不支持闪光灯")
                    FlashMode.OFF
                } else {
                    requestedMode
                }
                onFlashModeChanged(mode)
            }
        }

        btnHistogram.setOnClickListener {
            onHistogramToggle()
        }

        btnGridLines.setOnClickListener {
            onGridToggle()
        }

        btnResolution.setOnClickListener { view ->
            onResolutionClick(view)
        }

        btnFormat.setOnClickListener {
            onFormatPanelToggle()
        }

        btnModeSwitch.setOnClickListener {
            onModeSwitch()
        }

        btnModeSwitch.setOnLongClickListener {
            onModeReset()
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

    override fun onParamManualStep(key: ParamKey, selectedLabel: String) {
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

    override fun onParamAutoReset(key: ParamKey) {
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
        manualParamController.refreshViews(
            tvValEv, tvValWb, tvValIso, tvValS, tvValAf,
            photoParamState,
            getPreviewAutoValues()
        )
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
        val handler = backgroundHandler!!
        
        streamMonitor = CameraStreamMonitor(handler, this)
        streamMonitor.start()
        
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
            val characteristics = activeCameraCharacteristics ?: return
            val handler = backgroundHandler ?: return

            if (!cameraManager.configureImageReaders(characteristics, handler)) {
                showToast("当前镜头不支持预览")
                return
            }
            cameraManager.openCamera(currentLogicalCameraId, handler)
        } catch (e: Exception) {
            showToast("打开相机失败")
        }
    }


    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
    ) {
        streamMonitor.notifyFrameReceived()
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
            previewAutoKelvinLabel = PhotoParamMapper.gainsToKelvinLabel(gains, PhotoParamMapper.SUPPORTED_KELVIN_VALUES)
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
            sessionManager.applyZoom(builder, currentZoomRatio, activeCameraCharacteristics)
        }
        resumePreview()
    }

    private fun createCameraPreviewSession() {
        val camera = cameraDevice ?: return
        val rawReader = cameraManager.rawImageReader ?: return
        val jpegReader = cameraManager.jpegImageReader ?: return
        val handler = backgroundHandler ?: return

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

            val sessionTemplate = CameraDevice.TEMPLATE_PREVIEW
            previewRequestBuilder = camera.createCaptureRequest(sessionTemplate).apply {
                addTarget(surface)
                PhotoParamMapper.applyPhotoParamsToRequest(this, photoParamState, cameraCapabilities, previewAutoIso, previewAutoShutterNanos, isStream = true)
                currentFlashMode.applyToRequest(this, isStillCapture = false)
                sessionManager.applyZoom(this, currentZoomRatio, activeCameraCharacteristics)
            }

            val surfaces = listOf(surface, rawReader.surface, jpegReader.surface)
            sessionManager.createSession(
                camera = camera,
                surfaces = surfaces,
                physicalCameraId = currentPhysicalCameraId,
                initialRequest = previewRequestBuilder!!.build(),
                backgroundHandler = handler,
                rawSurface = rawReader.surface
            )
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
        val imageReader = cameraManager.rawImageReader
        val surface = previewSurface
        if (camera == null || session == null || imageReader == null) {
            showToast("拍照尚未就绪")
            return
        }

        isCaptureInProgress = true
        isRawCaptureComplete = false
        isJpegCaptureComplete = true
        btnShutter.isEnabled = false

        val shutterNanos = if (photoParamState.shutter.isAuto) {
            previewAutoShutterNanos ?: 100_000_000L
        } else {
            photoParamState.shutter.manualValue ?: 100_000_000L
        }
        startShutterCountdown(shutterNanos)

        if (currentFlashMode == FlashMode.SINGLE) {
            runFlashPulse {
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
                previewSurface?.let { addTarget(targetSurface) }
                setTag(captureStartTime)
                if (isJpgAndRaw() || (!isRawOnly() && !isPhotoMode)) { // Logic for JPG from RAW
                     cameraManager.rawImageReader?.surface?.let { addTarget(it) }
                }
                
                PhotoParamMapper.applyPhotoParamsToRequest(
                    builder = this,
                    state = photoParamState,
                    capabilities = cameraCapabilities,
                    fallbackAutoIso = previewAutoIso,
                    fallbackAutoShutterNanos = previewAutoShutterNanos,
                    isStream = false 
                )
                
                currentFlashMode.applyToRequest(this, isStillCapture = true)
                sessionManager.applyZoom(this, currentZoomRatio, activeCameraCharacteristics)
                
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

    private fun runFlashPulse(onReady: () -> Unit) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        try {
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
            // 延迟一小段时间让闪光灯稳定并让传感器适应光线变化
            backgroundHandler?.postDelayed({
                onReady()
            }, 10)
        } catch (e: Exception) {
            onReady()
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val wallTime = request.tag as? Long ?: System.currentTimeMillis()
            
            pendingCaptureResults[sensorTimestamp] = result
            captureStartTimeMap[sensorTimestamp] = wallTime
            
            android.util.Log.d("CapturePerformance", "OnCaptureCompleted: ts=$sensorTimestamp, wall=$wallTime")
            
            runOnUiThread {
                applyPhotoParamsToPreview()
            }
            processPendingCaptures()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            finishCaptureWithError("RAW 拍照失败")
        }
    }

    private fun processPendingCaptures() {
        val characteristics = activeCameraCharacteristics ?: return
        
        // Match images with capture results by timestamp
        for (timestamp in pendingRawImages.keys()) {
            val image = pendingRawImages[timestamp]
            val result = pendingCaptureResults[timestamp]
            val wallTime = captureStartTimeMap[timestamp]
            
            if (image != null && result != null && wallTime != null) {
                // Remove from maps BEFORE submitting to ensure they aren't processed again
                pendingRawImages.remove(timestamp)
                pendingCaptureResults.remove(timestamp)
                captureStartTimeMap.remove(timestamp)
                
                dispatchCaptureTask(image, result, characteristics, wallTime)
            }
        }
        
        // Cleanup old data if burst is very long or something dropped
        if (pendingRawImages.size > 20) {
            val oldest = pendingRawImages.keys().toList().sorted().first()
            pendingRawImages.remove(oldest)?.close()
        }
        if (pendingCaptureResults.size > 20) {
            val oldest = pendingCaptureResults.keys().toList().sorted().first()
            pendingCaptureResults.remove(oldest)
            captureStartTimeMap.remove(oldest)
        }
    }

    private fun dispatchCaptureTask(
        image: Image,
        captureResult: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        timestamp: Long
    ) {
        val savedFormat = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")

        // 立即重置 UI 状态，允许下一次快门触发 (对于连拍，这个逻辑可能需要更精细，
        // 但目前先保持原样，只是把 image 传下去)
        isCaptureInProgress = false
        runOnUiThread {
            btnShutter.isEnabled = true
            stopShutterCountdown()
        }

        captureExecutor.submit {
            try {
                // 1. 保存 RAW (如果需要)
                if (savedFormat == "RAW" || savedFormat == "JPG + RAW") {
                    try {
                        val uri = rawMediaStore.saveRawImage(image, characteristics, captureResult, 0, timestamp)
                        // 如果只有 RAW 模式，更新预览图。如果是 JPG+RAW，则由 JPG 任务负责更新更好的预览
                        if (savedFormat == "RAW") {
                            updateRecentPhotoPreview(uri)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CapturePerformance", "RAW 直接保存失败", e)
                    }
                }

                // 2. 处理 JPG (如果需要)
                if (savedFormat == "JPG" || savedFormat == "JPG + RAW") {
                    try {
                        val task = prepareCaptureTask(image, captureResult, characteristics, savedFormat, timestamp)
                        executeCaptureTask(task, characteristics, captureResult)
                    } catch (e: Exception) {
                        android.util.Log.e("CapturePerformance", "JPG 处理失败", e)
                    }
                }
            } finally {
                image.close()
            }
        }
    }

    private fun prepareCaptureTask(
        image: Image,
        result: TotalCaptureResult,
        chars: CameraCharacteristics,
        format: String,
        timestamp: Long
    ): CaptureTask {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val currentLsc = lscStrength * (if (iso <= 100) 1.0f else if (iso >= 3200) 0.5f else 1.0f - ((iso - 100).toFloat() / 3100f * 0.5f))
        
        val savedAlgoFull = preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast")
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

        val bayerPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val blackLevelPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevel = if (blackLevelPattern != null) {
            (blackLevelPattern.getOffsetForIndex(0, 0) + blackLevelPattern.getOffsetForIndex(1, 0) + 
             blackLevelPattern.getOffsetForIndex(0, 1) + blackLevelPattern.getOffsetForIndex(1, 1)) / 4
        } else 64
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        
        val wbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wbArray = if (wbGains != null) floatArrayOf(wbGains.red, wbGains.greenEven, wbGains.greenOdd, wbGains.blue)
                      else floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

        // 提取硬件 Lens Shading Map
        val shadingMapObj = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shadingMapData = if (shadingMapObj != null) {
            val data = FloatArray(shadingMapObj.rowCount * shadingMapObj.columnCount * 4)
            shadingMapObj.copyGainFactors(data, 0)
            data
        } else null
        val shadingMapSize = if (shadingMapObj != null) {
            android.util.Size(shadingMapObj.columnCount, shadingMapObj.rowCount)
        } else null

        // 提取硬件 CCM (Color Correction Matrix)
        // 优先使用 ForwardMatrix1 (白平衡后的 Sensor RGB -> XYZ)
        val cct = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) 
                  ?: chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)

        val ccm = FloatArray(9)
        if (cct != null) {
            val rationals = arrayOfNulls<android.util.Rational>(9)
            cct.copyElements(rationals, 0)
            for (i in 0 until 9) {
                ccm[i] = rationals[i]?.toFloat() ?: 0f
            }
        } else {
            ccm[0] = 1f; ccm[4] = 1f; ccm[8] = 1f // Identity
        }

        val plane = image.planes[0]
        val cloneBuffer = ByteBuffer.allocateDirect(plane.buffer.remaining())
        cloneBuffer.put(plane.buffer)
        cloneBuffer.position(0)

        return CaptureTask(
            rawBuffer = cloneBuffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            captureStartTime = timestamp,
            metadata = CaptureMetadata(iso, wbArray, ccm, shadingMapData, shadingMapSize, bayerPattern, blackLevel, whiteLevel, currentLsc),
            algoKey = algoKey,
            savedFormat = format,
            rotationDegrees = rotation.degrees,
            exifOrientation = rotation.exifOrientation,
            cropRect = currentCropRectInSensor
        )
    }


    private fun executeCaptureTask(
        task: CaptureTask,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult
    ) {
        val startProcess = System.currentTimeMillis()
        android.util.Log.d("CapturePerformance", "Processing Thread Start: ${System.currentTimeMillis() - task.captureStartTime}ms")
        try {
            if (task.savedFormat != "RAW") {
                // 所有算法现在统一走 JNI 流程，包含裁剪和旋转
                var bitmap = DemosaicEngine.processFromBuffer(
                    task.rawBuffer, task.width, task.height, task.rowStride, task.pixelStride,
                    task.algoKey, task.metadata.wbArray, task.metadata.ccm, 
                    task.metadata.shadingMap, task.metadata.shadingMapSize,
                    task.metadata.bayerPattern,
                    task.metadata.blackLevel, task.metadata.whiteLevel, task.metadata.lscStrength,
                    task.cropRect, task.rotationDegrees
                )

                val savedUri = if (task.algoKey == "Mono" || task.algoKey == "None") {
                    captureProcessor.savePngBitmap(bitmap, "", 0, task.captureStartTime)
                } else {
                    captureProcessor.saveJpegBitmap(bitmap, 0, task.captureStartTime, seekbarJpgQuality.progress, characteristics, captureResult)
                }
                
                updateRecentPhotoPreview(savedUri)
                bitmap.recycle()
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
        applyPhotoParamsToPreview()
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
        } catch (e: Exception) {
            showToast("恢复预览失败")
        }
    }

    private fun closeCamera() {
        sessionManager.close()
        cameraManager.closeCamera()
        previewSurface?.release()
        previewSurface = null
        isCaptureInProgress = false
        releaseImageResources()
    }

    private fun releaseImageResources() {
        cameraManager.releaseReaders()

        captureExecutor.submit {
            pendingRawImages.values.forEach { it.close() }
            pendingRawImages.clear()
            pendingCaptureResults.clear()
            captureStartTimeMap.clear()
            
            pendingJpegImage?.close()
            pendingJpegImage = null
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
                    selectedPhotoRatioStr = "1:1"
                }
                "3:4" -> {
                    cropRatio = 4f / 3f
                    selectedPhotoRatioStr = "3:4"
                }
                "9:16" -> {
                    cropRatio = 16f / 9f
                    selectedPhotoRatioStr = "9:16"
                }
                "1:2" -> {
                    cropRatio = 2.0f
                    selectedPhotoRatioStr = "1:2"
                }
                "自定义" -> {
                    showCustomRatioDialog()
                    return@showStyledPopup
                }
            }
            btnResolution.text = selectedPhotoRatioStr
            btnResolution.setTextColor(Color.parseColor("#FFCC00"))
            updateCropOverlayUI()
            
            preferenceManager.saveFloat(PreferenceManager.KEY_PHOTO_RATIO, cropRatio)
            preferenceManager.saveString(PreferenceManager.KEY_PHOTO_RATIO_STR, selectedPhotoRatioStr)
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
                    selectedPhotoRatioStr = "$h:$w"
                    btnResolution.text = selectedPhotoRatioStr
                    updateCropOverlayUI()
                    
                    preferenceManager.saveFloat(PreferenceManager.KEY_PHOTO_RATIO, cropRatio)
                    preferenceManager.saveString(PreferenceManager.KEY_PHOTO_RATIO_STR, selectedPhotoRatioStr)
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
        val rawReader = cameraManager.rawImageReader ?: return
        val surface = previewSurface

        isCaptureInProgress = true
        isJpegCaptureComplete = false
        isRawCaptureComplete = false
        btnShutter.isEnabled = false
        pendingJpegImage?.close()
        pendingJpegImage = null

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
                    setTag(captureStartTime)
                    
                    PhotoParamMapper.applyPhotoParamsToRequest(
                        builder = this,
                        state = photoParamState,
                        capabilities = cameraCapabilities,
                        fallbackAutoIso = previewAutoIso,
                        fallbackAutoShutterNanos = previewAutoShutterNanos,
                        isStream = false
                    )
                    
                    currentFlashMode.applyToRequest(this, isStillCapture = true)
                    sessionManager.applyZoom(this, currentZoomRatio, activeCameraCharacteristics)
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
            runFlashPulse { captureAction() }
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

    private fun refreshActiveCameraCharacteristics() {
        activeCameraCharacteristics = try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            manager.getCameraCharacteristics(activeMetadataCameraId()).also { characteristics ->
                currentSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                currentLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            }
        } catch (e: Exception) {
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

        manualParamController.ParamGestureHandler(findViewById(R.id.layout_param_ev), ParamKey.EV, evValues).setup()
        manualParamController.ParamGestureHandler(findViewById(R.id.layout_param_wb), ParamKey.WB, wbValues).setup()
        manualParamController.ParamGestureHandler(findViewById(R.id.layout_param_iso), ParamKey.ISO, isoValues).setup()
        manualParamController.ParamGestureHandler(findViewById(R.id.layout_param_s), ParamKey.SHUTTER, sValues).setup()
        manualParamController.ParamGestureHandler(findViewById(R.id.layout_param_af), ParamKey.AF, afValues).setup()
    }

    private fun generateLogValues(min: Double, max: Double, steps: Int): List<Double> {
        val logMin = Math.log(min.coerceAtLeast(1e-10))
        val logMax = Math.log(max.coerceAtLeast(1e-10))
        val delta = (logMax - logMin) / (steps - 1)
        return (0 until steps).map { Math.exp(logMin + it * delta) }
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

    override fun getPhotoParamState(): PhotoParamState = photoParamState

    override fun getPreviewAutoValues(): ManualParamController.PreviewAutoValues {
        return ManualParamController.PreviewAutoValues(
            evCompensation = previewAutoCompensation,
            evStep = cameraCapabilities.aeCompensationStep ?: 0f,
            wbKelvin = previewAutoKelvinLabel,
            iso = previewAutoIso,
            shutterNanos = previewAutoShutterNanos,
            afDistance = previewAutoFocusDistance
        )
    }

    override fun onFlashModeChanged(mode: FlashMode) {
        currentFlashMode = mode
        uiController.updateFlashUi(btnFlash, currentFlashMode)
        applyPhotoParamsToPreview()
    }

    override fun onHistogramToggle() {
        isHistogramVisible = !isHistogramVisible
        overlayController.setHistogramVisible(isHistogramVisible)
        uiController.updateHistogramUi(btnHistogram, isHistogramVisible)
        preferenceManager.saveBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, isHistogramVisible)
        updateCropOverlayUI()
    }

    override fun onGridToggle() {
        isGridVisible = !isGridVisible
        overlayController.setGridVisible(isGridVisible)
        uiController.updateGridUi(btnGridLines, isGridVisible)
        preferenceManager.saveBoolean(PreferenceManager.KEY_GRID_VISIBLE, isGridVisible)
    }

    override fun onResolutionClick(view: View) {
        if (isPhotoMode) {
            showRatioPopup(view)
        } else {
            showVideoParamsPopup(view)
        }
    }

    override fun onFormatPanelToggle() {
        isFormatPanelOpen = !isFormatPanelOpen
        if (isFormatPanelOpen) {
            updateFormatPanelUI()
            panelFormatSettings.visibility = View.VISIBLE
        } else {
            panelFormatSettings.visibility = View.GONE
        }
        uiController.updateFormatButtonUi(btnFormat, isFormatPanelOpen)
    }

    override fun onModeSwitch() {
        isPhotoMode = !isPhotoMode
        updateModeUI()
        preferenceManager.saveBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, isPhotoMode)
        closeCamera()
        openCamera()
    }

    override fun onModeReset() {
        preferenceManager.clear()
        showToast("设置已重置")
        loadSettings()
        closeCamera()
        openCamera()
    }

    override fun onCameraOpened(camera: CameraDevice) {
        cameraDevice = camera
        createCameraPreviewSession()
    }

    override fun onCameraDisconnected() {
        cameraDevice = null
    }

    override fun onCameraError(error: Int) {
        cameraDevice = null
        showToast("相机连接失败")
        finish()
    }

    override fun onRawImageAvailable(image: Image) {
        android.util.Log.i("HighResAttack", "收到 RAW 图像: ts=${image.timestamp}, ${image.width}x${image.height}")
        pendingRawImages[image.timestamp] = image
        processPendingCaptures()
    }

    override fun onSessionConfigured(session: CameraCaptureSession) {
        captureSession = session
        resumePreview()
    }

    override fun onSessionConfigureFailed() {
        showToast("Session 配置失败")
    }

    override fun isCaptureInProgress(): Boolean = isCaptureInProgress
    override fun isHistogramVisible(): Boolean = isHistogramVisible
    override fun getPreviewTexture(): TextureView = textureView

    override fun onStreamFrozen() {
        runOnUiThread {
            closeCamera()
            backgroundHandler?.postDelayed({
                openCamera()
            }, 500)
        }
    }

    override fun onHistogramUpdate(r: IntArray, g: IntArray, b: IntArray) {
        overlayController.updateHistogram(r, g, b)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
