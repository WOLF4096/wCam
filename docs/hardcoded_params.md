# wCam 硬编码参数审计文档

本文档整理了 `wCam` 项目中硬编码在代码里的关键参数、常量及其用途，以便于后续进行机型适配和参数优化。

## 1. 曝光与感光度 (Exposure & ISO)

| 参数位置 | 硬编码值 | 用途说明 |
| :--- | :--- | :--- |
| `PhotoParamMapper.kt` | `64000` | 扩展 ISO 上限。手动模式下允许用户选择超出硬件报告范围的 ISO 值（实验室特性）。 |
| `PhotoParamMapper.kt` | `200_000_000L` (1/5s) | 预览流快门上限。为防止预览画面在长曝光下完全冻结，强制限制预览请求的快门时间不超过 1/5 秒。 |
| `CaptureManager.kt` | `100_000_000L` (1/10s) | 默认快门兜底值。当无法获取自动曝光值且未设置手动快门时使用的默认值。 |

## 2. 图像处理与解拜耳 (Demosaic & Processing)

| 参数位置 | 硬编码值 | 用途说明 |
| :--- | :--- | :--- |
| `CaptureManager.kt` | `1.30f` | `lscStrength` (镜头阴影校正强度)。用于手动补偿画面四周暗角的系数。 |
| `CaptureManager.kt` | `64` | `blackLevel` 兜底值。当 API 无法返回传感器黑电平时使用的默认值。 |
| `CaptureManager.kt` | `1023` | `whiteLevel` 兜底值。针对 10-bit RAW 数据的默认白电平上限。 |
| `CaptureManager.kt` | `1.0f` (CCM Identity) | 色彩矩阵兜底。当无法从 DNG 元数据获取颜色变换矩阵时，使用单位矩阵防止颜色溢出。 |

## 3. 白平衡与色温 (White Balance & Kelvin)

| 参数位置 | 硬编码值 | 用途说明 |
| :--- | :--- | :--- |
| `PhotoParamMapper.kt` | `3200, 4000, 5200, 6000, 7000` | 手动白平衡预设值列表（单位：K）。 |
| `PhotoParamMapper.kt` | `2000` to `8000` | 色温映射范围。定义了从 Kelvin 转换为 RGB 增益向量的有效区间。 |
| `PhotoParamMapper.kt` | `5200K` | 自动白平衡匹配失败时的默认回归值。 |

## 4. 预览与显示 (Preview & UI)

| 参数位置 | 硬编码值 | 用途说明 |
| :--- | :--- | :--- |
| `CameraCoordinator.kt` | `2500` (px) | 预览尺寸上限。过滤 `StreamConfigurationMap` 时，限制预览宽度不超过 2500 像素以维持流畅度。 |
| `CameraCoordinator.kt` | `1.333f` (4:3) | 预览比例偏好。在拍照模式下优先寻找符合 4:3 比例的预览尺寸。 |
| `CameraCoordinator.kt` | `1920x1080` | 预览尺寸兜底。当无法匹配到合适尺寸时的最终回退方案。 |
| `CameraStreamMonitor.kt` | `160x120` | 直方图采样尺寸。为了兼顾计算性能，将预览帧缩放到此尺寸进行实时直方图统计。 |
| `CameraStreamMonitor.kt` | `2000` (ms) | 冻结检测阈值。如果预览帧停留超过 2 秒未更新，判定为流冻结并触发重连。 |

## 5. 对焦与缩放 (Focus & Zoom)

| 参数位置 | 硬编码值 | 用途说明 |
| :--- | :--- | :--- |
| `MainActivity.kt` | `10.0f` | 默认最大数码变焦倍率。如果 API 未返回厂商缩放限制，则默认支持 10 倍缩放。 |
| `PhotoParamMapper.kt` | `50.0f` | 最大屈光度（Diopter）。手动对焦滑块对应的物理最大值。 |

## 6. 存储与系统 (System & Storage)

| 参数位置 | 硬编码值 | 用途说明 |
| :--- | :--- | :--- |
| `MainActivity.kt` | `"0"` | 默认逻辑摄像头 ID (通常代表后置主摄)。 |
| `MainActivity.kt` | `"2"` | 默认物理摄像头 ID (针对 Vivo 等机型的超高清物理主摄)。 |
| `PreferenceManager.kt` | `"wcam_prefs"` | SharedPreferences 文件名。 |
| `CaptureProcessor.kt` | `"wCam 1.1"` | EXIF 软件标记字符串。 |

---
*注：上述参数中，涉及到“兜底值”或“校正系数”的内容，建议在未来的机型配置表（JSON）中实现动态加载，以消除硬编码带来的兼容性问题。*
