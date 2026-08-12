package com.dlssstyle;

/**
 * Minimal fired-hook counters (the parent mod's applied-but-never-fired
 * lesson, kept): a mixin that applies cleanly can still sit on a path
 * nothing uses, which is how a feature ends up decorative. Counts are
 * plain array slots - hot-path safe.
 */
public final class DSHooks {
    public static final int RENDER_SCALE = 0;
    public static final int SCALE_BIND = 1;
    private static final int COUNT = 2;

    private static final long[] fires = new long[COUNT];

    private DSHooks() {
    }

    public static void fired(int hook) {
        fires[hook]++;
    }

    public static long fireCount(int hook) {
        return fires[hook];
    }
}
