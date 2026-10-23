/*
 * M14 CinePro — White Balance RGB trim include.
 *
 * Ground truth: docs/stage3_camera2.md section 3 (dt0 forward gains) + PART 2 of the build spec.
 * The manual Kelvin/Tint gains computed by ColorScience.kt via the exact dt0 math are applied in
 * the ISP (COLOR_CORRECTION_GAINS, recorded stream) AND trimmed in the viewfinder shader chain so
 * the monitoring image matches the recorded white balance even when the HAL caps gain application.
 * Gains arrive normalized (green channel == 1.0); the trim is a plain per-channel multiply.
 */

// Uniforms for the white balance trim
uniform int wbTrimVisible;
uniform vec3 wbGains;

vec4 wbTrim(vec4 color) {
    color.rgb = clamp(color.rgb * wbGains, 0.0, 1.0);
    return color;
}
