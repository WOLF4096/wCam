# Photo Parameter Control Design

**Date:** 2026-06-29
**Scope:** Connect the existing `EV`, `WB`, `ISO`, `S`, and `AF` parameter UI to real Camera2 preview and RAW capture requests while keeping the current single-activity app structure.

## Goal

Turn the existing slide-based parameter bar into a real camera control surface:
- Auto mode shows current camera-derived values in `#FFFFFF`
- Manual mode shows user-selected values in `#FFCC00`
- Swipe up increases the selected parameter
- Swipe down decreases the selected parameter
- Single tap restores that parameter to auto mode only

This design keeps the current UI layout and RAW capture flow intact while introducing a small amount of structure to make further camera work easier.

## Current State

The current app already has:
- A working `TextureView` preview pipeline
- A working RAW capture and save flow
- A parameter bar UI for `EV`, `WB`, `ISO`, `S`, and `AF`
- A `ParamController` touch interaction model that only updates label text

The current gap is that parameter changes do not affect `CaptureRequest` values for preview or capture.

## Constraints

- Use only ranges supported by the current camera device and current lens selection
- Keep the existing `MainActivity`-based architecture for now
- Do not redesign the current parameter bar UI
- Do not change JPEG/RAW format panel behavior in this task
- Avoid illegal request combinations that can black-screen preview or break capture
- Prefer graceful degradation when the selected parameter is unsupported on a device

## Behavioral Requirements

### Shared interaction rules

Each parameter behaves independently:
- Auto/manual state is tracked per parameter
- Single tap on a parameter returns only that parameter to auto mode
- Manual changes take effect immediately on preview
- RAW capture reuses the same effective values as preview

### Display rules

For each parameter:
- Auto mode displays the latest camera-derived value in `#FFFFFF`
- Manual mode displays the user-selected value in `#FFCC00`

### Preview update rules

When any parameter changes:
- Update internal parameter state
- Rebuild or refresh the repeating preview request
- Apply all effective parameter values to the request

### Capture update rules

When RAW capture is triggered:
- Build the still capture request from the same effective parameter state
- Keep preview-visible values and captured RAW intent aligned as closely as Camera2 allows

## Architecture

This task introduces two focused units without fully restructuring the app.

### 1. `PhotoParamState`

A small state model in `MainActivity.kt` or a nearby file that stores the five parameter states.

Each parameter entry contains:
- `isAuto: Boolean`
- `displayValue: String`
- A typed selected value for manual mode

Suggested parameter typing:
- `EV`: exposure compensation index plus formatted display string
- `WB`: target Kelvin integer plus formatted display string
- `ISO`: target ISO integer plus formatted display string
- `S`: target exposure time in nanoseconds plus formatted display string
- `AF`: target focus distance float plus formatted display string

### 2. `applyPhotoParamsToRequest(...)`

A single request-application function that writes the effective parameter state into any `CaptureRequest.Builder`.

It is used by:
- Preview repeating request creation
- Preview request refresh after user interaction
- Still RAW capture request creation

This prevents parameter logic from diverging between preview and capture.

## Device Capability Model

When the active camera changes, read and cache these capabilities from `CameraCharacteristics`:
- `CONTROL_AE_COMPENSATION_RANGE`
- `CONTROL_AE_COMPENSATION_STEP`
- `SENSOR_INFO_SENSITIVITY_RANGE`
- `SENSOR_INFO_EXPOSURE_TIME_RANGE`
- `LENS_INFO_MINIMUM_FOCUS_DISTANCE`
- `REQUEST_AVAILABLE_CAPABILITIES`
- Any related support flags needed to determine whether manual sensor control is valid

This capability snapshot is refreshed whenever the selected logical or physical camera changes.

## Parameter Mapping

All manual values are clamped to the active device's supported range.

### EV

UI values remain the existing half-stop ladder from `-2.0` to `+2.0`.

Implementation:
- Read compensation step and supported range
- Convert the selected EV display value into the nearest legal compensation index
- Clamp to the device range
- In auto mode, derive the current display from `CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION`

Request behavior:
- If EV is auto, do not force compensation beyond the current default behavior
- If EV is manual, set `CONTROL_AE_EXPOSURE_COMPENSATION`

### WB

UI values use Kelvin-like labels such as `3200K`, `4000K`, `5200K`, `6000K`, `7000K`.

Implementation:
- Manual mode disables AWB
- Convert the selected Kelvin value into an approximate `RggbChannelVector`
- Auto mode uses camera result data to show the best available current value

Display source in auto mode:
- Prefer deriving an approximate Kelvin display from `CaptureResult.COLOR_CORRECTION_GAINS`
- If the result is too unreliable, keep the last stable auto display or fall back to an `AUTO`-style placeholder only if necessary

Request behavior:
- Auto mode: `CONTROL_AWB_MODE_AUTO`
- Manual mode: `CONTROL_AWB_MODE_OFF` plus `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` and `COLOR_CORRECTION_GAINS`

### ISO

Implementation:
- Manual values are clamped to `SENSOR_INFO_SENSITIVITY_RANGE`
- Auto display reads from `CaptureResult.SENSOR_SENSITIVITY`

Request behavior:
- If either `ISO` or `S` is manual, transition to manual exposure mode
- If ISO is manual, set `SENSOR_SENSITIVITY`
- If ISO is auto while manual exposure mode is active because shutter is manual, use a safe default or the latest observed auto ISO

### S (shutter)

Display format uses 1/100s style strings consistently.

Implementation:
- Convert labels like `1/1000s`, `1/125s`, `1/100s`, `1/8s` to nanoseconds
- Clamp to `SENSOR_INFO_EXPOSURE_TIME_RANGE`
- Auto display reads from `CaptureResult.SENSOR_EXPOSURE_TIME`

Request behavior:
- If either `ISO` or `S` is manual, transition to manual exposure mode
- If shutter is manual, set `SENSOR_EXPOSURE_TIME`
- If shutter is auto while manual exposure mode is active because ISO is manual, use a safe default or the latest observed auto shutter

### AF

Implementation:
- Interpret the existing `0.1 .. 1.0` ladder as a normalized near-to-far focus selection
- Convert the normalized value to `LENS_FOCUS_DISTANCE` using `LENS_INFO_MINIMUM_FOCUS_DISTANCE`
- Auto display reads from `CaptureResult.LENS_FOCUS_DISTANCE`

Request behavior:
- Auto mode: `CONTROL_AF_MODE_CONTINUOUS_PICTURE`
- Manual mode: `CONTROL_AF_MODE_OFF` plus `LENS_FOCUS_DISTANCE`

## Auto vs Manual Exposure Rules

`EV` stays on the AE path.

`ISO` and `S` share exposure-mode behavior:
- If both are auto, keep AE enabled
- If either is manual, disable AE and apply manual sensor controls
- In manual exposure mode, both ISO and shutter must resolve to valid effective values even if only one of them was explicitly switched to manual

This preserves the user's per-parameter auto/manual model without sending incomplete manual sensor requests.

## Capture Result Feedback Loop

Attach a preview `CaptureCallback` to the repeating request.

On each new preview `CaptureResult`:
- Update only parameters that are still in auto mode
- Recompute display strings from real camera values
- Refresh the corresponding `TextView` color and text

Manual parameters do not get overwritten by incoming result data.

## Failure and Degradation Rules

The app must degrade safely when a parameter is unsupported.

### EV unsupported
- Keep EV in auto mode
- Ignore manual EV attempts
- Optionally show a one-time toast

### Manual WB unsupported or unstable
- Keep AWB in auto mode
- Ignore manual WB application
- Leave the display in auto mode rather than pretending manual control succeeded

### Manual sensor exposure unsupported
- If the device cannot support manual ISO/shutter control, keep both on auto
- Ignore manual ISO and shutter writes
- Show a one-time toast or equivalent lightweight feedback

### Manual AF unsupported
- If `LENS_INFO_MINIMUM_FOCUS_DISTANCE` is `null` or `0f`, keep AF in auto mode
- Ignore manual AF writes

### Invalid or partial state
- Never send an incomplete manual sensor request
- Always clamp values before applying them
- Prefer keeping preview alive over forcing a requested manual state

## MainActivity Changes

`MainActivity.kt` should retain:
- View binding
- Current parameter bar touch areas and labels
- Current mode switch, histogram, grid, and lens selector UI
- Current RAW save flow ownership

`MainActivity.kt` should gain:
- Capability cache for the active camera
- Parameter state storage
- A reusable preview capture callback for auto-value updates
- A helper to refresh parameter labels and colors
- A helper to rebuild preview requests after parameter changes
- Parameter-aware request application for both preview and still capture

## Testing Strategy

### Unit-testable logic

Add or extend local unit tests for pure mapping logic such as:
- RAW filename formatting remains stable
- Shutter label to nanosecond conversion
- Kelvin label parsing
- EV label to compensation-index conversion using a known compensation step
- Range clamping behavior for ISO, shutter, and focus normalization

### Manual device validation

Because Camera2 capability support is hardware-dependent, device validation is required.

Minimum validation checklist:
- Preview opens normally with all five parameters in auto
- Auto-mode values update live and display in `#FFFFFF`
- Swiping ISO changes preview brightness and display color switches to `#FFCC00`
- Swiping shutter changes preview brightness and display color switches to `#FFCC00`
- Swiping WB changes preview color temperature visibly
- Swiping AF changes focus behavior on a supported device
- Single tap on each parameter restores auto mode and live camera-driven value display
- RAW capture still succeeds after manual adjustments
- Switching lenses refreshes supported ranges without crashing or black-screening preview

## Files In Scope

Primary files expected to change in the next implementation step:
- `D:\Project\wCam\wCam\app\src\main\java\com\example\wcam\MainActivity.kt`
- `D:\Project\wCam\wCam\app\src\test\java\com\example\wcam\ExampleUnitTest.kt`

Possible new files if implementation benefits from small extracted helpers:
- `D:\Project\wCam\wCam\app\src\main\java\com\example\wcam\camera\PhotoParamMapper.kt`
- `D:\Project\wCam\wCam\app\src\main\java\com\example\wcam\camera\PhotoParamState.kt`

## Non-Goals

This task does not include:
- A full activity-to-controller refactor
- Rebuilding the parameter bar UI
- JPEG parameter control
- Video parameter control
- Histogram implementation
- Persisting parameter presets across launches


