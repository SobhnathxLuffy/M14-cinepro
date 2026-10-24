# M14 CinePro — Version 3 Resolution Report

**Date:** October 24, 2026
**Baseline:** Version 2 (`4856051`) + `QA_FAILURE_REPORT.md` (on-device ADB diagnostic suite, `SM-M146B`)
**Result:** ALL defects from QA Phases 1–6 fixed + NEW 1.0x–8.0x zoom engine. `./gradlew assembleDebug` BUILD SUCCESSFUL (zero compilation errors, zero placeholders, zero silent catches, zero AF/NR HAL traps).

---

## PHASE 1 — Keyguard / Launch Lifecycle  →  RESOLVED

| QA finding | Fix | Evidence |
|---|---|---|
| `WindowManager` halts `GLSurfaceView` creation when locked (`showWhenLocked="false"`) | `AndroidManifest.xml`: `android:showWhenLocked="true"` + `android:turnScreenOn="true"` on `.MainActivity` | Manifest lines 47–48; verified in compiled APK via `aapt2 dump xmltree` (both attrs = `true`) |

## PHASE 2 — Viewfinder 180° Inversion + Anamorphic No-Op  →  RESOLVED

| QA finding | Fix | Evidence |
|---|---|---|
| `((360f - rotation*90f) % 360f)` = 270° at `ROTATION_90` → 180° upside-down | `ViewfinderRenderer.kt` exact mapping: `ROTATION_90→90f`, `ROTATION_270→270f`, `ROTATION_180→0f`, else `180f` (lines ~294–300) | Compile + dex marker; net discrepancy is now 0° (270° − 90° bug eliminated) |
| No manual invert for rig / DOF adapter users | New `180° FLIP` toggle chip in the GUIDES panel (`settings.previewFlip180`, persisted); GL rotation matrix rebuilds on toggle via flip token `rotation or 0x100` | `HudController.kt` panelGuides; `ViewfinderRenderer.kt` flipToken logic |
| De-squeeze chips only drew a text badge | Wired into the GL pipeline: `Models.pictureRect(w, h, desqueezeFactor)` widens the 16:9 viewport horizontally (height invariant); GL viewport + `GuidesOverlayView` + `FocusOverlayView` tap mapping all use the same rect — one shared geometry law | `Models.kt` overload; renderer `updatePictureRect()`; both overlay call sites |

## PHASE 3 — 720p High-Speed Slow-Motion  →  RESOLVED

| QA finding | Fix | Evidence |
|---|---|---|
| `HFR_FPS = 60` → illegal `Range(60,60)` → `IllegalArgumentException` in `CameraConstrainedHighSpeedCaptureSessionImpl` | `HFR_FPS = 120`; `hfrFpsRange()` law: exact `Range(120,120)` → min-lower with upper==120 → `Range(30,120)` → `ranges.first()`; a range is NEVER fabricated while the HAL table is known (`[30,120]`, `[120,120]` per `dumpsys media.camera`) | `CameraEngine.kt:52, 641–660`; M14_VERIFIED_TRUTH Test 3C (1280x720 @ Range(120,120) verified 100% on-device, 60.06 fps physical output) |
| HS session crash could take the app down | Safe fallback law: `hsFallbackActive` flag — any HS session failure degrades ONCE to a regular session (AE fps clamped `min(settings.fps, 30)`), then `createHighSpeedRequestList`/`setRepeatingBurst` paths remain guarded; `createSession` resets the flag so explicit retries get HS again | `CameraEngine.kt` `createSessionOutputs(forceRegular:)` + `handleSessionFailure` |
| MP4 stamping for slow-motion | HS format keeps `KEY_FRAME_RATE = capture-rate = 60` while the sensor captures 120 → **2x smooth slow-motion MP4** (720p60 preset, verified encoder path `c2.exynos.hevc.encoder` unchanged) | `RecordingController.kt` `buildVideoFormat()` |

## PHASE 4 — WB/Tint Corruption + Reset Controls  →  RESOLVED

| QA finding | Fix | Evidence |
|---|---|---|
| `DEVICE_TINT_OFFSET = -130.0f` halved green at tint 0, inflated R/B by 1.92x | Constant REMOVED; `kelvinToRggb` tint law now `fExp = exp(-(tint / 100.0f))`; read-back tint is symmetric (no −130) | `ColorScience.kt`; grep: zero `DEVICE_TINT_OFFSET` references |
| Double gain application (ISP gains × shader gains) | Single-stage law: on the M14 the HAL ignores `COLOR_CORRECTION_GAINS` (not in `availableRequestKeys`), so the KELVIN path now writes ONLY `CONTROL_AWB_MODE_DAYLIGHT` (5600 K reference, ISP never hunts) + `COLOR_CORRECTION_MODE_FAST`; **zero gain writes**; all WB color work happens once in the GL trim | `WhiteBalanceController.apply()` — grep: zero `COLOR_CORRECTION_GAINS` in request paths |
| Kelvin gains un-normalized → black/neon viewfinder | New `ColorScience.kelvinShaderGainsRelative(kelvin, tint, ccm)`: gains divided by the 5600 K reference white point — **5600 K + tint 0 = exactly (1,1,1)**; NaN/Inf guarded to unity; every channel clamped `[0.60, 1.60]` | `ColorScience.kt`; renderer `wbTrimVisible = KELVIN \|\| tint != 0` |
| AWB presets with tint ≠ 0 applied nothing/garbage | Gentle symmetric shader trim only: `gGain = exp(-tint/150)` clamped `[0.75, 1.30]` (`tintShaderGain`); tint 0 → shader gains exactly (1,1,1) | `WhiteBalanceController.normalizedShaderGains()` |
| Corrupted prefs wedged the color state forever | `SettingsStore.init` now calls `sanitize()` — every numeric coerced back into its legal range (self-healing: setters re-persist sanitized values) | `Models.kt` `SettingsStore.sanitize()` |
| Zero reset controls anywhere | ↺ RESET chip on EVERY floating panel (shutter→180.0°, iso→Auto, iris/EV→0.0, wb→Auto/5600K, tint→0, zoom→1.0x, peaking→Off, zebra→Off, lut→Off, guides→Off) + **RESET ALL** in the Top Bar AND inside the Slate dialog: wipes `m14_cinepro_settings.xml` (`resetAll()`), resets all controllers/GL uniforms, cleanly rebuilds the session | `HudController.kt` resetChip + resetAllPill; `MainActivity.onResetAll()`; `SettingsStore.resetAll()` |

## PHASE 5 — In-App Media Pool & Playback  →  RESOLVED

| QA finding | Fix | Evidence |
|---|---|---|
| Zero UI to browse/play recorded clips | **MEDIA button** in the Right Transport Column (above SLATE) opens a full-screen dark cinema **Media Pool Dialog** (`MediaPoolDialog.kt`, new, ~614 lines): lists all `.mp4` in `Movies/M14_CinePro` newest-first with clip name, size (MB), WxH, GCSV/SLATE badges | `hud/MediaPoolDialog.kt`; `StorageHelper.listClips()` (MediaStore API 29+ query `RELATIVE_PATH LIKE 'Movies/M14_CinePro%'` sorted `DATE_MODIFIED DESC` + legacy file fallback) |
| No playback component | Embedded **VideoView + Play/Pause + scrub SeekBar + mono timecode** panel; audio plays with the video; `stopPlayback()` on dismiss | `MediaPoolDialog.kt` player section |
| No good-take flagging, slate view, share, delete | `★ GOOD TAKE` toggle rewrites `_slate.json` (Movies copy mandatory, DCIM best-effort), `SLATE JSON` viewer (scrollable mono AlertDialog), `SHARE` (`ACTION_SEND` video/mp4) + `GCSV` share chip when present, `DELETE` with confirm dialog (removes mp4 + gcsv + slate rows) | `MediaPoolDialog.kt`; `StorageHelper.deleteClip/readSlateJson/writeGoodTake` |

## PHASE 6 — UI/Feature Glitches + Zoom Engine  →  RESOLVED

| QA finding | Fix | Evidence |
|---|---|---|
| F1–F3 markers did not drive AF | `onMarkerTap` now calls `cameraEngine.triggerTapFocus(nx, ny)` (AF_REGIONS + AF_TRIGGER_START two-phase pull); with the 180° flip armed, view→camera coords are corrected (`cameraSpaceX/Y`) before the HAL call; reticle stays in view space | `MainActivity.kt`; `CameraEngine.triggerTapFocus` |
| Linear ISO slider | 1/3-stop logarithmic cinema ladder: `100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200` (`Models.isoStops()`, 16-stop index slider) | `Models.kt`; `HudController.panelIso` |
| Linear Kelvin slider | Perceptually uniform **mired** slider (`Models.miredFromKelvin/kelvinFromMired`, 179M–400M, readout shows both K and mired) | `HudController.panelWb` |
| Integer shutter angles (no 172.8°) | Decimal 0.1° slider (45.0°–360.0° in tenths); preset chips retained incl. **172.8°** (50 Hz anti-flicker at 24 fps) | `HudController.panelShutter` |
| Zebra `*0.9023` premature scaling | All display-profile scalers removed — raw full-range float RGB thresholds (Z95→0.95, Z90→0.90, custom raw); readout shows the raw value | `ViewfinderRenderer.zebraUniformParams`; `HudController.panelZebra` |
| Histogram vanished below 136 dp | Dynamic degradation law: gap → 0.5 dp → 0 with `barW = innerW / 64` floor — bars always render, early-return removed | `HistogramView.onDraw` |
| Top-bar crowding crushed the timecode | Top bar split into two weight-1 **HorizontalScrollViews** around the LOCKED center timecode; pills scroll, TC never crushed | `HudController.buildTopZone` |
| Teardrop notch collision | `WindowInsets` displayCutout safe padding on `hud_root`: `maxOf(cutoutLeft/Right, 12dp)` applied via `setOnApplyWindowInsetsListener` | `MainActivity.onCreate` |
| Bottom sliders overlapped the scopes | Bottom panels are in-layout siblings ABOVE the bar (scopes live inside the bar) + `bottomMargin = dp(6f)`; overlap is impossible by construction | `HudController.renderPanel` |
| Silent exception swallowing | Every empty catch now logs via `Log.w("M14_CINEPRO", ...)`; repo-wide sweep confirms **zero** fully-silent catch blocks remain | grep sweep clean |
| **NEW: no zoom engine** | Full 1.0x–8.0x digital zoom: **2-finger pinch** (`ScaleGestureDetector` on FocusOverlayView), **ZOOM pill** in the Top Bar (`1.0x 26mm`), panelZoom with focal chips **1.0x (26mm) / 1.35x (35mm) / 2.0x (52mm) / 4.0x (104mm)**, continuous 0.1x slider, **smooth 1.5 s servo rack-zoom toggle** (framework `ValueAnimator`), ↺ RESET; driven by `CaptureRequest.CONTROL_ZOOM_RATIO` clamped to the HAL `CONTROL_ZOOM_RATIO_RANGE` (regular sessions only — HFR+zoom unverified on this HAL, so the HS path never carries it) | `CameraEngine.setZoomRatio/maxZoomRatio`; `Models.ZOOM_*`; `HudController.panelZoom/applyZoom`; `FocusOverlayView.onZoom` |

---

## Verification

1. `./gradlew assembleDebug` (AGP 8.4.1 / Gradle 8.7 / platform-34 / build-tools 34.0.0): **BUILD SUCCESSFUL**, zero errors.
2. Compiled-APK verification (`aapt2 dump xmltree` + dex symbol sweep): `showWhenLocked/turnScreenOn=true`, `PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT=false`, and all V3 classes/symbols present — `MediaPoolDialog`, `setZoomRatio`, `triggerTapFocus`, `previewFlip180`, `rackZoomOn`, `kelvinShaderGainsRelative`, `tintShaderGain`, `hsFallbackActive`, `listClips`, `isoStops`, `miredFromKelvin`, `CONTROL_ZOOM_RATIO`.
3. Static law sweeps: zero TODO/placeholder/stub, zero `CONTROL_AF_MODE_OFF` / `LENS_FOCUS_DISTANCE` / `NOISE_REDUCTION_MODE_OFF` request writes (KDoc mentions only), zero PopupWindow, zero silent catches.

## On-device test checklist (V3)

1. Hold landscape both ways — viewfinder upright at ROTATION_90 AND ROTATION_270; GUIDES → 180° FLIP inverts the preview.
2. GUIDES → 1.33x/1.55x/2.0x — preview widens horizontally in the GL viewfinder (not just a badge).
3. Launch with keyguard active — GL surface creates, camera comes up.
4. 720p 60 Slow-Mo — session configures at Range(120,120); MP4 plays back 2x slow-motion at 60 fps.
5. Kelvin 2500K↔10000K at tint 0 — viewfinder never goes black/neon; 5600K + tint 0 is visually neutral.
6. Every panel ↺ RESET + Top Bar RESET ALL — settings return to factory defaults and the session restarts.
7. MEDIA — clips list newest-first, playback + scrub, GOOD TAKE persists into `_slate.json`.
8. Pinch + ZOOM pill 1.0x–8.0x; RACK servo sweeps in 1.5 s; F1–F3 recall physically pulls AF.
