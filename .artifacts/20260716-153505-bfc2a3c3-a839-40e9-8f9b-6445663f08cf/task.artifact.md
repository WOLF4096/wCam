# Task Management

- [/] Refactor Project Structure
    - [/] Step 1: UI Controllers Separation
        - [ ] Create `ManualParamController.kt` and move `ParamController` logic
        - [ ] Create `CameraUiController.kt` and move UI listeners/state
        - [ ] Create `OverlayController.kt` and move overlay management
    - [ ] Step 2: Camera Management Separation
        - [ ] Create `CameraManager.kt`
        - [ ] Create `CaptureSessionManager.kt`
    - [ ] Step 3: Capture Processing Separation
        - [ ] Create `CaptureProcessor.kt`
    - [ ] Step 4: Native Code Splitting
        - [ ] Create `histogram.cpp` and update `CMakeLists.txt`
    - [ ] Step 5: MainActivity Cleanup and Integration
- [ ] Verification and Testing
    - [ ] Run automated tests
    - [ ] Perform manual verification (simulated)
