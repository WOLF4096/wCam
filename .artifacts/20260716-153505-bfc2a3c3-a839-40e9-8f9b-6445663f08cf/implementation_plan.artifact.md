# Project Refactoring Plan

The goal is to split the large `MainActivity.kt` and `native-lib.cpp` into smaller, logically organized files to improve maintainability and readability without affecting performance or functionality.

## User Review Required

> [!IMPORTANT]
> This refactoring involves moving a lot of code. While I will ensure logic remains identical, please verify the app's core functionality (preview, capture, manual controls) after each major step.

## Proposed Changes

### 1. Camera Logic Separation (`com.example.wcam.camera`)

Move camera device management and session handling out of `MainActivity`.

#### [NEW] [CameraManager.kt](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/camera/CameraManager.kt)
- Handles `CameraDevice` lifecycle, `CameraCaptureSession` creation, and camera state callbacks.
- Manages `ImageReader` setup and callbacks.

#### [NEW] [CaptureSessionManager.kt](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/camera/CaptureSessionManager.kt)
- Specifically manages the preview session and repeating requests.
- Handles AE/AF triggers and zoom.

#### [NEW] [CaptureProcessor.kt](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/camera/CaptureProcessor.kt)
- Contains logic for processing raw images, dispatching capture tasks, and saving files (JPEG/PNG).
- Moves `saveJpegBitmap`, `savePngBitmap`, `writeExifMetadata` here.

---

### 2. UI Control Separation (`com.example.wcam.ui`)

Move UI interaction and state management to controllers.

#### [NEW] [CameraUiController.kt](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/ui/CameraUiController.kt)
- Manages top bar icons (flash, grid, histogram), lens selector, and mode switching.
- Handles the "format panel" visibility.

#### [NEW] [ManualParamController.kt](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/ui/ManualParamController.kt)
- Moves the inner `ParamController` class and related gesture logic (EV, WB, ISO, S, AF).

#### [NEW] [OverlayController.kt](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/ui/OverlayController.kt)
- Manages `CropOverlayView`, `GridLineView`, and `HistogramView` updates.

---

### 3. Native Code Cleanup (`cpp`)

#### [NEW] [histogram.cpp](file:///D:/Project/wCam/wCam/app/src/main/cpp/histogram.cpp)
- Moves `nativeCalculateHistogram` to its own file.

#### [native-lib.cpp](file:///D:/Project/wCam/wCam/app/src/main/cpp/native-lib.cpp)
- Keep JNI entry points but consider moving the core demosaic processing loop to a separate file if it grows further.

---

### 4. MainActivity Cleanup

- `MainActivity` will now primarily act as a coordinator between these components.
- It will initialize the managers and controllers in `onCreate`.

## Refactoring Steps

1.  **Step 1: UI Controllers**: Extract `ParamController` and basic UI listeners.
2.  **Step 2: Camera Management**: Extract `CameraDevice` and `CaptureSession` logic.
3.  **Step 3: Capture Processing**: Extract image saving and metadata handling.
4.  **Step 4: Native Splitting**: Move histogram logic to separate C++ file.

## Verification Plan

### Automated Tests
- Run existing unit tests in `D:/Project/wCam/wCam/app/src/test/java/com/example/wcam/` to ensure no regressions in core logic.
  ```powershell
  ./gradlew test
  ```

### Manual Verification
- **Preview**: Verify camera preview starts correctly on all lenses.
- **Capture**: Take JPG and RAW photos, verify they are saved correctly in the gallery.
- **Manual Controls**: Test sliding EV, ISO, Shutter Speed, etc., and verify they update the preview.
- **Crop/Ratio**: Verify 1:2, 3:4, etc. still work as expected and indicator is correct.
- **Histogram**: Verify histogram overlay still updates.
