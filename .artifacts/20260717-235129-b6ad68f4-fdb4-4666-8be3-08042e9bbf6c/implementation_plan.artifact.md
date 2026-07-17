# [Refactor MainActivity to Decouple Responsibilities]

The `MainActivity` in `wCam` has become a "God Object" that manages everything from camera life cycles to UI state, permissions, and gesture handling. This refactoring aims to improve maintainability and testability by extracting distinct responsibilities into specialized components.

## User Review Required

- **No breaking functional changes expected**: The goal is a pure refactor.
- **Component communication**: We will use existing interfaces (like `CameraUiController.UiEventListener`, `ManualParamController.ParamChangeListener`) more effectively.

## Proposed Changes

### [Core Logic Extraction]

Extracting state management and complex coordination logic out of `MainActivity`.

#### [NEW] [CameraCoordinator](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/camera/CameraCoordinator.kt)

- Acts as a mediator between `MainActivity` and the various managers (`CameraManager`, `SessionManager`, `CaptureManager`).
- Handles the high-level camera flow: open -> configure -> session -> preview -> capture.
- Manages the background thread and executor.

#### [NEW] [PermissionHelper](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/util/PermissionHelper.kt)

- Encapsulates permission checking and requesting logic.

---

### [Activity Simplification]

Refactoring `MainActivity` to focus only on UI binding and event routing.

#### [MainActivity](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/MainActivity.kt)

- Remove camera-specific fields like `cameraDevice`, `captureSession`, `previewRequestBuilder`.
- Delegate camera lifecycle to `CameraCoordinator`.
- Simplify listener implementations by delegating to the coordinator where appropriate.

---

### [Component Updates]

Updating existing classes to support the new coordinator.

#### [CameraManager](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/camera/CameraManager.kt)

- Small updates to ensure it works well with the `CameraCoordinator`.

---

## Verification Plan

### Automated Tests
- `gradle_build("app:assembleDebug")` to ensure compilation.

### Manual Verification
- Deploy to device and verify:
    - Camera opens and previews correctly.
    - Switching between Photo/Video modes works.
    - Manual parameters (ISO, Shutter, etc.) update the preview.
    - Capture still works and saves to gallery.
    - Permissions are handled gracefully.
