# Photo Parameter Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing `EV`, `WB`, `ISO`, `S`, and `AF` parameter bar control real Camera2 preview and RAW capture requests, while showing live auto values in white and manual values in yellow.

**Architecture:** Keep `MainActivity` as the orchestration layer, but extract parameter mapping and state handling into small camera-focused units under `com.example.wcam.camera`. Preview and still capture both use the same parameter-application path so UI values, preview behavior, and RAW capture intent stay aligned.

**Tech Stack:** Kotlin, Android View system, Camera2 API, RAW `ImageReader`, local JUnit tests

---

## File Structure

**Create:**
- `app/src/main/java/com/example/wcam/camera/PhotoParamMapper.kt`
- `app/src/main/java/com/example/wcam/camera/PhotoParamState.kt`
- `app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt`

**Modify:**
- `app/src/main/java/com/example/wcam/MainActivity.kt`
- `app/src/test/java/com/example/wcam/ExampleUnitTest.kt`

**Responsibilities:**
- `PhotoParamState.kt`: parameter modes, current display values, selected manual values, capability container
- `PhotoParamMapper.kt`: parse `1/100s` style shutter labels, clamp supported ranges, map Kelvin and focus slider values, and apply state into `CaptureRequest.Builder`
- `PhotoParamMapperTest.kt`: pure JVM tests for label parsing, EV conversion, clamping, and AF normalization
- `MainActivity.kt`: wire touch gestures, observe preview `CaptureResult`, refresh labels/colors, and call mapper for preview/capture requests
- `ExampleUnitTest.kt`: keep existing filename-format regression check

### Task 1: Add failing mapper tests

**Files:**
- Create: `app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt`
- Test: `app/src/test/java/com/example/wcam/ExampleUnitTest.kt`

- [ ] **Step 1: Write the failing test file**

```kotlin
package com.example.wcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoParamMapperTest {
    @Test
    fun parseShutterLabel_convertsFractionalSecondsToNanos() {
        assertEquals(10_000_000L, PhotoParamMapper.parseShutterLabel("1/100s"))
        assertEquals(125_000_000L, PhotoParamMapper.parseShutterLabel("1/8s"))
    }

    @Test
    fun clampIso_respectsSupportedRange() {
        assertEquals(100, PhotoParamMapper.clampIso(50, 100..6400))
        assertEquals(6400, PhotoParamMapper.clampIso(12800, 100..6400))
    }

    @Test
    fun clampExposureTime_respectsSupportedRange() {
        assertEquals(10_000_000L, PhotoParamMapper.clampExposureTime(1_000_000L, 10_000_000L..100_000_000L))
        assertEquals(100_000_000L, PhotoParamMapper.clampExposureTime(300_000_000L, 10_000_000L..100_000_000L))
    }

    @Test
    fun normalizedFocusProgress_mapsIntoSupportedDistanceRange() {
        assertEquals(0.0f, PhotoParamMapper.mapAfProgressToDistance(1.0f, 0.0f), 0.0001f)
        assertEquals(5.0f, PhotoParamMapper.mapAfProgressToDistance(0.0f, 5.0f), 0.0001f)
        assertEquals(2.5f, PhotoParamMapper.mapAfProgressToDistance(0.5f, 5.0f), 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\狼黑\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' -p . testDebugUnitTest --tests com.example.wcam.camera.PhotoParamMapperTest
```

Expected: FAIL with unresolved reference errors for `PhotoParamMapper`

- [ ] **Step 3: Keep existing filename regression test intact**

```kotlin
package com.example.wcam

import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun createRawDisplayName_usesTimestampPatternAndDngExtension() {
        val fileName = createRawDisplayName(1_719_676_800_000)

        assertTrue(fileName.startsWith("wcam_"))
        assertTrue(fileName.endsWith(".dng"))
        assertTrue(fileName.matches(Regex("wcam_\\d{8}_\\d{6}\\.dng")))
    }
}
```

- [ ] **Step 4: Run the filename regression test**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\狼黑\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' -p . testDebugUnitTest --tests com.example.wcam.ExampleUnitTest.createRawDisplayName_usesTimestampPatternAndDngExtension
```

Expected: PASS once Gradle can resolve settings plugins

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/example/wcam/ExampleUnitTest.kt app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt
git commit -m "test: add photo parameter mapper coverage"
```

### Task 2: Implement parameter models and pure mapping helpers

**Files:**
- Create: `app/src/main/java/com/example/wcam/camera/PhotoParamState.kt`
- Create: `app/src/main/java/com/example/wcam/camera/PhotoParamMapper.kt`
- Test: `app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt`

- [ ] **Step 1: Write the minimal state model**

```kotlin
package com.example.wcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Range

data class CameraCapabilities(
    val aeCompensationRange: Range<Int>?,
    val aeCompensationStep: Float?,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val minimumFocusDistance: Float?,
    val supportsManualSensor: Boolean,
    val supportsManualWhiteBalance: Boolean,
)

enum class ParamKey {
    EV, WB, ISO, SHUTTER, AF
}

data class ParamValue<T>(
    val isAuto: Boolean,
    val displayValue: String,
    val manualValue: T?,
)

data class PhotoParamState(
    val ev: ParamValue<Int>,
    val wb: ParamValue<Int>,
    val iso: ParamValue<Int>,
    val shutter: ParamValue<Long>,
    val af: ParamValue<Float>,
)
```

- [ ] **Step 2: Implement the pure mapping helpers**

```kotlin
package com.example.wcam.camera

object PhotoParamMapper {
    fun parseShutterLabel(label: String): Long {
        val normalized = label.removeSuffix("s")
        val parts = normalized.split("/")
        require(parts.size == 2) { "Invalid shutter label: $label" }
        val numerator = parts[0].toDouble()
        val denominator = parts[1].toDouble()
        val seconds = numerator / denominator
        return (seconds * 1_000_000_000L).toLong()
    }

    fun clampIso(value: Int, range: IntRange): Int = value.coerceIn(range)

    fun clampExposureTime(value: Long, range: LongRange): Long = value.coerceIn(range)

    fun mapAfProgressToDistance(progress: Float, minimumFocusDistance: Float): Float {
        if (minimumFocusDistance <= 0f) return 0f
        val clamped = progress.coerceIn(0f, 1f)
        return minimumFocusDistance * (1f - clamped)
    }
}
```

- [ ] **Step 3: Run mapper tests to verify they pass**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\狼黑\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' -p . testDebugUnitTest --tests com.example.wcam.camera.PhotoParamMapperTest
```

Expected: PASS once Gradle settings resolution is available

- [ ] **Step 4: Refactor only after green**

```kotlin
// Optional follow-up after tests are green:
fun clampIso(value: Int, range: ClosedRange<Int>): Int = value.coerceIn(range.start, range.endInclusive)
fun clampExposureTime(value: Long, range: ClosedRange<Long>): Long = value.coerceIn(range.start, range.endInclusive)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/wcam/camera/PhotoParamState.kt app/src/main/java/com/example/wcam/camera/PhotoParamMapper.kt app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt
git commit -m "feat: add photo parameter mapping core"
```

### Task 3: Wire live parameter state into MainActivity preview updates

**Files:**
- Modify: `app/src/main/java/com/example/wcam/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Add shutter labels in `1/100s` format and keep current parameter IDs**

```kotlin
private fun setupParamGestures() {
    val evValues = listOf("-2.0", "-1.5", "-1.0", "-0.5", "0.0", "+0.5", "+1.0", "+1.5", "+2.0")
    val wbValues = listOf("3200K", "4000K", "5200K", "6000K", "7000K")
    val isoValues = listOf("50", "100", "200", "400", "800", "1600", "3200", "6400")
    val shutterValues = listOf("1/1000s", "1/500s", "1/250s", "1/125s", "1/100s", "1/60s", "1/30s", "1/8s")
    val afValues = listOf("0.1", "0.3", "0.5", "0.7", "0.9", "1.0")

    ParamController(R.id.layout_param_ev, R.id.tv_val_ev, evValues, 4, ParamKey.EV).setup()
    ParamController(R.id.layout_param_wb, R.id.tv_val_wb, wbValues, 2, ParamKey.WB).setup()
    ParamController(R.id.layout_param_iso, R.id.tv_val_iso, isoValues, 1, ParamKey.ISO).setup()
    ParamController(R.id.layout_param_s, R.id.tv_val_s, shutterValues, 4, ParamKey.SHUTTER).setup()
    ParamController(R.id.layout_param_af, R.id.tv_val_af, afValues, 2, ParamKey.AF).setup()
}
```

- [ ] **Step 2: Replace text-only controller state with parameter-aware updates**

```kotlin
private lateinit var photoParamState: PhotoParamState
private lateinit var cameraCapabilities: CameraCapabilities

private fun onParamManualStep(key: ParamKey, selectedLabel: String) {
    photoParamState = when (key) {
        ParamKey.EV -> photoParamState.copy(ev = photoParamState.ev.copy(isAuto = false, displayValue = selectedLabel))
        ParamKey.WB -> photoParamState.copy(wb = photoParamState.wb.copy(isAuto = false, displayValue = selectedLabel))
        ParamKey.ISO -> photoParamState.copy(iso = photoParamState.iso.copy(isAuto = false, displayValue = selectedLabel))
        ParamKey.SHUTTER -> photoParamState.copy(shutter = photoParamState.shutter.copy(isAuto = false, displayValue = selectedLabel))
        ParamKey.AF -> photoParamState.copy(af = photoParamState.af.copy(isAuto = false, displayValue = selectedLabel))
    }
    refreshParamViews()
    resumePreview()
}
```

- [ ] **Step 3: Add a preview capture callback that updates only auto parameters**

```kotlin
private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        photoParamState = updateAutoParamsFromResult(photoParamState, result)
        refreshParamViews()
    }
}
```

- [ ] **Step 4: Re-run preview request through the new callback**

```kotlin
private fun resumePreview() {
    val session = captureSession ?: return
    val previewRequest = previewRequestBuilder?.build() ?: return
    session.setRepeatingRequest(previewRequest, previewCaptureCallback, backgroundHandler)
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/wcam/MainActivity.kt
git commit -m "feat: wire live photo parameter state into preview"
```

### Task 4: Apply parameters to preview and RAW capture requests

**Files:**
- Modify: `app/src/main/java/com/example/wcam/MainActivity.kt`
- Modify: `app/src/main/java/com/example/wcam/camera/PhotoParamMapper.kt`

- [ ] **Step 1: Add a request application helper for preview and still capture**

```kotlin
fun applyPhotoParamsToRequest(
    builder: CaptureRequest.Builder,
    state: PhotoParamState,
    capabilities: CameraCapabilities,
) {
    val manualExposure = !state.iso.isAuto || !state.shutter.isAuto

    if (manualExposure && capabilities.supportsManualSensor) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        state.iso.manualValue?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        state.shutter.manualValue?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
    } else {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    }

    if (!state.ev.isAuto) {
        state.ev.manualValue?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
    }

    if (!state.af.isAuto && capabilities.minimumFocusDistance?.let { it > 0f } == true) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        state.af.manualValue?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
    } else {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }
}
```

- [ ] **Step 2: Call the helper when building preview requests**

```kotlin
previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
    addTarget(surface)
    applyPhotoParamsToRequest(this, photoParamState, cameraCapabilities)
}
```

- [ ] **Step 3: Call the helper when building RAW still requests**

```kotlin
val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
    addTarget(imageReader.surface)
    surface?.let { addTarget(it) }
    applyPhotoParamsToRequest(this, photoParamState, cameraCapabilities)
}
```

- [ ] **Step 4: Run compile-only verification**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\狼黑\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' -p . :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL once settings plugin resolution is available

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/wcam/MainActivity.kt app/src/main/java/com/example/wcam/camera/PhotoParamMapper.kt
git commit -m "feat: apply photo parameters to preview and raw capture"
```

### Task 5: Validate behavior and tighten edge handling

**Files:**
- Modify: `app/src/main/java/com/example/wcam/MainActivity.kt`
- Test: `app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt`

- [ ] **Step 1: Add one more failing edge-case test for AF fallback**

```kotlin
@Test
fun mapAfProgressToDistance_returnsZeroWhenLensDoesNotSupportManualFocus() {
    assertEquals(0.0f, PhotoParamMapper.mapAfProgressToDistance(0.7f, 0f), 0.0001f)
}
```

- [ ] **Step 2: Run the AF edge-case test to verify it fails before implementation**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\狼黑\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' -p . testDebugUnitTest --tests com.example.wcam.camera.PhotoParamMapperTest.mapAfProgressToDistance_returnsZeroWhenLensDoesNotSupportManualFocus
```

Expected: FAIL before the fallback path exists

- [ ] **Step 3: Implement the minimal fallback and keep unsupported parameters in auto**

```kotlin
if (!cameraCapabilities.supportsManualSensor) {
    showToast("当前镜头不支持手动曝光")
    photoParamState = photoParamState.copy(
        iso = photoParamState.iso.copy(isAuto = true),
        shutter = photoParamState.shutter.copy(isAuto = true),
    )
}
```

- [ ] **Step 4: Run manual validation on device**

Checklist:
```text
1. Auto values update live in white
2. Swipe ISO upward increases preview brightness and label turns yellow
3. Swipe shutter to 1/100s updates label and preview changes immediately
4. Swipe WB changes preview tint and label turns yellow
5. Tap any parameter once to restore auto and white label
6. RAW capture still succeeds after manual adjustments
7. Switching lenses refreshes ranges without black screen
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/wcam/MainActivity.kt app/src/test/java/com/example/wcam/camera/PhotoParamMapperTest.kt
git commit -m "fix: stabilize manual photo parameter fallbacks"
```

## Environment Note

Current local verification may be blocked before Kotlin compilation if the Gradle settings plugin `org.gradle.toolchains.foojay-resolver-convention` cannot be resolved in the active environment. If that happens, keep the TDD sequence intact conceptually, but resolve the Gradle settings/plugin environment first so the fail/pass cycle can be observed normally.

