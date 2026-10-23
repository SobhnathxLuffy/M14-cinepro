# Stage 4 — Viewfinder Shader & Monitoring Math (Blackmagic Camera v3.5.0.0064, clean-room)

Target: `com.blackmagicdesign.android.blackmagiccam` 3.5.0.0064 (versionCode 26101001).
Scope: the 16 GLSL assets in `assets/glsl/`, the built-in LUT assets, the Java/Kotlin GL glue
(R8-minified `ax0` renderer in jadx defpackage + `com/blackmagicdesign/android/graphics/` +
`com/blackmagicdesign/android/colorutils/luts/`), and the native LUT libraries
(`liblut_processor.so`, `liblutparser.so`, `liblut_float16_parser.so`, `liblut_compressor.so`).
All analysis is READ-ONLY (strings / od / grep / jadx reads). Nothing was executed or patched.

Shader source paths below are relative to `/home/z/my-project/apks/base_extracted/assets/glsl/`.
Java paths are relative to `/home/z/my-project/jadx_out/sources/`.
R8-obfuscated names are reported verbatim (e.g. `ax0`); anything beyond the literal file text is
labeled **[inference]**.

All 16 fragment/vertex/effect GLSL files carry the header comment
`Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> ... 2024/3/15 … 2025/7/28`,
and several say "function based on the iOS codebase" — i.e. the Android GLSL is a port of the
iOS Blackmagic Camera monitoring shaders. (File-text fact.)

---

## 1. The 16 GLSL assets — full quotes + commentary

### 1.1 `fragment_header.glsl` — the OES variant of the "master" fragment header (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 */

#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform highp samplerExternalOES cameraTexture;
in vec2 cameraTexCoordinates;
out vec4 fragColor;

// Uniforms to activate/deactivate all the effects but keeping individual states
uniform int areEffectsVisible;
```

**Commentary.** `#version 320 es` — the app requires GLES 3.2 (manifest `glEsVersion 0x30002`,
Stage-1). External-OSES image extension is required because the camera feed arrives as an
android `SurfaceTexture` bound to `GL_TEXTURE_EXTERNAL_OES`. `precision highp float` everywhere
(no `mediump` cost cutting). `cameraTexCoordinates` comes from `transform_vertex.glsl`
(texMatrix-transformed). `areEffectsVisible` is a master switch uniform that lets the app
hide ALL monitoring overlays at once "keeping individual states" — i.e. each effect keeps its
own `*Visible` uniform, but `areEffectsVisible` gates the whole set (used e.g. for the
"clean feed"/snapshot path — **[inference]**).

### 1.2 `fragment_header_fbo.glsl` — the FBO (render-to-texture) variant (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/11/11.
 */

#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform highp sampler2D cameraTexture;
in vec2 cameraTexCoordinates;
out vec4 fragColor;

// Uniforms to activate/deactivate all the effects but keeping individual states
uniform int areEffectsVisible;
```

**Commentary.** Identical contract but `uniform highp sampler2D cameraTexture` — the same effect
body is compiled a second time against a *regular 2D texture*, i.e. against an FBO (offline
frame) rather than the live OES camera texture. The 2024/11/11 date shows this second path was
added months after the original 2024/8/13 header. The identical uniform/attribute names mean the
effect `.glsl` files are textually shared between both variants (a poor-man's template system;
the Kotlin side concatenates header + effect bodies + generated `main()` — see §10).

### 1.3 `transform_vertex.glsl` — the single shared vertex shader (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/5/17.
 */

in vec4 vPosition;
uniform mat4 texMatrix;
out vec2 cameraTexCoordinates;
out vec4 outPosition;

void main() {
    outPosition = vPosition;
    vec4 texCoord = vec4((vPosition.xy + vec2(1.0, 1.0)) / 2.0, 0.0, 1.0);
    cameraTexCoordinates = (texMatrix * texCoord).xy;
    gl_Position = vPosition;
}
```

**Commentary.** Full-screen-quad vertex shader: clipspace `[-1,1]` is remapped to `[0,1]`
(`(pos+1)/2`) before `texMatrix` (the `SurfaceTexture.getTransformMatrix()` 4x4, which handles
the Y-flip and any crop) produces `cameraTexCoordinates`. `outPosition` passes the raw clip
position downstream (unused by the shipped effect bodies — kept for future geometry effects
**[inference]**). One vertex shader for the whole pipeline; everything else is fragment/compute.

### 1.4 `focus_assist_effect.glsl` — FOCUS PEAKING (full file)

```glsl
/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 */

// Uniforms for the focus assist
uniform int focusAssistVisible;
uniform int enableColoredLines;
uniform vec4 lineColor;
uniform float peakingThreshold;

float getLaplacian5x5(vec2 pos) {
    float level = 0.0;
    vec2 delta = 1.0 / vec2(outWidth, outHeight);

    // 5x5 coefficient matrix:
    // { -1, -3, -4, -3, -1 }
    // { -3,  0,  6,  0, -3 }
    // { -4,  6, 20,  6, -4 }
    // { -3,  0,  6,  0, -3 }
    // { -1, -3, -4, -3, -1 }

    // Unrolled loop. Those with level 0 are commented for obvious reasons, but don't remove the lines in case we change the matrix.
    level += -1.0 * texture(cameraTexture, pos + delta * vec2(-2, -2)).r;
    level += -3.0 * texture(cameraTexture, pos + delta * vec2(-1, -2)).r;
    level += -4.0 * texture(cameraTexture, pos + delta * vec2(0, -2)).r;
    level += -3.0 * texture(cameraTexture, pos + delta * vec2(1, -2)).r;
    level += -1.0 * texture(cameraTexture, pos + delta * vec2(2, -2)).r;

    level += -3.0 * texture(cameraTexture, pos + delta * vec2(-2, -1)).r;
    //level +=  0.0 * texture(sTexture, pos + delta * vec2(-1, -1)).r;
    level +=  6.0 * texture(cameraTexture, pos + delta * vec2(0, -1)).r;
    //level +=  0.0 * texture(sTexture, pos + delta * vec2( 1, -1)).r;
    level += -3.0 * texture(cameraTexture, pos + delta * vec2(2, -1)).r;

    level += -4.0 * texture(cameraTexture, pos + delta * vec2(-2, 0)).r;
    level +=  6.0 * texture(cameraTexture, pos + delta * vec2(-1, 0)).r;
    level += 20.0 * texture(cameraTexture, pos + delta * vec2(0, 0)).r;
    level +=  6.0 * texture(cameraTexture, pos + delta * vec2(1, 0)).r;
    level += -4.0 * texture(cameraTexture, pos + delta * vec2(2, 0)).r;

    level += -3.0 * texture(cameraTexture, pos + delta * vec2(-2, 1)).r;
    //level +=  0.0 * texture(sTexture, pos + delta * vec2(-1,  1)).r;
    level +=  6.0 * texture(cameraTexture, pos + delta * vec2(0, 1)).r;
    //level +=  0.0 * texture(sTexture, pos + delta * vec2( 1,  1)).r;
    level += -3.0 * texture(cameraTexture, pos + delta * vec2(2, 1)).r;

    level += -1.0 * texture(cameraTexture, pos + delta * vec2(-2, 2)).r;
    level += -3.0 * texture(cameraTexture, pos + delta * vec2(-1, 2)).r;
    level += -4.0 * texture(cameraTexture, pos + delta * vec2(0, 2)).r;
    level += -3.0 * texture(cameraTexture, pos + delta * vec2(1, 2)).r;
    level += -1.0 * texture(cameraTexture, pos + delta * vec2(2, 2)).r;

    return level;
}

vec4 focusAssist(vec4 color) {
    float edge = getLaplacian5x5(cameraTexCoordinates); // Assuming cameraTexCoordinates and getLaplacian5x5 are defined elsewhere
    float edgeAbs = abs(edge);

    // Calculate thresholds for edge detection
    float coloredLinesThreshold = 5.5 - peakingThreshold;
    float peakingThresholdAdjusted = 3.5 - peakingThreshold;

    // Calculate peaking effect
    float peakEffect = edge * (peakingThreshold / 4.0);
    vec4 peakColor = vec4(peakEffect, peakEffect, peakEffect, 0.0) * step(peakingThresholdAdjusted, edgeAbs);

    // Blend between original color and line color based on colored lines condition
    vec4 linesColor = mix(color, lineColor, step(coloredLinesThreshold, edgeAbs));

    // Blend between original color (with peak effect) and linea color based on colored lines condition
    return mix(color + peakColor, linesColor, float(enableColoredLines));
}
```

**Commentary — the exact kernel.** A 5×5 Laplacian-of-magnitude kernel (quoted above in the
comment as the canonical form). Sum of all coefficients: 4 corners ×(−1) + 8 edge-of-5x5
×(−3) + 4 far-axis ×(−4) + 8 near-axis/cross ×(6) + center 20 = −4 −24 −16 +48 +20 = **+24**
(not the zero-sum of a pure Laplacian — it has a DC bias of +24, i.e. it is *not* a normalized
second-derivative; it's a tuned "edge energy" detector). It is sampled on the **red channel
only** (`.r` of `cameraTexture`) — luma approximated by R is unusual; on the OES YUV texture
`.r` would be... **[inference]** on the FBO RGB texture it is literally the red channel; using
one channel keeps the 25 taps cheap. `delta = 1.0 / vec2(outWidth, outHeight)` — `outWidth` /
`outHeight` are the same uniforms declared by `zebra_effect.glsl`, so the includes share them.

**Threshold math.** With `peakingThreshold` (uniform, set from Java/UI — see §2 for the UI range):
- `coloredLinesThreshold = 5.5 − peakingThreshold` — above this |edge| the pixel is painted
  solidly with `lineColor` (when `enableColoredLines == 1`).
- `peakingThresholdAdjusted = 3.5 − peakingThreshold` — above this |edge| the pixel gets a
  *monochrome additive highlight*: `peakEffect = edge * (peakingThreshold/4.0)` added to all
  three channels (`peakColor` with alpha 0).
- Final: `mix(color + peakColor, linesColor, enableColoredLines)` — i.e. mode A = additive
  white-ish peaking scaled by the user threshold, mode B = full replacement with `lineColor`.
  Because thresholds are `5.5 − t` / `3.5 − t`, **raising** `peakingThreshold` *lowers* the
  |edge| needed to trigger — the UI "strength" knob is inverted into sensitivity here.

### 1.5 `false_color_effect.glsl` — FALSE COLOR, "full" luminance-computing variant (full file)

```glsl
/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2025/7/28.
 */

#define falseColorRed       vec4(0.886, 0.055, 0.012, 1.0) // Y(76), Cb(87), Cr(235)
#define falseColorYellow    vec4(0.992, 0.976, 0.106, 1.0) // Y(225), Cb(16), Cr(148)
#define falseColorPink      vec4(0.949, 0.710, 0.741, 1.0) // Y(200), Cb(122), Cr(158)
#define falseColorGreen     vec4(0.306, 1.000, 0.145, 1.0) // Y(235), Cb(16), Cr(16)
#define falseColorBlue      vec4(0.118, 0.000, 0.863, 1.0) // Y(30), Cb(235), Cr(128)
#define falseColorPurple    vec4(1.000, 0.059, 1.000, 1.0) // Y(128), Cb(235), Cr(235)


// RGB to XYZ conversion matrices.

// Rec. 709 / sRGB
const mat3 RGB_TO_XYZ_709 = mat3(
    0.412391, 0.212639, 0.019331,  // Column 1
    0.357584, 0.715169, 0.119195,  // Column 2
    0.180481, 0.072192, 0.950532   // Column 3
);

// Rec. 2020
const mat3 RGB_TO_XYZ_2020 = mat3(
    0.636958, 0.262700, 0.000000,  // Column 1
    0.144617, 0.779998, 0.028073,  // Column 2
    0.168881, 0.059302, 1.060985   // Column 3
);

// Display P3 (D65 white point)
const mat3 RGB_TO_XYZ_P3D65 = mat3(
    0.486571, 0.228975, 0.000000,  // Column 1
    0.265668, 0.691739, 0.045113,  // Column 2
    0.198217, 0.079287, 1.043944   // Column 3
);



// Uniforms for the false color effect
uniform int falseColorVisible;
uniform int colorSpace;


// Define constants for the Rec. 709 transfer function
const float REC709_LINEAR_THRESHOLD = 0.081f;
const float REC709_LINEAR_SLOPE = 4.5f;
const float REC709_OFFSET = 0.099f;
const float REC709_SCALE_FACTOR = 1.099f;
const float REC709_GAMMA_RECIPROCAL = 0.45f;

/**
 * @brief Decodes a Rec. 709 gamma-encoded value back to a linear value.
 * @param p_Val The non-linear (gamma-encoded) input value.
 * @return The decoded linear value.
 */
float rec709DecodeGamma(float p_Val) {
    if (p_Val <= REC709_LINEAR_THRESHOLD) {
        // For very dark values, use a simple linear conversion.
        return p_Val / REC709_LINEAR_SLOPE;
    } else {
        // For all other values, use a power function to reverse the main gamma curve.
        return powf((p_Val + REC709_OFFSET) / REC709_SCALE_FACTOR, 1.0f / REC709_GAMMA_RECIPROCAL);
    }
}


/**
 * @brief Applies gamma correction while preserving the input's sign.
 * @param val The input value.
 * @param gamma The gamma exponent.
 * @return The gamma-corrected value.
 *
 * This function is equivalent to the Swift code's use of log2/exp2,
 * which calculates pow(abs(val), gamma), and then restores the original
 * sign of 'val'.
 */
float gammaCorrection(float val, float gamma) {
    return sign(val) * pow(abs(val), gamma);
}

// --- HLG Inverse OETF ---
// Converts HLG non-linear signal values into (relative) scene linear light.
const float HLG_A         = 0.17883277f;
const float HLG_B         = 0.28466892f;
const float HLG_C         = 0.55991073f;
const float HLG_SDR_GAMMA  = 0.5f;
const float HLG_REF_WHITE  = 0.5f;

/**
 * @brief Decodes an HLG (Hybrid Log-Gamma) signal to a linear value.
 * @param p_Log The non-linear HLG input signal.
 * @return The decoded linear light value.
 */
float hlgDecodeGamma(float p_Log) {
    float lin = (p_Log > 0.5f)
    ? (exp((p_Log - HLG_C) / HLG_A) + HLG_B)
    : gammaCorrection((p_Log / HLG_REF_WHITE), (1.0f / HLG_SDR_GAMMA));

    return (lin / 2.0f);
}

// Constants for the CIE Y to L* conversion.
const float LSTAR_C1 = 0.008856451679036f;   // (6/29)^3
const float LSTAR_C2 = 903.2962962962963f;   // (29/3)^3

/**
 * @brief Converts a linear luminance value (CIE Y) to perceptual lightness (L*).
 * @param p_Y The input linear luminance, normalized to the range [0.0, 1.0].
 * @return The L* value, normalized to the range [0.0, 1.0].
 */
float y2Lstar(float p_Y) {
    float lstar_raw = (p_Y <= LSTAR_C1)
    ? (p_Y * LSTAR_C2)
    : (pow(p_Y, 1.0f / 3.0f) * 116.0f - 16.0f);

    // Normalize L* from its standard [0, 100] range to [0, 1]
    return lstar_raw / 100.0f;
}


/**
 * @brief Decodes a gamma-encoded value based on the specified color space.
 * @param p_ColorSpace An integer identifying the color space.
 * @param p_Val The non-linear (gamma-encoded) input value.
 * @return The decoded linear value.
 */
float decodeGamma(int p_ColorSpace, float p_Val) {
    if (p_ColorSpace == 1) return hlgDecodeGamma(p_Val);
    else return rec709DecodeGamma(p_Val);
}

/**
 * @brief Performs sigmoid-based tone mapping, handling out-of-range values.
 *
 * This function preserves the sign of the input value by processing its absolute value and restoring the sign at the end.
 */
float doSigmoidToneMapping(float c1, float c2, float c3, float p_Val, float srcMaxImgL, float dstMaxImgL) {
    float orgVal = p_Val;
    p_Val = abs(p_Val);

    float toneMappedVal;
    // Scale the value before processing, as per the original logic.
    float scaledVal = p_Val * 100.0f;

    if (scaledVal < srcMaxImgL) {
        // Apply sigmoid tone mapping for values within the source range.
        toneMappedVal = (c1 + (c2 * scaledVal)) / (1.0f + (c3 * scaledVal));
    } else {
        // Linearly extrapolate values outside the source range.
        toneMappedVal = scaledVal * (dstMaxImgL / srcMaxImgL);
    }

    // Scale the result back down and restore the original sign.
    toneMappedVal /= 100.0f;
    if (orgVal < 0.0f) toneMappedVal = -toneMappedVal;

    return toneMappedVal;
}


/**
 * @brief Calculates the perceptual lightness (L*) for a given color.
 * @param p_Color The input color as a vec4.
 * @param p_Y_coeffs The RGB-to-Y conversion coefficients (row 2 of the RGB-to-XYZ matrix).
 * @param p_ColorSpace An integer identifying the source color space.
 * @return The calculated L* value, normalized to [0, 1].
 */
float calcLuminance(vec4 p_Color, vec3 p_Y_coeffs, int p_ColorSpace) {
    // Step 1: Convert to linear RGB by decoding gamma.
    vec3 linRGB = p_Color.rgb;
    linRGB.r = decodeGamma(p_ColorSpace, p_Color.r);
    linRGB.g = decodeGamma(p_ColorSpace, p_Color.g);
    linRGB.b = decodeGamma(p_ColorSpace, p_Color.b);

    // Step 2: Apply tone mapping for HDR color spaces.
    if (p_ColorSpace == 1) { // HLG
                             float c1 = 0.0f, c2 = 1.021431f, c3 = 0.002381f;
                             float srcMaxImgL = 1200.0f, dstMaxImgL = 100.0f;
                             linRGB.r = doSigmoidToneMapping(c1, c2, c3, linRGB.r, srcMaxImgL, dstMaxImgL);
                             linRGB.g = doSigmoidToneMapping(c1, c2, c3, linRGB.g, srcMaxImgL, dstMaxImgL);
                             linRGB.b = doSigmoidToneMapping(c1, c2, c3, linRGB.b, srcMaxImgL, dstMaxImgL);
                             linRGB /= 2.03f;
    }

    // Step 3: Convert linear RGB to CIE Y.
    // The dot product is the standard, efficient way to do this in GLSL.
    float Y = dot(linRGB, p_Y_coeffs);

    // Step 4: Convert Y to perceptual lightness (L*).
    return y2Lstar(Y);
}

/**
  False Color function based on the iOS codebase.
*/
vec4 falseColor(vec4 color) {
    // Get the Y coefficients for the current color space.
    vec3 y_coeffs;
    // HLG uses Rec. 2020 primaries
    if (colorSpace == 1) y_coeffs = vec3(RGB_TO_XYZ_2020[0][1], RGB_TO_XYZ_2020[1][1], RGB_TO_XYZ_2020[2][1]);
    // P3-D65
    else if (colorSpace == 2) y_coeffs = vec3(RGB_TO_XYZ_P3D65[0][1], RGB_TO_XYZ_P3D65[1][1], RGB_TO_XYZ_P3D65[2][1]);
    // Default to Rec. 709
    else y_coeffs = vec3(RGB_TO_XYZ_709[0][1], RGB_TO_XYZ_709[1][1], RGB_TO_XYZ_709[2][1]);

    // Calculate perceptual luminance (L*), normalized to [0, 1].
    float luminance = calcLuminance(color, y_coeffs, colorSpace);

    vec4 grayscale = vec4(vec3(luminance), 1.0);
    vec4 finalColor;

    // Apply false color based on luminance thresholds from high to low.
    if (luminance > 0.96820304) finalColor = falseColorRed;
    else if (luminance > 0.93450500) finalColor = falseColorYellow; // "more yellow than red"
    else if (luminance > 0.89859341) finalColor = falseColorYellow;
    else if (luminance > 0.87515591) finalColor = grayscale; // yellow border
    else if (luminance > 0.67639759) finalColor = grayscale; // pink border
    else if (luminance > 0.65296009) finalColor = falseColorPink;
    else if (luminance > 0.61389759) finalColor = falseColorPink;
    else if (luminance > 0.59046009) finalColor = grayscale; // pink border
    else if (luminance > 0.50318239) finalColor = grayscale; // green border
    else if (luminance > 0.47974489) finalColor = falseColorGreen;
    else if (luminance > 0.45974489) finalColor = falseColorGreen;
    else if (luminance > 0.43630739) finalColor = grayscale; // green border
    else if (luminance > 0.22387647) finalColor = grayscale; // blue border
    else if (luminance > 0.20043897) finalColor = falseColorBlue;
    else if (luminance > 0.15487244) finalColor = falseColorBlue; // "more blue than purple"
    else if (luminance > 0.08991442) finalColor = falseColorPurple;
    else finalColor = falseColorPurple; // The final case, for everything from the last limit down to 0.

    return finalColor;
}
```

**Commentary.**
- Luminance is **CIE L\*** (perceptual lightness), not gamma-domain luma: decode OETF
  (Rec.709 gamma or HLG inverse-OETF per `colorSpace` uniform), tone-map HLG with a rational
  sigmoid `c=(c1+c2·x)/(1+c3·x)` with `c1=0, c2=1.021431, c3=0.002381, srcMax=1200 nits,
  dstMax=100 nits`, then `÷2.03`, dot with the **Y row of the RGB→XYZ matrix** (primaries
  selected by `colorSpace`: 0=Rec.709/sRGB, 1=Rec.2020 for HLG, 2=Display-P3 D65), then
  Y→L\* via the standard CIE formula normalized to [0,1].
  Note `RGB_TO_XYZ_2020[1][1]` prints as `0.779998` here vs the spec `0.677998` — a **typo in
  the shipped shader** (the 2020 G-column Y coefficient). **[file-text fact]** The `powf(`
  call in `rec709DecodeGamma` is likewise not GLSL (`powf` does not exist in GLSL ES);
  either the driver tolerates it or this variant fails to compile and only the `_fast` variant
  ships — see §3 and the Kotlin assembly in §10 (**[inference]**; needs runtime confirmation).
- `colorSpace` uniform maps to the ACTIVE camera color profile (HLG→2020, P3, 709) —
  see §3/§11 for the Java side.

### 1.6 `false_color_effect_fast.glsl` — FALSE COLOR, precomputed-luminance variant (full file)

```glsl
/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2025/7/28.
 */

#define falseColorRed       vec4(0.886, 0.055, 0.012, 1.0) // Y(76), Cb(87), Cr(235)
#define falseColorYellow    vec4(0.992, 0.976, 0.106, 1.0) // Y(225), Cb(16), Cr(148)
#define falseColorPink      vec4(0.949, 0.710, 0.741, 1.0) // Y(200), Cb(122), Cr(158)
#define falseColorGreen     vec4(0.306, 1.000, 0.145, 1.0) // Y(235), Cb(16), Cr(16)
#define falseColorBlue      vec4(0.118, 0.000, 0.863, 1.0) // Y(30), Cb(235), Cr(128)
#define falseColorPurple    vec4(1.000, 0.059, 1.000, 1.0) // Y(128), Cb(235), Cr(235)


// Uniforms for the false color effect
uniform int falseColorVisible;
uniform int colorSpace;

/**
  False Color function based on the iOS codebase.
*/
vec4 falseColor(float luminance) {

    vec4 grayscale = vec4(vec3(luminance), 1.0);
    vec4 finalColor;

    // Apply false color based on luminance thresholds from high to low.
    if (luminance > 0.96820304) finalColor = falseColorRed;
    else if (luminance > 0.93450500) finalColor = falseColorYellow; // "more yellow than red"
    else if (luminance > 0.89859341) finalColor = falseColorYellow;
    else if (luminance > 0.87515591) finalColor = grayscale; // yellow border
    else if (luminance > 0.67639759) finalColor = grayscale; // pink border
    else if (luminance > 0.65296009) finalColor = falseColorPink;
    else if (luminance > 0.61389759) finalColor = falseColorPink;
    else if (luminance > 0.59046009) finalColor = grayscale; // pink border
    else if (luminance > 0.50318239) finalColor = grayscale; // green border
    else if (luminance > 0.47974489) finalColor = falseColorGreen;
    else if (luminance > 0.45974489) finalColor = falseColorGreen;
    else if (luminance > 0.43630739) finalColor = grayscale; // green border
    else if (luminance > 0.22387647) finalColor = grayscale; // blue border
    else if (luminance > 0.20043897) finalColor = falseColorBlue;
    else if (luminance > 0.15487244) finalColor = falseColorBlue; // "more blue than purple"
    else if (luminance > 0.08991442) finalColor = falseColorPurple;
    else finalColor = falseColorPurple; // The final case, for everything from the last limit down to 0.

    return finalColor;
}
```

**Commentary.** Identical IRE→color decision table; the ONLY difference is the signature:
`falseColor(float luminance)` — the expensive per-pixel `calcLuminance()` (gamma decode +
HLG tone-map + XYZ dot + L\*) is skipped and the luminance is handed in precomputed. The
companion is `luminance_function.glsl` (§1.9): a 3D-texture "luminance LUT" that encodes
RGB→L\* on the CPU/native side once per color-space change, so the per-pixel cost is one
3D-texture fetch. This is the classic fast/slow split: `_fast` + `luminanceTexture` = the
shipping path, full variant kept for parity with iOS (**[inference]**).

### 1.7 `zebra_effect.glsl` — ZEBRAS (full file)

```glsl
/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 */

// Uniforms for the zebra effect
uniform int zebrasVisible;
uniform int midGreyMode; // 0 - none, 1 - mid grey, 2 - mid grey 1+
uniform float zebraThreshold;
uniform float zebraShift;
uniform float zebraWidth;
uniform vec4 zebraColor;
uniform int outWidth;
uniform int outHeight;

const vec2 midGrey = vec2(0.39550781, 0.41894531);
const vec2 midPlus1Grey = vec2(0.51171875, 0.53417969);

/**
  Zebras function based on the iOS codebase.
*/
vec4 zebras(vec4 color, float luminance) {
    // Calculate the screen position as a float to work with mod
    float x = cameraTexCoordinates.x * float(outWidth);
    float y = cameraTexCoordinates.y * float(outHeight);

    // Apply the shift to move the zebra stripes diagonally
    float combinedPosition = x + y + zebraShift; // Combining x and y for a diagonal movement

    float stripePattern = mod(combinedPosition, 2.0 * zebraWidth);

    // Determine if the pixel is in a stripe or not using the step function
    float inStripe = step(zebraWidth, stripePattern);

    // Always check the zebra threshold condition
    float thresholdCondition = step(zebraThreshold, luminance);

    // Compute additional mid-grey condition only when needed.
    float midCondition = 0.0;
    if (midGreyMode == 1) {
        // Luminance must be between midGrey.x (inclusive) and midGrey.y (exclusive)
        midCondition = step(midGrey.x, luminance) * (1.0 - step(midGrey.y, luminance));
    } else if (midGreyMode == 2) {
        // Luminance must be between midPlus1Grey.x (inclusive) and midPlus1Grey.y (exclusive)
        midCondition = step(midPlus1Grey.x, luminance) * (1.0 - step(midPlus1Grey.y, luminance));
    }

    // Combine conditions:
    // If midGreyMode == 0, use only the threshold condition.
    // Otherwise, use a logical OR (via max) so that if either condition is met, stripes are applied.
    float condition = (midGreyMode == 0) ? thresholdCondition : max(thresholdCondition, midCondition);

    return mix(color, zebraColor, inStripe * condition);
}
```

**Commentary.**
- **Geometry:** 45° diagonal stripes: `combinedPosition = x + y + zebraShift` in *output
  pixel* space (`cameraTexCoordinates * outWidth/outHeight`), `stripePattern = mod(pos,
  2·zebraWidth)`, `inStripe = step(zebraWidth, stripePattern)` → 50% duty-cycle diagonal
  stripes of on-width `zebraWidth` output px, period `2·zebraWidth` px along x+y.
  `zebraShift` phase-shifts the pattern diagonally (lets the app animate/slide stripes or
  offset them from the HUD grid — **[inference]** for its exact UI driver).
- **Cutoff:** single high threshold `luminance ≥ zebraThreshold` → classic over-exposure
  zebras. **Dual mode:** `midGreyMode` 0/1/2 adds a *band* zebra at mid-grey (and "+1 stop"
  mid-grey) using hard-coded bands: `midGrey = [0.39550781, 0.41894531)`,
  `midPlus1Grey = [0.51171875, 0.53417969)` — these are **10-bit limited-range Y code values
  normalized by 1024**: 0.39550781·1024 = **405**, 0.41894531·1024 = **429**,
  0.51171875·1024 = **524**, 0.53417969·1024 = **547** (i.e. ≈ 39–42% and 51–53% of
  code range; 18%-grey and one-stop-up anchors — **[inference]** on the IRE labels).
  OR-combined with the overexposure condition via `max()`.
- `luminance` is passed in (same precomputed `getLuminance()` as false-color-fast — see
  §1.9). Stripes paint `zebraColor` over the untouched image with `mix()`.

### 1.8 `histogram_compute.glsl` — RGB histogram accumulation (GLES 3.1 compute, full file)

```glsl
#version 310 es
// RGB histogram accumulation (GLES compute).
// Samples the graded (LUT-applied, overlay-free) analysis frame and bins each channel into BINS
// buckets, laid out contiguously: R = [0,BINS), G = [BINS,2*BINS), B = [2*BINS,3*BINS).
// BINS is injected from ImageHistogramState.HISTOGRAM_BINS so the kernel and the CPU readback stay
// in lockstep - see CompositeRenderer.createFrameExtractionResources.
//
// Two-level accumulation: each work-group first tallies its pixels into an on-chip `shared`
// histogram (fast, workgroup-scoped atomics), then flushes those partial counts into the global
// SSBO with one atomicAdd per bin. This collapses the global-atomic traffic from one-per-pixel to
// one-per-bin-per-workgroup, which is where the contention cost lives. The global buffer is
// pre-zeroed on the CPU each frame, so the flush accumulates across workgroups.
precision highp float;
precision highp int;

layout(local_size_x = 16, local_size_y = 16) in;

uniform highp sampler2D uSrc;

layout(std430, binding = 0) buffer Bins {
    uint bins[];
};

const uint BINS = %%BINS%%u;
const uint TOTAL = BINS * 3u;
// Must equal local_size_x * local_size_y. Used to stride the cooperative clear/flush so they cover
// TOTAL regardless of how TOTAL compares to the work-group size.
const uint WG_SIZE = 256u;

shared uint sBins[TOTAL];

void main() {
    uint lid = gl_LocalInvocationIndex;

    // Cooperatively zero the shared histogram (strided so it works for any TOTAL).
    for (uint i = lid; i < TOTAL; i += WG_SIZE) {
        sBins[i] = 0u;
    }
    memoryBarrierShared();
    barrier();

    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dims = textureSize(uSrc, 0);
    if (p.x < dims.x && p.y < dims.y) {
        vec4 c = texelFetch(uSrc, p, 0);
        // clamp(v) * BINS maps [0,1] -> [0,BINS]; min() folds the v==1.0 edge back into the last bin.
        uint r = min(uint(clamp(c.r, 0.0, 1.0) * float(BINS)), BINS - 1u);
        uint g = min(uint(clamp(c.g, 0.0, 1.0) * float(BINS)), BINS - 1u);
        uint b = min(uint(clamp(c.b, 0.0, 1.0) * float(BINS)), BINS - 1u);
        atomicAdd(sBins[r], 1u);
        atomicAdd(sBins[BINS + g], 1u);
        atomicAdd(sBins[2u * BINS + b], 1u);
    }
    memoryBarrierShared();
    barrier();

    // Flush the work-group's partial counts into the global SSBO (one atomicAdd per non-empty bin).
    for (uint i = lid; i < TOTAL; i += WG_SIZE) {
        uint v = sBins[i];
        if (v != 0u) {
            atomicAdd(bins[i], v);
        }
    }
}
```

**Commentary.**
- The ONLY compute shader (GLES 3.1, `#version 310 es`); everything else is `#version 320 es`
  fragment/vertex. 16×16 work-groups (256 threads). `%%BINS%%` is a **textual template slot**
  replaced by the Kotlin loader ("BINS is injected from `ImageHistogramState.HISTOGRAM_BINS`
  so the kernel and the CPU readback stay in lockstep — see `CompositeRenderer.
  createFrameExtractionResources`") — both class names are quoted in the shader itself and turn
  out to be **iOS class names** (no such classes exist in the Android app); the Android
  equivalents are `ax0.c()` (per §10.4: `%%BINS%%` is replaced with **"64"** via
  `kr6.H0(source, "%%BINS%%", "64", false)`), and the readback goes through a double-buffered
  `GL_PIXEL_PACK_BUFFER` pair (768 B = 64×3×4) mapped one frame late and handed to the
  `ne0.onImageBufferAvailable(ByteBuffer)` callback (feeds the HUD histogram **and scene
  detection** — GLES<3.1 logs "Histogram and scene detection are disabled").
- **Input frame:** "the graded (LUT-applied, overlay-free) analysis frame" — the histogram is
  computed AFTER the 3D LUT and BEFORE/without overlays (zebra/peaking) — direct evidence of
  the pipeline order (§10).
- **Binning math:** per-channel `bin = min(uint(clamp(c,0,1)·BINS), BINS−1)`; layout
  `R=[0,BINS) G=[BINS,2BINS) B=[2BINS,3BINS)`; two-level accumulation (shared-memory
  workgroup histogram → one `atomicAdd` per non-empty bin into a `std430` SSBO at binding 0);
  the global SSBO is pre-zeroed **on the CPU** each frame. Results are read back via the
  double-buffered PBO pair to a 768-byte `ByteBuffer` and delivered through the
  `ne0.onImageBufferAvailable(ByteBuffer)` callback for the Compose histogram HUD and scene
  detection (verified in §10.4 — the earlier [inference] is superseded).

### 1.9 `luminance_function.glsl` — the luminance 3D-LUT helper (full file)

```glsl
/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 */

// Uniforms for the Luminance LUT
uniform int luminanceVisible;
uniform highp sampler3D luminanceTexture;
// They are precalculated (1.0 - voxelSize) and (voxelSize/2.0) operations, as they are the same for each pixel.
uniform highp float luminanceScale;
uniform highp float luminanceOffset;

float getLuminance(vec4 color) {
    if (luminanceVisible == 0) {
        // Return a default luminance (e.g., standard Rec.709 coefficients) if the LUT is disabled
        return dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    // To ensure accurate linear interpolation, adjust the coordinates so 0 and 1 correspond to the centers of the first and last voxels, respectively.
    vec3 coord = color.rgb * luminanceScale + luminanceOffset;
    return texture(luminanceTexture, coord).r;
}
```

**Commentary.** A **3D texture that maps RGB → luminance (single float in `.r`)**. Same
half-voxel-center coordinate correction as the 3D LUT (`scale = 1 − 1/N`, `offset = 1/(2N)`)
— precalculated in uniforms `luminanceScale` / `luminanceOffset`. This is the "fast" source of
the `luminance` parameter consumed by `zebras()` and `falseColor_fast()`: the full
L\* pipeline of §1.5 (OETF decode + HLG tone map + XYZ Y-row dot + Y→L\*) is baked into this
3D texture once (by CPU/native code — see §5/§7 for who builds it), and per-pixel it is one
`texture(sampler3D)` fetch with hardware trilinear interpolation. Fallback when
`luminanceVisible == 0` is the plain Rec.709 luma dot `0.2126/0.7152/0.0722`.

### 1.10 `lut_3d_effect.glsl` — the 3D-LUT include used by the master shader (full file)

```glsl
/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 */

// Uniforms for the 3D LUT
uniform int lut3DVisible;
uniform highp sampler3D lut3DTexture;
// They are precalculated (1.0 - voxelSize) and (voxelSize/2.0) operations, as they are the same for each pixel.
uniform highp float lut3DScale;
uniform highp float lut3DOffset;


vec4 lut3D(vec4 color) {

    // To ensure accurate linear interpolation, adjust the coordinates so 0 and 1 correspond to the centers of the first and last voxels, respectively.
    vec3 coord = color.rgb * lut3DScale + lut3DOffset;

    return texture(lut3DTexture, coord);
}
```

**Commentary.** The LUT effect body for the composed master fragment shader (no `main()` —
included). Sampling strategy: `texture()` on a `sampler3D` → **hardware trilinear
interpolation** (no `texelFetch`, no manual tetrahedral math), with the **half-texel offset
correction** so domain 0.0 and 1.0 land on the *centers* of the first and last voxels:
`coord = rgb·lut3DScale + lut3DOffset` with `lut3DScale = 1 − 1/N` and
`lut3DOffset = 1/(2N)` (precalculated Java-side; comment says exactly "(1.0 − voxelSize) and
(voxelSize/2.0)"). Alpha passes through unchanged.

### 1.11 `yuv_to_rgb_lut3d_fragment.glsl` — YUV decode + LUT combined pass (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/5/17.
 */

#extension GL_EXT_YUV_target : require
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform __samplerExternal2DY2YEXT sTexture;
in vec2 cameraTexCoordinates;
out vec4 fragColor;

// Uniforms for the 3D LUT
uniform int lut3DVisible;
uniform highp sampler3D lut3DTexture;
// They are precalculated (1.0 - voxelSize) and (voxelSize/2.0) operations, as they are the same for each pixel.
uniform highp float lut3DScale;
uniform highp float lut3DOffset;

uniform int isFullRange;

vec4 lut3D(vec4 color) {
    // To ensure accurate linear interpolation, adjust the coordinates so 0 and 1 correspond to the centers of the first and last voxels, respectively.
    vec3 coord = color.rgb * lut3DScale + lut3DOffset;

    return texture(lut3DTexture, coord);
}

vec3 yuvToRgb(vec3 yuv) {
    if (isFullRange == 1) {
        const mat3 yuvToRgbColorTransformFull = mat3(
            1.0000f,  1.0000f,  1.0000f,
            0.0000f, -0.1646f,  1.8814f,
            1.4746f, -0.5714f,  0.0000f
        );
        const vec3 yuvOffsetFull = vec3(0.0, 0.5, 0.5);
        yuv = yuv - yuvOffsetFull;
        return clamp(yuvToRgbColorTransformFull * yuv, 0.0, 1.0);
    } else {
        const mat3 yuvToRgbColorTransformLimited = mat3(
            1.1689f, 1.1689f, 1.1689f,
            0.0000f, -0.1881f, 2.1502f,
            1.6853f, -0.6530f, 0.0000f
        );
        const vec3 yuvOffsetLimited = vec3(0.0625, 0.5, 0.5);
        yuv = yuv - yuvOffsetLimited;
        return clamp(yuvToRgbColorTransformLimited * yuv, 0.0, 1.0);
    }
}

void main() {
    vec4 outColor = texture(sTexture, cameraTexCoordinates);
    outColor.rgb = yuvToRgb(outColor.rgb);
    if (lut3DVisible == 1) outColor = lut3D(outColor);
    fragColor = outColor;
}
```

**Commentary.**
- Uses `GL_EXT_YUV_target` + `__samplerExternal2DY2YEXT` — Android's
  *YUV native-buffer* external sampler: the camera `SurfaceTexture` is sampled in
  **native YUV** space and converted in-shader (this is the HLG10/HDR10 path where the
  producer attaches YCbCr data — Stage-2 noted the Oppo/OnePlus preview is reconfigured to
  HLG10 DRP + `isFullRange=1`).
- `main()` order is literal: **sample YUV → `yuvToRgb()` → `lut3D()`** — i.e. the 3D LUT is
  applied AFTER YUV→RGB, in the same fragment pass, directly from the camera texture (no FBO
  in between on this path).
- **Matrix identification [derivation]:** the GLSL `mat3(...)` is column-major, so the
  effective rows are R = Y + 1.4746·Cr′, G = Y − 0.1646·Cb′ − 0.5714·Cr′, B = Y + 1.8814·Cb′
  (after offset). Those are **BT.2020/BT.2100 full-range** coefficients
  (Kr=0.2627: 2(1−Kr)=1.4746, Kb=0.0593: 2(1−Kb)=1.8814, and the G chroma weights
  2Kb(1−Kb)/(1−Kr−Kb)=0.1646, 2Kr(1−Kr)/(1−Kr−Kb)=0.5714) with chroma offset 0.5 and Y
  offset 0. The limited matrix gains (1.1689 = 1024/876; chroma ×1024/896 = ×1.142857:
  1.4746→1.6853, 1.8814→2.1502, 0.1646→0.1881, 0.5714→0.6530; Y offset 64/1024 = 0.0625)
  are the **BT.2020 limited-range (narrow/10-bit-style swing, normalized by 1024)**
  coefficients. So both YUV matrices are BT.2020/2100 — NOT BT.601/709 — matching the HDR
  preview dataspace (HLG10 = BT.2020 primaries). **[derivation from file text]**

### 1.12 `yuv_to_rgb_passthrough_fragment.glsl` — YUV decode only (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/5/17.
 */

#extension GL_EXT_YUV_target : require
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform __samplerExternal2DY2YEXT sTexture;
in vec2 cameraTexCoordinates;
out vec4 fragColor;

uniform int isFullRange;

vec3 yuvToRgb(vec3 yuv) {
    if (isFullRange == 1) {
        const mat3 yuvToRgbColorTransformFull = mat3(
            1.0000f,  1.0000f,  1.0000f,
            0.0000f, -0.1646f,  1.8814f,
            1.4746f, -0.5714f,  0.0000f
        );
        const vec3 yuvOffsetFull = vec3(0.0, 0.5, 0.5);
        yuv = yuv - yuvOffsetFull;
        return clamp(yuvToRgbColorTransformFull * yuv, 0.0, 1.0);
    } else {
        const mat3 yuvToRgbColorTransformLimited = mat3(
            1.1689f, 1.1689f, 1.1689f,
            0.0000f, -0.1881f, 2.1502f,
            1.6853f, -0.6530f, 0.0000f
        );
        const vec3 yuvOffsetLimited = vec3(0.0625, 0.5, 0.5);
        yuv = yuv - yuvOffsetLimited;
        return clamp(yuvToRgbColorTransformLimited * yuv, 0.0, 1.0);
    }
}

void main() {
    vec4 color = texture(sTexture, cameraTexCoordinates);
    color.rgb = yuvToRgb(color.rgb);
    fragColor = color;
}
```

**Commentary.** Identical YUV decode, no LUT — the "LUT off" variant of §1.11 (likely the
compile-time pruned partner when `lut3DVisible` will never be 1, e.g. capture/record path or
LUT-off preview — **[inference]**; the Kotlin selects between the two sources, §10).

### 1.13 `passthrough_fragment.glsl` (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/3/15.
 */

#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 cameraTexCoordinates;
uniform samplerExternalOES sTexture;
out vec4 fragColor;
void main() {
    fragColor = texture(sTexture, cameraTexCoordinates);
}
```

**Commentary.** The oldest shader in the set (2024/3/15): bare OES blit. Note the different
sampler name (`sTexture` not `cameraTexture`) — matches the two 2024/3/15 standalone shaders
(`lut3d_fragment.glsl`, this) that predate the header/effect-include split; these standalone
shaders are used as-is (each has its own `main()`).

### 1.14 `lut3d_fragment.glsl` (full file)

```glsl
#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/3/15.
 */

#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 cameraTexCoordinates;
uniform samplerExternalOES sTexture;
out vec4 fragColor;

// Uniforms for the 3D LUT
uniform int lut3DVisible;
uniform highp sampler3D lut3DTexture;
// They are precalculated (1.0 - voxelSize) and (voxelSize/2.0) operations, as they are the same for each pixel.
uniform highp float lut3DScale;
uniform highp float lut3DOffset;

vec4 lut3D(vec4 color) {
    // To ensure accurate linear interpolation, adjust the coordinates so 0 and 1 correspond to the centers of the first and last voxels, respectively.
    vec3 coord = color.rgb * lut3DScale + lut3DOffset;

    return texture(lut3DTexture, coord);
}

void main() {
    vec4 outColor = texture(sTexture, cameraTexCoordinates);
    if (lut3DVisible == 1) outColor = lut3D(outColor);
    fragColor = outColor;
}
```

**Commentary.** Standalone (own `main()`), same LUT body as §1.10 but sampling a plain OES
camera texture (RGB path, not Y2YEXT) — this is the *SDR / RGB SurfaceTexture* LUT-preview
pass: camera OES → optional LUT → out. **[inference]** this is the LUT-on-SDR-preview shader
used when the camera feed is an RGB(A) external texture rather than a YUV one, or for the
LUT-picker preview surface.

### 1.15 `blur_fragment.glsl` — 9-tap separable Gaussian (full file)

```glsl
#version 320 es

precision highp float;
uniform sampler2D sTexture;
// Per-axis step between taps, in texture coordinates: (stride / width, 0) for a horizontal pass,
// (0, stride / height) for a vertical pass, (0, 0) for a plain passthrough (all 9 taps land on the
// same texel and the weights sum to 1.0). The stride is scaled by the ramp strength on the Kotlin
// side, so the blur radius varies continuously instead of being quantised into whole passes.
uniform vec2 texelOffset;
// Output alpha: 1.0 for the offscreen blur passes, and the ramp strength for the final composite,
// which is alpha-blended over the sharp frame so the blur fades in/out without a visible pop.
uniform float alpha;
in vec2 cameraTexCoordinates;
out vec4 fragColor;

// 9-tap separable Gaussian blur.
void main() {
    vec4 sum = texture(sTexture, cameraTexCoordinates) * 0.227027;
    sum += texture(sTexture, cameraTexCoordinates + texelOffset * 1.0) * 0.1945946;
    sum += texture(sTexture, cameraTexCoordinates - texelOffset * 1.0) * 0.1945946;
    sum += texture(sTexture, cameraTexCoordinates + texelOffset * 2.0) * 0.1216216;
    sum += texture(sTexture, cameraTexCoordinates - texelOffset * 2.0) * 0.1216216;
    sum += texture(sTexture, cameraTexCoordinates + texelOffset * 3.0) * 0.054054;
    sum += texture(sTexture, cameraTexCoordinates - texelOffset * 3.0) * 0.054054;
    sum += texture(sTexture, cameraTexCoordinates + texelOffset * 4.0) * 0.016216;
    sum += texture(sTexture, cameraTexCoordinates - texelOffset * 4.0) * 0.016216;
    fragColor = vec4(sum.rgb, alpha);
}
```

**Commentary.** The classic 9-tap Gaussian weights (0.227027, 0.1945946, 0.1216216, 0.054054,
0.016216 — the well-known "efficient gaussian blur" weight set). `texelOffset` makes it
**separable**: the Kotlin runs it horizontally then vertically (two FBO passes), with
`(0,0)` degenerating to passthrough. The comments are unusually chatty for this codebase and
reveal the *use case*: a **blur ramp** whose strength is animated on the Kotlin side —
"alpha-blended over the sharp frame so the blur fades in/out without a visible pop".
**[inference]** this is the viewfinder frame-grab transition (e.g. when entering/exiting a
clip's playback from the camera tab, or photo-capture freeze), not a monitoring overlay:
it reads `sampler2D` (FBO texture), not the camera OES texture. See §10 for callers.

### 1.16 `color_transforms.glsl` — HLG EOTF + PQ OETF + YUV→RGB include (full file)

```glsl
// BT.2100 / BT.2020 HLG EOTF for one channel.
highp float hlgEotfSingleChannel(highp float hlgChannel) {
    // Specification:
    // https://www.khronos.org/registry/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_HLG
    // Reference implementation:
    // https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=265-279;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
    const highp float a = 0.17883277;
    const highp float b = 0.28466892;
    const highp float c = 0.55991073;
    return hlgChannel <= 0.5 ? hlgChannel * hlgChannel / 3.0 :
    (b + exp((hlgChannel - c) / a)) / 12.0;
}

// BT.2100 / BT.2020 HLG EOTF.
highp vec3 hlgEotf(highp vec3 hlgColor) {
    return vec3(
        hlgEotfSingleChannel(hlgColor.r),
        hlgEotfSingleChannel(hlgColor.g),
        hlgEotfSingleChannel(hlgColor.b)
    );
}

// BT.2100 / BT.2020, PQ / ST2084 OETF.
highp vec3 pqOetf(highp vec3 linearColor) {
    // Specification:
    // https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_PQ
    // Reference implementation:
    // https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=514-527;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
    const highp float m1 = (2610.0 / 16384.0);
    const highp float m2 = (2523.0 / 4096.0) * 128.0;
    const highp float c1 = (3424.0 / 4096.0);
    const highp float c2 = (2413.0 / 4096.0) * 32.0;
    const highp float c3 = (2392.0 / 4096.0) * 32.0;

    highp vec3 temp = pow(linearColor, vec3(m1));
    temp = (c1 + c2 * temp) / (1.0 + c3 * temp);
    return pow(temp, vec3(m2));
}


uniform int isFullRange;

vec3 yuvToRgb(vec3 yuv) {
    if (isFullRange == 1) {
        const mat3 yuvToRgbColorTransformFull = mat3(
            1.0000f,  1.0000f,  1.0000f,
            0.0000f, -0.1646f,  1.8814f,
            1.4746f, -0.5714f,  0.0000f
        );
        const vec3 yuvOffsetFull = vec3(0.0, 0.5, 0.5);
        yuv = yuv - yuvOffsetFull;
        return clamp(yuvToRgbColorTransformFull * yuv, 0.0, 1.0);
    } else {
        const mat3 yuvToRgbColorTransformLimited = mat3(
            1.1689f, 1.1689f, 1.1689f,
            0.0000f, -0.1881f, 2.1502f,
            1.6853f, -0.6530f, 0.0000f
        );
        const vec3 yuvOffsetLimited = vec3(0.0625, 0.5, 0.5);
        yuv = yuv - yuvOffsetLimited;
        return clamp(yuvToRgbColorTransformLimited * yuv, 0.0, 1.0);
    }
}
```

**Commentary.** The include-file twin of the two YUV standalone shaders: same BT.2020
full/limited `yuvToRgb()` (see §1.11 derivation) plus the two HDR transfer functions, quoted
verbatim from Khronos Data Format spec / AOSP `ProgramCache.cpp` (the comments cite exact
lines):
- `hlgEotf`: HLG→linear, `x≤0.5 → x²/3`, else `(b + e^((x−c)/a))/12` with a=0.17883277,
  b=0.28466892, c=0.55991073 — the BT.2100 HLG inverse OETF with the ÷12 scene-light scaling.
- `pqOetf`: **PQ/ST-2084 OETF (linear→PQ)** with the standard m1=2610/16384,
  m2=2523/4096·128, c1=3424/4096, c2=2413/4096·32, c3=2392/4096·32.
  **[inference]** `pqOetf` exists to *encode* linear intermediate frames back to PQ (e.g.
  when the HDMI/record path needs PQ output after an LUT or HLG→PQ conversion);
  `hlgEotf` decodes HLG frames to linear for effects that need scene-linear light.
  These are the "HDR OETF shader variants" promised in the Stage-1 handoff — they live in
  `assets/glsl`, and §10 documents which composed shader actually pulls this file in.

---

## 2. FOCUS PEAKING (summary; UI wiring in §10/§11)

- **Kernel (exact, from `focus_assist_effect.glsl`):**

```
{ -1, -3, -4, -3, -1 }
{ -3,  0,  6,  0, -3 }
{ -4,  6, 20,  6, -4 }
{ -3,  0,  6,  0, -3 }
{ -1, -3, -4, -3, -1 }     (sampled on texture().r, 25 taps, DC sum = +24)
```

- **Thresholds:** `coloredLinesThreshold = 5.5 − peakingThreshold` (solid `lineColor` when
  `|edge|` exceeds), `peakingThresholdAdjusted = 3.5 − peakingThreshold` (additive mono
  highlight `edge·(t/4)` when exceeded), gated by `enableColoredLines` 0/1.
- **Overlay colors:** additive grayscale highlight (mode 0) or uniform color `lineColor`
  (mode 1, `vec4` uniform from Java — the "Focus Assist Color" picker, `focus_assist_color`
  string, drives it).
- **Uniform setters + UI range (verified, §10.3):** uploaded per draw as
  `peakingThreshold = UI×0.04f` (UI default **0.85**, i.e. 0.034 in the shader → solid-line
  threshold `5.5−0.034 ≈ 5.466`, mono threshold `3.5−0.034 ≈ 3.466`),
  `enableColoredLines = k72.h ? 1 : 0` (default **true**), `lineColor = k72.i` ARGB/255
  (default **0xFFFF0000 = red**), `focusAssistVisible = per-output flag gu5.i`.
  Settings flow: Compose UI → `yq1{f,g,h}` → `pe5/se0` setters → `k72{h,i,j}` → uniforms.

## 3. FALSE COLOR (summary)

- **Luminance formula (full variant, exact):** decode OETF (Rec.709: linear x/4.5 ≤0.081 else
  `((x+0.099)/1.099)^(1/0.45)`; HLG: inverse OETF with a=0.17883277, b=0.28466892,
  c=0.55991073, ÷2) → if HLG, sigmoid tone-map `(1.021431·(100v))/(1+0.002381·(100v))` with
  linear extrapolation above 1200 nits scaled to 100 nits, then ÷2.03 → dot with Y-row of
  RGB→XYZ matrix (709/2020/P3 by `colorSpace`) → CIE L\* (`Y≤(6/29)³ → Y·(29/3)³` else
  `Y^(1/3)·116−16`, ÷100).
- **Fast variant:** same color decision, luminance from the `luminanceTexture` 3D LUT
  (`getLuminance()`, §1.9) — the ONLY difference between the two files (signatures:
  `falseColor(vec4 color)` vs `falseColor(float luminance)`; both also declare
  `falseColorVisible`+`colorSpace`; the full one additionally carries the matrices/OETF code).
- **Complete IRE→RGB threshold table** (L\* normalized [0,1]; all cutoffs strict `>`):

| L\* range (lower, upper] | Color | RGB (from shader) | comment in shader |
|---|---|---|---|
| > 0.96820304 | **Red** | 0.886, 0.055, 0.012 | // Y(76), Cb(87), Cr(235) |
| 0.93450500 – 0.96820304 | **Yellow** | 0.992, 0.976, 0.106 | "more yellow than red" |
| 0.89859341 – 0.93450500 | **Yellow** | 0.992, 0.976, 0.106 | |
| 0.87515591 – 0.89859341 | grayscale(L\*) | — | yellow border |
| 0.67639759 – 0.87515591 | grayscale(L\*) | — | pink border |
| 0.65296009 – 0.67639759 | **Pink** | 0.949, 0.710, 0.741 | |
| 0.61389759 – 0.65296009 | **Pink** | 0.949, 0.710, 0.741 | |
| 0.59046009 – 0.61389759 | grayscale(L\*) | — | pink border |
| 0.50318239 – 0.59046009 | grayscale(L\*) | — | green border |
| 0.47974489 – 0.50318239 | **Green** | 0.306, 1.000, 0.145 | |
| 0.45974489 – 0.47974489 | **Green** | 0.306, 1.000, 0.145 | |
| 0.43630739 – 0.45974489 | grayscale(L\*) | — | green border |
| 0.22387647 – 0.43630739 | grayscale(L\*) | — | blue border |
| 0.20043897 – 0.22387647 | **Blue** | 0.118, 0.000, 0.863 | |
| 0.15487244 – 0.20043897 | **Blue** | 0.118, 0.000, 0.863 | "more blue than purple" |
| 0.08991442 – 0.15487244 | **Purple** | 1.000, 0.059, 1.000 | |
| ≤ 0.08991442 | **Purple** | 1.000, 0.059, 1.000 | final case down to 0 |

(All six color constants with their Y/Cb/Cr provenance comments are quoted verbatim in §1.5.
"Bands between colored zones render as the *grayscale of L\**" — i.e. the un-flagged image
regions show B&W luminance, not the original color — file-text fact.)

## 4. ZEBRAS (summary)

- 45° diagonal stripes in output-pixel space, on-width `zebraWidth` px, period `2·zebraWidth`,
  phase from `zebraShift` (`mod(x+y+shift, 2w) ≥ w` → in-stripe).
- Over-exposure: `luminance ≥ zebraThreshold` (uniform from Java). **Dual**: optional
  mid-grey band zebras at Y ∈ [405,429)/1024 (mode 1) or [524,547)/1024 (mode 2 = "mid grey
  1+"), OR-ed with the overexposure condition. Paint = `zebraColor` uniform.
- IRE mapping: luminance comes from `getLuminance()` (L\*-domain via 3D LUT, or 709 luma
  fallback) — so `zebraThreshold` is compared against L\*-normalized [0,1], not raw video Y;
  the mid-grey constants are 10-bit video Y values (§1.7). Over-exposure-only when
  `midGreyMode == 0` (the default), dual when 1/2.

## 5. HISTOGRAM (summary)

GLES 3.1 compute; bins = `clamp(c,0,1)·BINS` per RGB channel into a `std430` SSBO
(binding 0), R/G/B contiguous; `%%BINS%%` injected from `ImageHistogramState.HISTOGRAM_BINS`
by `CompositeRenderer.createFrameExtractionResources` (quoted from the shader header);
two-level shared→global accumulation; input = the **graded (LUT-applied), overlay-free
analysis frame**; global buffer CPU-zeroed each frame; read back on CPU for the histogram
HUD. Work-group 16×16. (Java wiring in §10.)

## 6. 3D LUT SAMPLING (summary)

- Texture: `highp sampler3D` (`lut3DTexture`), half-texel-center correction
  `coord = rgb·(1−1/N) + 1/(2N)` via precalculated uniforms `lut3DScale`/`lut3DOffset`;
  sampling = single `texture()` fetch ⇒ **hardware trilinear** (no texelFetch, no manual
  tetrahedral) — quoted in §1.10/§1.11.
- YUV→RGB matrices: BT.2020 full-range and BT.2020 limited-range (normalized-by-1024) —
  full derivation in §1.11.
- **Render-chain position (from `main()` bodies):** on the YUV path the LUT is applied
  immediately after YUV→RGB, before any monitoring overlay (`yuv_to_rgb_lut3d_fragment.glsl`);
  on the composed master shader, `lut_3d_effect.glsl` is an include whose call order is decided
  by the generated `main()` in ax0 (§10) — and `histogram_compute.glsl` proves the graded
  frame is "LUT-applied, overlay-free", i.e. LUT before overlays.

## 8. COLOR TRANSFORMS + LUMINANCE + BLUR (summary)

- `color_transforms.glsl`: BT.2100 HLG EOTF (inverse OETF) + PQ/ST-2084 OETF + BT.2020
  YUV→RGB (full/limited). Quotes and constants in §1.16.
- `luminance_function.glsl`: 3D-texture RGB→luminance LUT with 709-luma fallback — quotes in
  §1.9. This is the shared luminance source for zebras + false-color-fast.
- `blur_fragment.glsl`: 9-tap separable Gaussian (weights 0.227027/0.1945946/0.1216216/
  0.054054/0.016216), `texelOffset` horizontal/vertical FBO passes, `alpha` = ramp strength
  for fade-in/out composite over the sharp frame — the *UI transition* blur, not a monitoring
  effect. Quotes in §1.15.

---

## 7. LUT FILE FORMATS + PARSING + 3D-TEXTURE UPLOAD

### 7.1 The built-in `.obj` assets are Java-serialized `Float16Lut` proxies with Zstd payloads

Hex/`od` of every file in `assets/bmc_luts/*.obj` (10 files) and
`assets/Oppo-O-Log2-to-Rec709_Gamma24-D65_33_0303.obj` shows the **Java object-serialization
stream** header `AC ED 00 05  sr  "com.blackmagicdesign.android.colorutils.luts.
SerializationProxy"` followed by, per `SerializationProxy.readObject` (smali quoted in §7.2):

| Field (Java serialization order) | Content observed in the assets |
|---|---|
| `readUTF()` → `f` (fileName) | `"Cinema Teal.cube"`, `"Monochrome.cube"`, `"Day Night.cube"`, `"Dusty.cube"`, `"Nature.cube"`, `"Nostalgic.cube"`, `"Paloma.cube"`, `"Rift.cube"`, `"Vivid.cube"`, `"Warm Fade.cube"`; `".Oppo-O-Log2-to-Rec709_Gamma24-D65_33_0303.cube"` |
| `readBoolean()` → optional `readUTF()` `i` (description) | `"Generated by Resolve"` (all built-ins — they were exported from DaVinci Resolve) |
| 2× `readBoolean()` → `m`, `w` | `isInVideoRange` / `isOutVideoRange` (Float16Lut.toString) |
| `readInt()` → `x` (lut3dSize) | 33 for the Oppo LUT ("33" in filename = 33³ grid); built-ins decompress to 33³·4·2-byte payloads (sizes 50,539–184,825 B compressed) |
| `readByte()` → compressionLevel; `readInt()` len; `readFully(byte[])` | **Zstd frame** — the zstd magic `28 B5 2F FD` is visible in every `.obj` right after the length fields |
| → `decompressZstd(compressed, short[x*x*x*4], level)` | native Zstd decompress into **4 half-floats per voxel** (RGBA16F) |

So: **`.obj` = Java-serialized `SerializationProxy` {name, description, isInVideoRange,
isOutVideoRange, size, short[size³×4]} with the short[] Zstd-compressed.** `Float16Lut`
(jadx: `com/blackmagicdesign/android/colorutils/luts/Float16Lut.java`) is the in-memory data
class — `toString()` = `"Float16Lut(fileName=…, description=…, isInVideoRange=…,
isOutVideoRange=…, lut3dSize=…, lut3dArray=…)"` — and its static identity constant is:

```java
z = new Float16Lut("identity_3d_lut", null, false, false, 2, new short[]{0, 0, 0, 15360, 15360, 0, 0, 15360, 0, 15360, 0, 15360, 15360, 15360, 0, 15360, 0, 0, 15360, 15360, 15360, 0, 15360, 15360, 0, 15360, 15360, 15360, 15360, 15360, 15360, 15360});
```

(i.e. a 2×2×2 RGBA16F identity; 15360 = 0x3C00 = half-float 1.0 — the diagonal is 1.0.)
`Float16Lut.writeReplace()` swaps in the `SerializationProxy` for writing, so imported/user
LUTs persist in exactly the same `.obj` format.

### 7.2 The native LUT quartet (arm64-v8a)

`strings` + JNI symbol census (all four BMD-proprietary):

| Library | JNI export(s) | Key strings | Verdict |
|---|---|---|---|
| `liblutprocessor` → **liblut_processor.so** (42,704 B) | `LutProcessor_combineLutsNative`, `LutProcessor_bakeProfileNative`, `LutProcessor_generateLuminanceLutNative`, `LutProcessor_generateIdentityLutNative`, `LutProcessor_generateCubeFileContentNative` | `"LUT_3D_SIZE %d"`, `"TITLE \""` | Kotlin `object LutProcessor` (`System.loadLibrary("lut_processor")`, clinit) — generates/bakes/combines LUTs and **writes .cube text** (export) |
| **liblut_float16_parser.so** (42,496 B) | `Float16LutParser_parseCubeFileNative(String fileName, int fd)` | `"LUT_3D_SIZE"`, `"LUT_3D_INPUT_RANGE"`, `"LUT_1D_SIZE"`, `"DOMAIN_MIN"`, `"DOMAIN_MAX"`, `"com/blackmagicdesign/android/colorutils/luts/Float16Lut"` | Parses **user-imported .cube** (Adobe .cube grammar incl. input-range + domain lines) straight into a `Float16Lut` |
| **liblutparser.so** (46,848 B) | `NativeLutParser_parseCubeFile` | same .cube grammar + `"LUT_3D_INPUT_RANGE_{R,G,B}"` | Older/full-precision parser; **no Java class references it** in v3.5.0 (dead native path kept for compatibility) |
| **liblut_compressor.so** (569,960 B) | `SerializationProxy_compressZstd([S,I,I)[B`, `SerializationProxy_decompressZstd([B[SI)V` | `ZSTD_createCCtx`, `ZSTD_compressCCtx`, `ZSTD_decompressDCtx`, `ZSTD_compressBound`, XXH64… | Full **Zstd** library — the `.obj` payload codec. Confirms compression = Zstd |

Java-side signatures (from `LutProcessor.smali` `@kotlin/Metadata` + `Float16LutParser.smali`):
`bakeProfileNative(sourceCurveId: Int, targetCurveId: Int, gamutMatrix: FloatArray,
lutArray: ShortArray, lutSize: Int): ShortArray`,
`generateLuminanceLutNative(curveId: Int, yCoeffR: Float, yCoeffG: Float, yCoeffB: Float,
size: Int): ShortArray`, `generateIdentityLutNative(size: Int): ShortArray`,
`generateCubeFileContentNative(title: String, size: Int, data: ShortArray): String`,
`combineLutsNative(...)` (via `LutProcessor.a(vu, Float16Lut, String)` — if the profile's
gamut matrix (`vu.b`, a `b64` 3×3 double matrix) ≠ Rec.709's `hs0`, it multiplies the two 3×3s
into a `float[9]` and composes it into the LUT natively — that is how Rec.709-baked creative
LUTs get re-targeted onto HDR/P3 profiles).

### 7.3 Where LUTs come from (u40.smali + `LutManager$1.smali` + `settings/b.smali`)

`u40` (LUT loader) resolves a LUT for the current profile in three ways:
1. **Vendor-Log technical LUT**: `AssetManager.open("Oppo-O-Log2-to-Rec709_Gamma24-D65_33_0303.obj")`
   → `ObjectInputStream.readObject()` → `Float16Lut` (Oppo/OnePlus O-Log2 → Rec.709 Gamma24 D65,
   33³ — matches Stage-2's Vendor Log finding).
2. **Dynamically baked**: `LutProcessor.e(profileVu)` — e.g. identity fallback is
   `new Float16Lut("lut_identity_"+size, "Dynamically generated Identity LUT of size "+size,
   …, generateIdentityLutNative(size))`; log/technical conversions use `bakeProfileNative`
   (curve ids from `ColorSpaceProfile.b()`: REC709→0, SRGB→15, P3D65→2, P3D60→14,
   BT2020_HLG10→19, BT2020_LOG→21, BT2020_VENDOR_LOG→17, BT2020_HDR10→18, HDR10+→1,
   DV_PQ→3, DV_HLG→20).
3. **Built-in creative LUTs**: `LutManager$1` enumerates `AssetManager.list("bmc_luts")`,
   filters `".obj"`, and `u40` opens `"bmc_luts/<name>.obj"` → same Java-deserialization path.
4. **User imports** (settings LUTs manager, `settings/b.smali` holds
   `r: Float16LutParser`): SAF `Uri` → query `_display_name` →
   `openFileDescriptor("r")` → `parseCubeFileNative(fileName, fd)` → `Float16Lut`.
   Failure → strings `"LUT Import Failed"` / `"This LUT cannot be imported…"`.
   Export → `generateCubeFileContentNative` (writes `TITLE "…"`, `LUT_3D_SIZE %d` .cube text;
   strings `"Export LUT"`, `"Select LUTs to Export"`).

### 7.4 LUTs → GL 3D textures (s24 + ef2.i)

`s24` (LUT3D effect holder, smali quoted): texture unit **GL_TEXTURE2** (`k = 0x84C2`),
sampler uniform bound to unit 2, target `GL_TEXTURE_3D` (0x806F). Upload path in `ef2.i`
(`ef2.smali`):

```smali
:cond_0  → glTexImage3D(GL_TEXTURE_3D /*0x806F*/, level 0, internalformat 0x881A /*GL_RGBA16F*/,
            w=size, h=size, d=size, border 0, format 0x1908 /*GL_RGBA*/, type 0x140B /*GL_HALF_FLOAT*/, ShortBuffer)
(if isSub) → glTexSubImage3D(same formats, offsets 0) — used for LUT swaps after first upload
```

- **Data layout**: `ShortBuffer.wrap(Float16Lut.y)` — `x³` voxels × 4 half-floats (RGBA),
  i.e. the LUT file's channel order is R,G,B,(A) per voxel; the identity constant's diagonal
  (R=v.v.v) proves RGB order (not BGR) **[derivation]**.
- **Dimension checks**: `s24` ctor requires `Float16Lut.x > 0` else `"Failed requirement."`;
  `r24.c` validates a cached luminance LUT's `x == requestedSize` before reuse.
- **Scale/offset uniforms** (s24.c, quoted): `l = 1 − 1/N`, `m = (1/N)/2` → exactly the
  shader's `lut3DScale`/`lut3DOffset` half-voxel correction.
- **"LUT off"** = `Float16Lut.z` (2³ identity) via `s24.e(Float16Lut)` (null → identity), so
  the shader path never changes, just the data.
- **Luminance LUT** (`r24`): same `ef2.i` upload → **33³ RGBA16F** on texture unit
  **GL_TEXTURE3** (0x84C3), sampler `luminanceTexture`=3; data from
  `LutProcessor.c(vu, 33)` → `generateLuminanceLutNative(curveId, yR, yG, yB, 33)`, cached as
  `"lut_lum_<sanitized-profile-name>_<33>.bmdlut"` (Java-serialized `Float16Lut`) in
  `context.getCacheDir()` — the yCoeffs + curveId bake the whole "OETF decode + HLG tone-map +
  XYZ Y-dot + L\*" chain of §1.5 into one 3D fetch.
- **Filters**: `ef2.f` sets MIN/MAG `GL_LINEAR`, WRAP S/T `GL_CLAMP_TO_EDGE` (2D); the 3D
  texture gets LINEAR (trilinear) + clamp from `ef2.i` (checked via the same param block).

---

## 9. ANAMORPHIC + FRAME GUIDES

- **`AnamorphicLensDeSqueezeFactor`** (`com/blackmagicdesign/android/utils/entity/…`, jadx
  full read): `ALDSF_1(100, 1.0f, Size(1,1))`, `ALDSF_133(133, 1.33f, Size(4,3))`,
  `ALDSF_155(155, 1.55f, Size(14,9))`, `ALDSF_200(200, 2.0f, Size(2,1))` — i.e. desqueeze
  factors **1.0 / 1.33 / 1.55 / 2.0** with target aspects **4:3 / 14:9 / 2:1**. Settings key
  `SettingsTypeName.anamorphicLensDeSqueezeFactor` (zb6 ordinal 18); state holder `ut5.J`
  (default `ALDSF_1`); UI label `anamorphic_de_squeeze` = "Anamorphic De-Squeeze".
- **Application of the factor**:
  - *Media library thumbnails*: `f9` (defpackage) is a Glide `BitmapTransformation` (cache
    key `"anamorphic_transform_" + factor`) that **horizontally stretches** the bitmap —
    `c()` quote (jadx, full): `int width = (int) (bitmap.getWidth() * f); …
    Matrix matrix = new Matrix(); matrix.setScale(f, 1.0f);
    canvas.drawBitmap(bitmap, matrix, new Paint(6));` (returns the bitmap unchanged when
    `f == 1.0f`); `BmdMedia.getAnamorphicFactor` (coroutine) reads the factor from clip
    metadata.
  - *Live viewfinder*: **[inference]** the factor drives the preview/output aspect (the
    `du5` output list + `gu5.c` Size and `ov4.f`'s aspect-fit matrix take the anamorphic
    aspect from the settings state); the GL transform itself (`ov4.f`) is a rotation/aspect/
    crop-fit builder — its `Integer` param is the **sensor orientation in degrees**
    (`ce0.r`, passed as `Integer` into ax0's ctor from `GraphicCameraPipeline`), not the
    desqueeze. **[inference]** the HUD frame-guide overlay is drawn by Compose, not GL (next
    bullet).
- **Frame guides / safe areas / grids** are **Compose HUD, not GLSL** — there is no
  frame-guide shader. Evidence: `ds1` = `EftOptionsUiState(guidesState=…, gridsState=…,
  safeAreaState=…, focusAssistState=…, zebraState=…, lightState=…)`; `gs1` =
  `EftSafeAreaUiState(safeAreaEntriesFlow, safeAreaValueFlow, onSafeAreaSelected)`;
  `o40` (settings/HUD state, 57 strings) carries `guidesAspectRatio=…`, `safeArea=…`,
  `pixelAspectRatio=…`; remote-control exposure of the same: `SettingsTypeName` + `GsFlags`
  contain `frameGuide`/`safeArea` tokens, and REST `DisplaySettings`/`EventProperty` mirror
  them (monitoring_control.yaml). The only GL-visible guide machinery is the `du5` rotation +
  aspect-fit path above.

---

## 10. PIPELINE ASSEMBLY (who renders what, in which order)

### 10.1 Class map (R8 names verified via smali/jadx)

| Name (file) | Role |
|---|---|
| `ax0` (`defpackage/ax0.java`, 1800 lines) | **"CamPreviewRenderer"** — the EGL/GLES viewfinder renderer (name string passed to its ctor from `GraphicCameraPipeline`); implements `SurfaceTexture.OnFrameAvailableListener`; runs on a dedicated `HandlerThread` (prio −19) with messages init/configure/frame/release |
| `com.blackmagicdesign.android.camera.pipeline.a` (= `GraphicCameraPipeline`, smali inner-class names confirm) | Holds `r: ax0`; ctor `(Context, ce0 cameraInfo, CameraAccessType, ColorSpaceProfile capture, ColorSpaceProfile display, yq1 monitoring, fps Int, q31 scope, ne0 callbacks, w04 logger)`; `s: [jm5` per-lens holders |
| `com.blackmagicdesign.android.camera.manager.a` (`CameraManager`) | `initializePipeline` builds `yq1` and the pipeline; supplies zebra threshold (`q0`), focus-assist colored-lines (`u0`), peaking (`t0`), line color (`v0`), LUT (`y0: Float16Lut`), and `j = w0 && !x0` |
| `yq1` | monitoring settings: `a`=falseColor on, `b`=zebras on, `c`=focusAssist on, `d`=lut3D on, `e`=zebraThreshold (default **0.75f**), `f`=focus colored lines (default **true**), `g`=peakingThreshold (default **0.85f**), `h`=lineColor (default **0xFFFF0000 red**), `i`=Float16Lut, `j`=luminance-on-graded flag (`w0 && !x0`) |
| `gu5` | one render output: `a`=CameraOutputType, `b`=Surface, `c`=Size, `d`/`e`/`f` pacing+mode, `g`=falseColor flag, `h`=zebras flag, `i`=focus flag, `j`=LUT flag, `k`=blur-transition flag, `o`=clean-feed flag, `p`=viewport Rect, `q`=EGLSurface, `s`/`t`=4×4 matrices (master/passthrough), `b(J)Z`=per-output frame gate (fps Float `f`, accumulated-time ≥ 2.5× interval check) |
| `du5` | configure message: `(List<gu5>, int rotation, Float, boolean)` |
| `fu` | GL program wrapper — always pairs a fragment source with `glsl/transform_vertex.glsl`; `b`=vPosition attrib, `c`=texMatrix uniform |
| `ef2` | GL util: `f(w,h,bpp)` makes a 2D texture (**bpp 10 → GL_RGB10_A2 / UNSIGNED_INT_10_10_10_REV; else GL_RGBA8 / UNSIGNED_BYTE**; LINEAR + CLAMP_TO_EDGE); `i(ShortBuffer, tex, size, isSub)` = RGBA16F 3D upload (§7.4); `a(II)` FBO attach; `d()` gen texture; `c(label)` GL-error check (`"load3DTexture"`) |
| `ss1` | EGL context + window-surface factory (1,520 lines; owns `EGLDisplay/Context`, `d(surface)`=makeCurrent, `b(surface,w,h,ColorTransfer)`=create EGLSurface…) |
| `ov4` | matrix/transfer utils: `f(matrix, rotation, aspect, Float, flipH, flipV, Integer sensorRot)` builds the per-output texture matrix (rotation about center via `Matrix.rotateM` after translate-to-center; `Integer` sensor-orientation branch adds a fit-crop of the SurfaceTexture matrix + extra rotation; aspect 16:9 default `Q=1.777`); `A(ColorSpaceProfile)` → `ColorTransfer` |
| `r24`/`s24`/`m22`/`et7`/`k72` | the five `cd6` effect holders (§§1.4–1.9): `cd6` abstract `{a: Z, b: Z, c: initialized}` — `a()` returns the **generated-main statement**, `b(Context)` returns the **include asset**, `b` gates `areEffectsVisible` |
| `jm5` | **per-LUT-preview mini renderer** (LUTs settings panel live preview): owns its own SurfaceTexture + `ss1` + `s24` and compiles the standalone `glsl/yuv_to_rgb_lut3d_fragment.glsl` (HDR) or `glsl/lut3d_fragment.glsl` (SDR) — the only consumers of those two 2024/3/15-era shaders |
| `pe5`/`ne5`/`je5`/`hs4`/`se0` | **remote-preview renderer + overlay setters**: `pe5` holds a `MediaCodec` decoder + its own `ax0` (remote-phone live preview), `e(yq1)` pushes monitoring settings (and **forces zebraColor to 0xFF000000 BLACK and midGreyMode=0**); `se0` is the local-camera monitor-settings coordinator calling `ax0.v/z/w(null|type, z)`; `je5`/`hs4` drive the Wear preview output |
| `ta0`…(g62 lambdas), `ne0` | `ne0` = frame-analysis callback interface: `onImageBufferAvailable(ByteBuffer)` (the histogram buffer) + `onFrameProcessed()` — wired as `FunctionReference`s into ax0's `wc2`/`uc2` ctor args |

`CameraOutputType` = **{PREVIEW, RECORD, EXTERNAL, ANALYSIS}** (jadx enum, 4 values) — the four
kinds of GL output surfaces one `ax0` renders to. Per-output overlay setters:
`ax0.v(type, z)` falseColor, `ax0.z(type, z)` zebras, `ax0.w(type, z)` focusAssist,
`ax0.x(type, z)` LUT, `ax0.y(type, z)` clean-feed (`null` type = all outputs) — this is the
"Display LUT vs Record LUT" split (strings `display_lut` "Display LUT", `record_lUT_to_clip`
"Record LUT to Clip"), plus per-output clean feed.

### 10.2 The generated master fragment shader (exact reconstruction)

`ax0.c()` builds the master program `O0` by **string concatenation** (jadx decompilation of
`ax0.java:402-443`):

```java
StringBuilder sb = new StringBuilder(oz4.H(context2, !z4 ? "glsl/fragment_header.glsl" : "glsl/fragment_header_fbo.glsl"));
if (colorTransfer != null) sb.append(oz4.H(context2, "glsl/color_transforms.glsl"));
for (cd6 effect : effects) sb.append(effect.b(context2));          // include sources, in list order
sb.append("\nvoid main() {\n    vec4 inColor = texture(cameraTexture, cameraTexCoordinates);\n");
if (colorTransfer == PQ)   sb.append("    inColor.rgb = hlgEotf(inColor.rgb);\n    // HLG has a different L = 1 than PQ, which is 10,000 cd/m^2.\n    inColor.rgb /= 40.0f;\n    // Convert from linear optical [0.0, 1.0] to PQ electrical\n    inColor.rgb = pqOetf(inColor.rgb);\n");
else if (colorTransfer == LINEAR) sb.append("    inColor.rgb = hlgEotf(inColor.rgb);\n");
sb.append("    vec4 outColor = inColor;\n");
// effects with cd6.b == false → appended unconditionally (s24 only)
// effects with cd6.b == true  → appended inside "    if (areEffectsVisible == 1) { … }"
sb.append("    }\n    fragColor = outColor;\n}\n");
```

with the effect statements taken verbatim from the `cd6.a()` implementations:

```java
r24: g==true  ? "float inLuminance = getLuminance(inColor);" : ""      // r24.g = !yq1.j
s24: "if (lut3DVisible == 1) outColor = lut3D(outColor);"              // cd6(false, 3) → b==false
m22: a==true  ? "if (falseColorVisible == 1) outColor = falseColor(getLuminance(outColor));"
              : "if (falseColorVisible == 1) outColor = falseColor(inLuminance);"   // a = yq1.j
et7: a==true  ? "if (zebrasVisible == 1) outColor = zebras(outColor, getLuminance(outColor));"
              : "if (zebrasVisible == 1) outColor = zebras(outColor, inLuminance);" // a = yq1.j
k72: "if (focusAssistVisible == 1) outColor = focusAssist(outColor);"
```

So the **exact composed chain** (includes in ctor-insertion order r24→s24→m22→et7→k72;
statement order: unconditional LUT first, then the `areEffectsVisible` block):

```glsl
<fragment_header.glsl | fragment_header_fbo.glsl>
<color_transforms.glsl>            // only when ColorTransfer != null
<luminance_function.glsl>          // when zebras || falseColor
<lut_3d_effect.glsl>               // when LUT enabled
<false_color_effect_fast.glsl>     // when falseColor
<zebra_effect.glsl>                // when zebras
<focus_assist_effect.glsl>         // when focusAssist
void main() {
    vec4 inColor = texture(cameraTexture, cameraTexCoordinates);
    // [ColorTransfer.PQ]:  hlgEotf → /= 40.0f → pqOetf
    // [ColorTransfer.LINEAR]: hlgEotf
    vec4 outColor = inColor;
    if (lut3DVisible == 1) outColor = lut3D(outColor);          // LUT — always evaluated
    if (areEffectsVisible == 1) {
        // [yq1.j == false] float inLuminance = getLuminance(inColor);   ← pre-LUT luminance
        // [falseColorVisible == 1] outColor = falseColor(getLuminance(outColor) | inLuminance);
        // [zebrasVisible == 1]    outColor = zebras(outColor, getLuminance(outColor) | inLuminance);
        // [focusAssistVisible == 1] outColor = focusAssist(outColor);
    }
    fragColor = outColor;
}
```

**Render-chain order (proven):** camera OES/YUV sample → (HDR transfer decode/re-encode) →
**3D LUT** → **false color** → **zebras** → **focus assist** → display; the histogram compute
pass runs on the **graded, overlay-free** frame (`areEffectsVisible=0` in the histogram draw
at `ax0.o()`, and the shader's own header comment says "LUT-applied, overlay-free").
**Luminance for zebras/false-color is measured pre-LUT** (`getLuminance(inColor)`) when
`yq1.j == false` (the default construction path: `j = w0 && !x0`), and **post-LUT**
(`getLuminance(outColor)` per effect) when `yq1.j == true` **[inference: this corresponds to a
monitoring preference tying scopes to the graded image when a display LUT is active]**.

`false_color_effect.glsl` (full) is **referenced by no smali** — Android ships only the
`_fast` include + luminance 3D LUT (census of all `glsl/*.glsl` strings in smali: 15/16
referenced; the full variant is dead on Android, kept for iOS parity **[inference]**).

### 10.3 Uniform setters (exact)

From `ax0.c()/j()` (per compiled program) and per-draw uploads (`ax0.j()`):

```java
this.P0 = glGetUniformLocation(prog, "areEffectsVisible");        // = (g||h||i||j) per output
// r24 (luminance, once per program): luminanceVisible=1 (forced), luminanceScale = 1 − 1/33,
//                                     luminanceOffset = (1/33)/2, luminanceTexture = unit 3
// s24 (LUT):        lut3DScale = 1 − 1/N, lut3DOffset = (1/N)/2, lut3DTexture = unit 2,
//                   lut3DVisible = per-output gu5.j
// m22 (falseColor): falseColorVisible = gu5.g; colorSpace = 0|2|1 by profile mapping:
//                   REC709/SRGB/P3D60 → 0 (709); P3D65 → 2; all BT2020/HDR profiles → 1 (2020/HLG)
// et7 (zebras):     zebrasVisible = gu5.h; midGreyMode = et7.q; zebraWidth = et7.r (default 8.0px);
//                   zebraShift = et7.s, animated +1.0/frame, wraps at 2·zebraWidth ("marching");
//                   zebraThreshold = et7.m * (0.93f for REC709 display | 0.92f for SRGB | 1.0f)
//                   zebraColor = et7.p (ARGB int / 255 per channel; default BLACK);
//                   outWidth/outHeight = output size
// k72 (focus):      focusAssistVisible = gu5.i; enableColoredLines = k72.h ? 1 : 0;
//                   lineColor = k72.i (ARGB / 255); peakingThreshold = k72.j * 0.04f
// isFullRange = 1 iff (ti1.m() || ti1.e()) && captureProfile == BT2020_VENDOR_LOG  (ax0.c():491)
```

Note the two **scale factors on upload**: the UI zebra threshold (0..1, default 0.75) is
multiplied by **0.93 (Rec.709 display) / 0.92 (sRGB display)** — i.e. ~7–8% more sensitive —
and the UI peaking "strength" (default 0.85) becomes `peakingThreshold×0.04` in the shader,
where the shader derives its real thresholds as `5.5 − t` and `3.5 − t`.

### 10.4 Outputs, FBOs, HDR, and the display paths

- **Two render modes** (`this.T`): single-output/direct (`T=false`) — the master shader
  samples the camera `GL_TEXTURE_EXTERNAL_OES` directly (`fragment_header.glsl`); multi-output
  or HDR (`T=true`, set in `ax0.p()` when >1 output, or `ColorTransfer != null`, or 10-bit
  buffers `ColorSpaceProfile.a()==10`, or an output with `gu5.e==8` exists) — a
  **"passthrough" program `N0` first blits camera→FBO** (`U`/texture `V`), and the master
  (compiled from `fragment_header_fbo.glsl`, `sampler2D`) then renders from `V` to every
  output. `N0` = `glsl/yuv_to_rgb_passthrough_fragment.glsl` when `ColorTransfer != null`
  (YUV Y2YEXT camera feed), else `glsl/passthrough_fragment.glsl`.
- **ColorTransfer** (`com/blackmagicdesign/android/graphics/opengl/ColorTransfer` enum:
  PQ, LINEAR, HLG, SDR, DISPLAY_P3) = display path, from `ov4.A(displayColorSpaceProfile)`:
  requires `gv4.m()` (SDK≥34 **[inference]**) ∧ ¬`ti1.b()` ∧ **EGL extension support**
  (`EGL_KHR_gl_colorspace` + per-mode `EGL_EXT_gl_colorspace_bt2020_pq` / `_bt2020_hlg` /
  `_bt2020_linear` / `_display_p3` — queried live via `eglQueryString`), else null (SDR path).
  Only PQ and LINEAR inject GLSL (§10.2); HLG/SDR/P3 pass through unchanged.
- **Per-output draw** (`ax0.j`): make the output's EGLSurface current → viewport `gu5.p` →
  bind camera texture (OES id `K` or FBO texture `V`) on unit 0 → upload per-output uniforms
  (§10.3) → `glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)` → optional blur composite →
  `eglPresentationTimeANDROID` (frame pacing, per-output fps gate `gu5.b(timestamp)`) →
  `eglSwapBuffers`.
- **Histogram pass** (`ax0.o()`): gated to `r0` fps (100 ms cadence at 10 fps **[inference]**);
  draws the master with `areEffectsVisible=0` into FBO `m0`/texture `n0` sized
  `⌊w/16⌋×⌊h/16⌋` (even, min 1 — exactly one compute work-group per 16×16 block) →
  zeroes the ping/pong **PBO pair** (`w0[2]`, `GL_PIXEL_PACK_BUFFER`, 768 B each) with a
  CPU-cleared `ByteBuffer` → `glDispatchCompute(⌈w/16⌉, ⌈h/16⌉, 1)` →
  `glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)` → maps the *other* PBO
  (`glMapBufferRange` read-only) from the previous frame and delivers the 768-byte
  `ByteBuffer` (LITTLE_ENDIAN) to the `wc2` callback = `ne0.onImageBufferAvailable` →
  `onFrameProcessed`. **BINS = 64** (`kr6.H0(source, "%%BINS%%", "64", false)`), 768 B =
  64 bins × 3 channels × 4 B. GLES < 3.1 → log: *"GPU histogram unavailable: compute shaders
  unsupported on this device. Histogram and scene detection are disabled."* (histogram feeds
  both the HUD histogram and **scene detection**).
- **Blur/transition** (`gu5.k` flag, `ax0.t()/i()/f()`): 9-tap Gaussian at 1/6 resolution
  (FBO `a0`/`b0` + `c0`/`d0` ping-pong), **3 cascaded passes with strides
  `Q0[i]·ramp = {1.0, 2.0, 2.0}×ramp`** (`t()`), then alpha-blended over the output with
  `alpha = ramp` fading to 0 over **250 ms** (`f()`); `mz4.C` = clamp01. Triggered per
  output via `gu5.k` (a "transitional blur" on outputs — e.g. wear/external connect or
  snapshot-to-playback transitions **[inference]**; the blur pyramid is rebuilt from the raw
  camera texture each frame while active).
- **Remote-camera live preview**: `pe5` (MediaCodec H.264 decoder → its own `ax0` instance)
  with monitoring overlays from the remote-control settings (WebRTC v5 protocol, Stage-1);
  the **wear** companion gets a preview via `je5`/`hs4` (`WearFrameSender`, Stage-1).
- **HDMI/external** = `CameraOutputType.EXTERNAL` output of the same `ax0` (its own viewport,
  overlays, LUT flag, and presentation pacing). **[inference]**: external display surfaces are
  Presentation/DisplayView surfaces rendered by the same master shader.
- **HUD overlays are Compose views** — focus/zebra/false-color controls, histogram, frame
  guides/safe areas/grids (`ds1`/`gs1`/`o40` UI-state classes), LUT picker (`jm5` previews),
  plus the settings panels (`settings/ui/category/monitor`, `settings/ui/category/luts`) —
  they only *feed* uniforms through `yq1`/`gu5` flags. The only GL-drawn HUD element is the
  monitoring effect set itself (plus blur transitions).
- **Capture vs preview**: `CameraOutputType.RECORD` is a GL output of the same renderer
  (encoder-consumed surface **[inference]**), so the recorded clip can receive the graded
  (LUT-applied) frames and per-output overlays can be disabled for it (clean feed);
  "Record LUT to Clip" additionally embeds the LUT in clip metadata (jcodec-fork MP4
  surgery, Stage-1/Stage-8). Camera2 still frames/YUV + MediaCodec encoding stay in Kotlin
  (Stage-1 verdict — no native encoder bridge).

### 10.5 res/raw GLSL = androidx.media3.effect assets (NOT the viewfinder)

`res/raw/` holds 11 `.glsl` files + 20 YAMLs + keystore. All GLSL files there are verbatim
copies of **androidx.media3.effect** (`DefaultShader`-family) shader assets — "Copyright 2022/
2023 The Android Open Source Project", `#version 100`/`300 es`, `uTexSampler`/`uRgbMatrix`/
`vTexSamplingCoord` naming, `LINT.IfChange(color_transfer)` pointing at
`androidx/media3/common/C.java`:

- `fragment_shader_oetf_es3.glsl` — linear BT.2020 → HLG/PQ **OETF** (+ `normalizeHdrLuminance`
  10000↔1000 nits), `uOutputColorTransfer` ∈ {ST2084=6, HLG=7} — "e.g. to an encoder".
- `fragment_shader_transformation_hdr_internal_es3.glsl` — EOTF (PQ/HLG) → optional
  BT2020→BT709 OOTF tone-mapping (HLG OOTF with `hlgGamma = 1.0735674018211279`; PQ OOTF with
  two-control-point Hermite, 1000→500 nits) → matrix → OETF out (LINEAR/GAMMA2_2/ST2084/HLG).
- `fragment_shader_transformation_ultra_hdr_es3.glsl` — sRGB base + **Ultra HDR gainmap**
  (`applyGainmapToBase` per `android.graphics.Gainmap`) → BT709→BT2020 → OETF.
- `fragment_shader_transformation_external_yuv_es3.glsl` (312 lines) — EXT_YUV_target YUV→RGB
  with `uYuvToRgbColorTransform` (the media3 HDR input path).
- ES2 set: `fragment_shader_transformation_es2/sdr_external_es2/sdr_internal_es2/sdr_oetf_es2/
  alpha_scale_es2/copy_es2.glsl` + `vertex_shader_transformation_es2/es3.glsl`.

These are used by the media3 effects pipeline (media tab playback/editing/transcode), *not*
by the `ax0` viewfinder. Consumers in jadx: `defpackage/ta1.java` / `defpackage/gp.java` +
`R.raw` — media3-effect classes. (This resolves the Stage-1 handoff note "res/raw may contain
HDR OETF shader variants": yes — they are media3's.)

---

## 11. SUMMARY TABLES

### 11.1 Effects ↔ shaders ↔ constants ↔ UI

| Effect | Shader (assets/glsl) | Key constants (exact) | UI-configurable (default) |
|---|---|---|---|
| Focus peaking | `focus_assist_effect.glsl` (+ `fragment_header*.glsl`, `transform_vertex.glsl`) | 5×5 kernel (§2), thresholds `5.5−t`, `3.5−t`, gain `t/4` where `t = UI×0.04` | `focusAssistVisible` per output; `enableColoredLines` (true); `lineColor` (RED, "Focus Assist Color" picker); peaking strength (0.85 → 0.034 in shader) |
| False color | `false_color_effect_fast.glsl` (+ `luminance_function.glsl`) | 17-band L\* table §3; colors Red/Yellow/Pink/Green/Blue/Purple §3 | `falseColorVisible` per output; `colorSpace` 0/1/2 auto-set from capture profile (709/P3/2020-HLG); no user thresholds (fixed bands, iOS-parity) |
| (False color, unused) | `false_color_effect.glsl` | full per-pixel L\* math (§1.5), incl. HLG sigmoid 1200→100 nits | — (dead asset on Android) |
| Zebras | `zebra_effect.glsl` | diagonal `x+y`, period `2w`, `midGrey=[405,429)/1024`, `midPlus1=[524,547)/1024` | `zebrasVisible` per output; threshold `UI×{0.93 \| 0.92 \| 1.0}` (default 0.75 → 0.6975 on 709); `midGreyMode` none/Mid Grey/MG+1 (`ZebraMidGray`); `zebraColor` (default BLACK in remote path; main path default black); width 8 px (fixed); shift auto-marching |
| Histogram | `histogram_compute.glsl` (GLES 3.1) | BINS=64, WG 16×16, SSBO std430 | "Display Histogram" toggle; no numeric ranges |
| 3D LUT (display/record) | `lut_3d_effect.glsl` (include) / `lut3d_fragment.glsl`, `yuv_to_rgb_lut3d_fragment.glsl` (standalone, `jm5` LUT preview) | `scale = 1−1/N`, `offset = (1/N)/2`; RGBA16F 3D texture, unit 2; identity 2³ fallback | LUT on/off per output (Display LUT vs Record-LUT-to-clip); 10 built-ins + import .cube (SAF→native parser) + export .cube |
| Luminance LUT (scopes) | `luminance_function.glsl` | 33³ RGBA16F, unit 3; fallback dot(0.2126,0.7152,0.0722) | none (auto per capture profile; cached as `lut_lum_*.bmdlut`) |
| YUV→RGB | `yuv_to_rgb_passthrough_fragment.glsl`, `color_transforms.glsl` | BT.2020 full `1.4746/−0.1646/−0.5714/1.8814`, limited `1.1689/−0.1881/−0.6530/2.1502`, offsets (0,0.5,0.5)/(0.0625,0.5,0.5) | `isFullRange` auto (Samsung/Oppo+VendorLog) |
| HDR transfer | `color_transforms.glsl` + generated main | HLG EOTF a/b/c; PQ OETF m1/m2/c1/c2/c3; PQ path adds `/40.0` | follows capture/display profiles + EGL colorspace support |
| Blur transitions | `blur_fragment.glsl` | 9-tap Gaussian, 1/6-res, 3 cascades ×{1,2,2}, 250 ms fade | none (automatic) |
| Frame guides/safe areas/grids | — (Compose HUD) | `guidesAspectRatio`, `safeArea`, `pixelAspectRatio` in `o40`/`ds1`/`gs1` | guide aspect list incl. anamorphic targets |
| Anamorphic de-squeeze | — (GL aspect + Glide `f9`) | factors 1.0/1.33/1.55/2.0; aspects 4:3/14:9/2:1 | `AnamorphicLensDeSqueezeFactor` setting |

### 11.2 Render chain (one line)

**Viewfinder:** Camera2 preview → `SurfaceTexture` (OES or YUV Y2YEXT when HDR) → ax0 master
GLSL: sample → [HLG EOTF (+/40+PQ OETF) if PQ display | HLG EOTF if LINEAR] → 3D LUT
(trilinear, half-texel-corrected) → [areEffectsVisible: false-color → zebras → focus assist]
→ per-output EGLSurface (PREVIEW/RECORD/EXTERNAL/ANALYSIS) — with a parallel graded+overlay-free
1/16-res copy dispatched to the 64-bin RGB histogram compute shader, and a 1/6-res Gaussian
pyramid for transition fades. **HUD** = Compose. **LUT preview tiles** (`jm5`) and
**remote/wear previews** run separate instances of the same stack.

---

## 12. Curiosities / defects worth flagging

1. `powf(` in `false_color_effect.glsl:61` is not GLSL — the full variant would fail to
   compile; it is dead on Android (only `_fast` + luminance LUT ship). Likely a C-parity bug.
2. `RGB_TO_XYZ_2020` G-column Y coefficient is `0.779998` (spec: `0.677998`) in
   `false_color_effect.glsl:24` — same dead file. (The AOSP media3 copy in res/raw has the
   correct `0.67799807`.)
3. The 5×5 "Laplacian" has DC gain +24 and is sampled on **`.r` only**.
4. Zebra threshold is re-scaled per display profile (×0.93/×0.92) — undocumented tuning.
5. Zebra stripes **march** diagonally at +1 px/frame (wrap at 2×width) via `et7.s`.
6. LUT-off = 2³ identity LUT (shader path never changes).
7. Zebra color is hard-forced BLACK (0xFF000000) in the remote-preview path (`pe5.e`), while
   `zebraColor` is a uniform in the shader — the main path default is also black.
8. The histogram shader's comments cite iOS class names (`ImageHistogramState`,
   `CompositeRenderer`) that do not exist on Android; the Android equivalents are `ax0.c()`
   (BINS injection) + `ne0.onImageBufferAvailable` (readback).
9. `liblutparser.so` (`NativeLutParser_parseCubeFile`) has **no Java caller** left in the app.
10. `libinsbase2.so` (Insta360) embeds three trivial GLSL shaders (`#version 300 es`):
    a vertex shader (`uMVPMatrix`/`uSTMatrix`) and two blit fragments (`samplerExternalOES`
    + `sampler2D`) for its TexturePool/SurfaceTextureBridge — nothing BMD-related (build
    path `/Users/puhenghai/Desktop/Insta360Code/bmgmediaandroid/onestream/...`).

## 13. Evidence index

- GLSL assets: `/home/z/my-project/apks/base_extracted/assets/glsl/*.glsl` (16 files, quoted in full).
- LUT assets: `…/assets/bmc_luts/*.obj` (10), `…/assets/Oppo-O-Log2-to-Rec709_Gamma24-D65_33_0303.obj` (`od` headers).
- jadx: `defpackage/ax0.java` (full read), `bd6.java`, `cd6.java`, `gu5.java`, `ef2.java`;
  `com/blackmagicdesign/android/colorutils/luts/Float16Lut.java`;
  `…/utils/entity/{CameraOutputType,ColorSpaceProfile,AnamorphicLensDeSqueezeFactor}.java`;
  `…/graphics/opengl/ColorTransfer.java`; `…/camera/ui/entity/ZebraMidGray.java`;
  `…/camera/model/BmdMedia$getAnamorphicFactor$1.java`; UI-state `ds1/gs1/o40/ut5/f9/zb6`.
- smali (jadx gaps): `r24/s24/m22/et7/k72/yq1/du5/fu/gu5/ef2/ov4` (defpackage root),
  `com/blackmagicdesign/android/colorutils/luts/{LutProcessor,Float16LutParser,SerializationProxy}.smali`,
  `…/camera/pipeline/a.smali` (GraphicCameraPipeline), `…/camera/manager/a.smali` (CameraManager),
  `…/settings/LutManager$1.smali`, `settings/b.smali`, `u40.smali` (LUT loader),
  `jm5.smali` (LUT preview), `pe5/ne5/je5/hs4/se0` (overlay setters/remote/wear), `ne0.smali`.
- Native: `strings` of `liblut_processor.so`, `liblutparser.so`, `liblut_float16_parser.so`,
  `liblut_compressor.so` (Zstd), `libinsbase2.so`.
- apktool: `res/values/strings.xml` (monitoring labels), `res/raw/*.glsl` (media3 HDR OETF set).
- READ-ONLY throughout: no APK execution, no patching, no decompilation beyond jadx/apktool artifacts already present.
