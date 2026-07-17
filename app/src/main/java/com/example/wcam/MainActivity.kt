package com.example.wcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.wcam.camera.*
import com.example.wcam.camera.CameraManager as WCameraManager
import com.example.wcam.databinding.ActivityMainBinding
import com.example.wcam.ui.*
import com.example.wcam.util.PermissionManager
import com.example.wcam.viewmodel.CameraViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(),
    ManualParamController.ParamChangeListener,
    CameraUiController.UiEventListener,
    WCameraManager.CameraListener,
    CaptureSessionManager.SessionListener,
    CameraStreamMonitor.MonitorListener,
    CaptureManager.CaptureListener,
    GestureManager.GestureListener,
    LensController.LensListener,
    AspectRatioController.AspectRatioListener,
    LocationListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CameraViewModel by viewModels()

    private lateinit var cameraManager: WCameraManager
    private lateinit var sessionManager: CaptureSessionManager
    private lateinit var captureManager: CaptureManager
    private lateinit var captureProcessor: CaptureProcessor
    private lateinit var uiController: CameraUiController
    private lateinit var manualParamController: ManualParamController
    private lateinit var overlayController: OverlayController
    private lateinit var streamMonitor: CameraStreamMonitor
    private lateinit var gestureManager: GestureManager
    private lateinit var lensController: LensController
    private lateinit var aspectController: AspectRatioController
    private lateinit var galleryController: GalleryController

    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private lateinit var captureExecutor: ExecutorService

    private lateinit var formatPanelController: FormatPanelController
    private lateinit var rawMediaStore: RawMediaStore
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var deviceOrientationTracker: DeviceOrientationTracker

    private var currentLogicalCameraId = "0"
    private var currentPhysicalCameraId: String? = "2"
    private var activeCameraCharacteristics: CameraCharacteristics? = null
    private var previewAutoIso: Int? = null
    private var previewAutoShutterNanos: Long? = null
    private var previewAutoCompensation: Int? = null
    private var previewAutoKelvinLabel: String? = null
    private var previewAutoFocusDistance: Float? = null
    private var cameraCapabilities: CameraCapabilities = CameraCapabilities.EMPTY
    private var currentFlashSupported: Boolean = false
    private var currentSensorOrientation: Int = 0
    private var currentLensFacing: Int? = null
    private var selectedPhotoRatioStr: String = "4:3"
    private var selectedVideoRatioStr: String = "16:9"
    private var selectedVideoSizeStr: String = "1920x1080"
    private var selectedVideoFps: Int = 30
    private var selectedVideoBitrate: Float = 20.0f
    private var allVideoCapabilities = mutableMapOf<String, List<VideoSizeInfo>>()
    private var aeState = CameraMetadata.CONTROL_AE_STATE_INACTIVE
    private var isAeAfLocked = false
    private var cropRatio = 4f / 3f
    private var maxZoomRatio = 10.0f
    private var currentCropRectInSensor: Rect? = null

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1
        init { System.loadLibrary("wcam") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()

        CameraDebugger.logDetailedCameraInfo(this)
        CameraDebugger.logAllCameraStreams(this, allVideoCapabilities)
        CameraDebugger.verifyHighResRawSupport(this)
        CameraDebugger.dumpVendorKeys(this)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        initViews()
        setupListeners()
        setupParamGestures()
        loadSettings()
        observeViewModel()
        setupLifecycleObserver()
        initLocation()
    }

    private fun initLocation() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (PermissionManager.hasAllRequiredPermissions(this)) {
            try {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
                lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                    ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start location updates", e)
            }
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) { lastLocation = location }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun setupLifecycleObserver() {
        lifecycle.addObserver(CameraLifecycleObserver(
            onResumeAction = { handleOnResume() },
            onPauseAction = { handleOnPause() }
        ))
    }

    private fun handleOnResume() {
        startBackgroundThread()
        startLocationUpdates()
        streamMonitor = CameraStreamMonitor(backgroundHandler!!, this)
        streamMonitor.start()
        deviceOrientationTracker.enable()
        galleryController.getRecentUri() ?: rawMediaStore.findLatestRecentPhotoUri()?.let { galleryController.updateRecentPhotoPreview(it) }
        updateModeUI(viewModel.isPhotoMode.value)
        updateCropOverlayUI()
        binding.cameraPreview.post {
            if (binding.cameraPreview.isAvailable) openCamera()
            else binding.cameraPreview.surfaceTextureListener = surfaceTextureListener
        }
    }

    private fun handleOnPause() {
        deviceOrientationTracker.disable()
        stopLocationUpdates()
        closeCamera()
        stopBackgroundThread()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isPhotoMode.collect { updateModeUI(it) } }
                launch { viewModel.isGridVisible.collect { overlayController.setGridVisible(it); uiController.updateGridUi(binding.topToolbar.btnGridLines, it) } }
                launch { viewModel.isHistogramVisible.collect { overlayController.setHistogramVisible(it); uiController.updateHistogramUi(binding.topToolbar.btnHistogram, it); updateCropOverlayUI() } }
                launch { viewModel.isFormatPanelOpen.collect { isOpen -> if (isOpen) { updateFormatPanelUI(); binding.panelFormatSettings.visibility = View.VISIBLE } else binding.panelFormatSettings.visibility = View.GONE; uiController.updateFormatButtonUi(binding.topToolbar.btnFormat, isOpen) } }
                launch { viewModel.photoParamState.collect { refreshParamViews(it) } }
                launch { viewModel.currentFlashMode.collect { uiController.updateFlashUi(binding.topToolbar.btnFlash, it); applyPhotoParamsToPreview() } }
            }
        }
    }

    private fun initViews() {
        rawMediaStore = RawMediaStore(this)
        preferenceManager = PreferenceManager(this)
        deviceOrientationTracker = DeviceOrientationTracker(this)
        formatPanelController = FormatPanelController(
            this,
            binding.spinnerMainFormat,
            binding.layoutJpgAdvancedOptions,
            binding.spinnerDemosaicAlgo,
            binding.layoutVideoAdvancedOptions,
            binding.spinnerVideoFps,
            binding.spinnerVideoRes,
            binding.seekbarVideoBitrate,
            binding.tvVideoBitrateTitle
        )

        initControllers()
    }

    private fun initControllers() {
        cameraManager = WCameraManager(this, this)
        sessionManager = CaptureSessionManager(this)
        captureProcessor = CaptureProcessor(this, rawMediaStore)
        uiController = CameraUiController(this, this)
        manualParamController = ManualParamController(this)
        overlayController = OverlayController(binding.viewGridLines, binding.viewHistogram, binding.viewCropOverlay, binding.tvPhysicalRes)
        streamMonitor = CameraStreamMonitor(Handler(mainLooper), this)
        captureManager = CaptureManager(cameraManager, captureProcessor, preferenceManager, Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()), Handler(mainLooper), this)
        gestureManager = GestureManager(this, this)
        lensController = LensController(LensController.LensButtons(binding.lensSelector.btnLensFront, binding.lensSelector.btnLensWide, binding.lensSelector.btnLensMain, binding.lensSelector.btnLensTele), this)
        aspectController = AspectRatioController(this, preferenceManager, overlayController, this)
        galleryController = GalleryController(this, binding.bottomActionBar.btnGallery, rawMediaStore, Executors.newSingleThreadExecutor()) { runnable -> runOnUiThread(runnable) }
    }

    private fun setupListeners() {
        binding.bottomActionBar.btnShutter.setOnClickListener { capturePhoto() }
        binding.topToolbar.btnFlash.setOnClickListener { view ->
            uiController.showStyledPopup(view, listOf(getString(R.string.flash_off), getString(R.string.flash_on), getString(R.string.flash_torch))) { selected ->
                val mode = when (selected) { getString(R.string.flash_on) -> FlashMode.SINGLE; getString(R.string.flash_torch) -> FlashMode.TORCH; else -> FlashMode.OFF }
                if (mode != FlashMode.OFF && !currentFlashSupported) showToast(getString(R.string.toast_flash_not_supported)) else viewModel.setFlashMode(mode)
            }
        }
        binding.topToolbar.btnHistogram.setOnClickListener { viewModel.setHistogramVisible(!viewModel.isHistogramVisible.value) }
        binding.topToolbar.btnGridLines.setOnClickListener { viewModel.setGridVisible(!viewModel.isGridVisible.value) }
        binding.topToolbar.btnResolution.setOnClickListener { view -> onResolutionClick(view) }
        binding.topToolbar.btnFormat.setOnClickListener { viewModel.setFormatPanelOpen(!viewModel.isFormatPanelOpen.value) }
        binding.bottomActionBar.btnModeSwitch.setOnClickListener { onModeSwitch() }
        binding.bottomActionBar.btnModeSwitch.setOnLongClickListener { onModeReset(); true }
        binding.bottomActionBar.btnGallery.setOnClickListener { galleryController.openRecentPhotoInGallery() }
        binding.seekbarJpgQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                binding.tvJpgQualityTitle.text = getString(R.string.jpg_quality_title, p)
                if (f) preferenceManager.saveInt(PreferenceManager.KEY_JPG_QUALITY, p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.cameraPreview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && viewModel.isFormatPanelOpen.value) {
                val loc = IntArray(2); binding.panelFormatSettings.getLocationOnScreen(loc)
                if (event.rawX < loc[0] || event.rawX > loc[0] + binding.panelFormatSettings.width || event.rawY < loc[1] || event.rawY > loc[1] + binding.panelFormatSettings.height) {
                    viewModel.setFormatPanelOpen(false); binding.topToolbar.btnFormat.setTextColor(Color.WHITE)
                }
            }
            gestureManager.handleTouch(event)
        }
    }

    private fun loadSettings() {
        viewModel.setPhotoMode(preferenceManager.getBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, true))
        currentLogicalCameraId = preferenceManager.getString(PreferenceManager.KEY_LENS_ID, "0")
        currentPhysicalCameraId = preferenceManager.getString(PreferenceManager.KEY_PHYSICAL_LENS_ID, "2")
        viewModel.setGridVisible(preferenceManager.getBoolean(PreferenceManager.KEY_GRID_VISIBLE, false))
        viewModel.setHistogramVisible(preferenceManager.getBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, false))
        selectedPhotoRatioStr = preferenceManager.getString(PreferenceManager.KEY_PHOTO_RATIO_STR, "4:3")
        cropRatio = preferenceManager.getFloat(PreferenceManager.KEY_PHOTO_RATIO, 4f / 3f)
        selectedVideoRatioStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RATIO, "16:9")
        selectedVideoSizeStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RESOLUTION, "1920x1080")
        selectedVideoFps = preferenceManager.getInt(PreferenceManager.KEY_VIDEO_FPS, 30)
        selectedVideoBitrate = preferenceManager.getFloat(PreferenceManager.KEY_VIDEO_BITRATE, 20.0f)
        val jpgQual = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
        binding.seekbarJpgQuality.progress = jpgQual
        binding.tvJpgQualityTitle.text = getString(R.string.jpg_quality_title, jpgQual)
        
        lensController.refreshUi(currentLogicalCameraId, currentPhysicalCameraId)
        updateCropOverlayUI()
        restorePhotoParamState()
    }

    private fun restorePhotoParamState() {
        val state = PhotoParamState(
            ev = ParamValue(preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_EV_AUTO, true), preferenceManager.getString(PreferenceManager.KEY_PARAM_EV_VAL, "+0.0"), null),
            wb = ParamValue(preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_WB_AUTO, true), preferenceManager.getString(PreferenceManager.KEY_PARAM_WB_VAL, "5200K"), null),
            iso = ParamValue(preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_ISO_AUTO, true), preferenceManager.getString(PreferenceManager.KEY_PARAM_ISO_VAL, "100"), null),
            shutter = ParamValue(preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_S_AUTO, true), preferenceManager.getString(PreferenceManager.KEY_PARAM_S_VAL, "1/125"), null),
            af = ParamValue(preferenceManager.getBoolean(PreferenceManager.KEY_PARAM_AF_AUTO, true), preferenceManager.getString(PreferenceManager.KEY_PARAM_AF_VAL, "0"), null)
        )
        viewModel.setPhotoParamState(state)
    }

    private fun updateModeUI(isPhotoMode: Boolean) {
        val set = ConstraintSet(); set.clone(binding.main)
        set.setDimensionRatio(binding.previewContainer.id, "3:4")
        if (isPhotoMode) {
            uiController.updateResolutionText(binding.topToolbar.btnResolution, selectedPhotoRatioStr, selectedPhotoRatioStr != "4:3")
            binding.bottomActionBar.btnShutter.setBackgroundResource(R.drawable.bg_shutter_modern)
            overlayController.setCropOverlayVisible(true)
            binding.bottomActionBar.btnModeSwitch.text = getString(R.string.switch_to) + getString(R.string.mode_video)
        } else {
            uiController.updateResolutionText(binding.topToolbar.btnResolution, selectedVideoRatioStr, true)
            binding.bottomActionBar.btnShutter.setBackgroundResource(R.drawable.bg_shutter_video)
            overlayController.setCropOverlayVisible(false)
            binding.bottomActionBar.btnModeSwitch.text = getString(R.string.switch_to) + getString(R.string.mode_photo)
        }
        set.applyTo(binding.main)
        if (viewModel.isFormatPanelOpen.value) updateFormatPanelUI()
    }

    private fun updateFormatPanelUI() {
        val format = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        val algo = preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast")
        formatPanelController.update(viewModel.isPhotoMode.value, format, algo)
        if (viewModel.isPhotoMode.value) {
            formatPanelController.setPhotoSettingsListener(object : FormatPanelController.PhotoSettingsListener {
                override fun onPhotoParamsChanged(f: String, a: String) {
                    preferenceManager.saveString(PreferenceManager.KEY_OUTPUT_FORMAT, f)
                    if (a.isNotEmpty()) preferenceManager.saveString(PreferenceManager.KEY_DEMOSAIC_ALGO, a)
                }
            })
        } else {
            val currentId = activeMetadataCameraId(); val caps = allVideoCapabilities[currentId] ?: emptyList()
            val availableFps = caps.filter { it.ratioStr == selectedVideoRatioStr }.map { it.maxFps }.distinct().sortedDescending()
            val availableRes = caps.filter { it.ratioStr == selectedVideoRatioStr && it.maxFps == selectedVideoFps }.map { "${it.width}x${it.height}" }.distinct()
            formatPanelController.setVideoSettingsListener(object : FormatPanelController.VideoSettingsListener {
                override fun onVideoParamsChanged(fps: Int, res: String, bitrateMbps: Double) {
                    var changed = false
                    if (selectedVideoFps != fps) {
                        selectedVideoFps = fps; val best = caps.find { it.ratioStr == selectedVideoRatioStr && it.maxFps == fps }; if (best != null) selectedVideoSizeStr = "${best.width}x${best.height}"
                        preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, fps); preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr); changed = true
                    } else if (selectedVideoSizeStr != res) {
                        selectedVideoSizeStr = res; preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, res); changed = true
                    }
                    if (selectedVideoBitrate != bitrateMbps.toFloat()) { selectedVideoBitrate = bitrateMbps.toFloat(); preferenceManager.saveFloat(PreferenceManager.KEY_VIDEO_BITRATE, selectedVideoBitrate) }
                    if (changed) { runOnUiThread { binding.topToolbar.btnResolution.text = selectedVideoRatioStr; updateFormatPanelUI() }; closeCamera(); openCamera() }
                }
                override fun getVideoCapabilities(): List<Any> = caps
                override fun getCurrentRatio(): String = selectedVideoRatioStr
            })
            formatPanelController.setupVideoSpinners(availableFps, availableRes, selectedVideoFps, selectedVideoSizeStr, selectedVideoBitrate.toDouble())
        }
    }

    private fun capturePhoto() {
        val cam = cameraDevice ?: return; val ses = captureSession ?: return; val surf = previewSurface ?: return
        captureManager.capturePhoto(cam, ses, surf)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!PermissionManager.hasAllRequiredPermissions(this)) { ActivityCompat.requestPermissions(this, PermissionManager.getRequiredPermissions(), REQUEST_CAMERA_PERMISSION); return }
        try {
            refreshActiveCameraCharacteristics(); refreshCameraCapabilities()
            val chars = activeCameraCharacteristics ?: return; val handler = backgroundHandler ?: return
            if (!cameraManager.configureImageReaders(chars, handler)) { showToast(getString(R.string.toast_preview_not_supported)); return }
            cameraManager.openCamera(currentLogicalCameraId, handler)
        } catch (e: Exception) { showToast(getString(R.string.toast_camera_open_failed)) }
    }

    private fun closeCamera() { 
        sessionManager.close()
        cameraManager.closeCamera()
        previewSurface?.release()
        previewSurface = null
        captureSession = null
        previewRequestBuilder = null
        releaseImageResources() 
    }

    private fun releaseImageResources() { cameraManager.releaseReaders(); captureManager.release() }

    private fun resumePreview() {
        val ses = captureSession ?: return; val req = previewRequestBuilder?.build() ?: return
        try { ses.setRepeatingRequest(req, previewCaptureCallback, backgroundHandler) } catch (e: Exception) { showToast(getString(R.string.toast_preview_resume_failed)) }
    }

    private fun updateCropOverlayUI() { currentCropRectInSensor = aspectController.updateCropUI(viewModel.isPhotoMode.value, activeCameraCharacteristics, selectedVideoSizeStr, selectedVideoFps) }

    private fun refreshActiveCameraCharacteristics() {
        activeCameraCharacteristics = try { (getSystemService(CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(activeMetadataCameraId()).also { currentSensorOrientation = it.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0; currentLensFacing = it.get(CameraCharacteristics.LENS_FACING) } } catch (e: Exception) { null }
    }

    private fun refreshCameraCapabilities() {
        val chars = activeCameraCharacteristics ?: run { cameraCapabilities = CameraCapabilities.EMPTY; return }
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        cameraCapabilities = CameraCapabilities(chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE), chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat(), chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE), chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE), chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE), caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR), chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty().contains(CameraMetadata.CONTROL_AWB_MODE_OFF), chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList())
        currentFlashSupported = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        maxZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 10f else chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f
        runOnUiThread { reapplyRestoredManualValues(); setupDynamicParamGestures(); updateCropOverlayUI() }
    }

    private fun reapplyRestoredManualValues() {
        val state = viewModel.photoParamState.value
        if (!state.ev.isAuto) onParamManualStep(ParamKey.EV, state.ev.displayValue)
        if (!state.wb.isAuto) onParamManualStep(ParamKey.WB, state.wb.displayValue)
        if (!state.iso.isAuto) onParamManualStep(ParamKey.ISO, state.iso.displayValue)
        if (!state.shutter.isAuto) onParamManualStep(ParamKey.SHUTTER, state.shutter.displayValue)
        if (!state.af.isAuto) onParamManualStep(ParamKey.AF, state.af.displayValue)
    }

    private fun setupDynamicParamGestures() {
        val evValues = (-40..40).map { String.format(java.util.Locale.US, "%+.1f", it * 0.1f) }
        val wbValues = (2000..8000 step 100).map { "${it}K" }
        val isoValues = generateLogValues(50.0, 64000.0, 150).map { PhotoParamMapper.formatIsoDisplay(it.toInt()) }.distinct()
        val sValues = generateLogValues((1e9/12000), 32e9, 150).map { PhotoParamMapper.formatShutterDisplay(it.toLong()) }.distinct()
        val afValues = (generateLogValues(1.0, 50.0, 64).reversed() + generateLogValues(0.0001, 1.0, 64).reversed()).map { PhotoParamMapper.formatDiopterDisplay(it.toFloat()) }.distinct().toMutableList().also { if (!it.contains("0")) it.add("0") }
        manualParamController.ParamGestureHandler(binding.paramBar.layoutParamEv, ParamKey.EV, evValues).setup()
        manualParamController.ParamGestureHandler(binding.paramBar.layoutParamWb, ParamKey.WB, wbValues).setup()
        manualParamController.ParamGestureHandler(binding.paramBar.layoutParamIso, ParamKey.ISO, isoValues).setup()
        manualParamController.ParamGestureHandler(binding.paramBar.layoutParamS, ParamKey.SHUTTER, sValues).setup()
        manualParamController.ParamGestureHandler(binding.paramBar.layoutParamAf, ParamKey.AF, afValues).setup()
    }

    private fun generateLogValues(min: Double, max: Double, steps: Int): List<Double> {
        val logMin = Math.log(min.coerceAtLeast(1e-10)); val logMax = Math.log(max.coerceAtLeast(1e-10)); val delta = (logMax - logMin) / (steps - 1)
        return (0 until steps).map { Math.exp(logMin + it * delta) }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = android.graphics.Matrix(); val viewW = binding.cameraPreview.width.toFloat(); val viewH = binding.cameraPreview.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val streamRatio = viewHeight.toFloat() / viewWidth.toFloat(); val viewRatio = viewW / viewH
        var scaleX = 1.0f; var scaleY = 1.0f
        if (streamRatio > viewRatio) scaleY = (viewW / streamRatio) / viewH else scaleX = (viewH * streamRatio) / viewW
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f); binding.cameraPreview.setTransform(matrix)
    }

    private fun setupParamGestures() { refreshParamViews(viewModel.photoParamState.value) }
    private fun refreshParamViews(state: PhotoParamState) { manualParamController.refreshViews(binding.paramBar.tvValEv, binding.paramBar.tvValWb, binding.paramBar.tvValIso, binding.paramBar.tvValS, binding.paramBar.tvValAf, state, getPreviewAutoValues()) }

    override fun onParamManualStep(key: ParamKey, selectedLabel: String) {
        val newState = PhotoParamMapper.createManualStateForLabel(key, selectedLabel, cameraCapabilities, viewModel.photoParamState.value)
        viewModel.setPhotoParamState(newState); saveParamState(key); applyPhotoParamsToPreview()
    }
    override fun onParamAutoReset(key: ParamKey) {
        if (key == ParamKey.EV) onParamManualStep(ParamKey.EV, "+0.0")
        val newState = PhotoParamMapper.restoreAutoState(key, viewModel.photoParamState.value)
        viewModel.setPhotoParamState(newState); saveParamState(key); applyPhotoParamsToPreview()
    }
    override fun getPhotoParamState(): PhotoParamState = viewModel.photoParamState.value
    override fun getPreviewAutoValues(): ManualParamController.PreviewAutoValues = ManualParamController.PreviewAutoValues(previewAutoCompensation, cameraCapabilities.aeCompensationStep ?: 0f, previewAutoKelvinLabel, previewAutoIso, previewAutoShutterNanos, previewAutoFocusDistance)
    override fun onFlashModeChanged(mode: FlashMode) { viewModel.setFlashMode(mode) }
    override fun onHistogramToggle() { viewModel.setHistogramVisible(!viewModel.isHistogramVisible.value); preferenceManager.saveBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, viewModel.isHistogramVisible.value) }
    override fun onGridToggle() { viewModel.setGridVisible(!viewModel.isGridVisible.value); preferenceManager.saveBoolean(PreferenceManager.KEY_GRID_VISIBLE, viewModel.isGridVisible.value) }
    override fun onResolutionClick(view: View) { if (viewModel.isPhotoMode.value) aspectController.showPhotoRatioPopup(view) else showVideoParamsPopup(view) }
    override fun onFormatPanelToggle() { viewModel.setFormatPanelOpen(!viewModel.isFormatPanelOpen.value) }
    override fun onModeSwitch() {
        viewModel.setPhotoMode(!viewModel.isPhotoMode.value)
        preferenceManager.saveBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, viewModel.isPhotoMode.value)
        closeCamera(); openCamera()
    }
    override fun onModeReset() { preferenceManager.clear(); showToast(getString(R.string.toast_settings_reset)); loadSettings(); closeCamera(); openCamera() }
    override fun onCameraOpened(camera: CameraDevice) { cameraDevice = camera; createCameraPreviewSession() }
    override fun onCameraDisconnected() { cameraDevice = null }
    override fun onCameraError(error: Int) { cameraDevice = null; showToast(getString(R.string.toast_camera_connection_failed)); finish() }
    override fun onRawImageAvailable(image: Image) { captureManager.onRawImageAvailable(image) }
    override fun onSessionConfigured(session: CameraCaptureSession) { captureSession = session; resumePreview() }
    override fun onSessionConfigureFailed() { showToast(getString(R.string.toast_session_config_failed)) }
    override fun isCaptureInProgress(): Boolean = captureManager.isCaptureInProgress
    override fun isHistogramVisible(): Boolean = viewModel.isHistogramVisible.value
    override fun getPreviewTexture(): TextureView = binding.cameraPreview
    override fun onStreamFrozen() { runOnUiThread { closeCamera(); backgroundHandler?.postDelayed({ openCamera() }, 500) } }
    override fun onHistogramUpdate(r: IntArray, g: IntArray, b: IntArray) { overlayController.updateHistogram(r, g, b) }
    override fun onCaptureStarted(shutterNanos: Long) { runOnUiThread { binding.bottomActionBar.btnShutter.isEnabled = false; uiController.startShutterCountdown(shutterNanos, binding.bottomActionBar.pbShutterProgress, binding.bottomActionBar.tvShutterCountdown) } }
    override fun onCaptureCompleted(uri: Uri?) { runOnUiThread { uri?.let { galleryController.updateRecentPhotoPreview(it) } } }
    override fun onCaptureFailed(message: String) { runOnUiThread { binding.bottomActionBar.btnShutter.isEnabled = true; uiController.stopShutterCountdown(binding.bottomActionBar.pbShutterProgress, binding.bottomActionBar.tvShutterCountdown); showToast(message) } }
    override fun onReadyToResumePreview() { runOnUiThread { binding.bottomActionBar.btnShutter.isEnabled = true; uiController.stopShutterCountdown(binding.bottomActionBar.pbShutterProgress, binding.bottomActionBar.tvShutterCountdown); resumePreview() } }
    override fun onSingleTap(x: Float, y: Float) { binding.viewFocusRing.x = x - binding.viewFocusRing.width / 2; binding.viewFocusRing.y = y - binding.viewFocusRing.height / 2 + binding.cameraPreview.top; binding.viewFocusRing.visibility = View.VISIBLE; binding.viewFocusRing.alpha = 1f; binding.viewFocusRing.scaleX = 1.2f; binding.viewFocusRing.scaleY = 1.2f; binding.viewFocusRing.animate().scaleX(1f).scaleY(1f).alpha(0.5f).setDuration(300).withEndAction { binding.viewFocusRing.postDelayed({ binding.viewFocusRing.visibility = View.GONE }, 1000) }.start(); sessionManager.triggerFocusAndMetering(x, y, binding.cameraPreview, activeCameraCharacteristics, previewRequestBuilder, backgroundHandler, previewCaptureCallback); resumePreview() }
    override fun onLongPress() { isAeAfLocked = !isAeAfLocked; sessionManager.toggleAeAfLock(isAeAfLocked, previewRequestBuilder); showToast(if (isAeAfLocked) getString(R.string.ae_af_locked) else getString(R.string.ae_af_unlocked)); applyPhotoParamsToPreview() }
    override fun onZoomChanged(scale: Float) { aspectController.setCropScale(scale); updateCropOverlayUI() }
    override fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit) { uiController.showStyledPopup(anchor, options, onSelected) }
    override fun getActiveCameraCharacteristics(): CameraCharacteristics? = activeCameraCharacteristics
    override fun getFlashMode(): FlashMode = viewModel.currentFlashMode.value
    override fun getZoomRatio(): Float = viewModel.zoomRatio.value
    override fun getDeviceRotationDegrees(): Int = deviceOrientationTracker.deviceRotationDegrees
    override fun getSensorOrientation(): Int = currentSensorOrientation
    override fun getLensFacing(): Int? = currentLensFacing
    override fun getCurrentCropRect(): Rect? = currentCropRectInSensor
    override fun getJpgQuality(): Int = binding.seekbarJpgQuality.progress
    override fun getCameraCapabilities(): CameraCapabilities = cameraCapabilities
    override fun getLocation(): Location? = lastLocation

    private fun updateVideoParamsForNewLens() {
        val newId = currentPhysicalCameraId ?: currentLogicalCameraId
        val caps = allVideoCapabilities[newId] ?: emptyList()
        val supported = caps.find { "${it.width}x${it.height}" == selectedVideoSizeStr && it.maxFps >= selectedVideoFps }
        if (supported == null) {
            val fallback = caps.find { it.width == 1920 && it.height == 1080 && it.maxFps >= 60 }
            if (fallback != null) {
                selectedVideoSizeStr = "1920x1080"; selectedVideoFps = 60; selectedVideoRatioStr = fallback.ratioStr
            }
        }
        binding.topToolbar.btnResolution.text = selectedVideoRatioStr
    }

    override fun onLensSelected(logicalId: String, physicalId: String?) {
        if (currentLogicalCameraId != logicalId || currentPhysicalCameraId != physicalId) {
            currentLogicalCameraId = logicalId; currentPhysicalCameraId = physicalId
            saveCurrentLensSettings(); lensController.refreshUi(currentLogicalCameraId, currentPhysicalCameraId)
            if (!viewModel.isPhotoMode.value) updateVideoParamsForNewLens()
            closeCamera(); if (viewModel.isFormatPanelOpen.value) updateFormatPanelUI()
            if (binding.cameraPreview.isAvailable) openCamera()
        }
    }

    override fun onRatioChanged(ratio: Float, label: String) {
        cropRatio = ratio; selectedPhotoRatioStr = label; binding.topToolbar.btnResolution.text = label
        binding.topToolbar.btnResolution.setTextColor(if (label != "4:3") Color.parseColor("#FFCC00") else Color.WHITE)
        updateCropOverlayUI()
        preferenceManager.saveFloat(PreferenceManager.KEY_PHOTO_RATIO, cropRatio)
        preferenceManager.saveString(PreferenceManager.KEY_PHOTO_RATIO_STR, selectedPhotoRatioStr)
    }

    override fun onVideoParamsChanged() { /* Handled via FormatPanelController */ }

    private fun startBackgroundThread() { backgroundThread = HandlerThread("CameraBackground").also { it.start() }; backgroundHandler = Handler(backgroundThread!!.looper); val numCores = Runtime.getRuntime().availableProcessors(); captureExecutor = Executors.newFixedThreadPool(numCores) }
    private fun stopBackgroundThread() { backgroundThread?.quitSafely(); try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null; captureExecutor.shutdown() } catch (e: InterruptedException) { showToast("后台线程停止失败") } }
    private fun activeMetadataCameraId(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) currentPhysicalCameraId ?: currentLogicalCameraId else currentLogicalCameraId
    private fun showToast(msg: String) { runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
    private fun applyPhotoParamsToPreview() { previewRequestBuilder?.let { PhotoParamMapper.applyPhotoParamsToRequest(it, viewModel.photoParamState.value, cameraCapabilities, previewAutoIso, previewAutoShutterNanos, true); viewModel.currentFlashMode.value.applyToRequest(it, false); sessionManager.applyZoom(it, viewModel.zoomRatio.value, activeCameraCharacteristics) }; resumePreview() }
    private fun saveParamState(key: ParamKey) {
        val state = viewModel.photoParamState.value
        when (key) {
            ParamKey.EV -> { preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_EV_AUTO, state.ev.isAuto); preferenceManager.saveString(PreferenceManager.KEY_PARAM_EV_VAL, state.ev.displayValue) }
            ParamKey.WB -> { preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_WB_AUTO, state.wb.isAuto); preferenceManager.saveString(PreferenceManager.KEY_PARAM_WB_VAL, state.wb.displayValue) }
            ParamKey.ISO -> { preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_ISO_AUTO, state.iso.isAuto); preferenceManager.saveString(PreferenceManager.KEY_PARAM_ISO_VAL, state.iso.displayValue) }
            ParamKey.SHUTTER -> { preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_S_AUTO, state.shutter.isAuto); preferenceManager.saveString(PreferenceManager.KEY_PARAM_S_VAL, state.shutter.displayValue) }
            ParamKey.AF -> { preferenceManager.saveBoolean(PreferenceManager.KEY_PARAM_AF_AUTO, state.af.isAuto); preferenceManager.saveString(PreferenceManager.KEY_PARAM_AF_VAL, state.af.displayValue) }
        }
    }
    private fun saveCurrentLensSettings() { preferenceManager.saveString(PreferenceManager.KEY_LENS_ID, currentLogicalCameraId); currentPhysicalCameraId?.let { preferenceManager.saveString(PreferenceManager.KEY_PHYSICAL_LENS_ID, it) } }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { streamMonitor.notifyFrameReceived(); aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: CameraMetadata.CONTROL_AE_STATE_INACTIVE; updateAutoValuesFromResult(result); runOnUiThread { refreshParamViews(viewModel.photoParamState.value) } }
    }

    private fun updateAutoValuesFromResult(result: CaptureResult) {
        result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)?.let { previewAutoCompensation = it }
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { previewAutoIso = it }
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { previewAutoShutterNanos = it }
        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { previewAutoFocusDistance = it }
        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains -> previewAutoKelvinLabel = PhotoParamMapper.gainsToKelvinLabel(gains, PhotoParamMapper.SUPPORTED_KELVIN_VALUES) }
    }

    override fun onResume() { super.onResume() }
    override fun onPause() { super.onPause() }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
    }

    private fun createCameraPreviewSession() {
        val cam = cameraDevice ?: return; val raw = cameraManager.rawImageReader ?: return; val jpg = cameraManager.jpegImageReader ?: return; val handler = backgroundHandler ?: return
        try {
            captureSession?.close(); val tex = binding.cameraPreview.surfaceTexture ?: return
            val map = activeCameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = if (viewModel.isPhotoMode.value) map?.getOutputSizes(SurfaceTexture::class.java)?.filter { Math.abs(it.width.toFloat()/it.height - 1.333f) < 0.1 }?.sortedByDescending { it.width * it.height }?.find { it.width <= 2500 } ?: Size(1440, 1080) else { val dims = selectedVideoSizeStr.split("x"); if (dims.size == 2) Size(dims[0].toInt(), dims[1].toInt()) else Size(1920, 1080) }
            tex.setDefaultBufferSize(previewSize.width, previewSize.height); runOnUiThread { configureTransform(previewSize.width, previewSize.height) }
            previewSurface = Surface(tex); val surf = previewSurface ?: return
            previewRequestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf); PhotoParamMapper.applyPhotoParamsToRequest(this, viewModel.photoParamState.value, cameraCapabilities, previewAutoIso, previewAutoShutterNanos, true); viewModel.currentFlashMode.value.applyToRequest(this, false); sessionManager.applyZoom(this, viewModel.zoomRatio.value, activeCameraCharacteristics) }
            sessionManager.createSession(cam, listOf(surf, raw.surface, jpg.surface), currentPhysicalCameraId, previewRequestBuilder!!.build(), handler, raw.surface)
        } catch (e: Exception) { Log.e("wCam", "Preview error: ${e.message}") }
    }

    private fun showVideoParamsPopup(view: View) {
        val currentId = activeMetadataCameraId(); val caps = allVideoCapabilities[currentId] ?: emptyList(); if (caps.isEmpty()) { showToast(getString(R.string.toast_video_caps_failed)); return }
        val common = listOf("3:4", "9:16"); val ratioMap = caps.groupBy { it.ratioStr }.mapValues { it.value.first().ratio }
        val sortedRatios = ratioMap.keys.toList().sortedWith { a, b -> val aIn = common.indexOf(a); val bIn = common.indexOf(b); if (aIn != -1 || bIn != -1) { if (aIn != -1 && bIn != -1) aIn.compareTo(bIn) else if (aIn != -1) -1 else 1 } else ratioMap[a]!!.compareTo(ratioMap[b]!!) }
        uiController.showStyledPopup(view, sortedRatios) { ratioStr ->
            selectedVideoRatioStr = ratioStr; preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RATIO, ratioStr)
            val ratioCaps = caps.filter { it.ratioStr == ratioStr }; val bestFor30 = ratioCaps.filter { it.maxFps >= 30 }.sortedWith(compareByDescending<VideoSizeInfo> { it.width * it.height }.thenBy { Math.abs(it.maxFps - 30) }).firstOrNull() ?: ratioCaps.maxByOrNull { it.width * it.height }
            if (bestFor30 != null) { selectedVideoSizeStr = "${bestFor30.width}x${bestFor30.height}"; selectedVideoFps = if (bestFor30.maxFps >= 30) 30 else bestFor30.maxFps; preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr); preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, selectedVideoFps) }
            binding.topToolbar.btnResolution.text = selectedVideoRatioStr; binding.topToolbar.btnResolution.setTextColor(Color.parseColor("#FFCC00")); updateFormatPanelUI(); closeCamera(); openCamera()
        }
    }
}
