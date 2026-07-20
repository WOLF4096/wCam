# CaptureSessionManager 流程图

## 功能描述
`CaptureSessionManager` 是 wCam 相机系统的 "流控中心"。它位于硬件连接（CameraDevice）之上，负责搭建数据传输管道。主要职责包括：
1. **会话生命周期管理**：创建和销毁 `CameraCaptureSession`，确保预览流和拍照流能正确分发到对应的 Surface。
2. **多摄适配**：在支持的设备上，利用 `OutputConfiguration` 将特定的流绑定到指定的物理摄像头 ID。
3. **3A 触发与联动**：实现手动点击对焦（AF）和自动曝光（AE）测光区域的动态更新。
4. **性能优化**：在 Android 13+ 设备上应用 `StreamUseCase`，提示系统根据当前场景（如静态拍摄或预览）优化功耗和画质。
5. **缩放与裁剪应用**：在 Request 层级处理数码变焦指令。

## 核心处理流程

```mermaid
graph TD
    A[createSession 启动] --> B[关闭现有 CaptureSession]
    B --> C{系统版本?}
    
    subgraph 会话构造
        C -- API 28+ (P) --> D1[构造 OutputConfiguration 列表]
        D1 --> D2[应用 PhysicalCameraId & StreamUseCase]
        D2 --> D3[创建 SessionConfiguration]
        D3 --> D4[camera.createCaptureSession]
        
        C -- 旧版本 --> D5[camera.createCaptureSession 直接传入 Surface 列表]
    end

    D4 & D5 --> E[onConfigured 回调]
    E --> F[通知监听者 session 已就绪]

    subgraph 对焦逻辑 (triggerFocusAndMetering)
        G[用户点击屏幕] --> H[UI 坐标 -> 传感器坐标映射]
        H --> I[构造 MeteringRectangle 对焦框]
        I --> J[设置 AF_TRIGGER_START & AE_PRECAPTURE_TRIGGER_START]
        J --> K[提交单次 Capture 请求]
    end
```

## 关键方法说明
- `createSession()`: 该方法兼容了从 Android 5.0 到最新版本的会话创建逻辑。在现代设备上，它优先使用 `SessionConfiguration`，这允许更精细的参数控制。
- `physicalCameraId`: 这是一个高级特性。当使用逻辑多摄（如 ID "0" 代表三摄系统）时，通过此 ID 可以强制要求某个 Surface 必须来源于特定的物理传感器（如长焦镜头）。
- `triggerFocusAndMetering()`: 实现了典型的 "对焦并测光" 流程。它不仅移动对焦框，还触发 AE 预捕获，确保点击位置的曝光也是准确的。
- `toggleAeAfLock()`: 通过将 AF 模式切换为 `EDOF` (扩展景深) 或锁定 AE 状态，实现 "长按锁定曝光与对焦" 的专业相机功能。
- `StreamUseCase`: 在 Android 13 中引入，wCam 将 RAW 输出流标记为 `STILL_CAPTURE`，这有助于系统调度更高质量的 ISP 处理资源。
