package com.dlssstyle.render;

/**
 * The quality ladder, using DLSS's own scale ratios so the names mean
 * what players already expect them to mean. Fewer pixels = more fps:
 * Quality renders 44% of native pixels, Ultra Performance just 11%.
 */
public enum DSPreset {
    OFF("Off", 1.0),
    /**
     * DLSS Quality ratio, 2/3 per axis - shaded a hair below it, and the
     * hair matters. Shader packs clamp their post-processing budgets against
     * fixed line counts: BSL's bloom uses min(720.0, viewHeight), so at 1080p
     * an exact 2/3 renders exactly 720 lines and lands ON the clamp, buying
     * nothing in the bloom loop. 0.660 gives 713 lines - visually identical,
     * and the bloom tile budget finally scales with us.
     */
    QUALITY("Quality", 0.660),
    /** DLSS Balanced ratio - 0.58 per axis, 34% of the pixels. */
    BALANCED("Balanced", 0.58),
    /**
     * DLSS Performance ratio - 1/2 per axis, 25% of the pixels. The floor:
     * anything below sacrificed more image than Aryan was willing to trade
     * (Ultra Performance at 1/3 was cut 2026-08-11 on his call).
     */
    PERFORMANCE("Performance", 0.50),
    /**
     * Native resolution through the temporal pass - no fps gain, the
     * accumulation becomes pure anti-aliasing (DLAA's trick).
     */
    DLAA("DLAA", 1.0),
    /**
     * The scale picks itself: drops when fps falls under the target
     * (fps cap, or the monitor's refresh rate when uncapped), climbs
     * back when there is headroom. Consoles run DLSS exactly this way.
     */
    DYNAMIC("Dynamic", -1.0);

    private final String label;
    private final double scale;

    DSPreset(String label, double scale) {
        this.label = label;
        this.scale = scale;
    }

    public String label() {
        return this.label;
    }

    /** Fixed render scale, or -1 for {@link #DYNAMIC}. */
    public double scale() {
        return this.scale;
    }

    public boolean enabled() {
        return this != OFF;
    }
}
