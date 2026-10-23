# M14 CinePro — Version 1 → Version 2 Code Audit Report

**Auditor**: Principal Android Camera2 & OpenGL ES Code Auditor
**Repository**: `https://github.com/SobhnathxLuffy/M14-cinepro` (branch `main`)
**Scope**: every file under `m14_cinepro/` audited against the 8 Strict Hardware & Architecture
Checkpoints, cross-referenced against the on-device evidence (`V1_BUG_LOG.txt`, `TEST_RESULTS.md`)
and the ground-truth law (`docs/M14_VERIFIED_TRUTH.md`, `docs/M14_SYSTEM_STRESS_REPORT.md`,
`docs/stage3_camera2.md`, `docs/stage4_shaders.md`, `docs/stage5_media.md`).
**Target**: Samsung Galaxy M14 5G (`SM-M146B`, Exynos 1330, Mali-G68 MP2, Android 15).

---

## 0. Executive verdict

| Checkpoint | V1 verdict | Root cause found | Fixed in V2 |
|---|---|---|---|
| CP1 Viewfinder orientation + 16:9 lock | **FAIL** | No `PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT`; ST matrix passed to the shader without counter-rotation; full-screen viewport `2343x1080` (log line 19) with no 16:9 lock | `AndroidManifest.xml`, `ViewfinderRenderer.kt`, `Models.kt` |
| CP2 Split pipeline (Architecture A) | **PASS** | — | (kept; regression-guarded) |
| CP3 720p 60fps high-speed session | **FAIL** | Preview `SurfaceTexture` forced to 1920x1080 in the HFR session → `IllegalArgumentException: Surface size 1920x1080 is not part of the high speed supported size list [1280x720]` (bug log lines 77/111/195) | `CameraEngine.kt` |
| CP4 AF_MODE_OFF / LENS_FOCUS_DISTANCE / NR_OFF traps | **PASS** | — | (kept) |
| CP5 Manual exposure + 110 µs headroom snap | **PASS** | — | (kept) |
| CP6 GLSL 320 es shaders + 3D LUT quality | **PARTIAL FAIL** | Shader set is a verbatim BMD port (Laplacian/17-band/half-voxel all exact) **but** the renderer feeds `zebraThreshold = 0.0` in mid-grey modes → whole-frame striping; the four built-in LUTs are single-key tints, not film emulations | `ViewfinderRenderer.kt`, `LutEngine.kt`, `Models.kt`, HUD |
| CP7 CFR PTS / Gyroflow / audio VU | **PARTIAL FAIL** | CFR restamp + gcsv v1.3 + dBFS math all correct; **but** `drop-input-frames=1` is never reset to 0 at record start → takes after the first encode 0 frames (bug log lines 73, 153: `frames=0`); `thread.threadId == -1` race (bug log lines 7, 47, 158) | `RecordingController.kt`, `CameraEngine.kt` |
| CP8 3-zone Blackmagic HUD | **FAIL** | `PopupWindow` panels float mid-air, no FPS/IRIS/TINT/TEMP-BATT pills, F1/F2/F3 in the bottom bar, scopes float above the bar — the overlap/glitch symptom | `HudController.kt` (full 3-zone rewrite), `FocusOverlayView.kt`, `GuidesOverlayView.kt` |

---

## 1. Complete file inventory (29 production files)

Build/config:

| File | Status |
|---|---|
| `m14_cinepro/build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew*`, wrapper | OK (AGP 8.4.1 / Gradle 8.7 / Kotlin 1.9.24, zero external deps) |
| `app/build.gradle` | OK (minSdk 28 / targetSdk 34 / compileSdk 34) |
| `app/src/main/AndroidManifest.xml` | **FIXED (CP1)** — see §2.1 |
| `app/src/main/res/layout/activity_main.xml` | OK (single `hud_root` FrameLayout) |
| `res/values/{strings,colors,themes}.xml`, `drawable`, `mipmap` | OK |

Kotlin (`app/src/main/java/com/m14/cinepro/`):

| File | Verdict | Notes |
|---|---|---|
| `MainActivity.kt` | OK (with user's V1.1 patches kept) | `applyImmersive()` ordering fix + insets guard + MANAGE_EXTERNAL_STORAGE gating retained |
| `core/Models.kt` | **UPDATED (CP1/CP6)** | added `pictureRect()` law + `KODAK_2383`/`ETERNA` LUT presets |
| `core/EngineListener.kt` | OK | |
| `camera/CameraEngine.kt` | **FIXED (CP3 + log bug)** | see §2.3/§2.4 |
| `camera/ExposureController.kt` | PASS (CP5) | AE_MODE_OFF stealth path, FRAME_DURATION = 1e9/fps, guarded key sets |
| `camera/FocusController.kt` | PASS (CP4) | never writes AF_MODE_OFF/LENS_FOCUS_DISTANCE; NR_MODE_FAST always; 1000-weight ROI |
| `camera/WhiteBalanceController.kt` | PASS | dt0 Kelvin math + CCM cache + shader gains |
| `color/ColorScience.kt` | PASS | dt0 port (compile-verified in V1) |
| `gl/ViewfinderRenderer.kt` | **FIXED (CP1 + CP6)** | see §2.2/§2.5 |
| `gl/GlViewfinder.kt` | OK | full-screen EGL3.2 surface; letterbox handled in renderer |
| `gl/LutEngine.kt` | **UPGRADED (CP6)** | see §2.6 |
| `gl/HistogramEngine.kt` | PASS | 64-bin compute, 768 B SSBO readback |
| `hud/HudController.kt` | **REWRITTEN (CP8)** | see §2.7 |
| `hud/FocusOverlayView.kt` | **FIXED (CP1)** | tap normalization now maps through the 16:9 picture rect |
| `hud/GuidesOverlayView.kt` | **FIXED (CP1)** | masks/grids now drawn relative to the 16:9 picture rect |
| `hud/HistogramView.kt` | PASS | |
| `hud/AudioMeterView.kt` | PASS | |
| `hud/SlateDialog.kt` | PASS | |
| `recording/RecordingController.kt` | **FIXED (CP7)** | see §2.8 |
| `recording/AudioEngine.kt` | PASS (CP7) | 20·log10 − 90.308998 dBFS, −60..0, peak-hold + 50 ms RMS |
| `recording/GyroflowLogger.kt` | PASS (CP7) | v1.3 header, `t_us,gx,gy,gz,ax,ay,az`, tscale:1, 500 Hz FASTEST |
| `recording/StorageHelper.kt` | OK (user's V1.1 fallback patch kept) | |
| `recording/SlateManager.kt` | PASS | |
| `sensor/GyroEisController.kt` | PASS | 0.9 crop + roll/pan inside crop headroom |
| `system/SystemMonitor.kt` | PASS | |

GLSL (`app/src/main/assets/glsl/`):

| File | Verdict |
|---|---|
| `fragment_header.glsl` | PASS — verbatim BMD §1.1 |
| `transform_vertex.glsl` | PASS — verbatim BMD §1.3 (rotation composed into `texMatrix` on the CPU) |
| `focus_assist_effect.glsl` | PASS — exact 5×5 Laplacian, center 20, DC bias +24, thresholds `5.5 − t` / `3.5 − t` |
| `false_color_effect_fast.glsl` | PASS — exact 17-band CIE L* table |
| `luminance_function.glsl` | PASS — verbatim BMD §1.9 |
| `lut_3d_effect.glsl` | PASS — `coord = rgb·(1−1/N) + (1/2N)`, sampler3D unit 2 |
| `zebra_effect.glsl` | PASS — verbatim BMD §1.7 (diagonal `mod(x+y+shift, 2w)`; bug was Kotlin-side, see §2.5) |
| `wb_trim_effect.glsl` | PASS |
| `histogram_compute.glsl` | PASS — BINS=64, 16×16 workgroups, std430 SSBO |

Stray files uploaded with the V1 evidence commit (`m14_cinepro/AndroidManifest.xml`,
`m14_cinepro/assets/`, `m14_cinepro/src/`, `CURRENT_V1_SOURCE.txt`, `V1_BUG_LOG.txt`,
`TEST_RESULTS.md`): not referenced by Gradle, no build impact. Left in place as evidence.

---

## 2. Findings per checkpoint (exact defect lines)

### CP1 — Viewfinder Orientation & Aspect Ratio Matrix Bug — **FAIL (fixed)**

1. **Manifest property missing.** `app/src/main/AndroidManifest.xml` had `sensorLandscape`
   (`<activity>` line 35 ✓) but the `<application>` block carried **no**
   `<property android:name="android.camera.PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT"
   android:value="false"/>`. On Android 15 the compatibility framework may letter-box/crop
   "portrait apps" even when the activity locks orientation, re-introducing the crop symptom.
   **V2**: property added inside `<application>`.
2. **No counter-rotation.** `ViewfinderRenderer.onDrawFrame()` (V1 lines 238–245) composed
   `combinedMatrix = ST · EIS` and uploaded it to `texMatrix` **without any
   `Display.getRotation()` compensation**. Camera 0 has `SENSOR_ORIENTATION = 90`; the
   SurfaceTexture transform is portrait-referenced, so in a landscape window the image shows
   rotated 90° and squashed — exactly the "horizontal monitor appears as a vertical rectangle"
   report. **V2**: `final = R_display · ST · EIS` with
   `R_display = T(0.5,0.5) · R((360 − rotation·90) mod 360 around +Z) · T(−0.5,−0.5)`
   → 270° for `ROTATION_90`, 90° for `ROTATION_270`, 0° for `ROTATION_0`, per the checkpoint law.
   (Derivation: at `ROTATION_90` with sensor 90 the raw HAL frame is upright-landscape
   (`(90−90)%360 = 0` JPEG rule); ST rotates it +90 to portrait; the display rotation must be
   undone by −90 ≡ +270 of sampling rotation. The two checkpoints interlock: the counter-rotation
   is aspect-correct **only** because the viewport is simultaneously locked to 16:9.)
3. **No 16:9 lock.** `onSurfaceChanged()` (V1 lines 205–211) did `glViewport(0,0,width,height)`
   on the full surface — the bug log shows `M14GL onSurfaceChanged: 2343x1080` (aspect 2.17:1)
   while the camera stream is 16:9, so the picture is horizontally stretched even where not
   rotated. **V2**: `Models.pictureRect()` computes the largest centered 16:9 rect of the
   surface; the renderer clears the full surface black and renders into the picture rect only
   (1920x1080 centered inside 2343x1080 → 211 px pillar bars), and `outWidth`/`outHeight`
   (zebra/peaking pixel-space uniforms) are the picture-rect dimensions.
4. **Overlay mapping.** `FocusOverlayView` normalized taps against the full window, so after the
   letterbox a tap would hit the wrong sensor region. **V2**: taps are remapped through the same
   `Models.pictureRect()`; taps in the pillar bars clamp to the picture edge.
   `GuidesOverlayView` masks/grids now draw inside the picture rect.

### CP2 — Split Pipeline Architecture — **PASS (unchanged)**

- `CameraEngine.createSessionOutputs()` adds exactly two outputs: the encoder input surface and
  the 1920x1080 preview `SurfaceTexture` (`setDefaultBufferSize(1920,1080)` before `Surface()`,
  Architecture A law).
- `RecordingController.prepareEncoder()` builds the record surface via
  `MediaCodec.createByCodecName("c2.exynos.hevc.encoder")` + `createPersistentInputSurface()`
  (fallback chain `OMX.Exynos.HEVC.Encoder` → default HEVC → `c2.exynos.h264.encoder` → default AVC).
  The 4K stream goes **camera HAL → encoder surface** directly — it never touches the Mali-G68.
  V1 bug log line 20 confirms: `encoder ready: c2.exynos.hevc.encoder (video/hevc) 3840x2160 @24fps
  80 Mbps highSpeed=false` and `TEST_RESULTS.md` confirms dual concurrent streams
  (`Stream 0: 3840x2160 record`, `Stream 1: 1920x1080 viewfinder`).
- MediaFormat keys match stage5 §8.1: `color-format=2130708361`, `color-standard=BT709(1)`,
  `color-transfer=SDR_VIDEO(3)`, `color-range=limited(2)`, `i-frame-interval=1.0`,
  `priority=0`, `max-input-size=0`, `capture-rate` on high-speed.

### CP3 — Why 720p 60fps failed — **FAIL (fixed)**

- HFR session machinery itself was correct: `createConstrainedHighSpeedCaptureSession()`
  (`CameraEngine.kt` line 410), `createHighSpeedRequestList()` + `setRepeatingBurst()`
  (lines 519–521), fps-range law `upper==60, prefer lower==60` (`hfrFpsRange()` lines 546–562),
  encoder already reconfigured to 1280x720 (`bug log line 75`).
- **The defect**: `createSession()` line 228 unconditionally ran
  `previewTexture.setDefaultBufferSize(1920, 1080)`. The M14 high-speed pipeline accepts
  **exactly one** surface size — `checkConstrainedHighSpeedSurfaces` rejected the session:
  `IllegalArgumentException: Surface size 1920x1080 is not part of the high speed supported size
  list [1280x720]` (bug log lines 77, 111, 195 — three reproductions).
  **V2**: in high-speed mode the preview texture is set to `resolution.width x resolution.height`
  (1280x720), matching the checkpoint law that **both** the preview buffer **and** the encoder
  are 1280x720. The GL pipeline is size-agnostic (the ST matrix + 16:9 letterbox handle it;
  1280x720 is itself 16:9).

### CP4 — HAL crash traps — **PASS (unchanged, regression-guarded)**

- Grep across all camera code: `CONTROL_AF_MODE_OFF` **never written**; `LENS_FOCUS_DISTANCE`
  **never written**; `NOISE_REDUCTION_MODE_OFF` **never written**.
- `FocusController` uses only `AF_MODE_AUTO(1)` / `CONTINUOUS_PICTURE(4)` two-phase tap AF with
  1000-weight `CONTROL_AF_REGIONS`/`AE_REGIONS`, F1/F2/F3 persistent marker regions, 5 s tap-hold
  fallback to CAF, and stamps `NOISE_REDUCTION_MODE_FAST(1)` on every request it touches.
- `ExposureController`/`WhiteBalanceController` also never touch the three forbidden keys.

### CP5 — Manual exposure & the 110 µs headroom snap — **PASS (unchanged)**

- Manual path: `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` (clamped to the HAL range) +
  `SENSOR_SENSITIVITY` (ISO 100..3200, clamped) — the verified stealth unlock
  (`M14_VERIFIED_TRUTH.md` §4.D).
- `Models.applyHeadroomSnap()`: `frameNs = 1e9/fps`; if `frameNs <= exposure + 110_000` then
  `exposure = frameNs − 110_000` (constant `EXPOSURE_HEADROOM_NS = 110_000L`) — 360° at 24 fps
  becomes 41.583 ms, so the sensor never drops frames at any shutter angle.
- `SENSOR_FRAME_DURATION = 1e9/fps` injected in both AE-ON and AE-OFF paths (non-fatal, B2 law).

### CP6 — GLSL 320 es shaders & 3D LUT quality — **PARTIAL FAIL (fixed)**

- **Focus peaking**: `focus_assist_effect.glsl` is the exact BMD kernel — 5×5 Laplacian
  `{−1,−3,−4,−3,−1; −3,0,6,0,−3; −4,6,20,6,−4; …}`, center weight 20, DC bias +24, red-channel
  sampling, thresholds `5.5 − peakingThreshold` / `3.5 − peakingThreshold`, gain `t/4` with
  `t = UI×0.04`. PASS.
- **False color**: `false_color_effect_fast.glsl` carries the exact 17-band CIE L* table
  (0.96820304 red / 0.9345 yellow / 0.8986 yellow / borders / pink 0.65296 / green 0.4797 /
  blue 0.2004 / purple below 0.0899) and the luminance path is the 33³ RGBA16F `sampler3D` bake
  (`LutEngine.bakeLuminanceLut()`: 709 decode → XYZ Y dot → CIE L* → /100) on unit 3 with
  half-voxel uniforms. PASS.
- **Zebras**: `zebra_effect.glsl` is verbatim (diagonal `mod(x + y + shift, 2·w)` marching
  stripes, mid-grey bands `[405,429)/1024` and `[524,547)/1024`). The **bug was Kotlin-side**:
  `ViewfinderRenderer.zebraUniformParams()` passed `zebraThreshold = 0.0` in
  `MID_GREY`/`MID_GREY_PLUS1`, and because the include ORs the threshold with the band
  (`max(thresholdCondition, midCondition)`), `step(0.0, L*) = 1` striped **the entire frame**
  instead of the band. **V2**: mid-grey modes pass `zebraThreshold = 2.0` (above the L* domain)
  so only the band stripes; the shader stays verbatim.
- **3D LUT plumbing**: `glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, 33,33,33, 0, GL_RGBA,
  GL_HALF_FLOAT)` + `glTexSubImage3D` re-upload law + `lut3DScale = 1 − 1/33`,
  `lut3DOffset = (1/33)/2` + unit bindings 2/3 — all correct (matches stage4 §7.4). PASS.
- **LUT quality (the "washed-out tint" symptom)**: the V1 generators were single-key tints —
  `smoothContrast` smoothstep + constant teal/warm offsets, a lifted-black warm fade, and a
  luma S-curve mono. **V2 replaces the whole generator family** with real film-emulation math
  (all 33³, same voxel law):
  - `NEUTRAL_709` → **Rec.709 soft-shoulder** contrast: filmic toe + soft-shoulder roll-off
    (knee 0.72, cosine shoulder) instead of the raw identity.
  - `TEAL_ORANGE` → **true YIQ skin-protected** grade: RGB→YIQ, I/Q axis splits along the
    skin-tone vector, luma-protected shadow teal / highlight warm pushes with explicit skin-band
    protection, back to RGB.
  - `KODAK_2383` (new) → print-film emulation: dense lifted toe, per-channel shoulder crossovers,
    saturated primaries with cyan-safe highlights and yellow-green shadow bias, contrast
    structured as a print density curve.
  - `ETERNA` (new) → low-contrast low-saturation film look: gentle S-curve, desaturation that
    preserves deep saturation, cool-green shadow bias, soft highlight shoulder.
  - `MONO_CONTRAST` → **panchromatic B&W**: spectral-response weights (0.31 R / 0.52 G / 0.17 B —
    pan-film red-boosted/blue-suppressed), density toe/shoulder.
  - `OFF` / `IMPORTED` (.cube parser) unchanged.

### CP7 — Synthetic CFR PTS, audio VU, 500 Hz Gyroflow — **PARTIAL FAIL (fixed)**

- **CFR restamp**: correct (`drainVideoOutput`):
  `info.presentationTimeUs = firstPtsUs + frameIndexCounter * frameDurationUs`,
  `frameDurationUs = round(1e6/fps)`, index advances only after a successful `writeSampleData`;
  `request-sync` forces the start IDR; `ffprobe` on the V1 4K clip confirms `24 fps, 24 tbr`
  exact CFR. PASS.
- **Gyroflow**: `gyroflow v1.3 / lens_profile / calibrator / orientation:XYZ /
  format:t_us,gx,gy,gz,ax,ay,az / tscale:1`, `SENSOR_DELAY_FASTEST` on a −1-priority
  HandlerThread, µs timestamps, dual Movies+DCIM writers. TEST_RESULTS confirms 500 Hz. PASS.
- **Audio VU**: `dBFS = 20·log10(x) − 90.308998` (16-bit FS reference), clamped −60..0, peak-hold
  decay 0.5 dB/~0.5 s, 50 ms RMS window, 20 Hz emit. PASS.
- **DEFECT 1 — second take records zero frames.** `stopRecordingLocked()` sends
  `drop-input-frames=1`, but `startRecordingLocked()` only re-sent `request-sync=0` — the
  stage5 §3 law (`mk7.f()`: start = `drop-input-frames=0` **and** `request-sync=0`) was
  half-implemented. Every take after the first therefore fed a drop-everything encoder →
  `frames=0 durationMs=20624` (bug log line 73) and `frames=0 durationMs=3523` (line 153),
  frozen second takes. **V2**: start sends both parameters.
- **DEFECT 2 — thread priority race.** `CameraEngine.ensureHandler()` called
  `Process.setThreadPriority(thread.threadId, -2)` immediately after `start()`; `threadId` is
  still −1 before the new thread runs → `IllegalArgumentException: Given thread -1 does not
  exist` on every open (bug log lines 7, 47, 158, 177). **V2**: priority is set from a first
  runnable posted onto the thread itself via `Process.myTid()`.

### CP8 — UI/UX 3-zone Blackmagic landscape HUD — **FAIL (rewritten)**

V1 glitch sources (why controls "overlap or glitch"):
1. `PopupWindow` panels anchored to pills with manual x/y math (`showPanel`, lines 614–654) —
   on Android 15 popups re-measure against window insets; with the cutout the anchor math can
   push panels off-window or over the bars they anchor to, and outside-touch dismissal fights
   the immersive sticky nav.
2. `F1/F2/F3` lived in the bottom pill row, `EIS/TORCH` in the right strip, `STATUS` crammed
   into the top bar — no zone separation, so the layout changed size whenever any label changed.
3. Scopes row floated above the pill bar with its own margins — three independently-sized rows
   at the bottom = the overlap/glitch feel.

**V2 — the checkpoint layout, exactly:**

- **Top bar (48 dp)**: `FPS` · `SHUTTER (angle/speed)` · `IRIS (f/1.8)` · `ISO` · `WB (Kelvin)` ·
  `TINT` · centered `TIMECODE 00:00:00:00` (+ red tally) · `HEVC 80M` · `TEMP/BATT`.
- **One slider row** docked directly under the top bar (in-layout, never a popup): tapping any
  parameter opens **one** floating panel row beneath the bar; opening another closes the open
  one first (`closePanel()` in every opener) — UI elements can never stack or overlap. Panels:
  FPS (format+bitrate chips), SHUTTER (angle chips+slider ⇄ speed denominators, live µs/ms
  readout), IRIS (fixed f/1.8 info + EV row), ISO (AUTO/MANUAL/AE-LOCK + ISO slider), WB
  (preset chips + Kelvin slider + AWB lock), TINT (slider), SYS (temp/battery/storage detail).
- **Center zone**: true 16:9 letterboxed GL viewfinder (`Models.pictureRect`), guides/grids and
  the touch-focus reticle mapped to the picture rect.
- **Right column (72 dp)**: iconic red/white `REC` circle (56 dp), `AE/AF LOCK` button,
  `F1/F2/F3` focus-pull buttons (long-press to set), `SLATE`.
- **Bottom bar (48 dp)**: left = live 64-bin histogram; center = `PEAK` `ZEBRA` `FALSE COLOR`
  `LUT` `GUIDES` `EIS` pills (each opens its panel row above the bar); right = stereo L/R VU
  meter (−60..0 dBFS).
- Chrome hide (double-tap / vertical swipe) kept; TORCH moved into the SYS panel to respect the
  zone law (was a stray right-strip pill).

---

## 3. Verification performed for V2

- Static law re-check of every edited file against the ground-truth docs (constants, key values,
  include order, uniform scales).
- Full sandbox Gradle build (AGP 8.4.1 / Gradle 8.7 / Kotlin 1.9.24 / platform 34) →
  `BUILD SUCCESSFUL`, APK assembled and signed (see commit message for build hash).
- Zero-placeholder sweep: no TODO/stub/placeholder/`...` bodies in the diff.

## 4. Pull-and-test instructions

```bash
git pull origin main
cd m14_cinepro
./build_and_install.sh          # builds, zipaligns, signs, adb installs
adb shell appops set com.m14.cinepro MANAGE_EXTERNAL_STORAGE allow
```

On-device checklist mirroring the four V1 symptoms:
1. Landscape at a horizontal monitor → upright, unstretched, pillar-boxed 16:9.
2. Format → 720p 60 (HS) → session must configure; both surfaces 1280x720.
3. LUT → Kodak 2383 / Eterna / Teal-Orange → filmic grade, not tint.
4. HUD: tap every top-bar pill → single slider row, nothing stacks; record two takes
   back-to-back → both report `frames>0` in logcat (`take finalized: … frames=`).
