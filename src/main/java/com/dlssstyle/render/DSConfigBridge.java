package com.dlssstyle.render;

/**
 * Kill switch for the shadow cache, kept out of DSConfig so the Iris
 * mixins can consult it without dragging config classes onto their
 * class-load path. Hardcoded on, per the standing preference for one
 * correct behaviour over a toggle - but a single field to flip if a pack
 * turns out to hate it.
 */
public final class DSConfigBridge {
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
