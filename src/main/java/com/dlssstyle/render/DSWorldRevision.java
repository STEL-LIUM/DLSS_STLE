package com.dlssstyle.render;

/**
 * Did the world change since the last shadow render?
 *
 * <p>The shadow cache needs exactly one bit: has any block moved, been
 * placed, broken or relit since the map was drawn. Minecraft already knows
 * - chunk sections are marked dirty and rebuilt only on change - so this
 * just counts those rebuilds. A single dirty section anywhere is enough to
 * re-render; being cheap and conservative beats being clever, because a
 * missed invalidation shows as a shadow that lingers under a block that is
 * no longer there.
 */
public final class DSWorldRevision {
    private static volatile long revision;
    private static long consumedAt;

    private DSWorldRevision() {
    }

    /** Called whenever a chunk section is rebuilt. Hot path: one increment. */
    public static void bump() {
        revision++;
    }

    /** True if anything changed since the last consume(). */
    public static boolean changedSince() {
        return revision != consumedAt;
    }

    /** Marks the current revision as the one the cached shadow map reflects. */
    public static void consume() {
        consumedAt = revision;
    }
}
