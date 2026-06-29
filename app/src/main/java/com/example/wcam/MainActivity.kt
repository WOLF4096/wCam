package com.example.wcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

import com.example.wcam.camera.CameraCapabilities
import com.example.wcam.camera.PARAM_TEXT_AUTO_COLOR
import com.example.wcam.camera.PARAM_TEXT_MANUAL_COLOR
import com.example.wcam.camera.ParamKey
import com.example.wcam.camera.ParamValue
import com.example.wcam.camera.PhotoParamMapper
import com.example.wcam.camera.PhotoParamState

private const val RAW_MIME_TYPE = "image/x-adobe-dng"
private const val RAW_FOLDER_NAME = "wCam"

internal fun createRawDisplayName(timestampMillis: Long = System.currentTimeMillis()): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    return "wcam_${formatter.format(Date(timestampMillis))}.dng"
}

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

    private lateinit var viewGridLines: GridLineView
    private lateinit var viewHistogram: View

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
    private lateinit var tvValEv: TextView
    private lateinit var tvValWb: TextView
    private lateinit var tvValIso: TextView
    private lateinit var tvValS: TextView
    private lateinit var tvValAf: TextView

    private var currentLogicalCameraId = "0"
    private var currentPhysicalCameraId: String? = "2"
    private var activeCameraCharacteristics: CameraCharacteristics? = null
    private var rawImageReader: ImageReader? = null
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var isCaptureInProgress = false
    private var photoParamState: PhotoParamState = PhotoParamState.default()
    private var previewAutoIso: Int? = null
    private var previewAutoShutterNanos: Long? = null
    private var previewAutoCompensation: Int? = null
    private var previewAutoKelvinLabel: String? = null
    private var previewAutoFocusDistance: Float? = null
    private var cameraCapabilities: CameraCapabilities = CameraCapabilities.EMPTY

    private var isGridVisible = false
    private var isHistogramVisible = false
    private var isPhotoMode = true
    private var isFormatPanelOpen = false

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

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

        viewGridLines = findViewById(R.id.view_grid_lines)
        viewHistogram = findViewById(R.id.view_histogram)

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
        tvValEv = findViewById(R.id.tv_val_ev)
        tvValWb = findViewById(R.id.tv_val_wb)
        tvValIso = findViewById(R.id.tv_val_iso)
        tvValS = findViewById(R.id.tv_val_s)
        tvValAf = findViewById(R.id.tv_val_af)
    }

    private fun setupListeners() {
        setupLensSelector()

        btnShutter.setOnClickListener {
            captureRawPhoto()
        }

        btnFlash.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("关闭")
            popup.menu.add("打开")
            popup.menu.add("常亮")
            popup.setOnMenuItemClickListener { item ->
                btnFlash.text = item.title
                btnFlash.setTextColor(Color.parseColor("#FFCC00"))
                true
            }
            popup.show()
        }

        btnHistogram.setOnClickListener {
            isHistogramVisible = !isHistogramVisible
            viewHistogram.visibility = if (isHistogramVisible) View.VISIBLE else View.GONE
            btnHistogram.setTextColor(if (isHistogramVisible) Color.parseColor("#FFCC00") else Color.WHITE)
        }

        btnGridLines.setOnClickListener {
            isGridVisible = !isGridVisible
            viewGridLines.visibility = if (isGridVisible) View.VISIBLE else View.GONE
            btnGridLines.setTextColor(if (isGridVisible) Color.parseColor("#FFCC00") else Color.WHITE)
        }

        btnResolution.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("4096 x 3072 px @ 12.6MP")
            popup.menu.add("3840 x 2160 px @ 8.3MP")
            popup.menu.add("1920 x 1080 px @ 2.1MP")
            popup.setOnMenuItemClickListener { item ->
                btnResolution.text = item.title.toString().split(" ")[0]
                btnResolution.setTextColor(Color.parseColor("#FFCC00"))
                true
            }
            popup.show()
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
            if (isPhotoMode) {
                btnModeSwitch.text = "录像"
                showToast("已切换至拍照模式")
            } else {
                btnModeSwitch.text = "照片"
                showToast("已切换至录像模式")
            }
            if (isFormatPanelOpen) {
                updateFormatPanelUI()
            }
        }

        seekbarJpgQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvJpgQualityTitle.text = "JPG 质量: $progress%"
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
        resumePreview()
    }

    private fun onParamAutoReset(key: ParamKey) {
        photoParamState = PhotoParamMapper.restoreAutoState(key, photoParamState)
        refreshParamViews()
        resumePreview()
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
                closeCamera()
                if (textureView.isAvailable) {
                    openCamera()
                }
            } else if (currentPhysicalCameraId != physicalId) {
                currentPhysicalCameraId = physicalId
                refreshActiveCameraCharacteristics()
                refreshCameraCapabilities()
                configureRawImageReader()
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
        if (isPhotoMode) {
            val photoFormats = listOf("JPG", "JPG + RAW", "RAW")
            spinnerMainFormat.adapter = createWhiteTextAdapter(photoFormats)
            spinnerMainFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = photoFormats[position]
                    if (selected == "JPG" || selected == "JPG + RAW") {
                        layoutJpgAdvancedOptions.visibility = View.VISIBLE
                        setupDemosaicSpinner()
                    } else {
                        layoutJpgAdvancedOptions.visibility = View.GONE
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        } else {
            val videoFormats = listOf("H264", "H265", "ProRes", "MPEG4")
            spinnerMainFormat.adapter = createWhiteTextAdapter(videoFormats)
            layoutJpgAdvancedOptions.visibility = View.GONE
            spinnerMainFormat.onItemSelectedListener = null
        }
    }

    private fun setupDemosaicSpinner() {
        val algos = listOf("AMaZE", "RCD", "DCB", "LMMSE", "IGV", "AHD", "EAHD", "HPHD", "VNG4", "Fast")
        spinnerDemosaicAlgo.adapter = createWhiteTextAdapter(algos)
        spinnerDemosaicAlgo.setSelection(0)
    }

    private fun createWhiteTextAdapter(data: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, data) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.WHITE)
                (view as? TextView)?.textSize = 14f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                return view
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
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
            if (!configureRawImageReader()) {
                showToast("当前镜头不支持 RAW")
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

    private fun createCameraPreviewSession() {
        val camera = cameraDevice ?: return
        val imageReader = rawImageReader ?: return

        try {
            captureSession?.close()

            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(textureView.width, textureView.height)
            previewSurface?.release()
            previewSurface = Surface(texture)
            val surface = previewSurface ?: return

            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyPhotoParamsToRequest(this)
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val previewOutput = OutputConfiguration(surface).apply {
                    currentPhysicalCameraId?.let { setPhysicalCameraId(it) }
                }
                val rawOutput = OutputConfiguration(imageReader.surface).apply {
                    currentPhysicalCameraId?.let { setPhysicalCameraId(it) }
                }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(previewOutput, rawOutput),
                    Executor { command -> backgroundHandler?.post(command) },
                    previewStateCallback
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                camera.createCaptureSession(
                    listOf(surface, imageReader.surface),
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
            showToast("RAW 拍照尚未就绪")
            return
        }

        isCaptureInProgress = true
        btnShutter.isEnabled = false
        pendingCaptureResult = null
        pendingRawImage?.close()
        pendingRawImage = null

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                surface?.let { addTarget(it) }
                applyPhotoParamsToRequest(this)
            }

            session.stopRepeating()
            session.capture(captureBuilder.build(), stillCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            finishCaptureWithError("RAW 拍照失败")
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
            pendingCaptureResult = null
            finishCaptureWithError("缺少 RAW 元数据")
            return
        }

        pendingRawImage = null
        pendingCaptureResult = null

        try {
            saveRawImageToGallery(image, characteristics, captureResult)
            image.close()
            finishCaptureSuccessfully()
        } catch (e: IOException) {
            image.close()
            finishCaptureWithError("RAW 保存失败")
        }
    }

    private fun saveRawImageToGallery(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult
    ) {
        val resolver = contentResolver
        val fileName = createRawDisplayName()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var outputPathForScan: String? = null

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, RAW_MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$RAW_FOLDER_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    RAW_FOLDER_NAME
                )
                if (!folder.exists() && !folder.mkdirs()) {
                    throw IOException("Unable to create RAW output directory")
                }
                val outputFile = File(folder, fileName)
                outputPathForScan = outputFile.absolutePath
                put(MediaStore.Images.Media.DATA, outputFile.absolutePath)
            }
        }

        val uri = resolver.insert(collection, contentValues)
            ?: throw IOException("Unable to create MediaStore record")

        val dngCreator = DngCreator(characteristics, captureResult)
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                dngCreator.writeImage(outputStream, image)
            } ?: throw IOException("Unable to open RAW output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publishValues, null, null)
            } else if (outputPathForScan != null) {
                MediaScannerConnection.scanFile(this, arrayOf(outputPathForScan), arrayOf(RAW_MIME_TYPE), null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw IOException("Unable to save RAW image", e)
        } finally {
            dngCreator.close()
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

    private fun resumePreview() {
        val session = captureSession ?: return
        val previewRequest = previewRequestBuilder?.build() ?: return
        try {
            session.setRepeatingRequest(previewRequest, previewCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            showToast("恢复预览失败")
        }
    }

    private fun configureRawImageReader(): Boolean {
        val characteristics = activeCameraCharacteristics ?: return false
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        val largestRawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return false

        val currentReader = rawImageReader
        if (currentReader != null && currentReader.width == largestRawSize.width && currentReader.height == largestRawSize.height) {
            return true
        }

        releaseRawResources()
        rawImageReader = ImageReader.newInstance(
            largestRawSize.width,
            largestRawSize.height,
            ImageFormat.RAW_SENSOR,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                pendingRawImage?.close()
                pendingRawImage = image
                maybePersistPendingRaw()
            }, backgroundHandler)
        }
        return true
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
        isCaptureInProgress = false
        releaseRawResources()
    }

    private fun releaseRawResources() {
        pendingRawImage?.close()
        pendingRawImage = null
        pendingCaptureResult = null
        rawImageReader?.close()
        rawImageReader = null
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
            cameraManager().getCameraCharacteristics(activeMetadataCameraId())
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

        cameraCapabilities = CameraCapabilities(
            aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat(),
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            supportsManualSensor = supportsManualSensor,
            supportsManualWhiteBalance = supportsManualWhiteBalance,
        )
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
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
