# wCam 拍照详细流程分析

本文档详细记录了 `wCam` 项目中从用户点击快门到图片最终保存在相册的完整拍照流程。核心控制逻辑位于 `MainActivity.kt` 中。

## 1. 触发拍照 (User Input)
- **触发入口**: 用户点击主界面的快门按钮 (`btnShutter.setOnClickListener`)。
- **模式分发**: 代码通过 `isRawOnly()` 判断当前用户设置的输出格式：
  - 如果仅输出 RAW，直接调用 `captureRawPhoto()`。
  - 如果输出包含 JPG (仅 JPG 或 JPG + RAW)，调用 `captureJpgPhoto()`。

## 2. 拍照前置准备 (Pre-capture Setup)
进入具体的捕获函数后，系统会进行一系列的 UI 和状态准备：
- **防抖控制**: 检查 `isCaptureInProgress`，如果当前正在处理拍照，则拦截重复点击，防止程序崩溃。
- **状态锁定与清理**: 设置 `isCaptureInProgress = true`，禁用快门按钮 (`btnShutter.isEnabled = false`)，并清空上一次拍照可能遗留的数据 (`pendingCaptureResult`, `pendingJpegImage`, `pendingRawImage`)。
- **快门倒计时 UI**: 判定当前的快门速度(曝光时间)。如果是手动长曝光（大于 0.5 秒），则调用 `startShutterCountdown` 在 UI 上展示圆形进度条和剩余时间倒计时。
- **闪光灯预闪判定**: 检查当前闪光灯状态：
  - 如果是单次闪光 (`FlashMode.SINGLE`)，会先调用 `runPrecaptureSequence` 发送一条带有 `CONTROL_AE_PRECAPTURE_TRIGGER_START` 的请求，触发硬件自动曝光预闪机制。预闪完成后再执行正式拍照请求。
  - 其他情况（关闭、常亮）则直接进入正式捕获 (`executeStillCapture` / `captureAction`)。

## 3. 构建与下发捕获请求 (Capture Request Creation)
这是与底层 Camera2 API 交互的最核心步骤：
- **创建 Builder**: 基于 `CameraDevice.TEMPLATE_STILL_CAPTURE` 模板创建捕获请求。
- **配置目标 Surface**: 
  - 对于带有后处理的流程，必然绑定 `rawReader.surface` 来获取 RAW 数据。
  - 可选绑定 `previewSurface`，以便在拍照瞬间预览画面能有正常的流转。
- **注入厂商黑科技指令 (Vendor Keys Injection)**: 
  - 这是本项目的一大特色。调用 `injectHighResVendorKeys`，在特定主摄(如物理 ID 为 "2") 拍照时，强行向请求中注入 Vivo/MTK 等厂商的底层私有 Key（如 `vivo.control.ultra_highresolution`, `vendor.vivo.remosaic.enable`），试图以“盲测”方式强行激活 2 亿像素直出或硬件 Remosaic 模式。
- **应用拍照参数**: 
  - 调用 `PhotoParamMapper.applyPhotoParamsToRequest`，将用户手动或自动确定的参数（曝光补偿 EV、白平衡、ISO、快门时间、手动对焦距离）打包写入 `CaptureRequest`。
- **附加状态控制**: 应用当前的闪光灯模式，以及基于用户缩放操作计算出的 `ZoomRatio` 或 `CropRegion` (数码变焦)。
- **计算旋转信息**: 通过 `CaptureOrientationCalculator` 根据设备当前陀螺仪/重力感应器获取的旋转角度、传感器默认方向以及镜头朝向，计算并写入正确的 `JPEG_ORIENTATION`。
- **下发请求**: 
  - 暂停预览: `session.stopRepeating()`（确保静止图像获得全部硬件资源）。
  - 下发请求: `session.capture(...)`，注册 `stillCaptureCallback` 监听硬件返回。

## 4. 接收数据与元数据 (Data & Metadata Reception)
拍照请求下发后，底层硬件完成曝光，会有两条并行的异步回调数据流返回：
- **接收元数据 (Metadata)**:
  - 触发 `stillCaptureCallback.onCaptureCompleted`。
  - 拿到 `TotalCaptureResult` 对象，其中包含了实际曝光使用的真实 ISO、快门、白平衡等数据。
  - 保存在全局变量 `pendingCaptureResult`，随后立即恢复相机预览 (`resumePreview()`)，并调用 `maybePersistPendingRaw()` 尝试流转到下一步。
- **接收图像原始数据 (Image Data)**:
  - 触发 `rawImageReader` 内部的 `OnImageAvailableListener` (执行在后台 Handler 线程)。
  - 拿到代表原始拜耳阵列(Bayer Pattern) 数据的 `Image` 对象。
  - 保存在全局变量 `pendingRawImage` 中，同样调用 `maybePersistPendingRaw()` 尝试流转到下一步。

## 5. 流程同步与解耦 (Synchronization & Decoupling)
`maybePersistPendingRaw()` 方法起到关键的**交汇同步**与**性能解耦**作用：
- **双端就绪检查**: 确保 `pendingCaptureResult` 和 `pendingRawImage` 同时不为空才会继续。
- **元数据提纯**: 从 Result 和 Characteristics 中提取后续算法依赖的关键信息：实际 ISO 算出的镜头阴影校正强度(LSC Weight)、旋转角度、拜耳阵列模式、黑白电平(Black/White Level) 和 白平衡增益(WB Gains)。
- **内存克隆 (极致连拍优化的核心)**:
  - 直接提取出 `Image.Plane[0].buffer`，并将它完整拷贝(Clone)到一个新的 `ByteBuffer` 中。
  - 整合所有的配置信息构造出一个 `CaptureTask` 实例。
  - **立即关闭**原生 `Image` 对象，将相机硬件 ImageBuffer 归还系统。
  - **立即重置 UI**：解除快门按钮的禁用状态，隐藏进度条，`isCaptureInProgress = false`。
  - *结果*：这一步使得用户在点击快门后，能以最快速度拍下一张，无需等待漫长的图像处理完成。
- **派发任务**: 将 `CaptureTask` 放入多线程线程池 `captureExecutor`。

## 6. 后台图像处理流水线 (Background Processing Pipeline)
任务在 `captureExecutor` 中的某个工作线程内执行，这部分计算量极大：
- **保存 RAW (DNG 封装)**:
  - 如果输出格式选了 RAW 或 JPG+RAW，调用 `rawMediaStore.saveRawImageFromBuffer`。该方法会将原始 ByteBuffer 加上元数据，依据 TIFF/DNG 规范生成 `.dng` 后缀的数字底片。
- **解拜耳与图像生成 (Demosaic)**:
  - 如果格式选了输出图像 (JPG类)，则根据下拉框选择的后处理算法 (`algoKey`) 进行分支处理：
    - **RGB通道分离 (`RgbCh`)**: 调用 `saveRgbChannelsFromBuffer`，用纯 Kotlin 逻辑遍历缓冲区，对拜耳阵列像素按红、绿、蓝进行分离，进行二次多项式的 LSC 暗角修复，并在软件层面运用自定义 Gamma LUT 查找表进行亮度映射，最终拼合为一张横向 3 分屏的测试图像。
    - **原生解拜耳 (Fast, Quality, Mono 等)**: 调用 C++ JNI 实现的 `DemosaicEngine.processFromBuffer`。将底层的内存直接暴露给 C++ 层并发处理，经过黑白电平扣除、白平衡相乘、邻域插值(解拜耳)、色彩空间转换后，直接生成并返回 Android 平台标准的 `Bitmap` 对象。
- **图像裁剪 (Crop)**:
  - 检查用户当前的画面比例 (如 4:3, 16:9, 或自定义比例)。
  - 如果设置了比例裁剪，直接根据计算得出的 Sensor 内相对坐标，使用 `Bitmap.createBitmap` 将其裁剪成需要的长宽比尺寸。

## 7. 落盘保存到相册 (Saving to MediaStore)
对于处理出来的最终 Bitmap，根据格式调用 `saveJpegBitmap` 或 `savePngBitmap`：
- **修正旋转**: 根据步骤3计算出的需要旋转的角度 `rotationDegrees`，通过 `Matrix` 对 Bitmap 进行硬件加速旋转，确保竖屏照片打开是竖的。
- **MediaStore 插入占位**: 构建 `ContentValues`，将文件指定保存至系统的 `Environment.DIRECTORY_PICTURES + "/wCam"` 目录下，向 Android 系统请求到合法的 `Uri`。
- **压缩编码写入**: 打开 Uri 对应的 OutputStream，按照用户在 UI 上配置的 JPG 压缩质量 (`seekbarJpgQuality.progress`) 进行压缩写入。
- **精细注入 EXIF 信息 (针对 JPEG)**: 
  - 实例化 `ExifInterface` 并绑定文件描述符。
  - 调用 `writeExifMetadata`，将从 CaptureResult 解析出的信息详尽写入：
    - 设备制造商 (Make/Model)、软件标记(Software: wCam 1.1)
    - 曝光三要素 (ISO, 曝光时间, 光圈 F-Number)
    - 焦段参数 (物理焦距 Focal Length，等效 35mm 焦距计算值)
    - 曝光程序 (Exposure Program)、测光模式、曝光补偿
    - 闪光灯状态、白平衡模式
    - 地理位置 GPS 信息 (如果在系统授权了位置权限)
  - 调用 `exif.saveAttributes()` 完成落盘。

## 8. UI 更新与相册回显 (UI Update & Thumbnail)
- **通知主线程**: Bitmap 和文件全部保存完毕后，触发 `updateRecentPhotoPreview(uri)` 方法。
- **生成缩略图**: 再次向后台线程池提交任务，利用系统 API (`ContentResolver.loadThumbnail` 或相似实现) 异步解析出刚才那张图的小尺寸 Bitmap。
- **最终展示**: 回到主 UI 线程，将提取的缩略图设置为主界面左下角相册按钮 (`btnGallery`) 的背景图片。至此，完整的拍照闭环宣告结束。
