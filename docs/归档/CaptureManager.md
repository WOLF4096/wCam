# CaptureManager 流程图

## 功能描述
`CaptureManager` 是 wCam 拍照系统的 "任务调度中心"。它不仅负责下发硬件拍照指令，更核心的任务是管理复杂的 "异步生产线"。主要职责包括：
1. **拍摄参数组装**：将 UI 层的曝光设置、变焦倍率、旋转方向等转化为 `CaptureRequest`。
2. **数据包匹配**：由于 ImageReader 回调 (RAW 图片) 和 CaptureCallback (元数据) 是异步且独立的，它负责根据时间戳将它们配对。
3. **并发控制**：维护一个任务队列，防止因连续点击快门导致内存溢出（OOM），同时优化快门响应时间。
4. **异步分发**：将耗时的 Demosaic (去马赛克) 和文件 IO 操作分发到自定义的线程池中执行。
5. **任务预处理**：在图片关闭前拷贝像素 Buffer，并计算 LSC 强度、黑电平、CCM 等 ISP 必要参数。

## 核心处理流程

```mermaid
graph TD
    A[capturePhoto 触发] --> B[构造 TEMPLATE_STILL_CAPTURE 请求]
    B --> C[应用手动曝光/变焦/旋转]
    C --> D[session.capture 提交任务]
    
    subgraph 硬件响应
        D --> E1[onCaptureStarted: 通知 UI 曝光开始]
        D --> E2[onCaptureCompleted: 获取元数据 Result]
        D --> E3[ImageReader: 获取 RAW 图片]
    end

    E2 & E3 --> F[processPendingCaptures: 按时间戳配对]
    F --> G[dispatchCaptureTask: 释放 UI 锁 & 创建 CaptureTask]
    
    subgraph 后台执行 (ExecutorService)
        G --> H1[保存 RAW/DNG 如果需要]
        G --> H2[DemosaicEngine.process: 执行像素处理]
        H2 --> H3[WatermarkProcessor: 叠加水印]
        H3 --> H4[CaptureProcessor.save: 保存 JPEG/HEIF]
    end

    H1 & H4 --> I[通知 UI 拍摄完成并更新预览]
```

## 关键方法说明
- `capturePhoto()`: 入口方法。它会计算当前的快门时间（用于 UI 倒计时显示），并根据 Android 版本选择最合适的变焦实现。
- `processPendingCaptures()`: 核心匹配算法。使用 `ConcurrentHashMap` 存储待处理的图片和元数据，只有当两者都到齐时才启动处理流程。
- `dispatchCaptureTask()`: 性能优化的关键。它在提交任务到线程池后**立即**将 `isCaptureInProgress` 设为 false，这使得用户可以不必等待图片保存完成，就能立即拍下一张（零快门延迟体验）。
- `prepareCaptureTask()`: 在主后台线程运行，负责执行昂贵的 `ByteBuffer.allocateDirect` 拷贝动作，确保原始 `Image` 对象可以被快速关闭并归还给相机系统。
- `executeCaptureTask()`: 真正的苦力劳动。它会根据用户的格式选择（JPG/HEIF/RAW），调用 `DemosaicEngine` 生成 8bit 或 16bit 的 Bitmap。
