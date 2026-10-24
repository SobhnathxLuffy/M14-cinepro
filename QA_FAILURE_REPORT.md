# M14 CinePro: Comprehensive Live On-Device QA Failure & Hardware Diagnostics Report

**Date:** October 24, 2026  
**Auditor:** Lead QA & Hardware Diagnostics Engineer  
**Target Hardware:** Samsung Galaxy M14 5G (`SM-M146B` / `s5e8535`)  
**Chipset & ISP:** Samsung Exynos 1330 (Octa-core 2x2.4 GHz Cortex-A78 & 6x2.0 GHz Cortex-A55, Mali-G68 MP2)  
**Primary Rear Sensor:** 50 MP Samsung ISOCELL S5KJN1 (`1/2.76"`, 0.64µm, f/1.8, 26mm equivalent, PDAF)  
**Host Environment:** Local Linux Workstation via Android Debug Bridge (`adb`)  
**Application Package:** `com.m14.cinepro` (M14 CinePro Version 2)  
**Target Revision:** Version 3 Architecture & Defect Remediation  

---

## Executive Summary

An exhaustive, automated live on-device diagnostic test suite was executed against Version 2 of **M14 CinePro** running directly on a connected physical **Samsung Galaxy M14 5G (`SM-M146B`)**. This hardware testing was paired with a deep line-by-line audit of the complete Kotlin, GLSL, and AndroidManifest codebase (`m14_cinepro/`).

The investigation has uncovered the precise hardware, algorithmic, and UI architectural root causes for all reported defects:

1. **Viewfinder 180° Inversion in Landscape**: `ViewfinderRenderer.kt` applies an inverted coordinate transform `(360 - rotation*90)%360` instead of accounting for the M14's hardware `SENSOR_ORIENTATION = 90`. When held in `Surface.ROTATION_90`, it applied a 270° counter-rotation instead of 90°, producing an exact $270^\circ - 90^\circ = 180^\circ$ upside-down image.
2. **720p 60fps Total Failure**: The Exynos 1330 HAL (`media.camera`) does **NOT** support 60fps in `android.control.availableHighSpeedVideoConfigurations`. It strictly supports `[30, 120]` and `[120, 120]`. Furthermore, `aeAvailableTargetFpsRanges` caps regular capture sessions at 30fps (`[30, 30]`). When 60fps was requested, `CameraEngine.kt` passed an illegal `Range(60, 60)` into `createHighSpeedRequestList()`, triggering an uncaught framework `IllegalArgumentException`.
3. **Tint / White Balance Corruption & Permanent Wedging**:
   - **Double Gain Application**: White balance is applied in hardware by the Camera2 ISP via `CaptureRequest.COLOR_CORRECTION_GAINS`, and then multiplied **again** in the OpenGL ES fragment shader (`wb_trim_effect.glsl`), exponentially squaring gain shifts.
   - **Invalid Blackmagic Calibration Constant**: `ColorScience.kt` hardcodes a `-130.0f` offset (`DEVICE_TINT_OFFSET`) borrowed from Blackmagic cinema cameras. On the Samsung S5KJN1 sensor, this artificially halves the green channel at neutral tint (`tint = 0`) and scales red/blue by 1.92x.
   - **Immediate Unchecked Persistence**: Sliders immediately write through to `m14_cinepro_settings.xml`. On restart, `SettingsStore` reloads the corrupted state directly into `KELVIN` mode without validation.
   - **Zero Reset Controls**: No parameter panel, slider, or menu contains a "Reset" button.
4. **Missing Media Pool / Video Playback Engine**: The recording pipeline successfully writes 4K HEVC 80Mbps MP4 files, Gyroflow GCSV motion logs, and JSON slates to `/sdcard/Movies/M14_CinePro/`. However, there is zero Media Pool implementation in the app — no button in `HudController.kt`, no gallery UI, no `VideoView`/`MediaPlayer` playback activity, and no clip metadata browser.
5. **Widespread UI & Feature Glitches**: Tap focus markers (F1–F3) and Anamorphic De-Squeeze chips are non-functional no-ops; top bar pill rows crowd and collapse the center timecode; camera notch safe insets are completely unhandled; and over 12 silent exception-swallowing blocks mask critical errors.

---

## PHASE 1: Reset App State & Baseline Launch Audit

### 1. Diagnostic Execution & Clean Slate
The application state was wiped clean via ADB to establish a verified baseline:
```bash
adb shell pm clear com.m14.cinepro
adb shell pm grant com.m14.cinepro android.permission.CAMERA
adb shell pm grant com.m14.cinepro android.permission.RECORD_AUDIO
adb shell pm grant com.m14.cinepro android.permission.MANAGE_EXTERNAL_STORAGE
adb logcat -c
adb shell am start -n com.m14.cinepro/.MainActivity
```

### 2. Live Logcat & System Observations
```
I ActivityManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 cmp=com.m14.cinepro/.MainActivity}
I ActivityTaskManager: Displayed com.m14.cinepro/.MainActivity: +412ms
D CompatibilityChangeReporter: Compat change id reported: 171228096; UID 10261; state: ENABLED
W WindowManager: Activity com.m14.cinepro/.MainActivity has android:showWhenLocked="false"
W AudioService: Audio record op 27 silenced for uid 10261 (recording muted while backgrounded)
```

### 3. Key Baseline Findings
- **Lock Screen / Keyguard Lifecycle Flaw**: `AndroidManifest.xml` does not set `android:showWhenLocked="true"`. If the Galaxy M14 screen is locked or keyguard is active (`deviceLocked=1`), the system `WindowManager` immediately transitions `MainActivity` to `WindowStopped`, preventing `GLSurfaceView` surface creation.
- **Microphone Op 27 Silencing**: Audio recording requires foreground service elevation when recording with screen dimmed or locked; otherwise, Samsung One UI audio policy mutes PCM capture streams.

---

## PHASE 2: Audit Viewfinder 180° Flip Bug

### 1. Hardware Characteristics
- **Sensor Orientation**: `CameraCharacteristics.SENSOR_ORIENTATION` for Camera 0 = `90°`.
- **Natural Display Orientation**: Portrait (`ROTATION_0`).
- **Activity Manifest Orientation**: `sensorLandscape` (Device outputs `Surface.ROTATION_90` when held landscape right, or `Surface.ROTATION_270` when held landscape left).

### 2. Code Inspection & Mathematical Root Cause
Inspecting `ViewfinderRenderer.kt` (lines 270–295):
```kotlin
// ViewfinderRenderer.kt (Version 2 Buggy Implementation)
val rotation = try { host.display?.rotation ?: Surface.ROTATION_0 } catch (t: Throwable) { Surface.ROTATION_0 }
if (rotation != displayRotationApplied) {
    displayRotationApplied = rotation
    val degrees = ((360f - rotation * 90f) % 360f + 360f) % 360f
    Matrix.setIdentityM(displayRotMatrix, 0)
    Matrix.translateM(displayRotMatrix, 0, 0.5f, 0.5f, 0f)
    Matrix.rotateM(displayRotMatrix, 0, degrees, 0f, 0f, 1f)
    Matrix.translateM(displayRotMatrix, 0, -0.5f, -0.5f, 0f)
}
```

#### The Geometric Inversion Proof:
1. The camera sensor is mounted at `90°` clockwise relative to portrait natural orientation.
2. The `SurfaceTexture` transform matrix (`getTransformMatrix()`) maps sensor coordinates to portrait buffer coordinates.
3. In `Surface.ROTATION_90` (landscape default), the screen coordinate system is rotated 90° counter-clockwise relative to portrait.
4. To align the `90°` sensor buffer with a `ROTATION_90` display, the required texture rotation is **`90°`**, NOT `270°`.
5. Version 2 evaluated:
   $$\text{degrees} = (360 - 1 \times 90) = 270^\circ$$
6. **Result**: The texture was rotated $270^\circ$ instead of $90^\circ$. The net angular discrepancy is:
   $$270^\circ - 90^\circ = 180^\circ$$
   The viewfinder rendered completely upside down!

### 3. Required Fix for Version 3
Map display rotation angles explicitly:
```kotlin
val degrees = when (rotation) {
    Surface.ROTATION_90 -> 90f
    Surface.ROTATION_270 -> 270f
    Surface.ROTATION_180 -> 0f
    else -> 180f
}
```

---

## PHASE 3: Audit 720p 60fps Failure (HAL vs. Engine Breakdown)

### 1. Hardware HAL Capability Audit
A complete query of Camera 0 characteristics via `adb shell dumpsys media.camera` revealed the exact HAL capabilities of the Exynos 1330 / S5KJN1 subsystem:

```
android.control.aeAvailableTargetFpsRanges (10014): int32[16]
  [15, 15] [15, 20] [20, 20] [24, 24] [8, 30] [10, 30] [15, 30] [30, 30]

android.control.availableHighSpeedVideoConfigurations (10023): int32[10]
  [1280, 720, 30, 120, 2]
  [1280, 720, 120, 120, 2]
```

#### Critical Hardware Facts:
1. **Regular Capture Sessions (`SESSION_REGULAR`)**: Max target FPS is strictly **30 fps**. There is no 60 fps entry in `aeAvailableTargetFpsRanges`.
2. **Constrained High Speed Sessions (`SESSION_HIGH_SPEED`)**:
   - Supported resolution: Strictly **1280x720**.
   - Supported High Speed FPS Ranges: Strictly `[30, 120]` and `[120, 120]`.
   - **There is NO 60 fps high-speed configuration on this device.**

### 2. Code Defect in `CameraEngine.kt`
Inspecting `CameraEngine.kt` (lines 48–50 and 561–575):
```kotlin
private const val HFR_FPS = 60

private fun hfrFpsRange(): Range<Int> {
    val ranges = highSpeedFpsRanges
    if (ranges != null) {
        var minLower = -1
        var bestWithMinLower: Range<Int>? = null
        for (range in ranges) {
            if (range.upper != HFR_FPS) continue  // HFR_FPS is 60; ranges are [30,120] and [120,120]!
            if (range.lower == HFR_FPS) return range
            if (minLower < 0 || range.lower < minLower) {
                minLower = range.lower
                bestWithMinLower = range
            }
        }
        if (bestWithMinLower != null) return bestWithMinLower
    }
    return Range(HFR_FPS, HFR_FPS)  // Falls through to Range(60, 60)!
}
```

### 3. The Fatal Crash Sequence
1. In `createSessionOutputs()` ([CameraEngine.kt:420](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/camera/CameraEngine.kt#L420)), `highSpeed` triggers `cameraDevice.createConstrainedHighSpeedCaptureSession(outputs, stateCallback, camHandler)`.
2. When configured, `setRepeating()` executes:
   ```kotlin
   if (targetSession is CameraConstrainedHighSpeedCaptureSession) {
       val list = targetSession.createHighSpeedRequestList(builder.build())
       targetSession.setRepeatingBurst(list, captureCallback, handler)
   }
   ```
3. `builder` contains `CONTROL_AE_TARGET_FPS_RANGE = Range(60, 60)`.
4. Inside Android's `CameraConstrainedHighSpeedCaptureSessionImpl.java`:
   ```java
   if (!Arrays.asList(mFpsRanges).contains(fpsRange)) {
       throw new IllegalArgumentException("Fps range " + fpsRange + " is not one of high speed fps ranges");
   }
   ```
5. **Crash / Abort**: The session configuration throws `IllegalArgumentException: Fps range [60, 60] is not one of the available high speed fps ranges [30, 120], [120, 120]`, causing the camera pipeline to fail with `"SESSION FAILED"`.

### 4. Required Fix for Version 3
- For 720p Slow-Motion: Target **120 fps** (`Range(120, 120)`) using `CameraConstrainedHighSpeedCaptureSession`.
- For standard high-rate video: If the user selects 60 fps, the app must gracefully report that the Galaxy M14 5G hardware HAL only supports 120 fps HFR slow motion or 24/30 fps standard cinematic recording.

---

## PHASE 4: Audit Tint / WB / Settings Corruption & Missing Reset

### 1. Tint & White Balance Pipeline Trace
```
[HudController.kt (panelTint)]
       │
       ▼
[SettingsStore (tint, wbPreset)] ──> SharedPreferences (m14_cinepro_settings.xml)
       │
       ├───> [CameraEngine / WhiteBalanceController]
       │           │
       │           └──> CaptureRequest.COLOR_CORRECTION_GAINS (Hardware ISP)
       │
       └───> [ViewfinderRenderer / wb_trim_effect.glsl]
                   │
                   └──> color.rgb = clamp(color.rgb * wbGains, 0.0, 1.0) (DOUBLE APPLIED!)
```

### 2. Four Catastrophic Color Flaws
1. **Double Gain Amplification**: The Camera2 hardware ISP already multiplies sensor pixels by `COLOR_CORRECTION_GAINS`. Then `wb_trim_effect.glsl` multiplies `color.rgb * wbGains` a second time in the OpenGL fragment shader, squaring gains and blowing out highlights.
2. **Hardcoded Blackmagic Offset (`DEVICE_TINT_OFFSET = -130.0f`)**:
   In `ColorScience.kt:74`:
   $$fExp = \exp\left(-\frac{\text{tint} - (-130)}{200}\right) = \exp\left(-\frac{\text{tint} + 130}{200}\right)$$
   At neutral `tint = 0`, $fExp = \exp(-0.65) \approx 0.5220$. The green channel is cut in half, forcing normalizer $k \approx 1.915$, which inflates red and blue gains by 192% at neutral 5600K!
3. **Matrix Inversion Denominator Collapse**: At extreme color temperatures or with uncalibrated CCMs, Rec.709 Planckian white projections produce zero or negative denominators in `rReq` or `bReq`, leading to `Float.NaN` or negative gains that clamp the viewfinder to pitch black.
4. **Permanent SharedPreferences Wedging**: Every slider move immediately persists `wbPreset = KELVIN` and `tint` to disk. Because `SettingsStore.init` does not validate these values on startup, the broken color state persists forever across app restarts.

### 3. Missing Reset Controls
No "Reset to Default" button exists anywhere in the UI:
- `panelTint`: No reset to 0.
- `panelWb`: No reset to 5600K or Auto.
- `panelShutter`: No reset to 180.0°.
- `panelIso`: No reset to Auto.
- `panelIris`: No reset to 0.0 EV.
- Top / Bottom Bar: No global "Reset All Settings to Factory Defaults" action.

---

## PHASE 5: Audit In-App Media Pool & Playback

### 1. Recorded Filesystem Verification
Physical files in `/sdcard/Movies/M14_CinePro/` were verified on the device:
```bash
adb shell ls -lh /sdcard/Movies/M14_CinePro/
```
Output:
```
-rwxrwx--- 1 u0_a261 media_rw 39M 2026-10-24 09:37 M14_R1_S1_T10_20261024_093743.mp4
-rwxrwx--- 1 u0_a261 media_rw 182K 2026-10-24 09:37 M14_R1_S1_T10_20261024_093743.gcsv
-rwxrwx--- 1 u0_a261 media_rw 431 2026-10-24 09:37 M14_R1_S1_T10_20261024_093743_slate.json
```

`ffprobe` inspection of `M14_R1_S1_T10_20261024_093743.mp4`:
- **Video Stream**: HEVC (Main), `3840x2160`, 80.7 Mb/s, 24.0 fps.
- **Audio Stream**: AAC (LC), 48000 Hz, stereo, 255 kb/s.
- **Motion Metadata**: Synchronized Gyroflow `.gcsv` file written with IMU angular velocity and acceleration samples.
- **Slate Metadata**: Valid JSON containing scene, take, lens, Kelvin, ISO, and shutter speed.

### 2. Missing In-App Playback Architecture
An exhaustive search of `HudController.kt`, `MainActivity.kt`, and `res/layout/` confirmed:
- **Zero Media Pool UI**: No gallery button exists in either the top bar, bottom bar, or transport column.
- **No Clip Playback Component**: There is no `VideoView`, `MediaPlayer`, or ExoPlayer integration in the project.
- Recorded clips cannot be previewed, reviewed, scrubbed, or tagged as "Good Take" after shooting without exiting to a third-party gallery app.

---

## PHASE 6: Full Codebase & UI Glitch Inventory

### Category A: Unfinished Buttons & No-Op Features
1. **Anamorphic De-Squeeze No-Op** ([HudController.kt:1316](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L1316)): Selecting 1.33x or 2.0x only displays a text badge in `GuidesOverlayView`. The OpenGL texture matrix in `ViewfinderRenderer.kt` is never scaled; the viewfinder preview remains completely unstretched.
2. **Focus Markers (F1–F3) Fail to Drive Focus** ([MainActivity.kt:418](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/MainActivity.kt#L418)): Because `LENS_FOCUS_DISTANCE` cannot be written on the M14 HAL, tapping F1–F3 merely draws a box on screen without driving focus to the stored point.
3. **AE Lock No-Op in Manual Exposure** ([HudController.kt:1095](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L1095)): `AE LOCK` is displayed when manual exposure is active, but `ExposureController.kt` skips setting `CONTROL_AE_LOCK` when `CONTROL_AE_MODE_OFF`.
4. **AWB Lock Shown in Kelvin Mode** ([HudController.kt:1158](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L1158)): `AWB LOCK` is present even when manual Kelvin mode is active, despite `CONTROL_AWB_MODE_OFF` ignoring lock requests.

### Category B: Broken Slider Ranges & Calculations
1. **Linear ISO Slider** ([HudController.kt:1099](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L1099)): Maps linearly from 100 to 3200 in steps of 100, dedicating 50% of track length to the single stop between 1600 and 3200 while compressing 100 to 800 into 22%.
2. **Linear Kelvin Slider** ([HudController.kt:1145](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L1145)): Maps linearly from 2500K to 10000K instead of perceptually uniform Mireds ($10^6/K$).
3. **Integer Shutter Angle** ([HudController.kt:1017](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L1017)): Restricts shutter angle to integer degrees, preventing the exact 172.8° setting required to suppress 50Hz lighting flicker at 24fps.
4. **Premature Zebra Rescaling** ([Models.kt:149](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/core/Models.kt#L149)): Multiplies threshold by $0.9023$ in full-range float RGB, causing zebras to trigger prematurely on non-clipping skin tones.
5. **Histogram Disappearance** ([HistogramView.kt:60](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HistogramView.kt#L60)): 64 bins with 2dp gaps require 126dp. If the view width is below 136dp, bar width calculates $\le 0.0$ and the histogram vanishes.

### Category C: UI Collisions & Layout Flaws
1. **Top Bar Pill Crowding**: The top pill row requires $>768\text{dp}$ min width. When pills display active strings (`4K HEVC 80M`, `180.0° (1/48)`), the width exceeds 920dp, crushing the center timecode or pushing rightmost pills off screen.
2. **Display Cutout (Notch) Collision** ([MainActivity.kt:125](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/MainActivity.kt#L125)): `MainActivity` does not consume `WindowInsets.getDisplayCutout()`. In landscape orientation, the teardrop camera notch cuts directly into `fpsPill` on the left or the record button on the right.
3. **Bottom Panel Slider Overlap** ([HudController.kt:305](file:///home/sobhnath/.t3/scratch/2026-10-24-open-m14-cinepro-app-src-feecdad5/m14_cinepro/app/src/main/java/com/m14/cinepro/hud/HudController.kt#L305)): Floating sliders opened from bottom pills overlap directly on top of `HistogramView` and `AudioMeterView`.

### Category D: Silent Catch Blocks
| File & Line | Swallowed Exception | Consequence |
| :--- | :--- | :--- |
| **`Models.kt:329–368`** | `IllegalArgumentException`, `NullPointerException` (12 places) | Corrupted enum values in SharedPreferences silently ignored. |
| **`MainActivity.kt:133`** | `Throwable` | Swallows window insets controller retrieval failures. |
| **`MainActivity.kt:471`** | `SecurityException` | Swallows SAF URI persistable permission rejections. |
| **`CameraEngine.kt:327`** | `Throwable` | Swallows stream configuration map lookup errors. |
| **`ExposureController.kt:89`** | `Throwable` | Swallows camera characteristics access exceptions. |
| **`WhiteBalanceController.kt:98, 105, 126, 143`** | `Throwable` (4 places) | Swallows CCM transforms, gains reads, and rational divide-by-zero errors. |
| **`RecordingController.kt:307`** | `Throwable` | Swallows buffer acquisition exceptions; fails silently on frame drainage. |
| **`GyroflowLogger.kt:225, 230`** | `IOException` (2 places) | Swallows writer close failures during telemetry streaming. |
| **`StorageHelper.kt:283`** | `Exception` | Swallows ContentResolver metadata query failures. |

---

## Actionable Version 3 Remediation Roadmap

For our Cloud Architect implementing Version 3:

1. **Viewfinder Rotation Matrix**: Update `ViewfinderRenderer.kt` to map `ROTATION_90 -> 90f` and `ROTATION_270 -> 270f`.
2. **720p HFR Alignment**: Restructure `CameraEngine.kt` to only configure high-speed video using supported hardware configurations (`[1280x720 @ 120fps]`), or restrict regular sessions to 30fps with clear UI indications.
3. **Single-Stage White Balance**: Disable `wbTrimVisible` in the GLSL viewfinder for standard preview, allowing the Camera2 ISP `COLOR_CORRECTION_GAINS` to control white balance. Remove `DEVICE_TINT_OFFSET = -130.0f` and implement symmetric tint scaling `fExp = exp(-(tint / 100.0f))`.
4. **Settings Recovery & Reset Controls**: Add explicit "Reset" buttons to all parameter panels, add a global "Reset to Defaults" option in the HUD, and validate SharedPreferences bounds in `SettingsStore.init`.
5. **In-App Media Pool**: Implement a dedicated Media Pool dialog/activity featuring a grid thumbnail gallery of `/sdcard/Movies/M14_CinePro/`, video playback with scrubber, audio playback, slate metadata display, and "Good Take" flag toggling.
6. **Responsive HUD & Notch Insets**: Apply `displayCutout` safe insets to `hud_root`, wrap the top bar in a `HorizontalScrollView` or use a compact cinema layout, and scale the OpenGL preview texture when anamorphic de-squeeze factors are selected.
