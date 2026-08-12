package com.dlssstyle.render;

/**
 * Sub-pixel camera jitter - the detail-reconstruction half of DLSS. Each
 * frame the projection is nudged by a fraction of a scaled pixel (Halton
 * 2,3 sequence, 8-phase), so successive frames sample DIFFERENT points
 * inside every output pixel; the temporal accumulation then integrates
 * them into more detail than any single low-res frame contains. Without
 * jitter, temporal upscaling can only smooth - with it, it reconstructs.
 *
 * <p>The resolve shader subtracts the same offset when sampling the
 * current frame ("unjitter"), so the output grid never wobbles; the
 * captured reprojection matrices are likewise cleaned in noteViewProj.
 */
public final class DSJitter {
    /** Halton radical inverse - the sequence NVIDIA prescribes for DLSS. */
    private static float halton(int index, int base) {
        float f = 1.0F;
        float r = 0.0F;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    /**
     * Phase count scales as 8/(scale^2) - DLSS's own rule. The lower the
     * render scale, the more distinct sub-pixel positions are needed before
     * the accumulated frames cover the output grid: 8 at DLAA, 32 at
     * Performance. Too few phases and the sequence repeats before it has
     * reconstructed anything.
     */
    private static int phaseCount = 8;
    private static int phase;
    private static float ndcX;
    private static float ndcY;
    private static float uvX;
    private static float uvY;

    private DSJitter() {
    }

    /** Steps the sequence for a new frame rendering at the given size. */
    static void advance(int width, int height, double scale) {
        phaseCount = Math.max(8, Math.min(64,
                (int) Math.ceil(8.0 / Math.max(0.05, scale * scale))));
        phase = (phase + 1) % phaseCount;
        float px = halton(phase + 1, 2) - 0.5F;
        float py = halton(phase + 1, 3) - 0.5F;
        ndcX = px * 2.0F / Math.max(1, width);
        ndcY = py * 2.0F / Math.max(1, height);
        uvX = ndcX * 0.5F;
        uvY = ndcY * 0.5F;
    }

    static void clear() {
        ndcX = 0.0F;
        ndcY = 0.0F;
        uvX = 0.0F;
        uvY = 0.0F;
    }

    /** Clip-space offset applied to the projection matrix this frame. */
    public static float ndcX() {
        return ndcX;
    }

    public static float ndcY() {
        return ndcY;
    }

    /** The same offset in source-UV units, for the resolve's unjitter. */
    static float uvX() {
        return uvX;
    }

    static float uvY() {
        return uvY;
    }
}
