package com.example.wcam

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.wcam.camera.*
import com.example.wcam.databinding.ActivityMainBinding
import com.example.wcam.ui.*
import com.example.wcam.util.PermissionHelper
import com.example.wcam.viewmodel.CameraViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(),
    ManualParamController.ParamChangeListener,
    CameraUiController.UiEventListener,
    CameraStreamMonitor.MonitorListener,
    GestureManager.GestureListener,
    LensController.LensListener,
    AspectRatioController.AspectRatioListener,
    LocationListener,
    CameraCoordinator.CoordinatorListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CameraViewModel by viewModels()

    private lateinit var coordinator: CameraCoordinator
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var config: com.example.wcam.util.ConfigManager
    
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

    private lateinit var rawMediaStore: RawMediaStore
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var deviceOrientationTracker: DeviceOrientationTracker

    private var currentLogicalCameraId = ""
    private var currentPhysicalCameraId: String? = null
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
    private var isAeAfLocked = false
    private var cropRatio = 4f / 3f
    private var currentCropRectInSensor: Rect? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            initApp()
            if (binding.cameraPreview.isAvailable) openCamera()
            else binding.cameraPreview.surfaceTextureListener = surfaceTextureListener
        } else {
            showToast(getString(R.string.toast_camera_permission_required))
            finish()
        }
    }

    companion object {
        init { System.loadLibrary("wcam") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()

        permissionHelper = PermissionHelper(this)
        
        if (permissionHelper.hasAllPermissions()) {
            initApp()
        } else {
            permissionHelper.requestPermissions(permissionLauncher)
        }
    }

    private fun initApp() {
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
        if (permissionHelper.hasAllPermissions()) {
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
        loadSettings()
        coordinator.start()
        startLocationUpdates()
        streamMonitor = CameraStreamMonitor(coordinator.getBackgroundHandler() ?: android.os.Handler(android.os.Looper.getMainLooper()), config, this)
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
        coordinator.stop()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isPhotoMode.collect { updateModeUI(it) } }
                launch { viewModel.isGridVisible.collect { overlayController.setGridVisible(it); uiController.updateGridUi(binding.topToolbar.btnGridLines, it) } }
                launch { viewModel.isHistogramVisible.collect { overlayController.setHistogramVisible(it); uiController.updateHistogramUi(binding.topToolbar.btnHistogram, it); updateCropOverlayUI() } }
                launch { viewModel.photoParamState.collect { refreshParamViews(it) } }
                launch { viewModel.currentFlashMode.collect { uiController.updateFlashUi(binding.topToolbar.btnFlash, it); applyPhotoParamsToPreview() } }
            }
        }
    }

    private fun initViews() {
        preferenceManager = PreferenceManager(this)
        config = com.example.wcam.util.ConfigManager(this)
        PhotoParamMapper.init(config)
        rawMediaStore = RawMediaStore(this, config, preferenceManager)
        deviceOrientationTracker = DeviceOrientationTracker(this)
        deviceOrientationTracker.setRotationListener(object : DeviceOrientationTracker.RotationListener {
            override fun onRotationStepChanged(degrees: Int) {
                runOnUiThread { rotateUiElements(degrees) }
            }
        })

        initControllers()
    }

    private fun initControllers() {
        val captureProcessor = CaptureProcessor(this, rawMediaStore, config, preferenceManager)
        coordinator = CameraCoordinator(this, viewModel, preferenceManager, captureProcessor, config, this)
        
        uiController = CameraUiController(this, this)
        manualParamController = ManualParamController(this)
        overlayController = OverlayController(binding.viewGridLines, binding.viewHistogram, binding.viewCropOverlay, binding.tvPhysicalRes)
        
        val histW = config.getInt("histogram_sample_width", 160)
        val histH = config.getInt("histogram_sample_height", 80)
        overlayController.setHistogramSize(histW, histH)

        streamMonitor = CameraStreamMonitor(android.os.Handler(mainLooper), config, this)
        gestureManager = GestureManager(this, this)
        
        lensController = LensController(binding.lensSelector.lensSelectorContainer, this)
        lensController.setupLenses(config.getCameras(), config.getStringList("lens_order"))

        aspectController = AspectRatioController(this, preferenceManager, overlayController, this)
        galleryController = GalleryController(this, binding.bottomActionBar.btnGallery, rawMediaStore, java.util.concurrent.Executors.newSingleThreadExecutor()) { runnable -> runOnUiThread(runnable) }
    }

    private fun setupListeners() {
        binding.bottomActionBar.btnShutter.setOnClickListener { coordinator.capturePhoto() }
        binding.topToolbar.btnFlash.setOnClickListener { cycleFlashMode() }
        binding.topToolbar.btnHistogram.setOnClickListener { viewModel.setHistogramVisible(!viewModel.isHistogramVisible.value) }
        binding.topToolbar.btnGridLines.setOnClickListener { viewModel.setGridVisible(!viewModel.isGridVisible.value) }
        binding.topToolbar.btnResolution.setOnClickListener { view -> onResolutionClick(view) }
        binding.topToolbar.btnSettings.setOnClickListener { 
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.bottomActionBar.btnModeSwitch.setOnClickListener { onModeSwitch() }
        binding.bottomActionBar.btnModeSwitch.setOnLongClickListener { onModeReset(); true }
        binding.bottomActionBar.btnGallery.setOnClickListener { galleryController.openRecentPhotoInGallery() }
        binding.cameraPreview.setOnTouchListener { _, event ->
            gestureManager.handleTouch(event)
        }
    }

    private fun loadSettings() {
        viewModel.setPhotoMode(preferenceManager.getBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, true))
        
        val defaultLogicalId = config.getString("default_logical_camera_id", "0")
        val defaultPhysicalId = config.getString("default_physical_camera_id", "2")
        
        currentLogicalCameraId = preferenceManager.getString(PreferenceManager.KEY_LENS_ID, defaultLogicalId)
        currentPhysicalCameraId = preferenceManager.getString(PreferenceManager.KEY_PHYSICAL_LENS_ID, defaultPhysicalId)
        viewModel.setGridVisible(preferenceManager.getBoolean(PreferenceManager.KEY_GRID_VISIBLE, false))
        viewModel.setHistogramVisible(preferenceManager.getBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, false))
        selectedPhotoRatioStr = preferenceManager.getString(PreferenceManager.KEY_PHOTO_RATIO_STR, "4:3")
        cropRatio = preferenceManager.getFloat(PreferenceManager.KEY_PHOTO_RATIO, 4f / 3f)
        selectedVideoRatioStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RATIO, "16:9")
        selectedVideoSizeStr = preferenceManager.getString(PreferenceManager.KEY_VIDEO_RESOLUTION, "1920x1080")
        selectedVideoFps = preferenceManager.getInt(PreferenceManager.KEY_VIDEO_FPS, 30)
        selectedVideoBitrate = preferenceManager.getFloat(PreferenceManager.KEY_VIDEO_BITRATE, 20.0f)
        
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
    }

    private fun openCamera() {
        if (!permissionHelper.hasAllPermissions()) {
            permissionHelper.requestPermissions(permissionLauncher)
            return
        }
        coordinator.openCamera(currentLogicalCameraId, currentPhysicalCameraId)
    }

    private fun applyPhotoParamsToPreview() {
        coordinator.getPreviewRequestBuilder()?.let {
            PhotoParamMapper.applyPhotoParamsToRequest(it, viewModel.photoParamState.value, cameraCapabilities, previewAutoIso, previewAutoShutterNanos, true)
            viewModel.currentFlashMode.value.applyToRequest(it, false)
        }
        resumePreview()
    }

    private fun resumePreview() {
        coordinator.resumePreview(previewCaptureCallback)
    }

    private fun updateCropOverlayUI() { currentCropRectInSensor = aspectController.updateCropUI(viewModel.isPhotoMode.value, coordinator.getActiveCharacteristics(), selectedVideoSizeStr, selectedVideoFps) }

    private fun refreshCameraCapabilities(characteristics: CameraCharacteristics) {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        cameraCapabilities = CameraCapabilities(characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE), characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat(), characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE), characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE), characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE), caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR), characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty().contains(CameraMetadata.CONTROL_AWB_MODE_OFF), characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList())
        currentFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        
        currentSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        currentLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        
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
    
    private fun cycleFlashMode() {
        val current = viewModel.currentFlashMode.value
        val next = when (current) {
            FlashMode.OFF -> FlashMode.SINGLE
            FlashMode.SINGLE -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        viewModel.setFlashMode(next)
    }

    override fun onHistogramToggle() { viewModel.setHistogramVisible(!viewModel.isHistogramVisible.value); preferenceManager.saveBoolean(PreferenceManager.KEY_HISTOGRAM_VISIBLE, viewModel.isHistogramVisible.value) }
    override fun onGridToggle() { viewModel.setGridVisible(!viewModel.isGridVisible.value); preferenceManager.saveBoolean(PreferenceManager.KEY_GRID_VISIBLE, viewModel.isGridVisible.value) }
    override fun onResolutionClick(view: View) { if (viewModel.isPhotoMode.value) aspectController.showPhotoRatioPopup(view) else showVideoParamsPopup(view) }
    override fun onModeSwitch() {
        viewModel.setPhotoMode(!viewModel.isPhotoMode.value)
        preferenceManager.saveBoolean(PreferenceManager.KEY_IS_PHOTO_MODE, viewModel.isPhotoMode.value)
        coordinator.closeCamera(); openCamera()
    }
    override fun onModeReset() { preferenceManager.clear(); showToast(getString(R.string.toast_settings_reset)); loadSettings(); coordinator.closeCamera(); openCamera() }
    
    // CoordinatorListener implementation
    override fun onCameraOpened(characteristics: CameraCharacteristics) {
        refreshCameraCapabilities(characteristics)
    }

    override fun onSessionConfigured() {
        resumePreview()
    }

    override fun onPreviewReady(size: Size) {
        runOnUiThread { configureTransform(size.width, size.height) }
    }

    override fun onCaptureStarted(shutterNanos: Long) { runOnUiThread { binding.bottomActionBar.btnShutter.isEnabled = false; uiController.startShutterCountdown(shutterNanos, binding.bottomActionBar.pbShutterProgress, binding.bottomActionBar.tvShutterCountdown) } }
    override fun onCaptureCompleted(uri: Uri?) { runOnUiThread { uri?.let { galleryController.updateRecentPhotoPreview(it) } } }
    override fun onCaptureFailed(message: String) { runOnUiThread { binding.bottomActionBar.btnShutter.isEnabled = true; uiController.stopShutterCountdown(binding.bottomActionBar.pbShutterProgress, binding.bottomActionBar.tvShutterCountdown); showToast(message) } }
    override fun onReadyToResumePreview() { runOnUiThread { binding.bottomActionBar.btnShutter.isEnabled = true; uiController.stopShutterCountdown(binding.bottomActionBar.pbShutterProgress, binding.bottomActionBar.tvShutterCountdown); resumePreview() } }
    override fun onError(message: String) { showToast(message) }
    override fun getPreviewTexture(): TextureView = binding.cameraPreview
    override fun getCountdownTextView(): TextView? = binding.tvCountdownOverlay

    // State getters for Coordinator
    override fun getDeviceRotationDegrees(): Int = deviceOrientationTracker.deviceRotationDegrees
    override fun getSensorOrientation(): Int = currentSensorOrientation
    override fun getLensFacing(): Int? = currentLensFacing
    override fun getCurrentCropRect(): Rect? = currentCropRectInSensor
    override fun getJpgQuality(): Int = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
    override fun getCameraCapabilities(): CameraCapabilities = cameraCapabilities
    override fun getLocation(): Location? = lastLocation

    override fun onStreamFrozen() { runOnUiThread { coordinator.closeCamera(); android.os.Handler(mainLooper).postDelayed({ openCamera() }, 500) } }
    override fun onHistogramUpdate(r: IntArray, g: IntArray, b: IntArray) { overlayController.updateHistogram(r, g, b) }
    override fun isHistogramVisible(): Boolean = viewModel.isHistogramVisible.value
    override fun isCaptureInProgress(): Boolean = coordinator.isCaptureInProgress()

    override fun onSingleTap(x: Float, y: Float) { 
        binding.viewFocusRing.x = x - binding.viewFocusRing.width / 2; binding.viewFocusRing.y = y - binding.viewFocusRing.height / 2 + binding.cameraPreview.top; binding.viewFocusRing.visibility = View.VISIBLE; binding.viewFocusRing.alpha = 1f; binding.viewFocusRing.scaleX = 1.2f; binding.viewFocusRing.scaleY = 1.2f; binding.viewFocusRing.animate().scaleX(1f).scaleY(1f).alpha(0.5f).setDuration(300).withEndAction { binding.viewFocusRing.postDelayed({ binding.viewFocusRing.visibility = View.GONE }, 1000) }.start()
        coordinator.triggerFocus(x, y, binding.cameraPreview, previewCaptureCallback)
        resumePreview()
    }
    
    override fun onLongPress() { 
        isAeAfLocked = !isAeAfLocked
        coordinator.toggleAeAfLock(isAeAfLocked)
        showToast(if (isAeAfLocked) getString(R.string.ae_af_locked) else getString(R.string.ae_af_unlocked))
        applyPhotoParamsToPreview() 
    }
    override fun onZoomChanged(scale: Float) { 
        aspectController.setCropScale(scale)
        updateCropOverlayUI() 
    }
    override fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit) { uiController.showStyledPopup(anchor, options, onSelected) }

    private fun updateVideoParamsForNewLens() {
        val newId = activeMetadataCameraId()
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
            coordinator.closeCamera()
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

    override fun onVideoParamsChanged() { /* Handled via SettingsActivity */ }

    private fun activeMetadataCameraId(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) currentPhysicalCameraId ?: currentLogicalCameraId else currentLogicalCameraId
    private fun showToast(msg: String) { runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }

    private fun rotateUiElements(degrees: Int) {
        val targetRotation = when (degrees) {
            90 -> 270f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }
        
        val viewsToRotate = listOf(
            binding.topToolbar.btnFlash,
            binding.topToolbar.btnHistogram,
            binding.topToolbar.btnGridLines,
            binding.topToolbar.btnResolution,
            binding.topToolbar.btnSettings,
            binding.bottomActionBar.btnModeSwitch,
            binding.bottomActionBar.btnGallery,
            binding.paramBar.layoutParamEv,
            binding.paramBar.layoutParamWb,
            binding.paramBar.layoutParamIso,
            binding.paramBar.layoutParamS,
            binding.paramBar.layoutParamAf
        )

        viewsToRotate.forEach { view ->
            var currentRotation = view.rotation
            currentRotation = (currentRotation % 360 + 360) % 360
            
            var delta = targetRotation - currentRotation
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            
            view.animate().rotationBy(delta).setDuration(300).start()
        }
        
        val lensContainer = binding.lensSelector.lensSelectorContainer
        for (i in 0 until lensContainer.childCount) {
            val view = lensContainer.getChildAt(i)
            var currentRotation = view.rotation
            currentRotation = (currentRotation % 360 + 360) % 360
            var delta = targetRotation - currentRotation
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            view.animate().rotationBy(delta).setDuration(300).start()
        }
    }
    
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
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { streamMonitor.notifyFrameReceived(); updateAutoValuesFromResult(result); runOnUiThread { refreshParamViews(viewModel.photoParamState.value) } }
    }

    private fun updateAutoValuesFromResult(result: CaptureResult) {
        result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)?.let { previewAutoCompensation = it }
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { previewAutoIso = it }
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { previewAutoShutterNanos = it }
        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { previewAutoFocusDistance = it }
        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains -> previewAutoKelvinLabel = PhotoParamMapper.gainsToKelvinLabel(gains, PhotoParamMapper.SUPPORTED_KELVIN_VALUES) }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
    }

    private fun showVideoParamsPopup(view: View) {
        val currentId = activeMetadataCameraId(); val caps = allVideoCapabilities[currentId] ?: emptyList(); if (caps.isEmpty()) { showToast(getString(R.string.toast_video_caps_failed)); return }
        val common = listOf("3:4", "9:16"); val ratioMap = caps.groupBy { it.ratioStr }.mapValues { it.value.first().ratio }
        val sortedRatios = ratioMap.keys.toList().sortedWith { a, b -> val aIn = common.indexOf(a); val bIn = common.indexOf(b); if (aIn != -1 || bIn != -1) { if (aIn != -1 && bIn != -1) aIn.compareTo(bIn) else if (aIn != -1) -1 else 1 } else ratioMap[a]!!.compareTo(ratioMap[b]!!) }
        uiController.showStyledPopup(view, sortedRatios) { ratioStr ->
            selectedVideoRatioStr = ratioStr; preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RATIO, ratioStr)
            val ratioCaps = caps.filter { it.ratioStr == ratioStr }; val bestFor30 = ratioCaps.filter { it.maxFps >= 30 }.sortedWith(compareByDescending<VideoSizeInfo> { it.width * it.height }.thenBy { Math.abs(it.maxFps - 30) }).firstOrNull() ?: ratioCaps.maxByOrNull { it.width * it.height }
            if (bestFor30 != null) { selectedVideoSizeStr = "${bestFor30.width}x${bestFor30.height}"; selectedVideoFps = if (bestFor30.maxFps >= 30) 30 else bestFor30.maxFps; preferenceManager.saveString(PreferenceManager.KEY_VIDEO_RESOLUTION, selectedVideoSizeStr); preferenceManager.saveInt(PreferenceManager.KEY_VIDEO_FPS, selectedVideoFps) }
            binding.topToolbar.btnResolution.text = selectedVideoRatioStr; binding.topToolbar.btnResolution.setTextColor(Color.parseColor("#FFCC00")); openCamera()
        }
    }
}
