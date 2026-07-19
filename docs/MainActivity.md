# MainActivity 流程图

## 功能描述
`MainActivity` 是 wCam 应用的核心 Activity，担任 "中央枢纽" 的角色。它负责：
1. **生命周期管理**：协调相机硬件的开启与关闭，确保在应用前后台切换时正确释放资源。
2. **UI 协调**：初始化并管理多个 UI 控制器（如 `CameraUiController`, `ManualParamController`, `LensController` 等）。
3. **权限处理**：确保相机、存储和位置权限已授予。
4. **交互路由**：将用户的手势、点击事件转发给相应的业务逻辑层（如 `CameraCoordinator`）。
5. **状态同步**：观察 `CameraViewModel` 的状态变化，并实时更新 UI 元素。

## 核心处理流程

```mermaid
graph TD
    A[Activity.onCreate] --> B{检查权限}
    B -- 已授权 --> C[initApp]
    B -- 未授权 --> D[请求权限]
    D -- 用户同意 --> C
    
    subgraph initApp [初始化流程]
        C1[初始化配置与 Log] --> C2[初始化核心控制器]
        C2 --> C3[加载用户设置]
        C3 --> C4[启动 CameraCoordinator]
        C4 --> C5[硬件抢跑: openCamera]
    end

    C --> E[onResume]
    
    subgraph onResume [恢复流程]
        E1[启动流监控与姿态追踪] --> E2[更新相册预览图]
        E2 --> E3{是否冷启动?}
        E3 -- 否 --> E4[重新开启相机]
        E3 -- 是 --> E5[等待 Surface 准备就绪]
        E4 --> E5
        E5 --> E6[createPreviewSession]
    end

    subgraph 交互循环 [用户交互逻辑]
        F1[快门点击] --> F2[Coordinator.capturePhoto]
        F3[镜头切换] --> F4[Coordinator.switchCamera]
        F5[参数调整] --> F6[更新 ViewModel 并应用到预览]
        F7[手势操作] --> F8[缩放或对焦触发]
    end

    E --> G[onPause]
    
    subgraph onPause [暂停流程]
        G1[停止位置更新] --> G2[停止 Coordinator]
        G2 --> G3[关闭传感器监听]
    end
```

## 关键方法说明
- `initApp()`: 执行应用启动的关键路径初始化，包括 `ConfigManager` 和硬件预热。
- `handleOnResume()`: 处理热启动逻辑，确保在返回应用时相机能快速恢复预览。
- `observeViewModel()`: 使用协程流（Flow）实时监听曝光、对焦等参数的变化并更新 UI。
- `rotateUiElements()`: 根据设备重力传感器方向，通过动画旋转所有按钮图标，保持界面方向正确。
- `onSingleTap()`: 处理预览区的单点对焦，显示对焦环并通知底层执行 AF。
