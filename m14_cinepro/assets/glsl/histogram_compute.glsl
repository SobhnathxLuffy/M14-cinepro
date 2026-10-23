#version 310 es
// RGB histogram accumulation (GLES compute).
// Samples the graded (LUT-applied, overlay-free) analysis frame and bins each channel into BINS
// buckets, laid out contiguously: R = [0,BINS), G = [BINS,2*BINS), B = [2*BINS,3*BINS).
// M14 CinePro: BINS is injected textually (%%BINS%% -> "64") by HistogramEngine so the kernel
// and the CPU readback stay in lockstep.
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
