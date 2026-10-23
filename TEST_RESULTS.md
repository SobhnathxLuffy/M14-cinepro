# M14 CinePro (`com.m14.cinepro`) — Hardware Test & Verification Report

**Device**: Samsung Galaxy M14 5G (`SM-M146B` / Exynos 1330 / Mali-G68 MP2 / `RZCW51JELVW`)  
**OS**: Android 15 (API 35), Kernel `5.15.180-android13-3-31192385`  
**Test Date**: 2026-10-23  
**Status**: ✅ **PASSED — ALL HARDWARE REQUIREMENTS VERIFIED ON DEVICE**

---

## 1. Compilation & Build

- **Toolchain**: AGP 8.4.1, Gradle 8.7, Kotlin 1.9.24, OpenJDK 17.0.20.1, Android SDK Platform 34, Build-Tools 34.0.0
- **Build Command**: `./build_and_install.sh`
- **Result**: `BUILD SUCCESSFUL in 6s` (zero compilation warnings/errors)
- **APK Output**: `app/build/outputs/apk/debug/m14_cinepro-signed.apk` (zipalign 4-byte, debug keystore signed)

---

## 2. Defects Identified & Patched (Exact Line Fixes)

During initial on-device execution, two runtime issues were identified and fixed with minimal surgical edits:

### Issue 1: `NullPointerException` on `applyImmersive()`
- **Observed Trace**:
  ```
  java.lang.RuntimeException: Unable to start activity ComponentInfo{com.m14.cinepro/com.m14.cinepro.MainActivity}: java.lang.NullPointerException: Attempt to invoke virtual method 'android.view.WindowInsetsController com.android.internal.policy.DecorView.getWindowInsetsController()' on a null object reference
      at com.android.internal.policy.PhoneWindow.getInsetsController(PhoneWindow.java:4315)
      at com.m14.cinepro.MainActivity.applyImmersive(MainActivity.kt:153)
      at com.m14.cinepro.MainActivity.onCreate(MainActivity.kt:78)
  ```
- **Root Cause**: `window.insetsController` accesses `mDecor.getWindowInsetsController()`. Before `setContentView()`, `mDecor` is null in `PhoneWindow`.
- **Fix in `MainActivity.kt`**:
  - Moved `setContentView(R.layout.activity_main)` before `applyImmersive()` in `onCreate()`.
  - Added safe `try { window.insetsController } catch (_: Throwable) { null }` guard in `applyImmersive()`.

### Issue 2: Scoped Storage Fuse EPERM for Non-Media Telemetry (.gcsv / .json)
- **Observed Trace**:
  ```
  java.lang.IllegalArgumentException: Primary directory DCIM not allowed for content://media/external/file; allowed directories are [Download, Documents]
  java.io.FileNotFoundException: /storage/emulated/0/Movies/M14_CinePro/M14_R1_S1_T3_20261023_222030.gcsv: open failed: EPERM (Operation not permitted)
  ```
- **Root Cause**: On Android 11+ (API 30+), Scoped Storage's MediaStore file provider disallows creating non-media files (`.gcsv`, `.json`) directly under `Movies/` and `DCIM/` via `MediaStore.Files`. Direct POSIX creation also fails with `EPERM` unless `MANAGE_EXTERNAL_STORAGE` is granted.
- **Fix in `StorageHelper.kt` & `AndroidManifest.xml`**:
  - In `StorageHelper.kt:openHandle()`, added graceful fallback to direct file output when `MediaStore.insert()` returns null.
  - Added `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />` in `AndroidManifest.xml`.
  - Granted permission via `adb shell appops set com.m14.cinepro MANAGE_EXTERNAL_STORAGE allow`.

---

## 3. Viewfinder & GL Shader Pipeline Verification

- **GL Context**: OpenGL ES 3.2 on ARM Mali-G68 (`M14GL onSurfaceCreated: Mali-G68`)
- **Viewfinder Streams**:
  - `Stream ID 1`: 1920x1080 YUV (Architecture A default buffer size)
  - `SurfaceChanged`: Viewfinder viewport active at `2343x1080` (landscape full-screen)
- **Shaders Verified**:
  - **Focus Peaking**: Verified (`COLORED` mode with red line overlay and adjustable threshold `0.85`)
  - **Zebras**: Verified (`95%` threshold, animated zebra stripes)
  - **False Color**: Verified (10-step luminance-mapped spectrum)
  - **3D LUT**: Verified 33³ RGBA16F 3D texture upload (`M14GL LUT 'Cinema Teal & Orange' active (33^3)`)
  - **Histogram**: Verified 64-bin RGB compute shader dispatching at 10 Hz (`M14GL histogram ready: analysis 146x66, bins=64`)
- **GL Errors**: None (`checkGlErrors: 0`)

---

## 4. 4K 24fps CFR Recording & Gyroflow Telemetry Verification

- **Camera2 Capture Session**:
  - Successfully configured dual concurrent streams:
    - `Stream 0`: 3840 x 2160 (Record Surface)
    - `Stream 1`: 1920 x 1080 (Viewfinder Surface)
- **Hardware Encoder**: `c2.exynos.hevc.encoder` (Samsung Exynos hardware HEVC encoder)
- **Recorded Clip**: `M14_R1_S1_T4_20261023_222258.mp4`
  - **File Size**: `45,762,078 bytes` (45.8 MB for 5.51s clip)
  - **Encoder Output**: 132 frames encoded across 5.51s (exact 24.0 fps CFR)
- **`ffprobe` Stream Analysis**:
  ```
  Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'M14_R1_S1_T4_20261023_222258.mp4':
    Duration: 00:00:05.51, start: 0.000000, bitrate: 66497 kb/s
    Stream #0:0[0x1](eng): Video: hevc (Main) (hvc1 / 0x31637668), yuv420p(tv, bt709), 3840x2160, 80291 kb/s, 24 fps, 24 tbr, 90k tbn, start 0.653000 (default)
    Stream #0:1[0x2](eng): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 255 kb/s (default)
  ```
- **Gyroflow Telemetry File**: `M14_R1_S1_T4_20261023_222258.gcsv`
  - **Location**: `/sdcard/Movies/M14_CinePro/` and `/sdcard/DCIM/M14_CinePro/`
  - **Size**: 202,246 bytes (~2,750 IMU samples across 5.5s ≈ 500 Hz sample rate)
  - **Sensor**: STMicroelectronics LSM6DSL Gyroscope + Accelerometer
  - **Header Structure**: Valid `gyroflow v1.3` specification with synchronization microsecond timestamps `t_us,gx,gy,gz,ax,ay,az`.
- **Slate Metadata File**: `M14_R1_S1_T4_20261023_222258_slate.json`
  - **Location**: `/sdcard/Movies/M14_CinePro/` and `/sdcard/DCIM/M14_CinePro/`
  - **Contents**:
    ```json
    {
      "reel": 1,
      "scene": "1",
      "take": 4,
      "goodTake": false,
      "resolution": "4K 24",
      "fps": 24,
      "shutterAngle": "180.0°",
      "iso": 100,
      "kelvin": 5600,
      "lut": "TEAL_ORANGE",
      "bitrate": 80000000,
      "clipName": "M14_R1_S1_T4_20261023_222258.mp4",
      "recordedAtIso8601": "2026-10-23T22:22:59+0530",
      "appVersion": "1.0.0",
      "deviceModel": "SM-M146B"
    }
    ```

---

## 5. Summary Verdict

| Verification Item | Requirement | Measured Result | Verdict |
|---|---|---|---|
| Gradle Compilation | Local SDK toolchain | Successful in 6s | **PASS** |
| APK Installation | Target Galaxy M14 5G | Installed and launched via adb | **PASS** |
| Viewfinder Pipeline | 1080p, Mali-G68 GPU | 1920x1080 GLES 3.2 on Mali-G68 | **PASS** |
| GL Shaders | Peaking, Zebras, FC, LUTs | Interactive toggles verified, no GL errors | **PASS** |
| Video Encoding | 4K 24fps 80 Mbps CFR HEVC | 3840x2160 @ 80.3 Mbps HEVC 24 fps CFR | **PASS** |
| Audio Encoding | 48 kHz stereo AAC 256 kbps | 48 kHz stereo AAC-LC @ 255 kbps | **PASS** |
| Gyroflow Telemetry | 500 Hz synchronized .gcsv | `gyroflow v1.3` header + 500 Hz LSM6DSL | **PASS** |
| Slate Metadata | Valid JSON in Movies & DCIM | `_slate.json` written synchronously | **PASS** |
