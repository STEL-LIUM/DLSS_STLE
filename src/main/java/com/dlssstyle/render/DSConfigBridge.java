package com.dlssstyle.render;

/**
 * Kill switch for the shadow cache, kept out of DSConfig so the Iris
 * mixins can consult it without dragging config classes onto their
 * class-load path. Hardcoded on, per the standing preference for one
 * correct behaviour over a toggle - but a single field to flip if a pack
 * turns out to hate it.
 */
public final class DSConfigBridge {
    // BACK ON as of 1.2.6, on Aryan's call ("always on to increase fps") -
    // and on corrected evidence: the moving-camera flicker that got v2
    // benched in 1.2.5 was reported during the session where the mixin had
    // FAILED to apply (type-mismatched @Shadow), i.e. with the cache
    // inert. The disable judged a feature that was not running. v2
    // (snapshot/repaint, composite every frame, defensive matrix copies)
    // is validated under shaders static AND under scripted camera motion.
    private static volatile boolean shadowCache = true;

    private DSConfigBridge() {
    }

    public static boolean shadowCacheEnabled() {
        return shadowCache;
    }

    public static void setShadowCache(boolean on) {
        shadowCache = on;
    }
}
