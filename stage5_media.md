# STAGE 5 — MediaCodec Video & Audio Encoding Pipeline (Blackmagic Camera v3.5.0.0064)

Clean-room static analysis. Sources: `/home/z/my-project/jadx_fallback/sources` (complete R8 "fallback"
decompile — register-style Java quoted verbatim; R8 field letters mapped via usage + `toString()`),
`apktool_out/` resources, native `.so` strings. Inference labeled **[inference]**.

**Identity map (R8 names → real names, proven by `toString()` strings & usage):**

| R8 class | Real identity |
|---|---|
| `com.blackmagicdesign.android.recorder.a` | `MediaCodecSession` (extends `qy` = BaseRecorder) — one per recording; owns N track sessions |
| `defpackage.hk7` | `VideoConfig` (per-stream video encoder config) |
| `defpackage.ip` | `AudioConfig` (per-recording audio config) |
| `defpackage.i96` | `RecordingSessionConfig` (encoder names + video configs list `g` + audio configs list `h` + TimeCode `e` + slate `f` + capture/playback fps `c`/`d`) |
| `defpackage.mk7` | `VideoEncoder` wrapper (extends `yu1` = MediaCodec wrapper base) |
| `defpackage.rp` | `AudioEncoder` wrapper (async, queue-fed) |
| `defpackage.yu1` | abstract `MediaCodec` wrapper base (`MediaCodec.createByCodecName`) |
| `defpackage.qp` | `MediaCodec.Callback` bridge → `zu1` (encoder session callback interface) |
| `defpackage.u84` | video encoder output-drain callback (CFR PTS rewriter + tmcd writer + file-muxer writer) |
| `defpackage.t84` | audio encoder output-drain callback |
| `defpackage.eq6` / `s32` | `RecordingTrackSession` (video+audio+timecode tracks, muxer ref) |
| `defpackage.f47` | `TrackInfo` (MediaFormat + muxer track index + first PTS) |
| `defpackage.d50` | `Mp4RecordingMuxer` facade (co4 backend; fragmented or progressive) |
| `co4` (iface) | `Muxer` interface — impls: `rb2` = FragmentedMp4Muxer (wraps `vb2`), `tk4`/`uk4` = Mp4Muxer (wrap `xk4`), `ny2` = metadata-buffering wrapper over `tk4` |
| `defpackage.vb2`/`tb2`/`hl4`/`k32` | fMP4 track/fragment engine + box writer + FileChannel sink |
| `defpackage.xk4` | progressive MP4 box writer (`ftyp`, `mdat`) |
| `defpackage.j74` | `mdta` metadata item (`"mdta: key=..., value=..."`) |
| `defpackage.o40` | slate/clip metadata model (feeds BmdMetadata items) |
| `defpackage.m97` | MediaFormat factory (audio: `T()`, `U()` mime) |
| `defpackage.mz4` | codec factory: `e0()` mime map, `Q()` VideoConfig factory, `h0()` transfer map, `g0`/`f0` profile/level names |
| `defpackage.nr0` | bitrate formula + MediaCodecList codec queries (`c`,`d`,`e`,`f`,`g`,`h`,`i`,`j`) |
| `defpackage.ks` | audio capture engine (source + monitor player + meter hookup) |
| `defpackage.z5` (abs) | audio source interface (`wo4` = AAudio native, `hp` = AudioRecord) |
| `defpackage.js` | audio capture loop Runnable + source selector |
| `defpackage.cs` | per-sink audio distributor state |
| `defpackage.ar`/`vd`/`yr`/`ns` | audiometer engine (thread `"audiometer"`) + dB formulas + AudioState |
| `defpackage.uu1` | live-stream encoded-frame forwarder (CSD attach + CFR + push to GStreamer/davstream) |
| `defpackage.iq6` (abs) | streaming sink (pushVideo/pushAudio) |
| `recorder.timecode.a` | TimecodeManager |

---

## 1. Media API usage map (every class touching android.media)

| Class (path under jadx_fallback/sources) | Media API used | Role |
|---|---|---|
| `defpackage/p84.java` | `MediaFormat.createVideoFormat`, `MediaCodec.createPersistentInputSurface()` | **Video encoder config builder** (the runnable posted per video track inside MediaCodecSession). Builds the full video MediaFormat (below), reuses a persistent input surface across takes, launches `mk7` VideoEncoder. |
| `defpackage/mk7.java` (extends `yu1`) | `MediaCodec.configure(format,null,null,CONFIGURE_FLAG_ENCODE)`, `createInputSurface()`/`setInputSurface()`, `start()`, `setParameters("request-sync")` | VideoEncoder wrapper; surface-input encoder |
| `defpackage/yu1.java` | `MediaCodec.createByCodecName(name)`, `setParameters("drop-input-frames")`, `stop()/release()` | encoder wrapper base; **codec chosen by explicit name** |
| `defpackage/qp.java` | `MediaCodec.setCallback(this, handler)` | async callback bridge (onOutputBufferAvailable → `zu1.a/b/c/d`) |
| `defpackage/u84.java` | `MediaCodec.BufferInfo` mutation | video drain: CFR PTS rewrite, tmcd samples, fMP4 writes, live-stream tee |
| `defpackage/m97.java` | `new MediaFormat()` (audio) | audio MediaFormat factory `T()`/mime `U()` |
| `com/blackmagicdesign/android/recorder/a.java` (MediaCodecSession) | `MediaCodec` (via mk7/rp), `MediaFormat` (tmcd track), `MediaCodec.BufferInfo` | session orchestration: configures video+audio encoders, creates muxer `d50`, adds tracks, start/stop |
| `defpackage/rp.java` | `MediaCodec.configure(format,null,null,ENCODE)` + `start()` (async mode) | AudioEncoder wrapper |
| `defpackage/js.java` | `android.media.AudioRecord` (ctor + `getMinBufferSize`), `AudioTimestamp` | audio capture loop + **source selector** (AAudio vs AudioRecord) |
| `defpackage/hp.java` | `AudioRecord.read(short[],0,len,0)`, `AudioRecord.getTimestamp(ts,0)` | AudioRecord source wrapper |
| `com/blackmagicdesign/android/recorder/audio/NativeAudioEngine.java` | JNI `libbmdaudio.so` (AAudio) | native audio capture for >2 channels / 24–32-bit |
| `defpackage/d50.java`, `rb2/tk4/uk4/ny2`, `vb2/tb2/xk4/hl4/k32` | **NO `MediaMuxer`** — custom ISO-BMFF writers | MP4/fMP4 recording muxers (media3-muxer-fork style + jcodec box model) |
| `com/blackmagicdesign/android/metadataeditor/**` (jcodec fork) | FileChannel box parsing/writing | post-record moov edits: tmcd track injection, bmdt box, udta/meta/mdta slate rewrite |
| `defpackage/nr0.java` | `MediaCodecList`, `MediaCodecInfo` (`getBitrateRange`, `VideoCapabilities.isSizeSupported`, `profileLevels`, `isSoftwareOnly`) | codec capability queries (max bitrate, 10-bit profile support, size support) |
| `defpackage/z92`, `fh4`, `mz4.g0/f0` | MediaFormat profile/level ↔ strings | codec-capability negotiation + display strings |
| `defpackage/pe5.java` | `MediaCodec` decoder (createVideoFormat for decode) | remote-preview decoder (per Task 6) |
| `com/arashivision/*` | `MediaCodec` (Insta360 SDK) | out of scope (dormant path) |
| `defpackage/eo4/q57` (media3 Transformer `MuxerWrapper`) | androidx.media3.transformer/muxer | **proxy/transcode export path** (separate from recording muxer) |

**Zero `MediaMuxer` usage confirmed** (grep over complete jadx_fallback tree: no `MediaMuxer(` constructor call anywhere in BMD code).

---

## 2. Video encoding — exact MediaFormat configuration

### 2.1 The mime map (`mz4.e0`, quoted; `defpackage/mz4.java:4267`)

```java
public static final String e0(VideoCodecFormat codec, ColorSpaceProfile profile) {
    switch (codec.ordinal()) {
        case 0: return "video/avc";
        case 1:  // HEVC
            if (profile == ColorSpaceProfile.F || profile == ColorSpaceProfile.G)
                 return "video/dolby-vision";
            return "video/hevc";
        case 2: return "video/av01";
        case 3:  // APV
            if (gv4.n()) return "video/apv";       // gv4.n() = SDK >= 36 (Android 16)
            h3.o("APV is not supported below Android 16.");
            return null;
    }
}
```
- `VideoCodecFormat` enum (`recorder/entity/VideoCodecFormat.java`): **f=H264, i=HEVC, m=AV1, w=APV**.
- **Dolby Vision = HEVC codec + ColorSpaceProfile F or G** → mime `video/dolby-vision` (a 5th mime produced only when an HDR Dolby profile is active).
- **AV1 & APV are device/API-gated**: APV requires Android 16 (SDK≥36, `gv4.n()`); codec presence is then verified by `nr0.f(mime)` (MediaCodecList encoder query, hardware-only unless `"video/apv"` — see below) and 10-bit profile support by `nr0.g(profile, mime)`.

### 2.2 The full video MediaFormat (`defpackage/p84.java` — the encoder-setup runnable, quoted verbatim)

```java
hk7 cfg = trackConfigs.get(i);                     // defpackage.hk7 = "VideoConfig"
String mime = mz4.e0(cfg.a /*VideoCodecFormat*/, cfg.o /*ColorSpaceProfile*/);
MediaFormat fmt = MediaFormat.createVideoFormat(mime, cfg.b /*width*/, cfg.c /*height*/);
fmt.setString("mime", mime);
fmt.setInteger("color-format", 2130708361);        // 0x7F000789 = COLOR_FormatSurface
// ---- color standard ----
VideoColorStandard std = cfg.p ? (tag case 1 -> VideoColorStandard.m /*BT709*/,
                                            case 2 -> VideoColorStandard.w /*BT2020*/)
                               : cfg.u;
if (std != VideoColorStandard.i /*DEFAULT*/) fmt.setInteger("color-standard", std.f);
// Qualcomm vendor key:
if (ti1.A() && cfg.f /*bitDepth*/ == 8) fmt.setInteger("vendor.qti-ext-enc-colorspace-conversion.enable", 1);
// ---- color transfer + range ----
VideoColorTransfer xfer = cfg.p ? mz4.h0(cfg.n /*ColorSpaceTag*/, cfg.a, cfg.w /*profile*/) : cfg.v;
if (xfer != VideoColorTransfer.i) {
    int range = (ti1.m() || ti1.e()) && cfg.o == ColorSpaceProfile.C ? 1 : 2;
    fmt.setInteger("color-range", range);
    if (ti1.A() && cfg.o == ColorSpaceProfile.C && !cfg.p) {      // Qualcomm + profile C
        fmt.setInteger("vendor.qti-ext-enc-client-set-color-transfer-enable.value", 1);
        fmt.setInteger("color-transfer", 0);                      // => 0 (UNSPECIFIED)
        fmt.setInteger("color-transfer-request", 0);
    } else if ((ti1.m() || ti1.e()) && cfg.o == ColorSpaceProfile.C && !cfg.p) {
        fmt.setInteger("color-transfer", 3); fmt.setInteger("color-transfer-request", 3);
    } else {
        fmt.setInteger("color-transfer", xfer.f); fmt.setInteger("color-transfer-request", xfer.f);
    }
}
// ---- HDR editing features (ColorSpaceProfile ordinal switch, nm7.a) ----
// cases 9,10,11: fmt.setFeatureEnabled("hdr-editing", true);
// cases 5..8  : if (gv4.n()) fmt.setFeatureEnabled("hlg-editing", true);   // SDK>=36
// ---- bitrate & geometry ----
fmt.setInteger("bitrate", cfg.g);                   // see §2.4 formula
fmt.setInteger("max-width",  width);
fmt.setInteger("max-height", height);
if (cfg.w != -1) { fmt.setInteger("profile", cfg.w); fmt.setInteger("level", cfg.x); }
fmt.setInteger("priority", 0);
fmt.setInteger("max-input-size", 0);
fmt.setFloat("i-frame-interval", cfg.h);            // seconds (1.0 from factory)
fmt.setFloat("intra-refresh-period", cfg.h);        // same value as i-frame-interval!
// ---- rate control keys ----
if (cfg.i /*highSpeed/slow-mo*/) {
    fmt.setFloat("capture-rate", 1.0f / cfg.j /*playback fps*/);      // slow-motion stretch
} else if (cfg.e /*playbackFps*/ != cfg.d /*captureFps*/ && ti1.o()) {
    fmt.setFloat("operating-rate", cfg.e);
    fmt.setFloat("capture-rate",     cfg.e);
}
fmt.setFloat("frame-rate", cfg.e /*playbackFrameRate*/);
fmt.setInteger("stride",       width);
fmt.setInteger("slice-height", height);
// ---- B-frames / ts-schema — queried from the chosen codec's capabilities ----
if (streamType != StreamType.m /*not PROXY*/) {
    z92 capsQuery = new z92(); capsQuery.o = fh4.k(mime) /*codec*/;
    ok7 caps = ir0.a(capsQuery.b(playbackFps).v(width).w(height).a());   // MediaCodecInfo query
    if (caps.d > 0 && !cfg.i) fmt.setInteger("max-bframes", caps.d);
    fmt.setString("ts-schema", caps.e > 0 ? "android.generic." + caps.e + "+" + caps.f : "none");
}
```
Then the encoder is created with an **explicit codec name** and the persistent input surface:

```java
iu6 s = persistentSurfaces.get(sessionKey);          // reused across takes
if (s == null) { s = new iu6(); s.a = MediaCodec.createPersistentInputSurface(); ... }
// wait up to 5x100ms for previous take to release it, then:
mk7 encoder = new mk7(fmt, cfg.s /*videoEncoderName!*/, playbackFps);
encoder.f = s.a;                                     // setInputSurface()
encoder.d();                                        // configure+start (see mk7)
// error translation (throws RecorderException$...):
//   "VideoCodec failed. Format: "+fmt
//   video/avc  profile 16 (High10) / 32 (High422)           -> BitDepthException
//   video/apv  (any)                                        -> BitDepthException
//   video/hevc profile 2 (Main10) / 4096 / 8192             -> BitDepthException
//   video/av01 profile != 1 (i.e. 10-bit AV1)              -> BitDepthException
//   fps > 30 -> FpsException; height >= 2160 -> ResolutionException;
//   bitrate >= 30,000,000 -> BitrateException; else OtherException
```

### 2.3 Exact key values

| MediaFormat key | Value | Notes / source |
|---|---|---|
| `mime` | `video/avc` \| `video/hevc` \| `video/dolby-vision` \| `video/av01` \| `video/apv` | `mz4.e0()` |
| `color-format` | **2130708361 = COLOR_FormatSurface** | always — surface input only |
| `color-standard` | omitted if DEFAULT(0); else **BT709=1, BT601_PAL=2, BT601_NTSC=4, BT2020=6** (`VideoColorStandard.f`) | SDR→BT709; HDR (ColorSpaceTag case 2)→BT2020 |
| `color-range` | **2 (limited)** default; **1 (full)** when (ti1.m()‖ti1.e()) ∧ profile==ColorSpaceProfile.C | `ti1.m/e` are OEM predicates (Samsung/Pixel-class) |
| `color-transfer` + `color-transfer-request` | `SDR_VIDEO=3` (SMPTE170M), `HLG=7`, `ST2084=6` (`VideoColorTransfer.f`); **0** with Qualcomm vendor key when profile C | `VideoColorTransfer` enum: DEFAULT(0)/SDR_VIDEO(3)/LINEAR(1)/HLG(7)/ST2084(6) |
| `vendor.qti-ext-enc-colorspace-conversion.enable` | **1** if `ti1.A()` (Qualcomm device) ∧ bitDepth==8 | lets QTI encoder do BT2020→BT709 downconvert for 8-bit |
| `vendor.qti-ext-enc-client-set-color-transfer-enable.value` | **1** (Qualcomm + ColorSpaceProfile.C, non-tag path) with color-transfer=0 | QTI client-side transfer override |
| `setFeatureEnabled("hdr-editing")` | true for ColorSpaceProfile ordinals 9–11 | HDR10-family recording profiles |
| `setFeatureEnabled("hlg-editing")` | true for ordinals 5–8 **if `gv4.n()` (SDK≥36)** | HLG-family profiles |
| `bitrate` | computed (§2.4), clamped to device max (§2.4) | `KEY_BIT_RATE` |
| `profile` / `level` | `cfg.w`/`cfg.x` if != −1; **requested** 10-bit profiles observed: avc High10=16, hevc Main10=2 (Main10HDR10=4096, Main10HDR10Plus=8192), av1 Main10=2 (AV1ProfileMain8=1 is the 8-bit), dolby 1/2/4/8/16/32/64/128/256/512/1024 | set only when device/profile chosen; re-read from encoder output & stored into clip metadata (`u84.b()`) |
| `max-width` / `max-height` / `stride` / `slice-height` | width/height | also fed to muxer tracks |
| `priority` | **0** (realtime) | |
| `max-input-size` | **0** (surface input — encoder decides) | |
| `i-frame-interval` | **1.0 s** — hardcoded `1065353216` in `mz4.Q()` factory (`r26 = 1065353216 /* 1.0f */`); also written as `intra-refresh-period` (same value) | 1-second GOP for both |
| `frame-rate` | playbackFrameRate | |
| `capture-rate` | `1/playbackFps` when slow-motion (high-speed capture); or `playbackFps` when capture≠playback on `ti1.o()` devices (with `operating-rate`) | Android high-speed record semantics |
| `operating-rate` | playbackFps (same condition as above) | |
| `max-bframes` | from codec capability query (`ok7.d`), 0 when high-speed; skipped for proxies | only for ORIGINAL stream |
| `ts-schema` | `"android.generic.<n>+<m>"` or `"none"` (from caps) | Android temporal-layer schema |
| **`latency`** | **never set** | no KEY_LATENCY anywhere in BMD code |
| adaptive-playback keys | **none set on encoders** | decoder-only concern (pe5 remote preview) |

**No BITRATE_MODE key is ever set** — CBR/VBR/CQ is entirely up to the vendor codec (no `BITRATE_MODE_*` constant in the tree); BMD controls rate via `bitrate` + `i-frame-interval` + `max-bframes` only. **[verdict: rate-mode = codec default]**

### 2.4 THE BITRATE FORMULA (`nr0.d`, `defpackage/nr0.java:111` — quoted, decoded)

```java
static final int d(StreamType type, int fps, Size size, BitrateLevel level,
                   int maxSupported /* = nr0.c(mime) */, LiveStreamQuality q, Codec codec) {
    maxSupported = Math.min(maxSupported, 1_600_000_000);      // hard cap 1.6 Gbps
    float baseFps = fps >= 50 ? 9_000_000f : 6_000_000f;
    switch (type) {                                            // mr0.b: PROXY=1, LIVE_STREAM=2
        case PROXY:       return (int) baseFps;               // 6 or 9 Mbps fixed
        case LIVE_STREAM: return q.f * 1_000_000;             // LiveStreamQuality.f: LOW=3, MEDIUM=6, HIGH=9 (Mbps)
        default:           // ORIGINAL
            if (nr0.a > 0) return nr0.a;                      // static override (debug knob, set via zc.java)
            if (codec == Codec.B /*APV*/ && level == BitrateLevel.w /*MAX*/)
                return maxSupported;                          // APV MAX = device max bitrate
            if (codec == Codec.B /*APV*/) {
                int tier = width >= NamedResolution.RES_8K_UHD.width  ? 8 :
                           width >= NamedResolution.RES_4K_UHD.width  ? 4 :
                           width >  NamedResolution.RES_FHD.width      ? 2 : 1;
                float levelScale = (level == HIGH) ? 2.0f : (level == MEDIUM) ? 1.5f : 1.0f;
                float fpsMult = fps > 30 ? 2 : 1;
                return min(maxSupported, (int)(tier * 50_000_000f * levelScale * fpsMult));
            } else {
                int tier = width >= NamedResolution.RES_8K_UHD.width ? 5 :
                           width >= NamedResolution.RES_4K_UHD.width ? 4 :
                           (ti1.c() && width <= NamedResolution.RES_FHD.width) ? 3 : 1;
                // Pixel-10-Pro-class devices (ti1.c()) get tier 3 at <=1080p [inference from nr0.h context]
                return min(maxSupported, (int)(tier * level.f * baseFps));
            }
    }
}
```
`BitrateLevel` enum (field `f` = "scale"): **MAX=1.0E7, HIGH=2.0, MEDIUM=1.5, LOW=1.0**.
`maxSupported = nr0.c(mime)`: scans **all MediaCodecList encoders** for the mime, returns the **max `VideoCapabilities.getBitrateRange().upper`**.
Resulting numbers (ORIGINAL, fps<50 → base 6 Mbps; fps≥50 → 9 Mbps):

| Resolution tier | LOW (×1.0) | MEDIUM (×1.5) | HIGH (×2.0) | MAX (×1e7 ⇒ device max) |
|---|---|---|---|---|
| ≥7680 (8K) tier 5 | 30 Mbps | 45 Mbps | 60 Mbps | min(codecMax, 1.6 Gbps) |
| ≥3840 (4K) tier 4 | 24 Mbps | 36 Mbps | 48 Mbps | codecMax |
| ≤1080p tier 1 (3 on ti1.c() devices) | 6 Mbps (18) | 9 Mbps (27) | 12 Mbps (36) | codecMax |

APV (mezzanine): tier 8/4/2/1 × **50 Mbps** × level (2.0/1.5/1.0) × (fps>30 ? 2 : 1) → e.g. 4K30 HIGH = 4×50×2 = **400 Mbps**; MAX = device max.
The final bitrate is additionally clamped at session build: `bitrate = Math.min(requested, hk7.t /*maxSupportedVideoBitrate*/)` (`camera/domain/c.java:1993`), and `hk7.toString()` documents **`maxRecordingBitRate=300000000`** (300 Mbps display clamp).
Live-stream copies rebuilt from `LiveStreamService.Profile.Config.getVideoBitsPerSecond()` (`camera/domain/c.java:1984`).

### 2.5 AV1 / APV / Dolby Vision selection & gating

- **APV** (`video/apv`): only if `gv4.n()` (Android 16/SDK≥36) — `mz4.e0`, `nr0.j` logs "APV isn't supported below A16"; presence check `nr0.f(mime)` — for APV the `isSoftwareOnly` filter is **skipped** (any encoder), for all other mimes **hardware-only** encoders required (`nr0.f`: `if (!b /*hw req?*/) skip isSoftwareOnly()`); 10-bit profile verified via `nr0.g(profile, mime)` on `profileLevels`.
- **AV1**: `video/av01`; 8-bit = profile 1 (AV1ProfileMain8); failure mapping: AV1 profile≠1 → BitDepthException (10-bit AV1 unsupported by codec).
- **Dolby Vision**: HEVC + ColorSpaceProfile F/G → `video/dolby-vision`; muxer fourcc built as **`dvhe.<PP>.<LL>`** from profile/level ints (`d50.c()`: profile 4→"03", 8→"04", 16→"05", 32→"06", 64→"07", 256→"04", else "08"; level 1→"01" … 2048→"12").
- **H.264**: only 8-bit in practice — requesting profile 16 (High10)/32 (High422) and failing → BitDepthException.
- Codec name resolution (the `videoEncoderName` `hk7.s` passed to `MediaCodec.createByCodecName`) comes from the settings/capability layer; resolution support validated by `nr0.h/i` (`VideoCapabilities.isSizeSupported`, with **workaround**: Pixel 10 Pro models bypass the size check ≤4032×3024, and 1080-height devices bypass via `ti1.g0()`).

---

## 3. Frame pacing / CFR — presentationTimeUs math

**The recorded PTS is fully synthetic (CFR), not the sensor timestamp.** The GL pipeline (Task 6:
`ax0` CamPreviewRenderer → `CameraOutputType.RECORD` EGLSurface → persistent encoder input surface)
renders each frame; the MediaCodec drain callback **rewrites every buffer's PTS**:

`defpackage/u84.java` (video drain, quoted):
```java
long ptsUs = bufferInfo.presentationTimeUs;           // from encoder (EGL render time)
boolean key = (bufferInfo.flags & 1 /*BUFFER_FLAG_KEY_FRAME*/) != 0;
if (LongRef d.f > 0 && frameDurationUs > 0 && streamType != LIVE_STREAM) {
    d.f += frameDurationUs;                            // advance expected PTS by one frame
    bufferInfo.presentationTimeUs = s32.j + s32.r * frameDurationUs;
    //        ^firstPtsUs    ^frameIndex   ^1e6/fps  => PTS = base + n × frameDur  [CFR]
}
if (key) {                                             // IDR handling:
    if (state == STARTED && d.f == -1) {               // first keyframe of take:
        timecodeManager.post(new w17(this, bufferInfo.presentationTimeUs,
                                     System.currentTimeMillis()));   // anchor TC epoch
        d.f = bufferInfo.presentationTimeUs + frameDurationUs;
    }
}
// ... muxer write path (below), then:
if (streamType != LIVE_STREAM && timecodeManager.m > 0)
    bufferInfo.presentationTimeUs -= timecodeManager.m * 1000;   // re-base PTS so t=0 == timecode epoch
```
- `frameDurationUs` computed in `p84` at session build: `frameDurationUs = (long)(1.0f / playbackFps × 1000000.0f)` (or −1 when fps unknown) and `maxTsUs = round(1.0f/fps × 1e6)` (`z64.x`), passed into the drain config `u84`.
- Frames whose encoder PTS falls behind the synthetic CFR grid are **drop/duplicated implicitly**: the frame counter `s32.r++` only advances on muxer write, and `d.f` (expected PTS) advances per drained frame — output is stamped by index, so encoder input jitter cannot produce VFR in the file. **[inference: pacing = CFR by index]**
- The muxer-write path also reports `(presentationTimeUs, fileOffset, StreamType)` to `VideoSession.y(...)` for the growing-file cloud sync (Task: cloud live-sync).
- **End-of-take**: `mk7.f()` on start does `setParameters("drop-input-frames"=0)` + `setParameters("request-sync"=0)` (**force IDR at record start**); `mk7.g()` at stop sets `"drop-input-frames"=1` so no new frames enter while draining.
- **Persistent input surface** (`MediaCodec.createPersistentInputSurface()`, held in `ju6` map, waits 5×100 ms between takes) — the surface survives codec re-configure between takes so the GL pipeline's `RECORD` EGLSurface never changes.
- Audio PTS: `source.i()/1000` → µs, where `i()` = `AudioRecord.getTimestamp(ts, TIMEBASE_MONOTONIC).nanoTime` (fallback `System.nanoTime()`) for AudioRecord, or `System.nanoTime()` for AAudio (`wo4.i()`), i.e. **PTS = nanoTime/1000** (see §5).
- Live-stream path (`uu1.h`) does its own CFR: `frameDurationNs = (1/captureFps)×1e9`; for slow-motion streams the outgoing PTS is stepped by `frameDurationNs/1000` per frame (within one-frame tolerance) — so streamed slow-motion is CFR at playback rate.

---

## 4. MP4 authoring — custom muxer + jcodec-fork editors

### 4.1 Recording muxer (in-process, fed by MediaCodec drain)

`d50` facade, ctor quoted (`defpackage/d50.java:11`):
```java
d50(FileDescriptor fd, int trackCount, boolean timecodeRequired, o40 metadata,
    boolean isFragmented, r84 callback) {
    if (isFragmented) {                       // Fragmented MP4 (clips!):
        k32 channel = new k32(new FileOutputStream(fd));
        vb2 fmp4 = new vb2();
        fmp4.a = new tb2 { f = channel };      // sink
        fmp4.d = (fragmentDuration1000s) * 1000L;   // 1_000×1000 = 1 s, or 3_000×1000 = 3 s
        fmp4.e = 1;  fmp4.k = Long.MAX_VALUE;  fmp4.g = new kb0();  // timecode table
        e = new rb2 { f = fmp4, m = new SparseArray() };      // co4 (FragmentedMp4Muxer)
    } else {                                  // progressive MP4 (proxies etc.):
        uk4 muxer = new uk4 { f = k32(channel), i = new hl4(15) };
        muxer.m = new xk4(1) { b=channel, c=hl4, o=Range.closed(0,0) /*guava*/, h=new kb0() };
        e = muxer;                             // co4 (Mp4Muxer)
    }
}
```
- `rb2` (FragmentedMp4Muxer) writes **`moof`/`mdat` pairs** (fragment interval **1 s** when the fast-fragment flag set — cloud live-streaming — else **3 s**; error string in `vb2`: "Only 32-bit long mdat size supported in the fragmented MP4").
- `tk4`/`uk4` (Mp4Muxer, wraps `xk4`) write progressive **`ftyp`/`mdat` + moov at close**.
- Supported mime tables (identical in rb2/tk4/uk4 statics): video = `video/av01, video/3gpp, video/avc, video/hevc, video/mp4v-es, video/x-vnd.on2.vp9, video/apv, video/dolby-vision`; audio = `audio/mp4a-latm, audio/3gpp, audio/amr-wb, audio/iamf, audio/opus, audio/vorbis, audio/raw` (**PCM in MP4**, plus IAMF!).
- These fork androidx.media3's muxer structure (they construct `androidx.media3.muxer.MuxerException`) but add APV/Dolby/PCM/tmcd — i.e. **media3-muxer fork + jcodec box model**.
- **Track add** `d50.c(MediaFormat)`: maps mime→fourcc (`hevc`→`hev1`, dolby→`dvhe.PP.LL`, else `avc1`), copies `csd-0/1`, `max-input-size`, `width/height`, `frame-rate`, `rotation-degrees`, `sar-width/sar-height`→PAR, `color-standard/color-range/color-transfer` (→`js0`), `bitrate`, `channel-count`, `sample-rate`, `pcm-encoding`.
- **PAR**: MediaCodecSession.m() parses metadata PAR string (e.g. "2.0") and sets `sar-width=(int)(par×100), sar-height=100` on the video track format (anamorphic support).
- **Track order in file**: `[video-track][audio-track-0..n-1][timecode-track]` (`s32.d()`).
- **Metadata** `d50.i()` (start): writes `sk4(captureFps, playbackFps)` (= `com.android.capture.fps`, type 23 float-pair) + `b(o40)` = one **`mdta` item per BmdMetadata key** (UTF-8 payloads; `j74` types: 1=string, 23=float pair, 67=int, 75=ubyte, 78=long, 0=auxiliary.tracks.map).
  - **BmdMetadata registry** (`utils/entity/BmdMetadata.java`, 721 lines): `com.blackmagic-design.camera.{reelNumber, scene, take, dayNight, environment, goodTake, dateRecorded, codec, resolution, guides.aspectRatio, guides.safeArea, iso, shutterAngle, shutterSpeed(=com.blackmagic-design.shutterSpeed), shutterMode, whiteBalanceKelvin, whiteBalanceTint, aperture, lensType, sensorFPS, projectName, cameraNumber, director, cameraOp, look.LUTName, audio.{bitDepth,channels,tracks,sampleRate,codecFormat,codecName}, video.{codecName,colorStandard,colorTransfer,codecFormat,codecProfile,codecLevel}, cameraId, exposure, pixelAspectRatio, gamma, manufacturer}`, plus `com.blackmagic-design.camera.android.*` variants, `com.apple.proapps.*` (Final Cut interop), `com.apple.quicktime.*` (incl. `location.ISO6709`), `com.samsung.android.logvideo`.
  - In the fragmented path, `ny2` (co4 wrapper) **buffers metadata items in a LinkedHashSet and writes them at close()** — slate can be edited during recording and is finalized into the (relocated) moov at take end.
- **Timecode track**: MediaCodecSession.m() creates `"timecode-track"` MediaFormat:
  ```java
  MediaFormat tmcd = new MediaFormat();
  tmcd.setString("mime", "application/quicktime tc");
  tmcd.setInteger("frame-rate", Math.round(hk7.e /*playbackFps*/));
  ```
  → muxed as a **`tmcd` handler track** (handler `mhlr/tmcd/appl`, "TimeCodeHandler"). Created only when `!isHighSpeed && captureFps >= playbackFps` (no tmcd on slow-motion takes).
  The drain writes one **4-byte sample per IDR** (`u84.a()`):
  ```java
  int frameNo = (int)(fps * (tcStartMs/1000)) + tcStartFrames;   // fps*(k/1000)+l
  ByteBuffer.allocate(4).putInt(frameNo) → d50.g("timecode-track", buf,
      BufferInfo{size=4, presentationTimeUs=videoPts});
  ```
  i.e. **tmcd payload = absolute frame number** (fps × secondsOfDay + startingFrame).
- **writeSample** `d50.f(track, buf, info)` → `co4.G(track, buf, q90{pts,size,flags})`, returns **running byte offset** `b += size` (used for cloud sync chunk tracking).

### 4.2 Finalize / moov placement

- At stop: `s32.b()` → `mk7.g()` (codec drop-input) then `d50.j()` → `co4.close()` — fMP4: `vb2` finishes the last `moof` and writes **`moov` at end of file**; progressive: `xk4` writes moov at end (`MP4Util.writeMovie`).
- Failure strings: "Bad file descriptor", "Invalid Nal units. Proxy? …", "muxer would have stopped already" / "Muxer stopped twice. Proxy? … fvts? …" (RecorderException.OtherException mapping).

### 4.3 Post-record surgery (jcodec fork = `com.blackmagicdesign.android.metadataeditor`)

**TimeCodeEditor** (`metadataeditor/TimeCodeEditor.java`) — injects tmcd track + bmdt after the fact:
```java
Companion.createFrom(context, uri, fps, secondsOfDay, startingFrame)   // SAF in/out streams,
                                                                       // JCodecUtil.detectFormatChannel == Format.MOV
save() → new RelocateMP4Editor().relocateForTimeCode(in, out, MovieEditor)
```
`MovieEditor.apply(MovieBox moov)` (quoted core):
```java
int nsPerFrame = round(1f/fps * 1e9);                       // tmcd timescale = 1 GHz
mdia: MediaHeaderBox(1000000000, nsPerFrame, ...),
      HandlerBox("mhlr", "tmcd", "appl", "TimeCodeHandler"),
      gmhd{ GenericMediaInfoBox, tmcd{ TimecodeMediaInfoBox(Courier, 12, fg {0,0,0}, bg {255,255,255}) } },
      dinf{ DataRefBox + self-ref alias },
      stbl: TimecodeSampleEntry(0, 1e9, nsPerFrame, fps) + stts{1×nsPerFrame}
            + stsc{(1,1,1)} + stsz{sampleSize=4, count=1}
            + co64{ chunkOffset = existing meta-track co64/stco, else movie.getOffset() + 124 }
trak duration = movieTimescale / 1e9 × nsPerFrame;
if (metaTracks.isEmpty()) movie.appendTrack(new TrakBox(tkhd copied from last track));
// THE bmdt box:
ByteBuffer bmdt = ByteBuffer.allocate(4).putInt(secondsOfDay * fps + startingFrame);
movie.add(1, new Box.LeafBox(new Header("bmdt"), bmdt));    // moov-level 4-byte leaf box = start frame#
```
**The 4-byte tmcd sample is stashed at moovOffset+124 — inside the moov header itself — so take finalization never touches mdat.**
- `RelocateMP4Editor.relocateForTimeCode()`: parses moov, applies the edit, then either rewrites moov **in place** (if it still fits) or writes `"free"` fourcc over the old moov header and **relocates the new moov to end-of-file** (`Logger.info("Relocating movie header to the end of the file.")`, `MP4Util.writeMovie`).
- `MetadataEditor` (slate/udta): `MovieEditor` holds `{keyedMeta(mdta/MetaBox), itunesMeta(udta/meta), udtaMeta}`; `save()` → `RelocateMP4Editor.modifyOrRelocate()` → `InplaceMP4Editor.modify()` first: for **fragmented** files (`BoxUtil.containsBox(moov,"mvex")`) it parses **every `moof`**, calls `edit.applyToFragment(moov, moofs)`, and rewrites each moof **in place** (same size; ≥8 bytes slack → appends a `"free"` box: `buf.putInt(remaining); put("free")`), else falls back to relocation.
- **H.264/CABAC note (correction of earlier handoff)**: the fork ships jcodec's complete H.264 stack — `codecs/h264/io/{CABAC.java, CAVLC.java}`, `codecs/common/biari/{Context, MDecoder}`, full `decode/` (SliceDecoder, MBlock*, deblock) and `io/write/CAVLCWriter` — but **grep shows no caller outside the fork** (only `JCodecUtil` references `H264Decoder`, for format detection). There is no CABAC *writer*, and nothing rewrites slice payloads. **The timecode/metadata edits never touch encoded data** (only moov/udta/bmdt/tmcd boxes) — that's precisely why they need no re-encode. **[verdict: "H.264 CABAC rewriter" from the Stage-1 handoff is actually the inherited jcodec H.264 CABAC *decoder* — unused in the record path]**

---

## 5. Audio pipeline

### 5.1 Capture source selection (`defpackage/js.java`, case 0 — quoted, decoded)

```java
if (ip.e /*audioChannels*/ > 2) {                       // >2 channels → NATIVE AAudio
    source = new wo4(ip);                                // System.loadLibrary("bmdaudio")
} else {                                                 // mono/stereo → classic AudioRecord
    hp src = new hp();  src.m = ip;
    int channelConfig = (channels == 1) ? 16 /*CHANNEL_IN_MONO*/
                          : (channels == 2) ? 12 /*CHANNEL_IN_STEREO*/
                          : 1 /* 4-ch mask; see below */;
    if (bitDepth != 16) { log("Unsupported audio bit depth (" + bitDepth + ")"); return; }
    int encoding = 2 /*ENCODING_PCM_16BIT*/;
    src.x = AudioRecord.getMinBufferSize(ip.g /*sampleRate*/, channelConfig, encoding);
    if (ip.d /*advanceAudioSource device*/ != null && channels == 4)
        log("4 channels should use the native pipeline instead. ");
    int audioSource = ip.c /*BasicAudioSources*/ == DEFAULT ? 0 :
                      ip.c == MIC        ? 1 /*MediaRecorder.AudioSource.MIC*/ :
                      ip.c == CAMCORDER  ? 5 /*CAMCORDER*/ : 9 /*UNPROCESSED*/;
    AudioRecord rec = new AudioRecord(audioSource, sampleRate, channelConfig, encoding, minBufferSize);
    if (ip.d != null) rec.setPreferredDevice(ip.d);       // USB-C / Bluetooth / HDMI mic
    src.w = rec;
}
if (ip.k /*isMonitored*/) { player = new tr(ip); player.a(monitorVolume); }   // headphones monitoring
```
- **`BasicAudioSources` enum** (`recorder/entity/BasicAudioSources.java`): `DEFAULT, MIC, CAMCORDER, UNPROCESSED` → MediaRecorder.AudioSource **0 / 1 / 5 / 9**.
- `ip` = **AudioConfig** (`toString()`): `AudioConfig(sessionId, audioFormat, audioSource, advanceAudioSource, audioChannels, audioTracks, audioSampleRate, audioBitRate, audioBitDepth, captureOnlyDuringRecording, isMonitored, playMonitored, advanceAudioSink, ratio, isStereoForStreamingRequired, isStereoEncodingRequired)`.
  Defaults (from the Kotlin-defaults synthetic ctor): **channels=2, tracks=1, bitrate=256 000 bps, bitDepth=16, playMonitored=true**. `ratio` = `ip.n` — divides minBufferSize into frame counts (the capture loop buffer = `short[minBufferSize/2]`).

### 5.2 AAudio source (`defpackage/wo4.java` + `NativeAudioEngine`)

```java
static { System.loadLibrary("bmdaudio"); }             // → libbmdaudio.so (AAudio, arm64)
public wo4(ip cfg) { y = new NativeAudioEngine().getMinBufferSize(2, cfg.g /*sampleRate*/, cfg.e /*channels*/); }
public void x() {                                     // start
    int fmt = bitDepth16 ? 1 /*PCM16*/ : bitDepth24 ? 3 : 4 /*PCM32*/;   // [inference: AAudio PCM16/24/32]
    handle = new NativeAudioEngine().init(deviceId /*0 if none*/, fmt, sampleRate, channelCount);
}
public int  n(short[] b) { return engine.readShort(handle, b); }
public long i()         { return System.nanoTime(); }   // PTS clock
public void o()         { engine.release(handle); }
```
JNI surface (`recorder/audio/NativeAudioEngine.java`): `init(deviceId, format, sampleRate, channelCount)→handle`, `getMinBufferSize(a,b,c)`, `readShort(handle, short[])`, `release(handle)`.
`libbmdaudio.so` strings: full **AAudio** API — `AAudioStreamBuilder_setDirection/SampleRate/ChannelCount/Format/DeviceId/PerformanceMode/SharingMode/AllowedCapturePolicy/InputPreset`, `AAudioStream_read`, `Error … AAudioStream: %s`.
**⇒ 4-channel / 24-bit / 32-bit / 192 kHz capture goes native (AAudio); plain 16-bit stereo can use AudioRecord.**

### 5.3 AudioRecord source (`defpackage/hp.java` — fully quoted in tree)

```java
public long i() {  // PTS clock
    if (audioRecord.getTimestamp(audioTimestamp, 0 /*TIMEBASE_MONOTONIC*/) == SUCCESS)
        return audioTimestamp.nanoTime;
    return System.nanoTime();
}
public int n(short[] b) { return audioRecord.read(b, 0, b.length, 0); }
public void x() { /* poll state 100 ms sleeps up to 1.5 s */ audioRecord.startRecording(); }
```

### 5.4 Capture loop (`defpackage/js.java`, case 1 — quoted, decoded)

```java
source.x();  if (isMonitored && player!=null) player.c();
short[] buf = new short[source.c() /*minBufferSize*/ / 2];
while (running) {
    int n = source.n(buf);                     // PCM16 shorts
    if (n <= 0) continue;
    long ptsUs = source.i() / 1000;            // nanoTime/1000  → presentationTimeUs
    if (isMonitored && playMonitored) player.e(buf, n);       // live headphone monitor
    audioDistributor.onAudio(buf, n, ptsUs);   // defpackage.cs → encoders' queues + meters
}
```
- `cs` (distributor state): holds per-sink routing; de-interleaves into `hs` **AudioFrames**: `d` = interleaved bytes (MAIN), `e[i]` = per-track channel-group bytes (TRACK_0/TRACK_1), `g` = stereo downmix (STEREO/streaming), `k` = ptsUs.
- AAC encoders are **async** MediaCodec (`rp`): `qp` case 0 `onInputBufferAvailable → queue.offer(id)`; encoder worker dequeues IDs and calls
  `rp.d(buffer, id, pcm, size, pts)` → `codec.queueInputBuffer(id, 0, size, ptsUs, 0)`.
  Track selection (`rp.e`): MAIN→`hs.d`, TRACK_0→`hs.e[0]`, TRACK_1→`hs.e[1]`, STEREO→`hs.g` (per `AudioEncoder$Track` enum MAIN/TRACK_0/TRACK_1/STEREO).
- **PCM recordings** (`AudioFormat.LINEAR_PCM`): no MediaCodec at all — `n84` wires `f47` TrackInfos into the muxer directly (`m97.T` gives `mime=audio/raw`) and raw samples are written to the fMP4/MP4 as `audio/raw` tracks (muxer mime table includes `audio/raw` and even `audio/iamf`).

### 5.5 AAC encoder MediaFormat (`m97.T`, `defpackage/m97.java:1296` — quoted)

```java
static final MediaFormat T(ip audio /*AudioConfig*/, int bufferSize) {
    MediaFormat fmt = new MediaFormat();
    fmt.setString("mime", U(audio.b));                 // "audio/mp4a-latm" | "audio/raw"
    fmt.setInteger("sample-rate",  audio.g);
    fmt.setInteger("channel-count", audio.e / audio.f /*channels per track*/);
    fmt.setInteger("bitrate",       audio.h / audio.f /*per-track share*/);
    fmt.setInteger("max-input-size", bufferSize / audio.f);
    if (audio.b == AudioFormat.y /*AAC*/) fmt.setInteger("aac-profile", 2);   // 2 = AAC LC (OMX object type 2)
    fmt.setInteger("pcm-encoding", 2);                 // ENCODING_PCM_16BIT (input PCM)
    fmt.setInteger("priority", 0);
    return fmt;
}
static final String U(AudioFormat f) {
    case 1: return "audio/mp4a-latm";   // AAC
    case 2: return "audio/raw";        // LINEAR_PCM  (PCM in MP4)
    case 3: log("IEEE_FLOAT format not supported."); return null;
}
```
- Session build (`MediaCodecSession.configureAudioSession`, `recorder/a.java`):
  - input size = `minBufferSize / ip.n(ratio)`, **doubled** when `isStereoForStreamingRequired && tracks==1 && channels==2`;
  - per-track AAC configs derived with `ip.a(orig, AAC, mask)` — normal track keeps configured channels/tracks/bitrate; the **STEREO-for-streaming extra encoder** forces **2 ch / 1 track / 256 kbps** (masks `0x3FFD` vs `0x3F4D`);
  - **default AAC bitrate = 256 kbps total**, per-track = `audioBitRate / audioTracks` (e.g. 4-ch/2-track → 2 × 128 kbps AAC-LC);
  - encoder created with **explicit name** `i96.b` + per-track `HandlerThread` (n sessions for n tracks); guard string "Only one audio configuration is supported.".
  - Role mapping (`AudioEncoder$Track`): tracks==1→MAIN; tracks==2→TRACK_0/TRACK_1; streaming-extra→STEREO.
- Audio PTS written to muxer unchanged (µs); **no audio resampling in Java**.

### 5.6 VU / PPM metering (exact math)

Thread: `HandlerThread("audiometer", prio 7)` (`gv4.D("audiometer", …)`, `defpackage/ar.java`).

**dB conversion** (`defpackage/ar.java:127`):
```java
static float b(float x) { return x > 0f ? (float)(Math.log10(x) * 2000.0) : 0f; }
```
i.e. **`dB×100 = 2000·log10(linear)`** — centi-dBFS relative to 32767 (16-bit full scale).

**Reference & VU scale** (`defpackage/yr.java` — fully quoted in tree):
```java
static { a = (float)(Math.log10(32767.0) * 20.0); }     // = 90.30 dB  (16-bit FS reference)
static br a(ns m, AudioMetering t) {
    // cases 1/2 (VU/PPM at −18/−20 dBFS reference — see enum below):
    int ref = (t == AudioMetering.m) ? -1800 : -2000;   // reference in centi-dB
    float db = (m.a - ref) / 100f - yr.a;               // meter position in dB (0 = reference tone)
    return new br(t, 1, db, db);
    // cases 3/4: db = m.b/100f - a; db2 = m.a/100f - a  (min/max pair)
}
```
- **`AudioMetering` enum** (`utils/entity/AudioMetering.java`): **`VU_18DBFS, VU_20DBFS, PPM_18DBFS, PPM_20DBFS`** — VU with 0 mark at −18 dBFS (EBU) or −20 dBFS (SMPTE).
- **Peak + RMS accumulation** (`defpackage/vd.java:700-860`, "audiometer" loop):
  per channel: `sumAbs += |sample|`; `peak = max(peak, |sample|)`; rolling window `x` samples (circular buffer, rolling sum `t`);
  per window:
  - `peakDb = ar.b(peak)` = `2000·log10(peakAbs)`; **decay**: `level = max(level − z /*fall rate*/, peakDb)`; also `max(level, 0)`;
  - **peak-hold**: `if (peakDb > ns.b) { ns.b = peakDb; ns.c = frameNo } else if (++holdCounter > B) ns.b -= A /*release*/; ns.b = max(ns.b, 0)`;
  - **RMS**: `rmsDb = ar.b(rollingSum / (window*x)) + v /*calibration offset*/`; `F[ch].a = max(F[ch].a − y /*fall*/, rmsDb)`.
  - `ns` = `AudioState(level, peak, peakHold)` — centi-dB ints, one per channel (`E[][]` = peak states, `F[][]` = RMS states).
- Constants z/A/B/y/v/x are per-AudioMetering calibration **[values are runtime-config; formulas quoted]**.

### 5.7 Devices

- `recorder/audio/AudioDeviceDetector` + `recorder/manager/AudioDeviceManager` — `AudioDeviceCallback` (`onAudioDevicesAdded/Removed` via `a extends android.media.AudioDeviceCallback`) maintain the device list; `advanceAudioSource/advanceAudioSink` (`AudioDeviceInfo`) → `AudioRecord.setPreferredDevice` / player sink. `ip.d != null` covers USB-C mics (VID 0x2e1a BMD Focus/Zoom Demand, 0x4255 Tentacle per manifest filter), Bluetooth, HDMI.
- "192.0 kHz" / "96.0 kHz" / "48.0 kHz" / "44.1 kHz" sample-rate options (`res/values/strings.xml` `_192_0_khz`, `_96_0_khz`, `_48_0_khz`, `_44_1_khz`).

---

## 6. GStreamer vs MediaCodec — verdict

**The file-recording path is 100 % Java: Camera2/GL (Task 6) → MediaCodec (explicit-name encoders) → custom ISO-BMFF muxers (`rb2`/fMP4 or `tk4|uk4`) + jcodec-fork finalize editors. GStreamer never touches files.**

What GStreamer actually does (all in `remote/` + native):
1. **`com/blackmagicdesign/android/remote/jni/NativeGstreamer.java`** → `libgstreamer_android_remote.so` (`GsPipeline`): WebRTC session for phone-to-phone remote control/preview (`webrtcbin name=webrtcbin bundle-policy=max-bundle`, `tsdemux`, appsink branches; SDP offer/answer processing).
2. **`NativeSRTGstreamer.java`** — `startStreamingForSink(uuid, videoCodec, frameRateNum, frameRateDen, audioFormat, audioNumChannelsToStream, audioNumChannels, audioSampleRate, GsPipelineStreamingSink, nativePtr)`; the native lib builds **literal pipelines**:
   ```
   appsrc name=bmdvideosrc … h264parse disable-passthrough=false config-interval=-1 name=videoparser ! mux. …
   appsrc name=bmdaudiosrc … ! srtclientsink name=livestreamsink uri=…
   ```
   logs: `"SRT pipeline initialized/failed"`, `"RTMP pipeline initialized/failed"`. Elements available in `libgstreamer_android.so` (GStreamer + FFmpeg plugin `avenc_*`, `srtsink/srtsrc`, `webrtcbin`, `mpegtsdemux`, `appsrc`).
3. **`NativeBmdLiveStreamClient.java`** — Blackmagic Cloud live streaming: `initialize(url, key, videoCodec, fpsNum/Den, audioFormat, channelsToStream, channels, sampleRate)`, `connect(host, port, connectionId, passphrase, latencyUS)`, `pushVideoBuffer(ByteBuffer, isKeyFrame, size, pts, duration, ptr)`, `pushAudioBuffer(...)`, `tallyState()`, `totalBytesSinceLastQuery()` — an **SRT-caller** protocol with passphrase+latency (davstream family).
4. **`libdavstream.so` / `libdavstreamd.so`** — SRT/DTLS-SRTP transport + TLS (mbedTLS; build paths `bmd-eng-ub_*`): the `connect(host,port,connectionId,passphrase,latencyUS)` client and server side for the BMD Cloud live-stream and the remote-control data path; also used by the cloud upload stack (`libbmddavcloudclient` for DaVinci-cloud REST).
5. **media sync**: `media_sync.v1` proto — chunked clip pull over WebSockets (cloud), not GStreamer.

**Encoder sharing (record + stream simultaneously)** — `defpackage/uu1.h()` (video drain tee, quoted in §3/§7): the **same MediaCodec-encoded buffers** from the LIVE_STREAM track are forwarded to `iq6` sinks (`h(ByteBuffer, ptsUs, durUs, CaptureVideoProperties, byte[] /*GstBmdCameraMetadata*/, String)`), which call `NativeBmdLiveStreamClient.pushVideoBuffer` / `NativeSRTGstreamer` appsrc pushes. GStreamer only **payloads/transports** (h264parse + mux + srt/rtmp sinks); the H.264/HEVC bits come from MediaCodec. `avenc_*` (FFmpeg) exists in the bundle as fallback/remote-decode path. **[inference: avenc used only where a pipeline needs re-encode; not in the record path]**

---

## 7. Live streaming

- **Protocols**: `LiveStreamService.Type` = **RTMP | SRT** (`utils/entity/LiveStreamService.java`); plus **BMD Cloud live stream** (`LiveStreamService` subclass, `BMD_SERVICE_NAME = "Blackmagic Cloud"`, `BMD_CAMERA_APP = "Blackmagic Cam App"`); service presets **Twitch, Vimeo, YouTube, Facebook** + custom RTMP/SRT entries (`Profile.Config(resolution, fps, videoBitsPerSecond, audioBitsPerSecond, codec)` — parsed from JSON service profiles, `LiveStreamService.Profile.Config.toJson()`).
- **Streaming encoder config vs recording**: the LIVE_STREAM track is a *separate* `hk7` VideoConfig built by `mz4.Q(...)`: bitrate from `LiveStreamQuality` (**LOW=3, MEDIUM=6, HIGH=9 Mbps**) or service `Profile.Config.getVideoBitsPerSecond()`; resolution capped: `if (playbackFps > 50 && 16:9) → 1280×720`; `NamedResolution.A(1080p)/B(720p) → 1280×720`; 16:9 otherwise → 1080p; **i-frame-interval = 1.0 s** (same factory); StreamType also skips max-bframes/ts-schema capability queries (p84: `if (r4 != StreamType.m)`).
- **Live-stream config copy** (`camera/domain/c.java:1930-2045`): chooses the service Config matching the settings codec name (`b01.E(codec)` vs `Config.getCodec()`), else default; bitrate = `min(serviceBitsPerSecond, maxSupportedVideoBitrate)`; **i-frame-interval hard 1.0 s**; `hk7.m = false` for live (no tmcd writes — u84 gates tmcd on `streamType != LIVE_STREAM`); audio gets a **STEREO downmix track** for the stream when the recording is multi-channel (`isStereoForStreamingRequired`, 256 kbps AAC-LC).
- **Simultaneous record + stream**: one MediaCodecSession, one encoder per stream; ORIGINAL goes to the file muxer, LIVE_STREAM frames are teed in `u84.a()` → `uu1.h()` which prepends **CSD (SPS/PPS/PPS or VPS/SPS/PPS from `csd-0/csd-1`) to the first IDR** (so the stream starts in-band parameter-set-complete), verifies NAL start-code layout with `nr0.a` (byte-buffer indexOf), then pushes `(buffer, pts, duration, GstBmdCameraMetadata(timecode), connectionId)` to the sink. Slow-motion streams are CFR-restamped at playback rate (§3).
- **Stats**: `com/mux/stats` — Mux.com stats SDK ships in the tree (per Stage-1 inventory) for streaming telemetry. **[no deeper integration found in recorder path]**
- **Remote preview** (receive side): `libgstreamer_android_remote` `tsdemux`/`webrtcbin` appsink branches → `pe5` MediaCodec decoder renderer (Task 6).

---

## 8. Master tables

### 8.1 Video MediaFormat — full key table

| MediaFormat key | Value / formula | Where set | avc / hevc / dv / av1 / apv differences |
|---|---|---|---|
| `mime` | `video/avc` \| `video/hevc` \| `video/dolby-vision` \| `video/av01` \| `video/apv` | `mz4.e0(codec, profile)` in p84 | dv = HEVC + ColorSpaceProfile F/G; apv only SDK≥36 |
| `color-format` | 2130708361 (COLOR_FormatSurface) | p84 | same all |
| `bitrate` | ORIGINAL: `tier × levelScale × (fps≥50?9:6) Mbps` (tiers 5/4/3-or-1), APV: `tier{8,4,2,1}×50 Mbps×scale×(fps>30?2:1)`, APV+MAX→codecMax; PROXY: 6/9 Mbps; LIVE: LiveStreamQuality 3/6/9 Mbps; clamped to `min(MediaCodecList bitrate-range max, 1.6 Gbps)` and `hk7.t` | `nr0.d`, applied in p84 | APV has its own branch (mezzanine ~400 Mbps @4K HIGH); others share tier formula |
| `profile` / `level` | requested when != −1 (avc High10=16/High422=32; hevc Main10=2, HDR10=4096, HDR10+=8192; av1 Main8=1/Main10=2; dolby 1..1024) | p84; re-read from output in `u84.b()` → stored in clip metadata | failure→BitDepthException map per mime (p84) |
| `color-standard` | BT709(1) SDR; BT2020(6) HDR; skip if DEFAULT; BT601_525(4)/625(2) enum values exist | p84 | dv/apv: transfer from `hk7.a(profile)` switch (HLG=7 or ST2084=6) |
| `color-transfer`+`color-transfer-request` | SDR_VIDEO=3, HLG=7, ST2084=6; QTI vendor override→0 | p84 | QTI-only path |
| `color-range` | 2 (limited); 1 (full) on (ti1.m‖ti1.e)∧profile C | p84 | — |
| `vendor.qti-ext-enc-colorspace-conversion.enable` | 1 (QTI ∧ bitDepth 8) | p84 | avc/hevc 8-bit SDR only |
| `vendor.qti-ext-enc-client-set-color-transfer-enable.value` | 1 (QTI ∧ profile C ∧ non-tag path) | p84 | — |
| `hdr-editing` / `hlg-editing` | featureEnabled per ColorSpaceProfile ordinal bucket (9-11 hdr / 5-8 hlg ∧ SDK≥36) | p84 | HDR10/HDR10+ & HLG10 profiles |
| `i-frame-interval` | **1.0 s** (hardcoded in `mz4.Q`); same value into `intra-refresh-period` | p84 | same all |
| `frame-rate` | playbackFrameRate | p84 | slow-mo uses playback fps |
| `capture-rate` | `1/playbackFps` (high-speed) or playbackFps | p84 | avc/av1 high-speed sessions |
| `operating-rate` | playbackFps (when capture≠playback on `ti1.o()` devices) | p84 | — |
| `max-width`/`max-height`/`stride`/`slice-height` | width/height | p84 | live capped 1280×720 for fps>50 16:9 |
| `max-input-size` | **0** | p84 | surface input |
| `priority` | **0** | p84 | realtime |
| `max-bframes` | from codec caps (`ok7.d`; 0 if high-speed) | p84 | skipped for PROXY |
| `ts-schema` | `"android.generic.<n>+<m>"` / `"none"` from caps | p84 | ORIGINAL only |
| (BITRATE_MODE, LATENCY) | **never set** | — | codec-default rate control |

### 8.2 Audio config table

| Item | Value | Where |
|---|---|---|
| Sources | BasicAudioSources DEFAULT(0)/MIC(1)/CAMCORDER(5)/UNPROCESSED(9) | `js` case 0 |
| Engine | ≤2 ch ∧ 16-bit → `AudioRecord`; >2 ch / 24-32 bit → AAudio (`libbmdaudio`) | `js` case 0 |
| AAudio builder | `init(deviceId, fmt∈{PCM16,PCM24?,PCM32}, sampleRate, channels)`, `getMinBufferSize` | `wo4`, NativeAudioEngine |
| Sample rates | 44.1 / 48 / 96 / **192 kHz** (`strings.xml`) | AudioConfig.g |
| Channels | up to **4** (`audioChannels`), `channel-count = channels/tracks` | ip, m97.T |
| Tracks | up to 3 encoder tracks (MAIN / TRACK_0 / TRACK_1) + STEREO streaming downmix | AudioEncoder$Track, MediaCodecSession |
| PCM encoding | **16-bit** (`pcm-encoding=2`, `ENCODING_PCM_16BIT`); 24/32 via native fmt | m97.T, js |
| Bit depth metadata | `com.blackmagic-design.camera.android.audio.bitDepth` | BmdMetadata |
| AAC | `mime=audio/mp4a-latm`, **`aac-profile=2` (AAC-LC)**, bitrate **256 000 total** default, per-track = total/tracks | m97.T, ip.a defaults |
| PCM-in-MP4 | `mime=audio/raw` direct mux (no MediaCodec); muxer also supports `audio/iamf` | m97.U, n84, rb2/tk4 |
| max-input-size (audio) | `minBufferSize/ratio` (×2 for stereo-streaming single-track) | MediaCodecSession |
| Buffer | `short[minBufferSize/2]`; encoder input-buf IDs queue `LinkedBlockingQueue(64)` | js, rp/qp |
| Audio PTS | `AudioTimestamp.nanoTime/1000` (fallback `System.nanoTime()/1000`) | hp, js |
| Monitoring | `tr` player (`isMonitored`, `playMonitored`, volume) | js |
| Metering | `dB×100 = 2000·log10(x)`; FS ref `20·log10(32767)=90.30 dB`; VU/PPM @ −18/−20 dBFS; peak-hold + RMS windows | ar.b, yr, vd |
| Timecode in audio? | none — tmcd only on video sessions | §4.1 |

### 8.3 Recorder track order in MP4
`[video-track][audio-track-0…n−1][timecode-track]` (`s32.d()`), plus moov-level `bmdt` (4-byte start frame) + `udta/meta/mdta` slate registry + `com.android.capture.fps` float pair for high-speed takes.

---

## Evidence index (primary files)
- `jadx_fallback/sources/defpackage/{p84, hk7, mz4, nr0, mr0, mk7, yu1, qp, u84, t84, d50, rb2, tk4, uk4, ny2, vb2, tb2, xk4, k32, hl4, j74, f47, s32, eq6, iu6, js, hp, wo4, ks, cs, rp, hs, ar, yr, vd, ns, uu1, iq6, n84, l96, zc}.java`
- `jadx_fallback/sources/com/blackmagicdesign/android/recorder/{a, encoder/AudioEncoder$Track, entity/{VideoCodecFormat, VideoColorStandard, VideoColorTransfer, BasicAudioSources}, audio/NativeAudioEngine, timecode/a, manager/a}.java`
- `jadx_fallback/sources/com/blackmagicdesign/android/utils/entity/{StreamType, BitrateLevel, LiveStreamQuality, NamedResolution, Codec, AudioFormat, AudioMetering, BmdMetadata, LiveStreamService}.java`
- `jadx_fallback/sources/com/blackmagicdesign/android/metadataeditor/{TimeCodeEditor, RelocateMP4Editor, InplaceMP4Editor, MetadataEditor, MP4Edit, containers/mp4/**, codecs/h264/**}`
- `jadx_fallback/sources/com/blackmagicdesign/android/camera/domain/{c, h, SessionFileHandler*}.java`
- `jadx_fallback/sources/com/blackmagicdesign/android/remote/jni/{NativeGstreamer, NativeSRTGstreamer, NativeBmdLiveStreamClient}.java`
- `apks/arm64_extracted/lib/arm64-v8a/{libbmdaudio, libgstreamer_android, libgstreamer_android_remote, libdavstream, libdavstreamd}.so` strings
- `apktool_out/res/values/strings.xml`

