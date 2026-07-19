# ManualParamController 流程图

## 功能描述
`ManualParamController` 是 wCam "专业模式" 的交互引擎。它将复杂的相机参数调节转化为直观的手势操作。核心职责包括：
1. **手势映射**：将垂直滑动位移转化为参数列表（如 ISO 50 -> 64000）的索引切换。
2. **状态实时反馈**：在自动模式下，它不显示固定的 "Auto"，而是实时显示相机当前正在使用的曝光值（如当前环境下的实时 ISO）。
3. **视觉编码**：通过颜色区分状态（白色代表系统自动控制，黄色代表用户手动干预）。
4. **快速恢复**：支持通过双击手势快速将单项参数恢复为自动模式。

## 核心处理流程

```mermaid
graph TD
    A[UI 触摸事件触发] --> B{手势类型?}
    
    subgraph 滑动调节 (ACTION_MOVE)
        B -- 单指滑动 --> C[获取滑动位移 DeltaY]
        C --> D{超过灵敏度阈值?}
        D -- 是 --> E[计算新的参数索引]
        E --> F[回调 onParamManualStep]
        F --> G[更新底层 CaptureRequest]
    end

    subgraph 自动/手动切换
        B -- 双击 --> H[回调 onParamAutoReset]
        H --> I[参数归位为系统托管]
    end

    subgraph UI 刷新 (refreshViews)
        J[接收 StateFlow 通知] --> K[检查各参数 isAuto 标志]
        K -- isAuto=true --> L[从底层获取实时 PreviewAutoValues]
        K -- isAuto=false --> M[从 ViewModel 获取用户设定值]
        L & M --> N[格式化显示文字 e.g., '1/125']
        N --> O[应用颜色样式]
    end
```

## 关键方法说明
- `ParamGestureHandler`: 内部类，为每个参数栏（如 ISO 布局、快门布局）独立创建一个手势检测器，实现互不干扰的精确调节。
- `findClosestIndex()`: 这是一个关键的算法。当用户从自动模式切换到手动模式时，它会寻找当前实时值在预设步进列表（如日志排列的 ISO 列表）中最接近的索引，确保切换过程平滑无跳变。
- `extractNumber()`: 一个强大的字符串解析器，能够处理诸如 "1/1000", "+0.3", "5200K", "0.2m" 等各种带有单位或分数的参数标签，并将其转化为可比较的浮点数。
- `setParamText()`: 封装了颜色逻辑。使用了项目预定义的 `PARAM_TEXT_AUTO_COLOR` (#FFFFFF) 和 `PARAM_TEXT_MANUAL_COLOR` (#FFCC00)，符合专业摄影器材的视觉惯例。
- `PreviewAutoValues`: 这是一个数据类，承载了从相机底层 `TotalCaptureResult` 中提取的、每一帧都在变化的实时曝光数据。
