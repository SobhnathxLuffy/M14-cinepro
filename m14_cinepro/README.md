# M14 CinePro (`com.m14.cinepro`)

A production-grade **Blackmagic-inspired cinema camera** for the **Samsung Galaxy M14 5G**
(`SM-M146B` / Exynos 1330 / Mali-G68 MP2), built strictly on the empirical law documents in
[`docs/`](docs/) and wired for the verified **Architecture A split pipeline**.

Built by the cloud "Principal Android Cinema Camera Architect" pipeline. **Status: compiles to a
signed APK (AGP 8.4.1 / Gradle 8.7 / Kotlin 1.9.24, minSdk 28, targetSdk 34, zero androidx).**
On-device verification happens via the local PC agent (ADB).

## Architecture (Architecture A — MANDATORY per docs/M14_SYSTEM_STRESS_REPORT.md)

```
                        ┌────────────────────────────────────────────────┐
 Camera 0 (S5KJN1) ──►  │ CameraCaptureSession (ISP hardware splitter)   │
                        │   ├─ Output 1: 4K/2.5K/1080p/720p60 surface ──►│──► c2.exynos.hevc.encoder
                        │   │   (ZERO GPU copy — direct to encoder)      │    (persistent input surface)
                        │   └─ Output 2: 1920x1080 SurfaceTexture ──────►│──► Mali-G68 MP2 GLES 3.2 viewfinder
                        └────────────────────────────────────────────────┘    (renderer thread priority -19)
```

- **Recording path never touches the GPU** — the ISP feeds the Exynos MFC HEVC encoder directly
  (`c2.exynos.hevc.encoder`, up to 81 Mbps verified at 4K 24/30). Synthetic CFR presentation
  timestamps (`firstPtsUs + frameIndex * frameDurationUs`) guarantee zero VFR drift.
- **Viewfinder path (1080p)** runs the exact Blackmagic GLSL 320 es chain, in order:
  `OES sample → WB RGB trim → 3D LUT (33³, trilinear + half-voxel) → False Color (17-band CIE L*)`
  `→ Zebras (marching diagonal) → Focus Peaking (exact 5×5 Laplacian)` + a GLES 3.1 compute
  64-bin RGB histogram on the graded overlay-free frame at 10 fps.
- **720p 60 fps Slow-Mo** uses `createConstrainedHighSpeedCaptureSession` (`Range(60, 60)`,
  verified 60.06 fps output).
- **500 Hz Gyroflow `.gcsv` logger** (LSM6DSL gyro+accel, `SENSOR_DELAY_FASTEST`) + Blackmagic
  cinema slate JSON per take.
- **Stereo 48 kHz AAC-LC 256 kbps** audio with live VU/PPM metering (exact dB law,
  peak-hold decay, −18/−20 dBFS references).

## Hardware law honored (docs/M14_VERIFIED_TRUTH.md)

| Rule | Implementation |
|---|---|
| Manual shutter/ISO works only with `AE_MODE_OFF` (stealth override) | `ExposureController` — verified 30 µs–100 ms, ISO 100–3200 |
| 110 µs headroom snap | `Models.applyHeadroomSnap` — prevents dropped frames at max duty cycle |
| Angle-invariant shutter (default 180°) | FPS change preserves angle, recomputes exposure |
| NEVER `AF_MODE_OFF` / `LENS_FOCUS_DISTANCE` on Camera 0 | `FocusController` — AF AUTO/CONTINUOUS + weight-1000 ROIs only |
| NEVER `NOISE_REDUCTION_MODE_OFF` | Always `NOISE_REDUCTION_MODE_FAST` |
| 1080p60 is HAL-blocked | Not offered; 720p60 high-speed is the 60 fps path |
| Kelvin/Tint via exact `dt0.java` math | `color/ColorScience.kt` — Planckian rationals, golden-section CCT (30×, 0.618033988749895), tint offset −130, mired [40,1000] |

## Build & install

```bash
cd m14_cinepro
./build_and_install.sh                # Gradle path: checks JDK17+, bootstraps SDK if missing,
                                      # assembles + zipalign + apksigner + adb install -r + launch
./build_manual_toolchain.sh           # no-Gradle path: aapt2 + kotlinc + d8 + zipalign + apksigner
                                      # (mirrors the m14_verifier pipeline)
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk` → signed as `m14_cinepro-signed.apk`.

## Controls

- **Top bar**: Format · Bitrate · Shutter (angle°/speed toggle) · ISO/AE-lock/EV · WB (presets +
  Kelvin 2500–10000 K + Tint ±50 + AWB lock) · Timecode · Battery/Temp/Free-GB.
- **Bottom bar**: PEAK (colored lines / peak + color + strength) · ZEBRA (95/90/Mid-Grey/MG+1) ·
  FALSE COLOR · LUT (Neutral 709 / Teal&Orange / Warm Fade / Mono / import `.cube`) ·
  GUIDES (2.39 / 2.00 / 1.85 / 4:3 / 9:16 + thirds/crosshair/horizon) · DESQUEEZE (1.0–2.0x) ·
  **F1/F2/F3 focus-pull markers** (long-press = save current tap point, tap = AF-lock there).
- **Right strip**: REC (red tally) · SLATE (reel/scene/take/good-take/director/operator modal) ·
  EIS (10% crop gyro counter-rotation) · TORCH. Swipe up/down hides the HUD.
- **Scopes**: bottom-left 64-bin RGB histogram (GLES 3.1 compute), bottom-right stereo VU/PPM.

## Source map

```
app/src/main/java/com/m14/cinepro/
├── MainActivity.kt          wiring + permissions + lifecycle
├── core/     Models.kt (settings contract + numeric law) · EngineListener.kt
├── camera/   CameraEngine · ExposureController · FocusController · WhiteBalanceController
├── recording/RecordingController (CFR + mux) · AudioEngine (VU/PPM) · GyroflowLogger (.gcsv)
│              SlateManager · StorageHelper (MediaStore/Movies/DCIM)
├── sensor/   GyroEisController (10% crop counter-rotation matrix)
├── gl/       GlViewfinder · ViewfinderRenderer (exact stage4 assembly) · LutEngine (33³ + .cube)
│              HistogramEngine (compute + readback)
├── color/    ColorScience.kt (exact dt0 port)
├── hud/      HudController · HistogramView · AudioMeterView · GuidesOverlayView ·
│              FocusOverlayView · SlateDialog
└── system/   SystemMonitor
app/src/main/assets/glsl/     verbatim stage4 shaders (+ wb_trim, histogram compute)
```
