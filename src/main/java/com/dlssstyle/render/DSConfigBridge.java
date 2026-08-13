package com.dlssstyle.render;

/**
 * Kill switch for the shadow cache, kept out of DSConfig so the Iris
 * mixins can consult it without dragging config classes onto their
 * class-load path. Hardcoded on, per the standing preference for one
 * correct behaviour over a toggle - but a single field to flip if a pack
 * turns out to hate it.
 */
public final class DSConfigBridge {
    // OFF as of 1.2.5, on Aryan's live call. v1 strobed Complementary's
    // volumetric wash outright; v2 (snapshot/repaint, composite kept
    // per-frame) fixed the static case but still steps visibly when the
    // camera moves - each refresh jumps the frozen map/matrix pair by the
    // accumulated motion. Shadow reuse doesn't ship again until it
    // survives a moving-camera A/B on a volumetric pack.
    private static volatile boolean shadowCache = false;

    private DSConfigBridge() {
    }

    public static boolean shadowCacheEnabled() {
        return shadowCache;
    }

    public static void setShadowCache(boolean on) {
        shadowCache = on;
    }
}
