package com.example.wcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // 基础业务控件
    private lateinit var btnFlash: TextView
    private lateinit var btnHistogram: TextView
    private lateinit var btnGridLines: TextView
    private lateinit var btnResolution: TextView
    private lateinit var btnFormat: TextView
    private lateinit var btnModeSwitch: TextView

    private lateinit var viewGridLines: GridLineView
    private lateinit var viewHistogram: View

    // 镜头选择控件
    private lateinit var btnLensFront: TextView
    private lateinit var btnLensWide: TextView
    private lateinit var btnLensMain: TextView
    private lateinit var btnLensTele: TextView

    // 格式设置面板控件组
    private lateinit var panelFormatSettings: LinearLayout
    private lateinit var spinnerMainFormat: Spinner
    private lateinit var layoutJpgAdvancedOptions: LinearLayout
    private lateinit var spinnerDemosaicAlgo: Spinner
    private lateinit var tvJpgQualityTitle: TextView
    private lateinit var seekbarJpgQuality: SeekBar

    // ★ 核心相机状态记录
    private var currentLogicalCameraId = "0"     // 逻辑ID：0为后置组，1为前置
    private var currentPhysicalCameraId: String? = "2" // 物理ID：2主摄，3长焦，4广角 (前置为空)

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
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        initViews()
        setupListeners()
    }

    private fun initViews() {
        textureView = findViewById(R.id.camera_preview)
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
    }

    private fun setupListeners() {
        setupLensSelector()

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
                Toast.makeText(this, "已切换至拍照模式", Toast.LENGTH_SHORT).show()
            } else {
                btnModeSwitch.text = "照片"
                Toast.makeText(this, "已切换至录像模式", Toast.LENGTH_SHORT).show()
            }
            if (isFormatPanelOpen) updateFormatPanelUI()
        }

        seekbarJpgQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvJpgQualityTitle.text = "JPG 质量: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ★ 物理镜头切换逻辑
    private fun setupLensSelector() {
        val buttons = listOf(btnLensFront, btnLensWide, btnLensMain, btnLensTele)

        val switchLens = { selectedBtn: TextView, logicalId: String, physicalId: String? ->
            // UI 更新
            buttons.forEach {
                it.setTextColor(Color.parseColor("#B3FFFFFF"))
                it.textSize = 14f
                it.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            selectedBtn.setTextColor(Color.parseColor("#FFCC00"))
            selectedBtn.textSize = 18f
            selectedBtn.setTypeface(null, android.graphics.Typeface.BOLD)

            // 相机逻辑流转
            if (currentLogicalCameraId != logicalId) {
                // 如果是跨逻辑相机切换（比如前后置切换），必须整体重启 CameraDevice
                currentLogicalCameraId = logicalId
                currentPhysicalCameraId = physicalId
                closeCamera()
                if (textureView.isAvailable) openCamera()
            } else if (currentPhysicalCameraId != physicalId) {
                // 如果只是同逻辑下的物理镜头切换（如广角切主摄），只需重建 Session 即可实现平滑切换
                currentPhysicalCameraId = physicalId
                createCameraPreviewSession()
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
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            val videoFormats = listOf("H264", "H265", "ProRes", "MPEG4")
            spinnerMainFormat.adapter = createWhiteTextAdapter(videoFormats)
            layoutJpgAdvancedOptions.visibility = View.GONE
            spinnerMainFormat.onItemSelectedListener = null
        }
    }

    private fun setupDemosaicSpinner() {
        val algos = listOf("AMaZE", "RCD", "DCB", "LMMSE", "IGV", "AHD", "EAHD", "HPHD", "VNG4", "Fast", "黑白", "像素偏移", "无")
        spinnerDemosaicAlgo.adapter = createWhiteTextAdapter(algos)
        spinnerDemosaicAlgo.setSelection(0)
    }

    private fun createWhiteTextAdapter(data: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, data) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(Color.WHITE)
                (v as? TextView)?.textSize = 14f
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.setTextColor(Color.BLACK)
                return v
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

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // ★ 修改：使用当前指定的逻辑 Camera ID 打开相机
            manager.openCamera(currentLogicalCameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
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
            finish()
        }
    }

    // ★ 修改：全面应用 SessionConfiguration 指定特定的物理镜头
    private fun createCameraPreviewSession() {
        if (cameraDevice == null) return

        try {
            // 清理旧的 Session 防止阻塞
            captureSession?.close()

            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            // API 28+ 物理摄像头路由支持
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(surface)

                // 如果指定了物理摄像头ID（例如 2, 3, 4），则绑定之
                if (currentPhysicalCameraId != null) {
                    outputConfig.setPhysicalCameraId(currentPhysicalCameraId)
                }

                // 转换 backgroundHandler 为 Executor 提供给 Session
                val executor = Executor { command -> backgroundHandler?.post(command) }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = session
                            try {
                                previewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                captureSession!!.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(this@MainActivity, "配置物理镜头预览失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                cameraDevice!!.createCaptureSession(sessionConfig)
            } else {
                // 降级方案 (不支持物理镜头级别的手动分流)
                cameraDevice!!.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = session
                            try {
                                previewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                captureSession!!.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    },
                    backgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
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
            e.printStackTrace()
        }
    }
}