/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 * Verbatim ground-truth port (docs/stage4_shaders.md section 1.9) for M14 CinePro.
 *
 * The luminance 3D LUT (RGB -> L*) is baked once per color-space change on the CPU side by
 * LutEngine.bakeLuminanceLut(): Rec.709 OETF decode -> XYZ Y-row dot -> CIE L* (y2Lstar).
 * Fallback when luminanceVisible == 0 is the plain Rec.709 luma dot.
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
