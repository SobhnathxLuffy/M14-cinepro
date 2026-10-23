# STAGE 3 — Camera2 API & Vendor Tag Pipeline (Blackmagic Camera v3.5.0.0064)

Scope: exact Camera2 driving model — every CaptureRequest key + value/formula, Kelvin+Tint→RggbChannelVector math,
ISP bypass/tonemap strategy, vendor tags, log/gamma profile system, lens switching, zoom, stabilization, fps ranges.

Method: static analysis only. Sources: `jadx_out/sources` (Kotlin→Java), `jadx_fallback/sources` (second jadx pass —
this tree DID fully decompile `camera/a.java`, `camera/b.java`, `he0`, `s06`, `q06`, `eh7`, `rh0`, `dt0` etc. in
bytecode-style Java; §3–§7 quote it), `apktool_out/smali*` (authoritative for enum member↔name maps and for
`manager/a` / `kf` / `camera/a.q`-style methods not in either jadx tree), `strings` on native libs,
`res/values/strings.xml|arrays.xml`. Sections 0–2.10 were drafted from smali before the fallback tree was found;
§7c lists the corrections that the fallback decompile forces.

---

## 0. Map: where the Camera2 code actually lives

| Class | Location | Role (evidence below) |
|---|---|---|
| `com.blackmagicdesign.android.camera.manager.CameraManager` | smali (classes.dex) | **Central camera controller**: opens CameraDevice, builds sessions, applies settings |
| `com.blackmagicdesign.android.camera.CameraStateController` | smali (classes.dex) | Session/camera lifecycle state machine (start/stop/restart session) |
| `he0` | smali (classes.dex, 4355 lines, jadx stub) | **CaptureRequest.Builder factory / settings applier** (biggest camera2 file) |
| `kf` | smali (classes.dex, 3207 lines) | Camera2 session/output-cfg or request repeat logic |
| `s06` | smali_classes3, 4095 lines | Camera capability/physical-id helper |
| `eh7` | jadx OK (`defpackage/eh7.java`, 335 lines) | **CameraCharacteristics wrapper ("camera info")** incl. QUIC vendor tags |
| `ch7` | smali | Lazy per-camera cached lists (resolutions/fps) keyed off eh7 |
| `ah0` | smali 308 lines | **Vendor-tag Key factory** (`CameraCharacteristics.Key`/`CaptureRequest.Key` by name+type) |
| `dt0` | jadx OK | **Pure-Java fallback of Kelvin/Tint math** (mirror of libcolor_science.so) |
| `com.blackmagicdesign.android.camera.ColorScienceUtils` | jadx OK | JNI glue to `libcolor_science.so` (nativeGenerateGains / nativeCalculateWhiteBalance) |
| `tg0` | smali 63 lines (jadx stub) | ColorSpaceProfiles vendor-key holder |
| `ug0`, `vg0`, `wd0`, `ie0`, `rh0`, `sh0`, `xz4`, `rz4`, `z90` | smali | Supporting camera2 glue (stream use case, sensor mode, etc.) |
| `CameraPipeline`/`SessionTemplate`/`SessionType`/`StreamUseCase` | `com/blackmagicdesign/android/camera/` | Session templates & pipeline state |

jadx decompile failure note: `defpackage/tg0.java` = 10 lines, `au4.java` = 20 lines, `eh7.java` = 335 lines OK — so for
the heavy classes the smali in `apktool_out/smali{,_classes3}` is the source of truth for quotes in this document.

---

## 1. Camera2 usage map & central controller

Central pipeline (all in `classes.dex` unless noted; jadx FAILED on most of these → file names are R8-renamed; roles
reconstructed from smali + lambda inner classes that kept names):

| Obfuscated class | Original name (evidence) | Role |
|---|---|---|
| `com/blackmagicdesign/android/camera/manager/a` (smali 5008 ln) | `camera.manager.CameraManager` (lambda classes `CameraManager$setShutter$1` etc. are its inner classes; it references them) | **Top orchestrator**: Mutex-serialized setters (setShutter/setIso/setManualWhiteBalance/setFrameRate/setZoomRatio/setAperture/setStabilization/setNoiseReduction/setSharpening/setDistortionCorrection/setTimelapse/setFlickerFreeShutter/setTorch/triggerAutoFocus/AfAe/AutoExposure/AutoWhiteBalance/setCurrentCamera/updateCameraList/startCameraSession/initializePipeline/physicalCameraFlow), owns StateFlows (h=current camera entity, U=kelvin, W=tint, Y=zoom, I=iso, E=shutter, C=fps, p=autoExposure) |
| `com/blackmagicdesign/android/camera/a` (3538 ln) | base camera engine ("BaseCameraEngine") | **CaptureRequest applier**: holds active `CaptureRequest$Builder` (field `i`), applies every CONTROL_*/SENSOR_*/COLOR_CORRECTION key, opens `HandlerThread("CamThread-ID-<id>")`, builds `sf0` CCM cache, subclasses override per SoC |
| `q06` (classes3) | Samsung/Qualcomm engine (extends camera/a) | Samsung vendor keys + `TONEMAP_CURVE` quirk + `iso_exp_priority` AE |
| `ws7` (classes3) | Xiaomi engine | `xiaomi.*` + `com.mediatek.hdrfeature.hdrMode` keys |
| `wk6` (classes3) | Sony engine | `com.sonymobile.*` keys |
| `rz4` | Oppo/MediaTek engine | `com.oplus.*` + `com.mediatek.smvrfeature.smvrV2Mode` |
| `com/blackmagicdesign/android/camera/b` (1204 ln) | `CameraStateController` (inner lambdas `CameraStateController$startCamera$1/startSession$1/...`) | **Session lifecycle state machine**: opens camera, builds `SessionConfiguration`, holds `CameraDevice q`, `CameraCaptureSession r`, repeating `CaptureRequest$Builder s`, session-parameter `CaptureRequest m` |
| `vg0` | CameraDevice.StateCallback | open/reconnect/disconnect/error |
| `ug0` | CameraCaptureSession.StateCallback | session configured/active |
| `rh0` (742 ln) | main CaptureCallback | per-frame results → ISO/exposure/gains/AF state flows + CCM persistence |
| `yd0`,`zd0` (classes3) | CaptureCallbacks | AE-precapture / AWB one-shot convergers after triggers |
| `he0` (4355 ln) | camera enumeration/`ce0` lens-info builder | **Lens eligibility + per-lens capability cache** (`ce0`) |
| `eh7` + subclasses `xz4`(Oplus base)/`ox4`(OnePlus)/`s06`(Samsung)/`xs7`(Xiaomi)/`ml5`(realme)/`ck4`(Motorola)/`sj2`(Pixel)/`xk6`(Sony)/`ko7` | `CameraInfo` wrapper hierarchy | per-vendor resolution/fps/aperture/CCM tables from vendor tags |
| `dt0` + `ColorScienceUtils` | white-balance math (Java fallback + native) | see §WB |
| `sf0` | CCM store | SharedPreferences `"camera_color_science"`, key `last_matrix_<cameraId>` = 18 comma-separated rationals → `ColorSpaceTransform`; fallback = identity (`ColorScienceUtils.b`) |
| `sh0` | per-camera "last applied settings" | kelvin `k`, tint `l`, ISO `e`, shutter `f`, aperture `d`, EV `g`, focus `j`, gains `p` (Rggb), CCM `q`, fallback CCM `s` |
| `ce0` (1060 ln) | per-lens capability snapshot | 60+ fields: ranges for exposure/ISO/EV/zoom/kelvin/tint, mode lists, supported keys |
| `td0` (912 ln) | session settings data class | built by manager per session; `a`=SessionTemplate, `b`=SessionType, `c`=ColorSpaceProfile, `d`=MirrorMode, `e`=fps int, `f`=autoWB, `g`=awbLock, `h`=kelvin, `i`=tint, `j`=zoom, `k`=autoExposure, `l`=AE priority, `m`=?, `n`=EV, `o`=shutter ns, `p`=ISO, `q`=aperture, `r`=FlickerFree mode, `s`=autoFocus, `t`=focusDist, `u`=torch, `v/w/x/y`=stab/NR/edge/distortion ints, `z`=flashOn, `A`=torchStrength, `B`=isHfrSession, `C`=isTimelapse, `D/E/F/G/H`=HashMap<CaptureRequest$Key,Int/Long/Float/Byte/Boolean> generic override maps (**created empty — extension point currently unused**) |

No stills/photo pipeline exists (no takePicture path; `SessionTemplate` = PREVIEW(1)/RECORD(3)/VIDEO_SNAPSHOT(4)/MANUAL(6)); app is video-first (matches features_report: "no photo mode on phone").

---

## 2. RECORDING CaptureRequest keys — with exact code

### 2.1 Manual exposure — `com/blackmagicdesign/android/camera/a.smali` method `q(Long shutter, Integer iso, Float aperture, Boolean apply)` (lines 2855-3107)

```smali
# frame duration = 1e9 / fps  (const 0x3b9aca00 = 1,000,000,000)
:cond_2  iget v1, p0, ->m:I            # this.m = current fps (set from td0.e)
  int-to-long v1, v1
  const-wide/32 v4, 0x3b9aca00         # 1e9
  div-long v1, v4, v1                  # v1 = 1e9/fps ns
  iget-boolean v2, p0, ->o:Z          # this.o = flicker-free (td0.C = SessionType==TIMELAPSE)
  if-eqz v2 ... if-eqz p1 ...
  invoke-static {v1, v2, p1.longValue()}, Math.max   # flicker-free: frameDuration = max(1e9/fps, exposure)
# exposure clamp + 110000ns headroom snap:
  iget-object v7, v0, Lce0;->H:Landroid/util/Range;   # SENSOR_INFO_EXPOSURE_TIME_RANGE
  invoke-virtual {v7, p1}, Range.clamp
  iget v7, p0, ->m:I; 1e9 / v7 (v4 = frameDuration)
  const-wide/32 v9, 0x1adb0            # 110,000 ns
  add-long/2addr v7, v9                # shutter + 110us
  cmp-long v7, v4, v7 ; if-gtz → snap:  p1 = frameDuration - 110000
# ISO clamp:
  iget-object v0, v0, Lce0;->L:Landroid/util/Range;   # SENSOR_INFO_SENSITIVITY_RANGE (clamped)
# aperture: requested ?: sh0.d (last applied)
# THEN apply all five keys:
  set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
  set(CaptureRequest.SENSOR_SENSITIVITY, iso)
  set(CaptureRequest.LENS_APERTURE, aperture)
  set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)   # 1e9/fps (or max(exp))
  set(CaptureRequest.CONTROL_AE_MODE, 0)                     # AE OFF
```
**Formulas:**
- `SENSOR_FRAME_DURATION = 1_000_000_000 / fps` ns (if AE is OFF; also set in `p()`); if flicker-free/timelapse mode: `= max(1e9/fps, exposureTime)`.
- `SENSOR_EXPOSURE_TIME = clamp(shutter, SENSOR_INFO_EXPOSURE_TIME_RANGE)`; **if `1e9/fps <= shutter + 110_000ns` → `exposure = 1e9/fps − 110_000ns`** (prevents exposure == frame duration; 110 µs safety headroom).
- `SENSOR_SENSITIVITY = clamp(iso, SENSOR_INFO_SENSITIVITY_RANGE)`, `LENS_APERTURE = requested (must be in LENS_INFO_AVAILABLE_APERTURES) else last`.
- Gating: `ce0.o0` (manual exposure supported = SENSOR_INFO_EXPOSURE_TIME_RANGE + SENSOR_INFO_SENSITIVITY_RANGE + SENSOR_INFO_MAX_FRAME_DURATION all non-null — `eh7.q()`), else logs `"Manual exposure unsupported by camera <id>"`.

### 2.2 Shutter angle ↔ shutter speed ↔ fps (UI side) — `manager/CameraManager$setFrameRate$1.smali`

`setShutter(x: Float)` (lambda `setShutter$1`): `exposureNs = round(1e9 / x)`, clamp to sensor range, `a.r(engine, exposure, null, null, mask=0xe)` → `q()` re-applies with last ISO/aperture + APPLY. `E` flow gets the re-derived `1e9/clamped` value.

`setFrameRate(fps: Float, CameraOutputType)` (`smali/.../CameraManager$setFrameRate$1.smali`):
```smali
# guard: if recording → return (fps locked during record)
#  angle = round((exposure_ns / 1e9) * oldFps * 360.0) % 360 ; if (angle <= 0) angle += 360
  long-to-double v1, v1 ; div-double v1, v6(1e9)      # exposure seconds
  int-to-double v8, p1(old fps) ; mul-double v1, v8
  const-wide v8, 0x4076800000000000L # 360.0
  mul-double v1, v8 ; invoke-static Lz64;->v(D)I      # round
  rem-int/lit16 p1, p1, 0x168                        # % 360
  if-gtz p1 → p1 += 0x168
#  newExposure = angle / 360 / newFps ; newX = 1 / newExposure ; setShutter(newX)
  div-double v1, v8(360.0) ; div-double v1, newFps ; mul-double v1, v6(1e9)
  div-double v6, v1 ; double-to-float p1, v6         # X = 1e9/exposureNs
  invoke-virtual {v4, p1}, manager/a;->G(F)V          # = setShutter(X)
  E.value = X
```
**EXACT formula: `exposure_ns = 1e9 * angle / (360 * fps)`, i.e. shutter-speed-denominator `X = 360*fps/angle`.** When frame rate changes (not recording), the app converts current exposure to an angle at the OLD fps and back to exposure at the NEW fps — **the shutter ANGLE is the user-visible invariant** (BMD UI default = 180°). Guards: `"FPS must be greater than zero."`, `"Exposure time must be greater than zero."`, fps snapped via `manager/a.i(entity, recordSize, fps)` (see below).

### 2.3 AE (auto) — `camera/a.smali` `i(AutoExposurePriority, isPriorityAE, aeLock, ev, iso, shutter, apply)` (ln 2119-2329)

`AutoExposurePriority` = {w=SHUTTER_AND_ISO, x=ISO, y=SHUTTER} (creation order in smali). Gate `ce0.O` (from `CONTROL_AE_AVAILABLE_PRIORITY_MODES`, API 34; list built in `eh7.a()`); `this.j` = priority modes supported (≥2 entries in `CONTROL_AE_AVAILABLE_PRIORITY_MODES`, SDK≥36 gate `gv4.n()`).
```smali
# isPriorityAE==true:
  set(CONTROL_AE_MODE, 1)                             # AE ON
  if (gv4.n()) set(z90.c() /*CaptureRequest.CONTROL_AE_PRIORITY_MODE (hidden)*/, priority.f)
  # xd0.b[ordinal]: 1=SHUTTER_AND_ISO → evComp; 2=ISO → SENSOR_SENSITIVITY=iso; 3=SHUTTER → SENSOR_EXPOSURE_TIME=shutter
  case SHUTTER_AND_ISO: ev=clamp(td0.n, ce0.K);
      set(CONTROL_AE_EXPOSURE_COMPENSATION, ah0.d(ce0.I /*steps range*/, ce0.J /*step*/, ev))
  case ISO:  set(SENSOR_SENSITIVITY, iso)
  case SHUTTER: set(SENSOR_EXPOSURE_TIME, shutter)
  set(CONTROL_AE_LOCK, aeLock)
# isPriorityAE==false: static r(this,null,null,null,7) → re-applies last manual shutter/ISO/aperture (AE OFF path)
```
`ah0.d(Range steps, float step, float ev) = steps.clamp(round(ev / step))` — **EV→steps conversion**.
API-34 priority mapping (`eh7.a()`): `CONTROL_AE_AVAILABLE_PRIORITY_MODES` 1→`ISO`, 2→`SHUTTER`; default list = [SHUTTER_AND_ISO].

### 2.4 FPS — `camera/a.smali` `p(int fps, boolean apply)` (ln 2692-2853)

```smali
# fps must match a supported entry in ce0.m0 (List<dq6>, built from StreamConfigurationMap + vendor fps tables)
# this.n = isHfrSession (td0.B = SessionType==HIGH_SPEED_FPS); v0 = (fps >= 120)
if (this.n == (fps >= 120)) {          # only apply when fps class matches session type!
  if (this.n) range = e(fps)           # high-speed: pick from StreamConfigurationMap.getHighSpeedVideoFpsRanges()
                                       #   where upper==fps; prefer lower==fps; else min-lower; else [fps,fps]
  else range = Range(fps, fps)         # REGULAR: FIXED [fps, fps]
  set(CONTROL_AE_TARGET_FPS_RANGE, range)
  if (builder.get(CONTROL_AE_MODE) == 0)   # manual AE:
      set(SENSOR_FRAME_DURATION, 1e9 / fps)
}
else log "Frame rate unsupported by camera <id>"
```
**Recording fps is FIXED ([fps,fps]) in normal sessions; HFR (≥120fps) sessions use the constrained-high-speed ranges and are a separate session type (SESSION_HIGH_SPEED) — switching class requires a session restart.** `e(fps)` = `SCALER_STREAM_CONFIGURATION_MAP.getHighSpeedVideoFpsRanges()` filtered by `upper==fps`, prefer `lower==fps`.

`setFrameRate` UI guard: refuses while recording (`q` flow), snaps to supported fps via `manager/a.i(entity, recordSize, fps)`; if z0 (`timelapse`?) and new frame interval < old, skip; preserves shutter angle (above).

### 2.5 Manual WB (Kelvin + Tint) — `camera/a.smali` `s(int kelvin, int tint, boolean apply)` (ln 3109-3209)

```smali
set(CONTROL_AWB_MODE, 0)            # OFF
set(CONTROL_AWB_LOCK, FALSE)
if (ce0.U) {                        # API>=36 COLOR_CORRECTION_COLOR_TEMPERATURE support (z90.h() range key present)
  set(COLOR_CORRECTION_MODE, 3)     # TRANSFORM_MATRIX
  set(z90.f() /* CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE (hidden, API36) */, kelvin)
  set(z90.i() /* CaptureRequest.COLOR_CORRECTION_COLOR_TINT (hidden, API36) */, tint)
} else {
  ccm = sf0.a(cameraId)             # last AWB-measured CCM from SharedPreferences "camera_color_science"/"last_matrix_<id>"
                                    # (identity if none)
  gains = ColorScienceUtils.e(kelvin, tint, ccm)      # → RggbChannelVector (native libcolor_science / dt0 fallback)
  if (gains != null) {
    set(COLOR_CORRECTION_MODE, 0)   # CANCEL
    set(COLOR_CORRECTION_GAINS, gains)
    set(COLOR_CORRECTION_TRANSFORM, ccm)   # the measured CCM, NOT identity
  }
}
```
`manager.setManualWhiteBalance(kelvin, tint)` clamps to `entity.a.B` (kelvin Range) / `entity.a.C` (tint Range), calls `engine.s(kelvin, tint, apply=true)`, emits flows `U`=kelvin, `W`=tint, `t`=autoWB false.

**Samsung override `q06.l()`** (ln 1474-1566): manual WB sets `CONTROL_AWB_MODE=0`, `COLOR_CORRECTION_MODE=0 (CANCEL)`, `COLOR_CORRECTION_GAINS = sh0.p` (**last measured gains from results**), `COLOR_CORRECTION_TRANSFORM = sh0.q ?: sh0.s` (last measured CCM or fallback). Then base `s()` still runs and overwrites gains with kelvin-computed values **unless ColorScienceUtils.e() returned null** — i.e. q06.l() is a measured-gains fallback.

Auto WB (`l(Z auto, Z lock, Z apply)`, ln 2457-2561): `CONTROL_AWB_MODE = 1 (AUTO)`, `CONTROL_AWB_LOCK = lock`, `COLOR_CORRECTION_MODE = 1 (FAST)`; manual branch = 0/0 + (API36 temp/tint keys, see s()).

### 2.6 Focus / AF — `k(Z auto, Z apply)` (ln 2331-2455), `o(Float focusDistance, Z)` (ln 2651-2690), `C/D/E/F(MeteringRectangle[])`

- `k(auto, apply)`: pick `CONTROL_AF_MODE` from `ce0.z` (available AF modes) in order **3 (CONTINUOUS_VIDEO) → 4 (CONTINUOUS_PICTURE) → 5 (EDOF) → fallback 1 (AUTO)**; if `ce0.A >= 1` `set(CONTROL_AF_REGIONS, ah0.a)` (a shared `MeteringRectangle(Rect(0,0,0,0), 0)`); manual: `CONTROL_AF_MODE = 0 (OFF)` + `LENS_FOCUS_DISTANCE = sh0.j` (last).
- `o(focus, apply)`: `focus = ce0.y.clamp(focus)` (Range from LENS_INFO_MINIMUM_FOCUS_DISTANCE..0 in `eh7.e()`), `CONTROL_AF_MODE = 0 (OFF)`, `LENS_FOCUS_DISTANCE = focus`.
- Tap-to-focus `C(regions)`: AF_MODE=1(AUTO); AF_TRIGGER=IDLE → apply (or one-shot `session.capture` via static `c()` when HFR); AF_TRIGGER=START; AF_REGIONS=regions (if `ce0.A >= regions.length`); AE_MODE=1; AE_LOCK=FALSE; EV recomputed; AE_PRECAPTURE_TRIGGER=START; AE_REGIONS=regions (if `ce0.N >= len`); attach `yd0` callback (waits for AE convergence in results, restores AE_PRECAPTURE_TRIGGER=IDLE).
- AE-only `D(regions)`, AF-only `E(regions)` (START then back to IDLE + default region), AWB `F(regions)`: `CONTROL_AWB_MODE=1`, `AWB_LOCK=FALSE`, `COLOR_CORRECTION_MODE=1`, `AWB_REGIONS` (if `ce0.R >= len`), `zd0` callback (waits until result gains stabilize vs `RggbChannelVector(2,1,1,2)` sentinel; converges → computes kelvin/tint via `ColorScienceUtils.a/b/d` and re-applies as manual).
- `ah0.e(RectF normalized, Rect array)` builds tap `MeteringRectangle` with **weight 1000** (0x3e8).

### 2.7 Lens/camera-id switching

- `he0.a()` **probes camera IDs 0..199** (`l33` IntRange iterator 0..0xc8) — catches physical IDs beyond `getCameraIdList()`; `getCameraCharacteristics()` failures are caught/skipped.
- **Eligibility gates** (he0.smali ln 888-965): non-null `REQUEST_AVAILABLE_CAPABILITIES`; **`SCALER_STREAM_CONFIGURATION_MAP.isOutputSupportedFor(35)` — the camera MUST support RAW_SENSOR output**; if capabilities contains 11 (LOGICAL_MULTI_CAMERA) the id must be in the system camera-id list; per-device **camera-id allowlists** for whitelisted models (`ti1` = Build.MODEL database: Samsung SM-A71/SM-N98x/G98x/S90x/S91x/S92x/S93x/S94x/F731/F741/F946/F956/F761/F776/F971/F976/G990/G998/S711/A72/A52, Motorola razr 50/60 & edge 50/60, realme RMX5061/2407FPN8E/25019PN, OnePlus KB200/CPH2573/CPH2581/CPH2585/CPH2609/PJD110, Sony XQ-* / SO-51B / SOG03 / A101SO, Pixel 3a/4a(5G)/5/6, Xiaomi 2201122/2203121/2407FPN8E).
- `CameraAccessType` enum: **DIRECT(0)**, **THROUGH_LOGICAL(1)**, **DIRECT_ZOOMED(2)** (`com/blackmagicdesign/android/utils/entity/CameraAccessType.smali`).
  - DIRECT: open `ce0.b` (the camera2 id) directly, no physical override.
  - THROUGH_LOGICAL / DIRECT_ZOOMED: engine constructor picks **`this.k = min(ce0.i)`** — the smallest-numbered id in `ce0.i` (set of physical ids from `CameraCharacteristics.getPhysicalCameraIds()`); `this.l = ce0.b` (logical id) only when accessType == THROUGH_LOGICAL — passed to session as the **`OutputConfiguration.setPhysicalCameraId()`** value. (camera/a.smali `<init>` ln 327-456 + `eh7.j()` builds the preference order.)
  - `eh7.j(z, z2, physicals)`: `z` (no logical support?) → [DIRECT]; else [THROUGH_LOGICAL, DIRECT_ZOOMED] when physical ids exist, else falls back to [DIRECT].
- `manager.setCurrentCamera(id, resetZoom)`: looks up `entity/a` by id, sets flow `h`; if resetZoom → `Y` (zoom) = `entity.m.lower`. Session is torn down/re-opened reactively (`physicalCameraFlow`, `startCameraSession` lambdas).
- Session (re)build `camera/b.b(CameraDevice, td0, physicalId, List<Pair<Surface,StreamUseCase>>, configurator, cont)`:
  - template: `this.o` (HFR session) → **TEMPLATE_PREVIEW(1)** required by CONSTRAINED_HIGH_SPEED; else `td0.a.f` (PREVIEW=1 / RECORD=3 / VIDEO_SNAPSHOT=4 / MANUAL=6) — `createCaptureRequest(template)`.
  - **repeating targets = only surfaces whose StreamUseCase == PREVIEW** (or all when `b.p` volatile flag set — preview-only session).
  - configurator (kf case 0x19 from `camera/a.A()`): **`camera/a.i = builder; camera/a.a(builder, td0, /*extendedKeys=*/true)`** → all settings + (currently empty) D–H override maps applied; `build()` → **`SessionConfiguration.setSessionParameters(request)`**.
  - `OutputConfiguration(surface)`; `if (physicalId != null && !hfr) setPhysicalCameraId(physicalId)`; `setDynamicRangeProfile(b31.I(profile, ce0))` (skip when HFR); `setMirrorMode(td0.d.f)`; `setStreamUseCase(0)`; **if capabilities contains 25 (hidden STREAM_USE_CASE capability)**: PREVIEW→`setStreamUseCase(1L)`, RECORD→`setStreamUseCase(3L)` (back camera + certain devices → 1L).
  - `SessionConfiguration(sessionType = hfr&&!ti1.o() ? SESSION_HIGH_SPEED : REGULAR, outputs, executor, ug0)`.
  - **HDR10/HLG/DV**: `ce0.i0` = Map<String,List<DynamicRangeProfile>> from `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` keyed "BT2020"/"BT2020_PQ"/"BT2020_HLG"; per ColorSpaceProfile picks supported DR profile (`b31.I(ColorSpaceProfile, ce0)`). **`SessionConfiguration.setColorSpace(ColorSpace.Named.valueOf(name))` when SDK>=34** (`tg0.f`, names: "BT709", "SRGB", "DISPLAY_P3", "DCI_P3", "BT2020", "BT2020_HLG", "BT2020_PQ"). On `ti1.m()||ti1.e()` devices, ColorSpaceProfile `C` is silently substituted with profile `A` for the DR profile choice.
  - retry: `REQUEST_AVAILABLE_CAPABILITIES` fetched with 5×100ms retry (`CameraStateController$1$1$1`).

### 2.8 Zoom — `z(boolean apply, float ratio)` (ln 3448-3538) + `manager.setZoomRatio`

```smali
if (ce0.F) set(CONTROL_ZOOM_RATIO, ratio)        # CONTROL_ZOOM_RATIO_RANGE available
else {                                            # SCALER_CROP_REGION fallback (manual centered crop)
  halfW = size.width/2 ; halfH = size.height/2    # ce0.q = SENSOR_INFO_PIXEL_ARRAY_SIZE (or active array)
  cropW = width  / (ratio * 2.0f)
  cropH = height / (ratio * 2.0f)
  set(SCALER_CROP_REGION, Rect(halfW-cropW, halfH-cropH, halfW+cropW, halfH+cropH))
}
```
`manager.setZoomRatio(ratio)`: clamp to `entity.a.m` (UI zoom Range<Float>) → **`deviceRatio = a74.d(uiRatio, ce0.v /*ui zoom range*/, ce0.w /*device zoom range*/)`** — a linear remap of the lens's *UI* zoom range onto the device's actual `CONTROL_ZOOM_RATIO_RANGE`/`SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` range, then clamp `ce0.w`, then `engine.z(true, deviceRatio)`. **This is how each physical lens gets a normalized UI zoom range for smooth lens switching (DIRECT_ZOOMED).** `ce0.v/w` from `eh7.m()`: `CONTROL_ZOOM_RATIO_RANGE ?: Range(1, SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)`.

### 2.9 Stabilization / torch / flash

- `y(int mode, apply)`: `CONTROL_VIDEO_STABILIZATION_MODE = mode` if in `ce0.C` (from `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`, `eh7.l()`); UI list = those ints (`s06`/`eh7.s()`: stab only offered when >1 mode && fps<120 && height<=2160).
- `u(boolean on)`: `LENS_OPTICAL_STABILIZATION_MODE = on ? 1 : 0` gated on `ce0.B` (from `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`).
- `x(torchOn)`: `FLASH_MODE = torchOn ? 2 (TORCH) : 0 (OFF)`.
- `w(int strength, torchOn)` (`CameraManager$setTorch$1`): `FLASH_MODE=2` + **if SDK>=35: `set(CaptureRequest.FLASH_STRENGTH_LEVEL, strength)`** (hidden key via `wd0.b()`; max from `CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL` via `wd0.a()`) — torch brightness control.

### 2.10 ISP keys (edge/NR/distortion) — the "ISP bypass" layer

- `t(mode, apply)`: `NOISE_REDUCTION_MODE = mode` if in `ce0.X` (`NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES`).
- `n(mode, apply)`: `EDGE_MODE = mode` if in `ce0.W` (`EDGE_AVAILABLE_EDGE_MODES`).
- `m(mode, apply)`: `DISTORTION_CORRECTION_MODE = mode` if in `ce0.e0` (`DISTORTION_CORRECTION_AVAILABLE_MODES`).
- `h(FlickerFreeShutterMode, apply)`: `CONTROL_AE_ANTIBANDING_MODE = mode.f` if in `ce0.P` (`CONTROL_AE_AVAILABLE_ANTIBANDING_MODES`) — value 3/AUTO for "flicker free".
- **TONEMAP**: base `a()` applier sets `TONEMAP_MODE = 1 (FAST)` on the active builder when not overridden. **q06.b() (Samsung models ti1.O()/ti1.Q()) sets `TONEMAP_MODE = 0 (CONTRAST_CURVE)` + `TONEMAP_CURVE` = 32-point identical RGB curve + `CONTROL_AE_EXPOSURE_COMPENSATION = steps(ev + 1.0)`** — a Samsung-only preview-brightness quirk (curve + forced +1 EV). Full curve dump in §5.3.
- The td0 D/E/F/G/H generic key-override maps (Int/Long/Float/Byte/Boolean) are applied at session start only — currently **always empty** (created in `manager/a` ln 3773-3801, never filled).

---

## 3. WHITE-BALANCE MATH — exact reconstruction (dt0.java + ColorScienceUtils + libcolor_science.so)

Sources: `jadx_out/sources/defpackage/dt0.java` (205 ln, FULL decompile — the pure-Java mirror of
`libcolor_science.so`), `jadx_out/sources/com/blackmagicdesign/android/camera/ColorScienceUtils.java` (89 ln),
`jadx_fallback/sources/defpackage/rh0.java` (CCM capture + result→(K,T)), `zd0.java` (AWB converger),
`sf0.java` (CCM store), `sh0.java` (CaptureState). `strings libcolor_science.so` shows ONLY two JNI exports —
`Java_com_blackmagicdesign_android_camera_ColorScienceUtils_nativeCalculateWhiteBalance` +
`..._nativeGenerateGains` — zero other constants: **the native lib is byte-for-byte the same math as dt0**.

### 3.1 Dispatch: who calls what

| Operation | Java facade (`ColorScienceUtils`) | Native path | Java fallback (`dt0`) |
|---|---|---|---|
| (kelvin, tint, CCM) → Rggb gains (manual WB) | `e(int kelvin, int tint, ColorSpaceTransform ccm)` | `nativeGenerateGains(kelvin, tint, ccm9float, deviceTintOffset=-130f)` → `float[4]` | `dt0.d()` |
| gains + CCM → (kelvin, tint) (read back / AWB result) | `a(Rggb, Ccm)` / `b(Rggb, float[9])` | `nativeCalculateWhiteBalance(r, ge, go, b, ccm9, -130f)` → `long` (low32=kelvin, high32=tint) | `dt0.a()/b()` |
| CCM rationals → float[9] | `c(ColorSpaceTransform)` | — (Java) | `dt0.c()` |
| gains → (kelvin, tint) heuristic (no color science) | `d(Rggb, Range kelvin, Range tint)` | — | — (pure heuristic, see 3.6) |

`ColorScienceUtils.java:18-26`: `System.loadLibrary("color_science")` guarded by try/catch UnsatisfiedLinkError →
`a=false`; identity fallback `b = new ColorSpaceTransform(int[]{1,1, 0,1, 0,1, 0,1, 1,1, 0,1, 0,1, 0,1, 1,1})`
(row-major identity, rationals n/1). **`deviceTintOffset = -130.0f` is hard-coded in every call** (lines 32, 41, 79).
`LottieType.LOTTIE_TYPE_BASIC` used throughout = `0.0f` (verified: `LottieType.java:5 public static final float LOTTIE_TYPE_BASIC = 0.0f`).

### 3.2 dt0.d(kelvin, tint, ccm) → RggbChannelVector — FORWARD (UI → gains)

```java
// jadx_out/sources/defpackage/dt0.java:53-151
public static RggbChannelVector d(int kelvin, int tint, ColorSpaceTransform ccm) {
    c(ccm, fArr, iArr);                          // fArr = ccm as float[9] (rational num/den, den==0 -> 0)
    float[] inv = inverse3x3(fArr);              // fArr2 = CCM^-1 via adjugate/det (kz2.d = det, tk1.b = 2x2 cofactor);
                                                 // det==0 -> identity (dt0.java:75-79)
    float fExp = (float) Math.exp(-((tint - (-130.0f)) / 200.0f));   // = exp(-(tint+130)/200)   [line 97]
    double d3 = 1000000.0 / kelvin;              // mired                                [line 99]
    float gR=1, gG=1, gB=1;
    for (int i = 0; i < 10; i++) {                // fixed-point refinement, <=10 iterations
        double T  = 1000000.0 / d3;               // temperature in K                    [line 106]
        double T2 = T*T;
        double uP = kz2.p(T2, 7.08145163E-7, (8.42420235E-4*T)+1.0, (1.28641212E-7*T2)+(1.54118254E-4*T)+0.860117757);
        double vP = kz2.p(T2, 1.61456053E-7, 1.0-(2.89741816E-5*T), (4.20481691E-8*T2)+(4.22806245E-5*T)+0.317398726);
        // kz2.p(x,a,b,c) = c / (a*x + b)   (jadx_out/sources/defpackage/kz2.java:126-128)
        double den = kz2.A(vP, 8.0, uP*2.0, 4.0); // = 2u - 8v + 4                       (kz2.java:18-20)
        float x = (float)((uP*3.0)/den), y = (float)((vP*2.0)/den);   // CIE1960(u,v) -> CIE1931(x,y)
        float X = x/y, Z = (1-x-y)/y;                                // white point, Y normalized to 1
        float Wr = -0.4986f*Z + (3.2406f*X - 1.5372f);               // Rec709 XYZ->RGB matrix rows
        float Wg =  0.0415f*X + (-0.9689f)*1   + 1.8758f;
        float Wb =  1.0570f*Z + (0.0557f*X - 0.2040f);
        float rReq  = 1.0f/((inv[2]*Wb)+(inv[1]*Wg)+(inv[0]*Wr));    // sensor-R raw needed for white = 1/gR
        float bReq  = 1.0f/((inv[8]*Wb)+(inv[7]*Wg)+(inv[6]*Wr));    // = 1/gB
        float gGM   = (float) Math.sqrt(rReq*bReq) * fExp;           // green gain = sqrt(gR*gB) * tint factor
        float min   = Math.min(Math.min(rReq, gGM), bReq);
        float k     = min < 1.0f ? 1.0f/min : 1.0f;                  // normalize so min gain >= 1
        gR = rReq*k; gG = gGM*k; gB = bReq*k;
        int kelvinAchieved = e(1.0f/gR, (1.0f/gG)*0.72f, 1.0f/gB, fArr);
        if (Math.abs(kelvin - kelvinAchieved) < 0.1) break;
        double d7 = ((d2 - (1000000.0/kelvinAchieved)) * 0.98) + d3;  // d2 = 1e6/kelvin (target mired)
        d3 = Math.max(40.0, Math.min(1000.0, d7));                    // clamp mired to [40,1000] = [25000K..1000K]
    }
    if (|gR| > Float.MAX_VALUE || |gG| > ... || |gB| > ...) return null;
    return new RggbChannelVector(gR, gG, gG, gB);                     // greenEven == greenOdd == gG
}
```

**Step-by-step meaning (all lines cited from dt0.java):**
1. **Planckian white point at temperature T**: `u_p(T)`, `v_p(T)` are the **Planckian locus in CIE 1960 UCS
   chromaticity space**, given by two rational polynomials in T (kelvin) — constant set verified by evaluation:
   at T=6500 → u_p=0.2005, v_p=0.3103 (true Planckian 6500K ≈ (0.199, 0.310) ✓):
   - `u_p(T) = (1.28641212e-7·T² + 1.54118254e-4·T + 0.860117757) / (7.08145163e-7·T² + 8.42420235e-4·T + 1.0)`
   - `v_p(T) = (4.20481691e-8·T² + 4.22806245e-5·T + 0.317398726) / (1.61456053e-7·T² − 2.89741816e-5·T + 1.0)`
   (INFERENCE: the classic rational-function approximation of the Planckian locus in CIE 1960 uv space — used by
   both dt0 and libcolor_science; constants match no other public constant table in the APK.)
2. **CIE 1960 → CIE 1931 xy** (line 110-113): `x = 3u/(2u−8v+4)`, `y = 2v/(2u−8v+4)`, then `X = x/y`, `Z = (1−x−y)/y`.
3. **XYZ → linear Rec.709 RGB** (lines 116-118) with the exact sRGB/Rec709 matrix (3.2406/−1.5372/−0.4986,
   −0.9689/1.8758/0.0415, 0.0557/−0.2040/1.0570): white displayed as RGB ≈ (1,1,1) after WB.
4. **Apply CCM⁻¹** (lines 119-120): `rReq = 1/(CCM⁻¹ row0 · Wrgb)`, `bReq = 1/(CCM⁻¹ row2 · Wrgb)` — the sensor
   R and B raw values that the *device's own color matrix* maps to neutral white. The **camera's measured CCM is
   a first-class input to manual WB** — without it (identity), gains are pure Rec709-white.
5. **Tint = green-gain offset** (line 121): `gG = sqrt(rReq·bReq)·exp(−(tint+130)/200)`.
   - tint is the **green–magenta axis**: it scales the green gain multiplicatively around the geometric mean of
     the R/B gains. At tint=0 factor = e^(−0.65) ≈ 0.5220; after the min-gain normalization the green gain is
     normalized to ~1 and R/B spread — i.e. **tint 0 means "green gain = GM(R,B) × calibration 0.522"**, the
     −130 offset being the device green baseline (the `deviceTintOffset` JNI arg).
   - tint +50 → factor e^(−0.9) ≈ 0.4066 (green pushed DOWN/magenta up); tint −50 → e^(−0.4) ≈ 0.6703 (green up).
6. **Gain normalization** (lines 122-129): scale all three so `min(gR,gG,gB) = 1` when <1 (gains always ≥ 1).
7. **Fixed-point refinement** (lines 130-144): because green is replaced by GM·tintFactor (not the CCM-native
   green) and gains are renormalized, the *achieved* CCT differs from target; the loop re-estimates the achieved
   kelvin via `e()` (same function used for read-back) and nudges mired by 98% of the error, clamped to
   [40, 1000] mired = **[25000 K .. 1000 K] operating range**; converges to <0.1 K in ≤10 passes.
8. Output: `RggbChannelVector(gR, gG, gG, gB)` — **both greens equal**; null (→ "keep old gains") if overflow.

### 3.3 dt0.e / dt0.f — gains → CCT (golden-section search on the Planckian locus)

```java
// dt0.java:153-195  e(redLin, greenLin, blueLin, ccm9) -> kelvin
float R = ccm[0]*r + ccm[1]*g + ccm[2]*b;  // CCM applied to *reciprocal gains* (sensor raws of a white card)
float G = ccm[3]*r + ccm[4]*g + ccm[5]*b;
float B = ccm[6]*r + ccm[7]*g + ccm[8]*b;
float X = 0.4124f*R + 0.3576f*G + 0.1805f*B;   // Rec709 RGB->XYZ
float Y = 0.2126f*R + 0.7152f*G + 0.0722f*B;
float d = 3f*(0.0193f*R + 0.1192f*G + 0.9505f*B) + 15f*Y + X;   // = X + 15Y + 3Z
if (d == 0f) return DNSConstants.PROBE_THROTTLE_COUNT_INTERVAL; // == 5000 (sentinel fallback CCT)
double u = 4f*X/d, v = 6f*Y/d;                  // CIE 1960 UCS chromaticity
// golden-section minimization of squared uv-distance to the Planckian locus over mired [40, 1000]
double f1 = f(u,v,406.6873708001008), f2 = f(u,v,633.3126291998992);  // golden interior points of [40,1000]
for (int i = 0; i < 30; i++) { ... 0.618033988749895 ... }            // classic golden-section search
return (int)(1000000.0 / ((lo+hi)/2));         // kelvin = 1e6/mired
// dt0.java:197-204  f(u,v,mired) = (u-u_p(1e6/mired))^2 + (v-v_p(1e6/mired))^2   [same rationals as d()]
```
This is a **nearest-point-on-Planckian-locator CCT estimator in CIE 1960 UCS** — mathematically the "u,v
distance" variant of Robertson's method (no iso-temperature-line interpolation — direct golden-section search,
30 iterations, resolution ~1e-9 mired).

### 3.4 dt0.b — gains + CCM → (kelvin, tint) — REVERSE (read-back)

```java
// dt0.java:23-41
public static Pair b(RggbChannelVector g, float[] ccm) {
    float red = 1.0f / g.getRed();                       // reciprocal gains = linear sensor raws
    float blue = 1.0f / g.getBlue();
    float green = (g.getGreenEven() > 0f)                // 0.72 factor: sensor green baseline calibration
            ? (1.0f / ((g.getGreenOdd() + g.getGreenEven()) * 0.5f)) * 0.72f : 0.0f;
    int kelvin = e(red, green, blue, ccm);               // CCT (3.3)
    int tint = 0;
    if (g.getRed() > 0f && g.getBlue() > 0f && g.getGreenEven() > 0f)
        tint = mz4.D((int)((-(float)Math.log(
                 ((g.getGreenOdd()+g.getGreenEven())*0.5f) / (float)Math.sqrt(g.getBlue()*g.getRed())
               )) * 200.0f) - 130.0f), -50, 50);         // clamp to [-50, +50]
    return new Pair(kelvin, tint);
}
```
- **tint read-back formula: `tint = clamp(−200·ln( gG / sqrt(gR·gB) ) − 130, −50, +50)`** where gG is the mean
  of the two green gains. NOTE the log uses the **raw gains** (not the reciprocals) — verified round-trip:
  forward `gG = sqrt(gR·gB)·e^(−(tint+130)/200)` inverts exactly (the min-gain normalization cancels: it
  multiplies all three gains equally).
- The **0.72 green factor appears only in the CCT estimate** (e() input), not in the tint formula.
- `mz4.D(v, min, max)` = `coerceIn` (jadx_fallback/sources/defpackage/mz4.java:80-88).

### 3.5 The 3×3 inverse (dt0.java:61-96) and rational→float (dt0.java:43-51 / ColorScienceUtils.c)

`dt0.c` = `ColorSpaceUtils.c`: `copyElements(int[18])`, `fArr[i] = (den==0) ? 0 : num/den` — ColorSpaceTransform
Rational → float row-major. Inverse: det via `kz2.d(f2,f10,f8*f5,f12, m00*f6−(f9−f11)*f7)` (kz2.java:39-41,
`((c−a·b)·d)+e`) = standard 3×3 determinant `m00·(m11m22−m12m21) − m01·(m10m22−m12m20) + m02·(m10m21−m11m20)`;
if det==0 → identity; else adjugate/det with `tk1.b(a,b,c,d) = (c−a·b)·d` (tk1.java:41-43).

### 3.6 ColorScienceUtils.d — no-lib/no-CCM heuristic (gains → kelvin/tint ranges)

```java
// ColorScienceUtils.java:59-72
float r = clamp01(a74.c(g.getRed(),   1.0f, 3.0f, 0f, 1f));   // red gain   remapped [1..3] -> [0..1]
float b = clamp01(a74.c(g.getBlue(),  1.0f, 3.0f, 0f, 1f));   // blue gain  remapped [1..3] -> [0..1]
float mid = ((r + b) / 2.0f) - 0.5f;
float bluePart = 0.5f + mid;      // leans on blue gain
float redPart  = b - mid;         // leans on red gain
Pair(kelvin = remap(clamp01(c(bluePart, .25f, .75f, 0f, 1f)), [0..1], kelvinRange),
     tint   = remap(clamp01(c(redPart,  .25f, .75f, 0f, 1f)), [0..1], tintRange));
```
Called ONLY from `rh0.java:131-137` when neither the API-36 result keys nor `ColorScienceUtils.a` (native) nor a
cached CCM float[] (`rh0.g`) are available: `ColorScienceUtils.d(gains, ce0.p0 /*kelvin Range*/, ce0.q0 /*tint Range*/)`.
(`a74.c(x,inLo,inHi,outLo,outHi)` = lerp remap — a74.java:19-21; `a74.d(v, from, to)` = linear range remap — a74.java:23-45.)

### 3.7 Where the CCM comes from — rh0 (main repeating-request CaptureCallback)

`jadx_fallback/sources/defpackage/rh0.java:18-141` (onCaptureCompleted, gated on CameraState==ACTIVE):
- **Every ≥333 ms** (line 39: `elapsed > 333`): read `COLOR_CORRECTION_GAINS`, `COLOR_CORRECTION_TRANSFORM`,
  `CONTROL_AWB_MODE` from the TotalCaptureResult. **If AWB_MODE != 0 (device AWB running) and the CCM changed**
  (lines 56-63): cache `ColorScienceUtils.c(ccm)` into `rh0.g` and **persist the 18 rationals joined by ',' into
  SharedPreferences "camera_color_science" / "last_matrix_<cameraId>"** (lines 68-85). → This is BMD's trick to
  keep a per-lens *measured* color matrix even after switching to manual WB: manual WB then uses
  `sf0.a(cameraId)` = last_matrix_<id> (or identity) as the CCM for gain generation
  (`camera/a.smali s()`; see §2.5).
- **Kelvin/tint read-back chain** (lines 87-141), priority order:
  1. `gv4.n()` (SDK ≥ 36): kelvin = result `z90.d()` (hidden `COLOR_CORRECTION_COLOR_TEMPERATURE`),
     tint = result `z90.g()` (hidden `COLOR_CORRECTION_COLOR_TINT`); used directly if `ti1.m()` (Google devices)
     — or if `ce0.U` (key advertised) **and not `ti1.A()`** (not Samsung).
  2. else if gains present + cached CCM + native lib → `ColorScienceUtils.b(gains, ccmFloats)` = **dt0.b** (§3.4).
  3. else if gains present → `ColorScienceUtils.d(gains, ce0.p0, ce0.q0)` (§3.6 heuristic).
  4. else null (HUD shows stale values).
- **Every ≥100 ms** (line 143): construct new `sh0` CaptureState from the result — reads
  `CONTROL_AE_EXPOSURE_COMPENSATION`(×ce0.J step), `STATISTICS_SCENE_FLICKER` (1→50 Hz, 2→60 Hz),
  `LENS_APERTURE` (validated against ce0.G aperture list), `SENSOR_EXPOSURE_TIME` (0→null), `SENSOR_SENSITIVITY`
  (0→null), `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`, `CONTROL_AE_MODE`, `CONTROL_AF_MODE`, cached (kelvin,tint),
  `LENS_FOCUS_DISTANCE`, `LENS_OPTICAL_STABILIZATION_MODE`, `CONTROL_VIDEO_STABILIZATION_MODE`,
  `CONTROL_AE_STATE`, `CONTROL_AF_STATE`, `CONTROL_AWB_STATE`, `COLOR_CORRECTION_GAINS`,
  `COLOR_CORRECTION_TRANSFORM` → `StateFlow<sh0>` (compareAndSet loop lines 230-328). **sh0 = "CaptureState"**
  (sh0.java:342-421 toString): physicalCameraId, aeMode, afMode, aperture, iso, exposureTime, ev, oisMode, eisMode,
  focusDistance, temperature(kelvin), tint, aeState, afState, awbState, colorGains, colorTransformMatrix, sceneFlicker.
- `onCaptureFailed` → emits timestamp on flow `e` (frame-drop counter, rh0.java:333-344).

### 3.8 AWB "converge then convert to manual" — zd0 (AWB region tap callback)

`jadx_fallback/sources/defpackage/zd0.java:24-132` (CaptureCallback, attached by `a.F(regions)` — AWB-tap):
- Skips until result has `COLOR_CORRECTION_GAINS`; persists result iso/exposure + engine.f(result) (kelvin,tint)
  into sh0 (`sh0.a(sh0, iso, exposure, k, t)`).
- Compares new gains vs. previously seen gains (tolerance `10^-2`, lines 66-105): when **all four channels stable
  for >2 consecutive frames**, it: stores final gains, **sets CONTROL_AWB_LOCK = TRUE** in the *pending builder*
  (`zd0.java:115-118`), resets `CONTROL_AWB_REGIONS` to the shared zero-rect (if `ce0.R >= len`), applies
  (`engine.v(null)`), and the manager then re-applies the captured (kelvin,tint) as **manual WB** (camera/a.s
  path) — i.e. **AWB-tap behaves like a one-shot grey-world sample that locks into manual mode**.
- Sentinel in the comparison: first sample compared against `RggbChannelVector(2,1,1,2)` (zd0.java:64).

### 3.9 Summary of constants (clean-room spec)

| Constant | Value | Where | Meaning |
|---|---|---|---|
| deviceTintOffset | −130 | ColorScienceUtils JNI arg | tint↔green-gain log offset: `gG = GM(gR,gB)·e^(−(tint+130)/200)` |
| green baseline | 0.72 | dt0.b/e input | sensor green scale for CCT estimate (2× green sites / green sensitivity calibration) |
| tint scale | 200 | dt0.b/d | `tint = −200·ln(gG/√(gR·gB)) − 130` |
| tint clamp | ±50 | dt0.b (mz4.D) | UI tint range |
| CCT search | mired 40..1000, 30× golden 0.618033988749895 | dt0.e | CCT range 25000K–1000K, golden-section |
| u_p/v_p coeffs | 7.08145163e-7 … 0.317398726 | dt0.d/f | Planckian locus (CIE 1960) rational polys in T[K] |
| XYZ↔RGB | Rec709 matrix (0.4124/0.3576/0.1805 …) | dt0.e | white-point chromaticity math |
| u,v→x,y | x=3u/(2u−8v+4), y=2v/(2u−8v+4) | dt0.d (kz2.A) | CIE1960→CIE1931 |
| d==0 fallback CCT | 5000 (DNSConstants.PROBE_THROTTLE_COUNT_INTERVAL) | dt0.e:161 | degenerate chromaticity |
| CCM store | "camera_color_science"/"last_matrix_<id>", 18 ints ','-joined | rh0/sf0 | measured CCM per lens |
| identity CCM | int[]{1,1,0,1,0,1,0,1,1,1,0,1,0,1,0,1,1,1} | ColorScienceUtils.b / sh0.s | fallback matrix |


## 4. ISP bypass & tonemapping — every EDGE/NR/TONEMAP/SHADING/HOT_PIXEL/STATISTICS key

Exhaustive census (rg over `jadx_fallback/sources` + `jadx_out/sources`): ISP request keys are set in exactly
`camera/a.java` (base engine), `q06` (Samsung), `ws7` (Xiaomi), `rz4` (Oppo); result keys read in `rh0`;
capability keys read in `he0`. **`SHADING_MODE`, `HOT_PIXEL_MODE`, `COLOR_CORRECTION_ABERRATION_MODE`,
`STATISTICS_FACE_DETECT_MODE` are NEVER SET as request keys — the corresponding *_AVAILABLE_MODES /
TONEMAP_MAX_CURVE_POINTS characteristics are only read into the ce0 cache in he0 (capability census), never
compared.** There is no full "ISP bypass" (no BLACK_LEVEL, no manual gamma per-profile); the pipeline keeps the
HAL's default ISP and uses `COLOR_CORRECTION_MODE + GAINS + TRANSFORM` for WB (§3), DynamicRangeProfile +
ColorSpace session params for gamma (§6), and vendor tags for OEM-specific log/sharpness/fps (§5).

### 4.1 Base session applier — `com/blackmagicdesign/android/camera/a.java:603-795` (`a(Builder, td0, extendedKeys)`)

```java
public final void a(CaptureRequest.Builder b, td0 td, boolean extendedKeys) {
    if (td.a == SessionTemplate.i /*RECORD*/ && !td.B /*not HFR*/) set(CONTROL_CAPTURE_INTENT, 3); // VIDEO_RECORD
    p(td.e /*fps*/, false);                                   // CONTROL_AE_TARGET_FPS_RANGE + frame duration
    if (td.k /*autoExposure*/) {
        AutoExposurePriority pr = (this.j && ce0.O.contains(td.l)) ? td.l : SHUTTER_AND_ISO;
        i(pr, true, false, td.n /*ev*/, null, null, 52);      // AE ON path (§2.3)
    } else if (ce0.o0) {                                      // manual exposure supported
        Float ap = ce0.G.contains(td.q) ? td.q : ce0.G.get(0);
        q(td.o /*shutterNs*/, td.p /*iso*/, ap, false);       // SENSOR_* keys (§2.1)
    }
    l(td.f /*autoWB*/, td.g /*awbLock*/, false);              // CONTROL_AWB_MODE / COLOR_CORRECTION_MODE (§2.5)
    if (!td.f) s(td.h /*kelvin*/, td.i /*tint*/, false);      // COLOR_CORRECTION_GAINS/TRANSFORM (§2.5/§3)
    if (td.s /*autoFocus*/) k(true, false); else o(td.t /*focusDistance*/, false);
    z(false, td.j /*zoomRatio*/);                             // CONTROL_ZOOM_RATIO / SCALER_CROP_REGION
    u(td.u /*oisOn*/);                                        // LENS_OPTICAL_STABILIZATION_MODE
    y(td.v /*eisMode*/, false);                               // CONTROL_VIDEO_STABILIZATION_MODE
    m(td.w /*distortion*/, false);                            // DISTORTION_CORRECTION_MODE (if in ce0.e0)
    h(td.r /*flickerFree*/, false);                           // CONTROL_AE_ANTIBANDING_MODE (if in ce0.P)
    t(td.x /*noiseReduction*/, false);                        // NOISE_REDUCTION_MODE (if in ce0.X)
    n(td.y /*edge/sharpening*/, false);                       // EDGE_MODE (if in ce0.W)
    if (this.i != null) this.i.set(CaptureRequest.TONEMAP_MODE, 1);   // line 696: **TONEMAP_MODE = FAST, always**
    if (td.A > 0) w(td.A, td.z); else x(td.z);                // FLASH_MODE / FLASH_STRENGTH_LEVEL (torch)
    if (extendedKeys) { apply td.G (Boolean), td.H (Byte), td.F (Float), td.D (Int), td.E (Long) override maps }
    b(b, td);                                                 // ← OEM override hook: q06.b / rz4.b / ws7.b / wk6.b
}
```
**The one mode-key that is always forced: `TONEMAP_MODE = 1 (FAST)` — for BOTH preview and recording (there is a
single repeating request + session parameters; no separate record request).** The OEM hook `b()` runs last, so
OEM overrides win.

### 4.2 Samsung tonemap quirk — `jadx_fallback/sources/defpackage/q06.java:370-408` (`b()` override)

Only for `ti1.O()` (SM-S921 = Galaxy S24 Ultra) or `ti1.Q()` (SM-S926 = S25 Ultra family):
```java
float[] curve = { 0,0, 0.0322581f,0.13001f, 0.0645161f,0.223851f, ... , 0.967742f,0.98827f, 1f,1f };  // 32 points
b.set(CaptureRequest.TONEMAP_MODE, 0);                       // CONTRAST_CURVE
b.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(curve, curve, curve));  // same curve for R,G,B
b.set(CONTROL_AE_EXPOSURE_COMPENSATION, ah0.d(ce0.I, ce0.J, ev + 1.0f));     // forced +1.0 EV (in steps)
```
Full 32-point curve (x = i/31, y decoded from the float[]):
(0,0) (0.0323,0.1300) (0.0645,0.2239) (0.0968,0.2981) (0.1290,0.3666) (0.1613,0.4164) (0.1935,0.4604)
(0.2258,0.4995) (0.2581,0.5396) (0.2903,0.5728) (0.3226,0.6041) (0.3548,0.6344) (0.3871,0.6657) (0.4194,0.6931)
(0.4516,0.7175) (0.4839,0.7419) (0.5161,0.7664) (0.5484,0.7879) (0.5806,0.8074) (0.6129,0.8270) (0.6452,0.8465)
(0.6774,0.8631) (0.7097,0.8798) (0.7419,0.8954) (0.7742,0.9110) (0.8065,0.9257) (0.8387,0.9384) (0.8710,0.9511)
(0.9032,0.9658) (0.9355,0.9775) (0.9677,0.9883) (1,1) — **a shadow-lifting S-curve** (≈2.2-gamma preview
brightening for Samsung's dim S24/S25 Ultra preview pipeline) paired with +1 EV metering offset.
This is the ONLY custom TONEMAP_CURVE in the entire app. **No log/HDR profile uses a custom tonemap curve** —
10-bit log/HDR is selected via DynamicRangeProfile + ColorSpace session params (§6), not TONEMAP.

### 4.3 EDGE / NOISE_REDUCTION / DISTORTION / stabilization (mode setters)

| Key | Setter | Value | Gate |
|---|---|---|---|
| `EDGE_MODE` | `a.n(mode, apply)` | UI "Sharpening" level int (0..3) | only if in `ce0.W` = `EDGE_AVAILABLE_EDGE_MODES` (he0 read) |
| `NOISE_REDUCTION_MODE` | `a.t(mode, apply)` | UI int | only if in `ce0.X` = `NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES` |
| `DISTORTION_CORRECTION_MODE` | `a.m(mode, apply)` | UI int | only if in `ce0.e0` = `DISTORTION_CORRECTION_AVAILABLE_MODES` |
| `CONTROL_VIDEO_STABILIZATION_MODE` | `a.y(mode, apply)` | UI StabilizationMode | only if in `ce0.C` = `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` (offered when >1 mode ∧ fps<120 ∧ ≤4K — `eh7.s()`, eh7.java:601-619) |
| `LENS_OPTICAL_STABILIZATION_MODE` | `a.u(on)` | on ? 1 (ON) : 0 (OFF) | `ce0.B` = `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` |
| `CONTROL_AE_ANTIBANDING_MODE` | `a.h(FlickerFreeShutterMode, apply)` | mode.f (AUTO=3 for flicker-free) | in `ce0.P` = `CONTROL_AE_AVAILABLE_ANTIBANDING_MODES` |
| `FLASH_MODE` | `a.x(on)` | torchOn ? 2 (TORCH) : 0 | — |
| `FLASH_STRENGTH_LEVEL` (hidden, API 35) | `a.w(strength, on)` | torch brightness 1..max | SDK≥35 ∧ `wd0.a()` = `FLASH_TORCH_STRENGTH_MAX_LEVEL` |
| `org.codeaurora.qcamera3.sharpness.strength` | q06.n / ws7.n | UI 1→2, 2→4, 3→2 (Samsung `ti1.L()||!M()||R()`; Xiaomi `ti1.p0()`) | vendor key in engine's extended-key set |
| `com.mediatek.hdrfeature.hdrMode` | ws7.b | 3 | `ti1.n0()` (Xiaomi 2406APNFA) or `ti1.g0()` ∧ model "25069PTEB" |
| `com.mediatek.smvrfeature.smvrV2Mode` | rz4.p | `{60,120,2}` @120fps; `{lo,hi,2}` for >120 | fps≥120 ∧ key advertised |

### 4.4 Result-side statistics key

- `STATISTICS_SCENE_FLICKER` — the ONLY statistics key read (`rh0.java:164-188`): 1 → "50 Hz", 2 → "60 Hz",
  else 0 → shown in HUD; stored in sh0.sceneFlicker.
- `CONTROL_AE_STATE` / `CONTROL_AF_STATE` / `CONTROL_AWB_STATE` read per-frame into sh0 (HUD focus/exposure rings).

### 4.5 Capability census read in he0 (stored, mostly unused)

`REQUEST_AVAILABLE_CAPABILITIES` (gate: contains 0 BACKWARD_COMPATIBLE; logical cam id ∈ system list),
`SCALER_STREAM_CONFIGURATION_MAP` (gate: `isOutputSupportedFor(35 /*YUV_420_888*/)`), `LENS_FACING`,
`INFO_SUPPORTED_HARDWARE_LEVEL` (recorded, **never compared**), `INFO_VERSION`, `SENSOR_ORIENTATION`,
`SENSOR_INFO_PIXEL_ARRAY_SIZE`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PHYSICAL_SIZE`,
`SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`, `SENSOR_AVAILABLE_TEST_PATTERN_MODES`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`,
`LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`,
`SENSOR_INFO_SENSITIVITY_RANGE`, `SENSOR_INFO_MAX_FRAME_DURATION`, `SENSOR_MAX_ANALOG_SENSITIVITY`,
`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, `CONTROL_ZOOM_RATIO_RANGE`, `TONEMAP_MAX_CURVE_POINTS`,
`SHADING_AVAILABLE_MODES`, `HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES`, `COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES`,
`STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES`, `CONTROL_AVAILABLE_SCENE_MODES`, `DISTORTION_CORRECTION_AVAILABLE_MODES`,
`REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` (ch7), `REQUEST_AVAILABLE_COLOR_SPACE_PROFILES` (ch7, SDK≥34),
hidden `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` (z90.h, SDK≥36), hidden `FLASH_TORCH_STRENGTH_MAX_LEVEL` (wd0.a).

---

## 5. VENDOR TAGS — complete census (how each is constructed & applied)

**Key factory** (`jadx_fallback/sources/defpackage/ah0.java:18-45`): both `ah0.a(type, name)`
(CameraCharacteristics.Key) and `ah0.b(type, name)` (CaptureRequest.Key) use **reflection on the hidden public
constructor** `new CaptureRequest$Key(name, Class)` / `new CameraCharacteristics$Key(name, Class)`.
`ah0.c(characteristics, key)` (`ah0.java:47-73`) probes **`characteristics.getAvailableCaptureRequestKeys()` by
name** — i.e. vendor key support is discovered per-lens through the standard key-enumeration API, not hardcoded.
Engine "extended key" HashSets (`q06.p`, `rz4.p`, `ws7.p`) gate which vendor keys are actually `set()` — a key is
only written when the lens advertised it (built from `ah0.c` checks + per-model whitelists below).

### 5.1 Samsung — engine `q06` (extends camera/a) + info `s06` (extends eh7)

| Tag | Type | Key | Set where / value |
|---|---|---|---|
| `samsung.android.control.pafAvailableMode` | Characteristics, Boolean | q06.r | read once in ctor: `q == ti1.K() \|\| characteristics.get(r)==TRUE` |
| `samsung.android.control.pafMode` | Request, Integer | q06.t | `b()`: =1 when q; `C()/E()`: mirrors CONTROL_AF_MODE (1) on tap-to-focus |
| `samsung.android.control.aeExtraMode` | Request, Integer | q06.s | `i()`: =1 when manual ISO-priority (SHUTTER case) or ISO case active; 0 when released |
| `samsung.android.control.recordingMaxFps` | Request, Integer | q06.u | `p()`: =fps (before base CONTROL_AE_TARGET_FPS_RANGE); whitelisted models: SM-N970/N971, SM-G98x/G78 (ti1.F), SM-N98x (ti1.D), SM-G99x (ti1.J), SM-A70/A71, SM-S90x (ti1.K), SM-S91x/S711 (ti1.L), SM-S92x/S721 (ti1.P), SM-S93x/S731/S948 (ti1.S) |
| `samsung.android.control.recordingMinFps` | Request, Integer | q06.v | added to extended set only for ti1.S() models; set alongside recordingMaxFps |
| `samsung.android.control.shootingMode` | Request, Integer | q06.w | extended set for ti1.K() (SM-S90x) and ti1.S() models |
| `samsung.android.control.recordingDrMode` | Request, Integer | q06.x | `b()` when `ce0.g0.contains(BT2020_VENDOR_LOG)`: **HLG10→3, HDR10→4, HDR10+→1, Vendor Log (Samsung Log)→5, else 0** (p06.a switch; q06.java:329-368) |
| `samsung.android.scaler.availableVideoConfigurations` | Characteristics, int[] | s06.m | parsed in s06 ctor as 6-tuples [w,h,fpsMin,fpsMax,?,?], fpsMax<120 ∧ 16:9 only → Samsung 16:9 fps table (parsed when SDK<36 or SM-S90x); s06.java:42-86 |
| `org.codeaurora.qcamera3.iso_exp_priority.select_priority` | Request, Integer | q06.z | `i()` manual-priority path: =1 (select ISO or shutter axis) |
| `org.codeaurora.qcamera3.iso_exp_priority.use_iso_exp_priority` | Request, Long | q06.A | `i()`: = shutter ns (SHUTTER priority) or 0; only when NOT ti1.S()/ti1.P() |
| `org.codeaurora.qcamera3.iso_exp_priority.use_iso_value` | Request, Integer | q06.B | `i()`: = ISO (ISO priority) or 0 |
| `org.codeaurora.qcamera3.sharpness.strength` | Request, Integer | q06.y | `n()`: EDGE 1→2, 2→4, 3→2 (models ti1.L() \|\| !ti1.M() \|\| ti1.R()) |

Qualcomm AE quirk: on `ti1.S()` (SM-S93x/731/S948) or `ti1.P()` (SM-S92x/S721) the iso_exp_priority keys are NOT
used — manual priority instead sets plain `SENSOR_EXPOSURE_TIME`/`SENSOR_SENSITIVITY` with the other axis = 0
(q06.java:496-529, 561-586). `ti1.O()`(SM-S921)/`ti1.Q()`(SM-S926) SHUTTER_AND_ISO AE → forced AE ON + EV=+1.0
(q06.java:442-457).

### 5.2 Oppo / OnePlus — engine `rz4` (extends camera/a) + info `xz4`/`ox4`/`sz4` (extends eh7)

| Tag | Type | Key | Set where / value |
|---|---|---|---|
| `com.oplus.feature.blackmagic.configstreams` | **Characteristics, int[]** | xz4.n | **THE O-Log2 enabler.** Parsed in xz4 ctor as 6-tuples `[OppoStreamMode(0=SDR,1=LOG,2=DOLBY_HLG,3=HDR10,4=HDR10+), width, height, fpsMin, fpsMax, eisSupported]` → List<OppoStreamConfig> (yz4: "OppoStreamConfig(mode,size,fpsRange,eisSupported)", yz4.java:101-124). Presence of a LOG entry ⇒ `BT2020_VENDOR_LOG` + `BT2020_LOG` offered; DOLBY_HLG entry ⇒ HLG10/DV_PQ/DV_HLG; HDR10/HDR10+ entries gate those (xz4.t + vz4/wz4) |
| `com.oplus.camera.mode` | Request, **byte[]** | rz4.r | `b()` when profile == BT2020_VENDOR_LOG: **`"pro_video_log_mode\0"` (19 bytes)** — rz4.java:40-50. This is how the app selects O-Log2 capture on Oppo/OnePlus |
| `com.oplus.control.custom.detail.info` | Request, **byte[] (64-byte LE struct)** | rz4.s | `b()`: `putInt(kelvin or -1 if autoWB); putInt(tint or 0x7fffffff if autoWB); putInt(-1); putFloat(-1.0f); putFloat(0.0f); putInt(0); putLong(-1)×3; putInt(-1)×2; putFloat(-1.0f)×2` — mirrors manual WB kelvin/tint into Oppo's ISP tuning struct (rz4.java:52-94) |
| `com.oplus.configure.dataspace` | Request, Integer | rz4.t | `b()` when profile == REC709: **0x10C10000** (= RANGE_FULL \| TRANSFER_SMPTE_170M \| STANDARD_BT709 per AOSP data_space layout; INFERENCE on bitfield decode) — rz4.java:96-104 |
| `com.mediatek.smvrfeature.smvrV2Mode` | Request, int[] | rz4.q | `p()` for fps≥120: `{60,120,2}` @120fps, `{range.lower, range.upper, 2}` otherwise (MediaTek HFR EIS mode; rz4.java:172-231) |

Oppo WB path (`rz4.l`/`rz4.s`, rz4.java:109-260): with API-36 keys advertised (`ti1.n()` = Oppo PMW110/CPH2911 or
OnePlus `o()`), manual WB sets the hidden `COLOR_CORRECTION_COLOR_TEMPERATURE`/`COLOR_CORRECTION_COLOR_TINT`
(z90.f/z90.i) instead of gains; AWB-lock converts to kelvin/tint manual; non-API36 fallback uses last measured
gains + last CCM. **Vendor-log sessions on Oppo/OnePlus declare HLG10 DynamicRangeProfile on every output**
(camera/b.java:190-205: profile C ∧ (ti1.m()=oppo ∨ ti1.e()=oneplus) → `b31.I(BT2020_HLG10, ce0)`).

### 5.3 Xiaomi — engine `ws7` + info `xs7`

| Tag | Type | Set where / value |
|---|---|---|
| `xiaomi.video.recordControl` | Request, Integer | `b()`: =1 for `ti1.w()` (Xiaomi 13/13 Pro class models 22111317/22101316U/23021RAAEG…) |
| `org.codeaurora.qcamera3.sharpness.strength` | Request, Integer | `n()`: EDGE 1→2, 2→4, 3→2 for `ti1.p0()` (Xiaomi 24030PN60/24031PN0D) |
| `com.mediatek.hdrfeature.hdrMode` | Request, Integer | `b()`: =3 when `ti1.n0()` (2406APNFA) or Xiaomi 25069PTEB (ws7.java:83-99) |
| `xiaomi.pro.video.movie.enabled`, `xiaomi.pro.video.log.enabled`, `xiaomi.video.cinelook.enabled`, `xiaomi.movie.shot.mode` | Request, Integer | constructed in ws7 static-init but **result discarded** (no setter call found — registered-only) |
| `com.xiaomi.lens.aperture` (Float), `com.xiaomi.lens.apertureMode` (Integer), `com.xiaomi.lens.apertureLock` (Integer) | Request | constructed, no setter call found in ws7 (aperture handled via standard LENS_APERTURE + ce0.G list) |
| `com.xiaomi.lens.info.availableApertures` | Characteristics, float[] | xs7 (aperture list) |
| `com.xiaomi.lens.info.lowResAvailableApertures` | Characteristics, float[] | xs7 (low-res lens apertures) |
| `com.xiaomi.capabilities.algoCustomHFRFpsTable` | Characteristics, int[] | xs7 (custom HFR fps table) |

### 5.4 Sony — engine `wk6` + info `xk6`

| Tag | Type | Set where / value |
|---|---|---|
| `com.sonymobile.control.aeMode` | Request, Integer | `i()`: SHUTTER-priority manual → **9** + exposureTimeHint; ISO-priority → **5** + sensitivityHint; SHUTTER_AND_ISO (≥2 priority modes) → 1; manual `q()` → **0** + AE_LOCK=TRUE (wk6.java:44-173, 191-243) |
| `com.sonymobile.sensor.exposureTimeHint` | Request, Long | = shutter ns (SHUTTER priority) |
| `com.sonymobile.sensor.sensitivityHint` | Request, Integer | = ISO (ISO priority) |
| `com.sonymobile.control.fps` | Request, Float | `p()`: =(float)fps, set before base fps keys, only when advertised (`ah0.c`, wk6.java:32-41,175-189) |
| `com.sonymobile.control.aeAvailableModes` | Characteristics, int[] | xk6 (available Sony AE modes) |
| `com.sonymobile.scaler.availableVideoStreamConfigurationMap` | Characteristics | xk6 (Sony video stream config) |

### 5.5 Qualcomm (generic QUIC) — info `eh7` (all devices)

| Tag | Type | Use |
|---|---|---|
| `org.quic.camera2.streamBasedFPS.info.StreamBasedFPSTable` | Characteristics, int[] | eh7 ctor: 4-tuples [w,h,fpsMin,fpsMax], fpsMax<120 → fps table merged into size/fps lists (eh7.java:85-118) |
| `org.quic.camera2.VideoConfigurations.info.VideoConfigurationsTable` | Characteristics, int[] | registered (ah0.a), no consumer found in this version |
| `org.quic.camera2.VideoConfigurations.info.VideoConfigurationsTableSize` | Characteristics, Integer | registered, no consumer found |
| `org.quic.camera2.customhfrfps.info.CustomHFRFpsTable` | Characteristics, int[] | registered, no consumer found |
| `org.quic.camera2.customhfrfps.info.NumberOfStructureParameters` | Characteristics, Integer | registered, no consumer found |

### 5.6 Metadata vendor tag (MP4 container)

`com.samsung.android.logvideo` — BmdMetadata key id 33 "SAMSUNG_LOG_VIDEO" (BmdMetadata.java:306-312): written
into recorded MP4s when recording in Samsung Log (so Samsung's own gallery/editor recognizes log footage).
Other vendor-adjacent container keys: `com.apple.quicktime.*` (title/director/model/creationdate/location.ISO6709),
`com.apple.proapps.*` (clipID/reel/scene/shot/logNote/isGood — Final Cut Pro interop), `com.blackmagic-design.camera.*`
(audio bitDepth/channels/tracks/sampleRate + more).

### 5.7 OEMs with standard-keys-only treatment

**Google/Pixel (sj2), vivo (ko7), realme (ml5), Motorola (ck4), Nothing (au4)**: zero vendor tags — their CameraInfo
subclasses only reshape standard characteristics (per-model camera-id curation happens in he0 tables). Google gets
no special treatment beyond Pixel 7/8/9 StreamUseCase quirk (§6.3) and API-36 key adoption. Front camera wrapper
`uc7` also standard-only.

## 6. LOG / GAMMA / COLOR-PROFILE SYSTEM — capture config vs monitoring look

### 6.1 The 11 profiles — `jadx_out/sources/com/blackmagicdesign/android/utils/entity/ColorSpaceProfile.java:26-37`

| Enum | gammaDescription | bits `a()` | curve id `b()` (native LUT gen) | MediaCodec profile `c(codec)` |
|---|---|---|---|---|
| REC709 | "Rec.709" | 8 | 0 | H264→8, HEVC→1, AV1→1, APV→−1 |
| SRGB | "sRGB" | 8 | 15 | " |
| P3D65 | "P3 D65" | 8 | 2 | " |
| P3D60 | "P3 D60" | 8 | 14 | " |
| BT2020_HLG10 | "BT.2020 HLG10" | 10 | 1 | H264→512, HEVC→256, AV1→1024, APV→256 |
| **BT2020_LOG** | **"Blackmagic Log HDR"** | 10 | 3 | " |
| **BT2020_VENDOR_LOG** | **"Vendor Log HDR"** | 10 | 20 | H264→−1, HEVC/AV1/APV→4096 |
| BT2020_HDR10 | "BT.2020 HDR10" | 10 | 18 | H264→−1, others→8192 |
| BT2020_HDR10_PLUS | "BT.2020 HDR10+" | 10 | 17 | H264→−1, HEVC/AV1→2, APV→1 |
| BT2020_DOLBY_VISION_PQ | "BT.2020 PQ Dolby Vision" | 10 | 19 | H264→16, HEVC/AV1→2, APV→1 |
| BT2020_DOLBY_VISION_HLG | "BT.2020 HLG Dolby Vision" | 10 | 21 | " |

(c() returns raw MediaCodec KEY_PROFILE ints per codec {H264,H265,AV1,APV} — ft0.b switch; constants are AOSP
MediaCodecInfo.CodecProfile values. Curve ids b() feed `LutProcessor.generateLuminanceLutNative(curveId,…)`
(§ stage 4).) R8 member names (needed to read jadx_fallback): w=REC709 x=SRGB y=P3D65 z=P3D60 A=HLG10
B=BT2020_LOG C=VENDOR_LOG D=HDR10 E=HDR10+ F=DV_PQ G=DV_HLG (verified from
`apktool_out/smali/…/ColorSpaceProfile.smali` const-strings).

### 6.2 Which profiles a lens offers — ch7 (base, all devices) + OEM adds

`ch7` (jadx_fallback/sources/defpackage/ch7.java) — three lazy builders on eh7:
1. **k() = supported DynamicRangeProfiles** (ch7.java:313-360): from `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES`
   → `getSupportedProfiles()`; **8-bit Dolby-Vision DRPs (ordinals 8-11) dropped; every 10-bit DRP additionally
   requires the matching EGL display extension**: HLG10 ⇒ `EGL_EXT_gl_colorspace_bt2020_hlg`, HDR10/HDR10+/DV ⇒
   `EGL_EXT_gl_colorspace_bt2020_pq` (v96.u = live EGL extension query). STANDARD passes unconditionally.
2. **c() = Map<ColorSpace name → List<DRP>>** (ch7.java:231-312): only when SDK≥34 via hidden
   `REQUEST_AVAILABLE_COLOR_SPACE_PROFILES` → `ColorSpaceProfiles.getSupportedColorSpaces(34)` filtered to
   {BT709, BT2020, BT2020_HLG, BT2020_PQ}, each mapped to `getSupportedDynamicRangeProfiles(named, 34)` ∩ k().
3. **b() = supported ColorSpaceProfiles** (ch7.java:27-230): always {REC709} + from c(): "SRGB"→SRGB,
   "DISPLAY_P3"→P3D65, "DCI_P3"→P3D60; "BT2020_HLG"→ DV_HLG if any 10-bit Dolby DRP present **else HLG10**;
   "BT2020_PQ"→ DV_PQ if 10-bit Dolby DRP, **else HDR10+ if HDR10_PLUS DRP, else HDR10**; then from k():
   HLG10→HLG10, HDR10→HDR10, HDR10+→HDR10+, DV_10B DRPs→ (if neither DV profile yet: **DV_HLG when
   ti1.m()=oppo ∨ ti1.e()=oneplus, else DV_PQ**).

**BT2020_LOG and BT2020_VENDOR_LOG are never in the base list** — added only by OEM wrappers:
- **Samsung** (`s06.u()`, s06.java:110-156): if SDK≥36 ∧ model ∈ {ti1.S() = SM-S93x/SM-S731/SM-S948, ti1.U(),
  ti1.a0(), ti1.X(), SM-F761, SM-F971, SM-F976, SM-F776} (S25/Flip7/Fold7 generation) → add **C (Vendor Log) =
  Samsung Log**. Samsung map tweak (s06.t): SM-S928 removes DCI_P3/DISPLAY_P3, all other Samsungs remove
  BT2020/BT2020_PQ (Samsung exposes only the BT2020_HLG group).
- **Oppo/OnePlus** (`xz4.t()`, xz4.java:109-209): from the `com.oplus.feature.blackmagic.configstreams` int[]:
  LOG stream present → add **C (Vendor Log = O-Log2)** and keep **B (Blackmagic Log HDR)** (wz4/vz4: B kept iff
  LOG config); HLG10/DV_PQ/DV_HLG kept iff DOLBY_HLG config; HDR10 / HDR10+ kept iff HDR10 / HDR10_PLUS config.
  → On Oppo, **both** "Blackmagic Log HDR" and "Vendor Log HDR" are implemented via the same O-Log2 LOG vendor
  stream (ht7.l0 maps both B and C → OppoStreamMode.LOG; ht7.java:2406-2444, zz4.a).
- Everything else (Google, vivo, realme, Motorola, Nothing, generic): no log profiles — standard 10-bit profiles only.

Offering gates (`eh7.p()`, eh7.java:524-570): HDR-family profiles (HLG10/LOG/VENDOR/HDR10/HDR10+/DV) additionally
require **SDK≥34 ∧ recording height ≤ 4K (NamedResolution RES_4K_UHD 3840×2160) ∧ fps < 120**. `eh7.o()`
(eh7.java:486-522): REC709 any fps; 8-bit profiles ≤60fps; 10-bit: ≤30fps always, ≤60fps only ≤4K width.

### 6.3 How a recording session is configured per profile — camera/b.java b() (session builder) + b31.I

Per-output (jadx_fallback/sources/com/blackmagicdesign/android/camera/b.java:186-253):
```java
OutputConfiguration oc = new OutputConfiguration(surface);
if (physicalId != null && !hfr) oc.setPhysicalCameraId(physicalId);
if (!hfr /* skip on CONSTRAINED_HIGH_SPEED */) {
    ColorSpaceProfile p = (td.c == BT2020_VENDOR_LOG && (ti1.m() /*oppo*/ || ti1.e() /*oneplus*/))
            ? BT2020_HLG10 : td.c;                       // Oppo vendor-log → HLG10 DRP substitution
    oc.setDynamicRangeProfile(b31.I(p, ce0).f);          // line 198-205
}
oc.setMirrorMode(td.d.f);
long suc = 0;
if (!hfr && ce0.g /*REQUEST_AVAILABLE_CAPABILITIES*/ != null
        && contains(ce0.g, 19 /* REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASES, AOSP hidden API 33 */)) {
    suc = (useCase == PREVIEW) ? 1                      // STREAM_USE_CASE_PREVIEW
        : (ce0.a /*cameraId*/ == "0" && (ti1.t() /*Pixel 7*/ || ti1.u() /*Pixel 8*/ || ti1.v() /*Pixel 9/9 Pro/Fold*/))
            ? 1 : 3;                                    // STREAM_USE_CASE_VIDEO_RECORD — except Pixel 7/8/9 back cam
    oc.setStreamUseCase(suc);                           // line 210-247
} else if (ce0.g == null) throw IllegalStateException("capabilities");
SessionConfiguration sc = new SessionConfiguration(hfr && !ti1.o() ? SESSION_HIGH_SPEED /*1*/ : REGULAR /*0*/,
        outputs, executor, ug0);
if (gv4.m() /*SDK>=34*/ && colorSpaceName != null)
    tg0.f(sc, ColorSpace.Named.valueOf(colorSpaceName)); // sc.setColorSpace — names checked: BT709, SRGB,
                                                        // DISPLAY_P3, DCI_P3, BT2020, BT2020_HLG, BT2020_PQ (b.java:273-419)
sc.setSessionParameters(this.m /* full settings-applied request */);
device.createCaptureSession(sc);
```
**DRP per profile — `b31.I(profile, ce0)` (b31.java:226-316) with cw1.a switch:**

| ColorSpaceProfile | android DynamicRangeProfile set on OutputConfiguration |
|---|---|
| REC709 / sRGB / P3D65 / P3D60 | `STANDARD` (1) |
| BT2020_HLG10 | `HLG10` (2) |
| **BT2020_LOG (Blackmagic Log HDR)** | **`HLG10` (2)** — BMD Log rides the Camera2 HLG10 10-bit pipe |
| **BT2020_VENDOR_LOG** | **`STANDARD` (1)** — the vendor tags switch the actual encoding; EXCEPT Oppo/OnePlus where camera/b substitutes HLG10 |
| BT2020_HDR10 | `HDR10` (4) |
| BT2020_HDR10_PLUS | `HDR10_PLUS` (8) |
| BT2020_DOLBY_VISION_PQ | best of map["BT2020_PQ"]: DV_10B_HDR_REF(16) → DV_10B_HDR_OEM(64) → DV_10B_HDR_REF_PO(32) → DV_10B_HDR_OEM_PO(128) (b31.J fallback → STANDARD) |
| BT2020_DOLBY_VISION_HLG | same priority from map["BT2020_HLG"] |

So the "capture side" of every HDR/log profile = **standard Camera2 keys only**: OutputConfiguration
DynamicRangeProfile + SessionConfiguration ColorSpace + (Samsung) `samsung.android.control.recordingDrMode`
(§5.1) + (Oppo) `com.oplus.camera.mode="pro_video_log_mode"` (§5.2) + codec profile via ColorSpaceProfile.c()
(10-bit HEVC/AV1). No TONEMAP, no vendor gain tricks for log.

### 6.4 How the monitoring LOOK is produced — `u40` (camera-curve descriptors + LUT baking)

Per-profile "camera encoding curve" objects (`u40.b(profile)` via t40.a switch — jadx_fallback/defpackage/u40.java:7-65):

| Profile(s) | Curve name (instance) |
|---|---|
| REC709 / SRGB / P3D65 / P3D60 | "Rec. 709" (vs0) / "sRGB" (ws0) / "Display P3 (P3-D65)" (ss0) / "P3-D60" (us0) |
| BT2020_HLG10, BT2020_DOLBY_VISION_HLG | "HLG" (qs0) |
| BT2020_HDR10, HDR10+, BT2020_DOLBY_VISION_PQ | "PQ (HDR10/Dolby Vision)" (rs0) |
| **BT2020_LOG (BMD Log) and BT2020_VENDOR_LOG** | **Samsung → "Samsung Log" (xs0); Oppo/OnePlus → "OPPO O-Log2" (ts0); all others → "Apple Log 2" (ps0)** |

(Names from vs0/ws0/ss0/us0/qs0/rs0/ps0/xs0/ts0 clinit strings. gs0 = four 2×3×3-matrix curve sets — the
coefficient tables for these log curves; "Apple Log 2" is the shared iOS-codebase curve descriptor
(INFERENCE: dead on generic Android devices since B/C are only offered on Samsung/Oppo/OnePlus, but it is the
fallback branch if a vendor-log profile is ever selected on another OEM).)

Display LUT assembly (`u40.a(profile, lutName)`, u40.java:67-164):
- **"Neutral Rec. 709"** (default look): generic devices → bake the camera curve → Rec709 **natively**
  (`LutProcessor.e(cameraCurve)` → 33³ RGBA16F LUT, cached "lut_lum_<profile>_<N>.bmdlut" per stage 4);
  **Oppo/OnePlus → load the Java-serialized asset `"Oppo-O-Log2-to-Rec709_Gamma24-D65_33_0303.obj"`**
  (AssetManager + ObjectInputStream → Float16Lut, u40.java:88-98).
- Any built-in/creative LUT name → load `"bmc_luts/<name>.obj"`, then **pre-compose camera-curve INTO it**:
  `LutProcessor.a(cameraCurve, creativeLut, "<curve_name_lowercased>_baked_into_<lutName>")` (u40.java:139-161).
- The composed 33³ LUT is uploaded as GL_RGBA16F GL_TEXTURE_3D in the viewfinder/record-clean-feed shader
  (stage 4 §LUT). Recording itself gets the **un-looked** clean feed (LUT only when user enables "record LUT").

### 6.5 Profile strings persisted / exchanged

`ma6.d(string)` (ma6.java:29-63): "BT2020_HLG"→HLG10; **"BT2020_SAMSUNG_LOG"→VENDOR_LOG**; "BT2020_DOLBY_VISION"
→ DV_HLG on Oppo/OnePlus else DV_PQ; other names via valueOf; parse failure → REC709. Clips recorded with a
vendor-log profile are tagged in the MP4 with `com.samsung.android.logvideo` = profile string
(BmdMetadata.b0, id 33 — written by `d50.b` (d50.java:750-759) and read back in `media/manager/a.java:1287,2569`).

**Bottom line (clean-room summary of the log system):** *Blackmagic Log HDR* on Android is the HLG10 Camera2
10-bit pipeline with a log-branded curve descriptor + metadata; *Samsung Log* = HLG10-group device stream +
`samsung.android.control.recordingDrMode=5` + `com.samsung.android.logvideo` tag + "Samsung Log" monitoring
curve; *O-Log2* (Oppo/OnePlus) = vendor LOG stream ("pro_video_log_mode") + HLG10-declared outputs +
"OPPO O-Log2" curve + the bundled O-Log2→Rec709 33³ LUT asset.

## 7. MASTER TABLES

### 7a. CaptureRequest keys — value / formula / set where / purpose

| CaptureRequest key | Value / formula | Set where (file:lines) | Purpose |
|---|---|---|---|
| `CONTROL_CAPTURE_INTENT` | 3 (VIDEO_RECORD) | camera/a.java:611-617 (`a()`), template==RECORD ∧ ¬HFR | HAL video intent |
| `SENSOR_FRAME_DURATION` | `1e9/fps` ns; flicker-free/timelapse `max(1e9/fps, exposure)` | camera/a.q (smali 2855-3107) + p() (2692-2853) | manual frame pacing |
| `SENSOR_EXPOSURE_TIME` | `clamp(shutterNs, SENSOR_INFO_EXPOSURE_TIME_RANGE)`; if `1e9/fps ≤ shutter+110000ns` → `1e9/fps − 110000` | a.q + manager setShutter (`1e9/x`) | shutter speed / angle (§2.2) |
| `SENSOR_SENSITIVITY` | `clamp(iso, SENSOR_INFO_SENSITIVITY_RANGE)` (0 = auto on Sony/Samsung S/P path) | a.q / q06.i / wk6.i | ISO |
| `LENS_APERTURE` | requested ∨ last (must be in ce0.G) | a.q (aperture else sh0.d) | iris (Samsung/Xiaomi dual-aperture) |
| `LENS_FOCUS_DISTANCE` | `clamp(diopters, [0, LENS_INFO_MINIMUM_FOCUS_DISTANCE])` | a.o (2651-2690) | manual focus |
| `CONTROL_AF_MODE` | auto: 3→4→5→1 preference; manual: 0 (OFF) | a.k (2331-2455) | AF engine select |
| `CONTROL_AF_TRIGGER` | CANCEL(2)→START(1)→IDLE(0) sequence | a.C/a.E (2119-2329) | tap-to-focus |
| `CONTROL_AF_REGIONS` / `CONTROL_AE_REGIONS` / `CONTROL_AWB_REGIONS` | `MeteringRectangle(Rect, weight=1000)`; reset = `ah0.a` (zero-rect w=0) | a.C/D/E/F (2119-2650) | tap metering |
| `CONTROL_AE_MODE` | 1 (ON) when priority AE; 0 (OFF) for manual | a.i (2119-2329), q06.i | AE switch |
| `CONTROL_AE_LOCK` | boolean | a.i / yd0 (converge→TRUE) | AE lock |
| `CONTROL_AE_EXPOSURE_COMPENSATION` | `steps.clamp(round(ev/step))` (`ah0.d`); Samsung quirk: `steps(ev+1.0)` | a.i; q06.b (373-407) | EV |
| `CONTROL_AE_PRIORITY_MODE` (hidden, API 34/36) | priority enum | a.i via `z90.c()` when SDK≥36 ∧ ≥2 priority modes | per-axis AE priority |
| `CONTROL_AE_TARGET_FPS_RANGE` | normal `[fps,fps]`; HFR from `getHighSpeedVideoFpsRanges()` (upper==fps, prefer lower==fps) | a.p (2692-2853) + e(); rz4.p; q06.p | fps lock |
| `CONTROL_AE_ANTIBANDING_MODE` | FlickerFreeShutterMode.f (AUTO=3) if in ce0.P | a.h (989-…) | flicker-free shutter |
| `CONTROL_AE_PRECAPTURE_TRIGGER` | START(1)→IDLE(0) | a.C; yd0/zd0 reset | AE precapture converge |
| `CONTROL_AWB_MODE` | 1 (AUTO) / 0 (OFF) | a.l (2457-2561), q06.l, rz4.l | WB switch |
| `CONTROL_AWB_LOCK` | boolean | a.l; zd0 (converge→TRUE) | WB lock |
| `COLOR_CORRECTION_MODE` | manual: 0 (CANCEL); auto: 1 (FAST); API-36: 3 (TRANSFORM_MATRIX) | a.s (3109-3209) | WB matrix control |
| `COLOR_CORRECTION_GAINS` | `RggbChannelVector(gR, gG, gG, gB)` from `ColorScienceUtils.e(kelvin,tint,ccm)` (§3) | a.s; q06.l (measured gains) | manual WB |
| `COLOR_CORRECTION_TRANSFORM` | last measured CCM (`last_matrix_<id>`) or identity | a.s; q06.l | manual WB color matrix |
| `COLOR_CORRECTION_COLOR_TEMPERATURE` / `_TINT` (hidden API 36) | kelvin / tint ints | a.s + rz4.s (z90.f/z90.i) | API-36 native manual WB |
| `CONTROL_ZOOM_RATIO` | `a74.d(uiRatio, uiRange, deviceRange)` remap | a.z (3448-3538) | zoom |
| `SCALER_CROP_REGION` | centered crop `Rect(cx−w/(2r), cy−h/(2r), …)` | a.z (CONTROL_ZOOM_RATIO unavailable) | zoom fallback |
| `CONTROL_VIDEO_STABILIZATION_MODE` | mode int if in ce0.C | a.y | EIS |
| `LENS_OPTICAL_STABILIZATION_MODE` | on ? 1 : 0 | a.u | OIS |
| `EDGE_MODE` | sharpening int if in ce0.W | a.n (+q06/ws7 vendor strength) | sharpening |
| `NOISE_REDUCTION_MODE` | int if in ce0.X | a.t | NR |
| `DISTORTION_CORRECTION_MODE` | int if in ce0.e0 | a.m | lens distortion |
| `TONEMAP_MODE` | **1 (FAST) always**; 0 (CONTRAST_CURVE) on SM-S921/S926 | a.a:696; q06.b:398-400 | ISP tonemap |
| `TONEMAP_CURVE` | 32-pt identical RGB lift curve (§4.2) | q06.b:401-404 | Samsung preview brightness |
| `FLASH_MODE` | TORCH(2)/OFF(0) | a.x/a.w | torch |
| `FLASH_STRENGTH_LEVEL` (hidden API 35) | 1..`FLASH_TORCH_STRENGTH_MAX_LEVEL` | a.w via `wd0.b()` | torch brightness |
| Vendor request keys | §5 table | q06/rz4/ws7/wk6 `b()/i()/p()/n()/s()/l()` | OEM behaviors |

### 7b. CameraCharacteristics reads — gate — expected values

| Characteristic | Read where | Gate / expected |
|---|---|---|
| `REQUEST_AVAILABLE_CAPABILITIES` | he0 probe (341-364) | non-null ∧ **contains 0** (BACKWARD_COMPATIBLE); logical (11) IDs must be in system list; STREAM_USE_CASE usage requires **19** (hidden, API 33) |
| `SCALER_STREAM_CONFIGURATION_MAP` | he0, eh7 | non-null ∧ `isOutputSupportedFor(35 /*YUV_420_888*/)`; sizes/fps source |
| `SENSOR_INFO_EXPOSURE_TIME_RANGE` / `SENSOR_INFO_SENSITIVITY_RANGE` / `SENSOR_INFO_MAX_FRAME_DURATION` | eh7.q (572-592) | **all three non-null = manual shutter/ISO** (ce0.o0) |
| `CONTROL_AE_AVAILABLE_PRIORITY_MODES` (API 34) | eh7.a (122-156) | SDK≥36; 1→ISO, 2→SHUTTER; ≥2 entries enables per-axis AE |
| `COLOR_CORRECTION_AVAILABLE_MODES` (API 36) | eh7.n (467-484) | contains **3 (TRANSFORM_MATRIX)** = API-36 kelvin/tint manual WB (ce0.U) |
| `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` (hidden 36) | he0 via z90.h | replaces default **kelvin Range 2500–10000** (he0.java:1290-1296); tint default **−50…+50** |
| `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` | eh7.l (414-438) | >1 mode ∧ fps<120 ∧ ≤4K → stabilization offered |
| `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` | he0 | OIS toggle |
| `CONTROL_ZOOM_RATIO_RANGE` | eh7.m (440-465) | fallback `Range(1, SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)` |
| `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` / `SENSOR_INFO_PHYSICAL_SIZE` | he0/eh7.g (287-333) | 35mm-equiv = `43.3/diagonal × f` |
| `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` | ch7(0) | 10-bit profiles, EGL-ext gated (`EGL_EXT_gl_colorspace_bt2020_hlg` / `_pq`) |
| `REQUEST_AVAILABLE_COLOR_SPACE_PROFILES` (hidden 34) | ch7(1) via tg0 | names ⊆ {BT709, BT2020, BT2020_HLG, BT2020_PQ} |
| `LENS_INFO_MINIMUM_FOCUS_DISTANCE` | eh7.e (180-199) | focus range [0, min] (catch → [0,0]) |
| `FLASH_TORCH_STRENGTH_MAX_LEVEL` (hidden 35) | he0 via wd0.a | torch strength |
| `INFO_SUPPORTED_HARDWARE_LEVEL` | he0 | recorded into ce0, **never compared** (even LIMITED works) |
| `SHADING_AVAILABLE_MODES`, `HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES`, `COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES`, `STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES`, `TONEMAP_MAX_CURVE_POINTS`, `CONTROL_AVAILABLE_SCENE_MODES` | he0 | cached only; no request-side usage |
| Vendor characteristics | s06 / xz4 / xs7 / xk6 / eh7 | `samsung.android.scaler.availableVideoConfigurations`, `com.oplus.feature.blackmagic.configstreams`, `com.xiaomi.lens.info.*`, `com.sonymobile.*`, `org.quic.camera2.*` (§5) |

### 7c. Corrections to earlier sections of this document (jadx_fallback cross-check)

- §2.7 said "capabilities contains 25" for StreamUseCase — the fallback decompile shows **19**
  (`tn.d0(19, ce0.g)` in camera/b.java:216-221) = `REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASES` (AOSP hidden,
  API 33). 19 is correct; 25 was a smali misread.
- §2.7's "back camera + certain devices → 1L" is precisely: cameraId=="0" ∧ **Pixel 7 / Pixel 8 / Pixel 9 (incl.
  Pro/Fold)** (ti1.t()/u()/v()) → record stream gets STREAM_USE_CASE_PREVIEW(1) instead of VIDEO_RECORD(3).
- The Oppo CameraInfo wrapper instantiated by he0 is **`sz4`** (concrete, extends xz4); OnePlus is `ox4`
  (he0.java:703-770). ti1.m() = manufacturer "oppo", ti1.e() = "oneplus" (ti1.java:342-344, 231-233) — so
  "ti1.m()||ti1.e()" everywhere above = Oppo-or-OnePlus.
- §2.5's s() branch: `ce0.U` comes from `eh7.n()` = SDK≥36 ∧ COLOR_CORRECTION_AVAILABLE_MODES ∋ 3.

### 7d. Numeric defaults (code-side; res has no numeric arrays)

- Kelvin UI range: **2500–10000 K** default (he0.java:1290-1296); device range via hidden API-36 key when present.
- Tint UI range: **−50…+50** (he0.java:1298+; dt0.b clamp) — 1 tint unit = 1/200 ln() green-gain step (§3.4).
- Shutter: UI is shutter **angle 0–360°** (default 180°); `exposure_ns = 1e9·angle/(360·fps)`; angle preserved
  across fps changes (§2.2). 110 µs headroom snap vs frame duration.
- Frame-duration/AE ranges and ISO come from characteristics (7b). WB gains normalized so min ≥ 1.
